"""
delamain Gateway - HTTP Request Router

Routes MCP tool requests to the single configured JADX backend.
"""

import asyncio
import logging
import time
from typing import Any, Optional

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


# instance_id validity: an identity round trip (one /file-info call) is only
# needed when instance_id doesn't already match the backend's own name — see
# resolve_instance(). Cached briefly so a burst of calls with the same
# instance_id doesn't hit the backend once per call.
_IDENTITY_CACHE_TTL_SECONDS = 5.0
_identity_cache: dict[str, Any] = {"ts": 0.0, "data": None}


def reset_identity_cache() -> None:
    """Clear the cached current-file identity (tests; also safe after a reload)."""
    _identity_cache["ts"] = 0.0
    _identity_cache["data"] = None


async def _fetch_current_identity() -> Optional[dict]:
    now = time.monotonic()
    if _identity_cache["data"] is not None and now - _identity_cache["ts"] < _IDENTITY_CACHE_TTL_SECONDS:
        return _identity_cache["data"]

    result = await get_from_jadx("file-info")
    if not result or result.get("error"):
        return None

    _identity_cache["ts"] = now
    _identity_cache["data"] = result
    return result


async def resolve_instance(instance_id: Optional[str]) -> Optional[dict]:
    """Validate ``instance_id`` against the single configured backend.

    Returns ``None`` when the instance_id is valid (including the
    backward-compatible ``None``/omitted case — zero extra round trips), or a
    router-level error dict (INSTANCE_NOT_FOUND) when it names something that
    is neither the backend itself nor the identity of whatever is currently
    loaded on it. This is the fix for the RCA where an unknown instance_id
    (e.g. a stale APK filename) was silently routed to the current backend
    instead of being rejected.
    """
    if instance_id is None or instance_id == "jadx":
        return None

    inst = InstanceRegistry.get_default()
    if inst is None:
        # No backend configured at all — let the NO_INSTANCE branch in
        # get_from_jadx report that; don't mask it with a different code.
        return None
    if instance_id == inst.name:
        return None

    identity = await _fetch_current_identity()
    if identity is None:
        return _routing_error(
            "INSTANCE_NOT_FOUND",
            f"Instance '{instance_id}' not found (unable to verify the currently loaded APK/JAR).",
        )

    # Identity fields live at the top level of /file-info AND inside its nested
    # apk_identity object (input_hash is only in the nested one) — merge both so
    # an instance_id given as any of file_name / input_hash / apk_package matches.
    nested = identity.get("apk_identity") or {}
    candidates = {
        str(value)
        for source in (identity, nested)
        for key in ("file_name", "input_hash", "apk_package")
        if (value := source.get(key))
    }
    if instance_id in candidates:
        return None

    file_name = identity.get("file_name") or "unknown"
    version_name = identity.get("version_name") or "unknown"
    return _routing_error(
        "INSTANCE_NOT_FOUND",
        f"Instance '{instance_id}' not found. Currently loaded: {file_name} ({version_name}).",
    )


async def get_from_jadx(
    endpoint: str,
    params: Optional[dict] = None,
    instance_id: Optional[str] = None,
    timeout: Optional[int] = None,
    method: str = "GET",
    json_body: Optional[dict] = None,
) -> dict:
    """Route a request to the single configured JADX backend.

    ``instance_id``, when provided, must resolve to either the backend's own
    name ("jadx") or the identity of whatever APK/JAR it currently has
    loaded (file_name / input_hash / apk_package) — see resolve_instance().
    An unresolvable instance_id is rejected with INSTANCE_NOT_FOUND rather
    than silently proceeding against the single fixed backend.
    """
    inst = InstanceRegistry.get_default()
    if inst is None:
        return _routing_error("NO_INSTANCE", "No JADX backend configured")

    if instance_id is not None:
        validation_error = await resolve_instance(instance_id)
        if validation_error is not None:
            return validation_error

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
        data = resp.json()
        if endpoint.lstrip("/").startswith("load-file"):
            # A (re)load changes which APK is current — drop the cached identity
            # so instance_id validation re-reads the backend instead of trusting
            # a pre-reload file_name for up to the TTL window.
            reset_identity_cache()
        return data
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
