"""
delamain Gateway - HTTP Request Router

Routes MCP tool requests to the single configured JADX backend.
"""

import asyncio
import logging
from typing import Optional

import httpx

from ..registry.instance_registry import InstanceRegistry

logger = logging.getLogger(__name__)

# Timeout tiers (seconds)
TIMEOUT_HEALTH = 10
TIMEOUT_METADATA = 30
TIMEOUT_CODE_READ = 120

_METADATA_ENDPOINTS = frozenset({
    "class-info", "methods-of-class", "fields-of-class", "all-classes",
    "search-classes-by-keyword", "search-native-methods", "file-info",
    "package-classes", "decompile-status", "apk-info", "memory-diagnostics",
    "rename-class", "rename-method", "rename-field", "method-signature",
})
_HEALTH_ENDPOINTS = frozenset({"health", "start-warmup", "warmup-status"})


def _routing_error(code: str, message: str) -> dict:
    """Build a router-level error dict.

    Keeps the legacy bare {"error", "message"} shape (existing callers key off
    result["error"]) while adding "ok"/"error_code" so callers can check success
    uniformly, matching the format_error_response() contract used elsewhere in
    the gateway (types.py). Backward compatible: no existing field is removed.
    """
    return {"error": code, "message": message, "ok": False, "error_code": code}


def _infer_timeout(endpoint: str) -> int:
    ep = endpoint.strip("/")
    if ep in _HEALTH_ENDPOINTS:
        return TIMEOUT_HEALTH
    if ep in _METADATA_ENDPOINTS:
        return TIMEOUT_METADATA
    return TIMEOUT_CODE_READ


# Single process, single event loop, single backend: one module-level httpx
# client for the whole process lifetime (no per-loop client pool needed —
# see gateway/main.py's single-uvicorn-worker constraint).
_http_client: Optional[httpx.AsyncClient] = None
_http_client_lock = asyncio.Lock()


async def get_http_client() -> httpx.AsyncClient:
    global _http_client
    if _http_client is not None and not _http_client.is_closed:
        return _http_client
    async with _http_client_lock:
        if _http_client is None or _http_client.is_closed:
            _http_client = httpx.AsyncClient(
                limits=httpx.Limits(max_connections=50, max_keepalive_connections=20),
                timeout=httpx.Timeout(TIMEOUT_CODE_READ),
            )
        return _http_client


async def close_http_client() -> None:
    """Close the shared client (process/loop shutdown)."""
    global _http_client
    client, _http_client = _http_client, None
    if client is not None and not client.is_closed:
        await client.aclose()


async def get_from_jadx(
    endpoint: str,
    params: Optional[dict] = None,
    instance_id: Optional[str] = None,
    timeout: Optional[int] = None,
    method: str = "GET",
    json_body: Optional[dict] = None,
) -> dict:
    """Route a request to the single configured JADX backend.

    ``instance_id`` is accepted only for call-site compatibility with the ~30
    tool modules that still pass it — this gateway proxies to exactly one
    fixed backend (see InstanceRegistry.configure()), so it is not used to
    select among instances.
    """
    inst = InstanceRegistry.get_default()
    if inst is None:
        return _routing_error("NO_INSTANCE", "No JADX backend configured")

    url = f"{inst.url}/{endpoint.lstrip('/')}"
    headers = {}
    if inst.token:
        headers["Authorization"] = f"Bearer {inst.token}"

    actual_timeout = timeout or _infer_timeout(endpoint)
    client = await get_http_client()

    try:
        if method.upper() == "GET":
            resp = await client.get(url, params=params or {}, headers=headers,
                                    timeout=httpx.Timeout(actual_timeout))
        else:
            resp = await client.request(method, url, json=json_body or {},
                                        params=params or {}, headers=headers,
                                        timeout=httpx.Timeout(actual_timeout))
        resp.raise_for_status()
        return resp.json()
    except (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout) as e:
        # Connection-level failures (backend down, wrong port, refused, etc.)
        # — log the real host:port/exception for operators, but never echo
        # internal topology back to the MCP client (see README token note).
        logger.warning(f"JADX backend unreachable for endpoint '{endpoint}': {e}")
        return _routing_error("INSTANCE_UNAVAILABLE", "JADX backend unreachable")
    except httpx.TimeoutException:
        return _routing_error("TIMEOUT", f"Request to {endpoint} timed out after {actual_timeout}s")
    except httpx.HTTPStatusError as e:
        try:
            return e.response.json()
        except Exception:
            logger.warning(f"JADX backend HTTP error for endpoint '{endpoint}': {e}")
            return _routing_error("HTTP_ERROR", f"JADX backend returned HTTP {e.response.status_code}")
    except Exception as e:
        logger.warning(f"JADX backend request failed for endpoint '{endpoint}': {type(e).__name__}: {e}")
        return _routing_error("JADX_ERROR", str(type(e).__name__))
