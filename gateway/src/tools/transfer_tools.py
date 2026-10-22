"""
delamain Gateway - File Transfer Tools

The Java backend's /transfer/upload and /transfer/status are only bound to
localhost inside the fused container (gateway 8651 is the only port exposed
externally) — so the gateway itself proxies those two endpoints (see
register_transfer_proxy_routes / PUT/GET custom_route handlers below), streaming
bytes straight through to the Java backend without buffering them in memory.
Bytes still never flow through the AI/MCP context: the AI hands the returned
token + upload_url to the user, who PUTs the file to *this gateway* (via curl or
the delamain-cli CLI), which relays it to the Java backend; the AI then calls
load_file() once the upload completes.
"""

import os
import re
from typing import Optional
from urllib.parse import urlsplit

import httpx
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from ..logging_config import get_logger
from ..routing.request_router import get_from_jadx, get_http_client, TIMEOUT_CODE_READ, TIMEOUT_METADATA
from ..registry.instance_registry import InstanceRegistry
from ..config.config_loader import get_config_loader

logger = get_logger("transfer_tools")

# Forwarded verbatim between the uploading client and the Java backend — these are
# the only headers TransferRoutes.java's PUT /transfer/upload and GET /transfer/status
# read (see src/main/java/.../server/routes/TransferRoutes.java).
_UPLOAD_HEADERS = ("X-Transfer-Token", "X-Chunk-Offset", "X-Chunk-Final", "X-Content-Sha256")

# The gateway's own externally-reachable base URL, set once at startup (see
# configure_gateway_public_base(), called from main.py with --host/--port). Used only
# as the last-resort fallback in _resolve_upload_base() when neither
# JADX_TRANSFER_PUBLIC_URL nor the TOML equivalent is configured.
_gateway_public_base: Optional[str] = None

_WILDCARD_HOSTS = frozenset({"", "0.0.0.0", "::", "[::]", "*"})
_HOST_HEADER_RE = re.compile(r"^(?:[A-Za-z0-9](?:[A-Za-z0-9.-]{0,252})|\[[0-9A-Fa-f:.]+\])(?::[0-9]{1,5})?$")


def _is_wildcard_host(host: Optional[str]) -> bool:
    return (host or "").strip().lower() in _WILDCARD_HOSTS


def configure_gateway_public_base(host: str, port: int) -> None:
    """Record this gateway process's own bind host:port as the default externally-
    reachable address for create_transfer_token's upload_url. Called once at startup
    (see gateway/main.py) — not meant to be called again afterwards."""
    global _gateway_public_base
    if _is_wildcard_host(host):
        _gateway_public_base = None
        return
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    _gateway_public_base = f"http://{host}:{port}"


def _request_base_url() -> Optional[str]:
    try:
        from fastmcp.server.dependencies import get_http_request
        request = get_http_request()
    except Exception:
        return None
    host = (request.headers.get("host") or "").strip()
    if not host or not _HOST_HEADER_RE.fullmatch(host):
        return None
    hostname = host.rsplit(":", 1)[0] if not host.endswith("]") else host
    if _is_wildcard_host(hostname):
        return None
    scheme = request.url.scheme if request.url.scheme in ("http", "https") else "http"
    return f"{scheme}://{host}"


def resolve_upload_base(
    env_value: Optional[str],
    toml_value: Optional[str],
    gateway_address: Optional[str],
    request_address: Optional[str] = None,
) -> Optional[str]:
    """Pick the externally-reachable base URL for the gateway's own Transfer API
    proxy (PUT/GET /transfer/*, forwarded internally to the Java backend).

    Priority: env_value (JADX_TRANSFER_PUBLIC_URL) > toml_value ([server]
    transfer_public_url) > gateway_address (this gateway process's own bind
    host:port). All three now describe *this gateway's* address (default port
    8651) — never the Java backend's address (8650), which is not reachable from
    outside the fused container.
    """
    for value in (env_value, toml_value):
        if value:
            return value.rstrip("/")
    for value in (gateway_address, request_address):
        if value and not _is_wildcard_host(urlsplit(value).hostname):
            return value.rstrip("/")
    return None


def _resolve_upload_base(instance_id: Optional[str]) -> Optional[str]:
    """Best-effort externally-reachable base URL for this gateway's transfer proxy."""
    env_value = os.getenv("JADX_TRANSFER_PUBLIC_URL")

    loader = get_config_loader()
    toml_value = loader.config.server.transfer_public_url if loader and loader.config else None

    return resolve_upload_base(env_value, toml_value, _gateway_public_base, _request_base_url())


async def _create_transfer_token_impl(
    filename: str,
    size_bytes: Optional[int],
    instance_id: Optional[str],
) -> dict:
    json_body: dict = {"filename": filename}
    if size_bytes is not None:
        json_body["size_bytes"] = size_bytes

    result = await get_from_jadx(
        "create-transfer-token",
        instance_id=instance_id,
        method="POST",
        json_body=json_body,
    )
    if result.get("error"):
        return result

    upload_path = result.get("upload_path", "/transfer/upload")
    base = _resolve_upload_base(instance_id)
    token = result.get("token")
    if base:
        result["upload_url"] = f"{base}{upload_path}"
    else:
        result["upload_url_hint"] = (
            "No externally-reachable address is configured for this gateway (the process "
            "on port 8651 that proxies /transfer/upload through to the Java backend). Ask "
            "the administrator to set the JADX_TRANSFER_PUBLIC_URL environment variable (or "
            "[server] transfer_public_url in the gateway TOML) to this gateway's "
            f"externally-reachable address, then PUT the file to <that-address>{upload_path} "
            f"with header 'X-Transfer-Token: {token}'."
        )
    return result


def register_transfer_tools(mcp):
    """Register file-transfer tools with the MCP server."""

    @mcp.tool()
    async def create_transfer_token(
        filename: str,
        size_bytes: Optional[int] = None,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Create a one-time upload token so the user can PUT an APK/JAR/AAR/DEX file
        directly to the JADX sandbox, out of the AI context (bytes never pass through
        this conversation).

        upload_url always points at **this gateway's own address** (default port 8651,
        which proxies PUT /transfer/upload and GET /transfer/status through to the Java
        backend on localhost:8650) — never at the Java backend directly, since it is
        not reachable from outside the fused container. The host is resolved in
        priority order: the JADX_TRANSFER_PUBLIC_URL env var > [server]
        transfer_public_url in the gateway TOML > this gateway process's own bind
        host:port. Set JADX_TRANSFER_PUBLIC_URL (or the TOML equivalent) when the
        gateway's bind address (e.g. 0.0.0.0) is not itself a reachable hostname/IP
        from the machine that will run the upload.

        Workflow:
          1. Call this tool with the destination filename (and size_bytes if known, for
             an upfront cap check).
          2. Tell the user to run: `delamain-cli --upload-url <upload_url> --token <token> <local-file>`
          3. Once the upload responds with {"status": "complete", "path": ...}, call
             load_file(path=<filename>) to load it into JADX.
             (Resume-on-stall mechanics and the raw curl equivalent: get_jadx_guide(verbose=True).)

        Args:
            filename: Destination file name — a plain basename, no path separators.
            size_bytes: Optional total size in bytes, used for an upfront cap check
                (rejected with an error if it exceeds the server's configured max).
            instance_id: Target JADX instance.
        Returns:
            dict: {token, filename, upload_path, status_path, expires_at_epoch_ms,
                   max_bytes, chunk_size_hint, upload_url} on success — or
                   {..., upload_url_hint} instead of upload_url when no
                   externally-reachable address is configured for this instance.
        """
        return await _create_transfer_token_impl(
            filename=filename, size_bytes=size_bytes, instance_id=instance_id,
        )

    logger.info("Transfer tools registered: create_transfer_token")


# ---------------------------------------------------------------------------
# Streaming reverse-proxy: PUT /transfer/upload and GET /transfer/status
# ---------------------------------------------------------------------------
#
# The Java backend binds these to 127.0.0.1:8650 only, so in the fused
# single-port deployment the gateway itself must relay the raw bytes. Both
# routes are registered as FastMCP custom_route handlers (plain Starlette
# endpoints outside the MCP tool-call transport) and therefore are NOT
# covered by the gateway's MCP bearer auth — same as /health. Authorization
# here is the one-time X-Transfer-Token itself, exactly as it is on the Java
# side (see TransferRoutes.java).

def _reject_token_in_query(request: Request) -> Optional[Response]:
    if "token" not in request.query_params:
        return None
    return JSONResponse(
        {
            "error": "token_in_query_not_allowed",
            "message": "send the transfer token in the X-Transfer-Token header, not in the URL",
        },
        status_code=400,
    )

def _backend_url_or_error():
    """Returns (base_url, None) or (None, JSONResponse) if no backend is configured."""
    inst = InstanceRegistry.get_default()
    if inst is None:
        return None, JSONResponse(
            {"error": "NO_INSTANCE", "message": "No JADX backend configured"},
            status_code=503,
        )
    return inst.url, None


async def _proxy_transfer_upload(request: Request) -> Response:
    """Stream the PUT body straight through to the Java backend without
    buffering the whole file in this process — request.stream() yields the
    body in chunks as they arrive off the socket, and httpx forwards that
    async iterator as the outgoing request body (also chunked)."""
    base, error = _backend_url_or_error()
    if error is not None:
        return error

    rejected = _reject_token_in_query(request)
    if rejected is not None:
        return rejected

    headers = {
        name: value
        for name, value in ((h, request.headers.get(h)) for h in _UPLOAD_HEADERS)
        if value is not None
    }
    content_length = request.headers.get("content-length")
    if content_length is not None:
        headers["Content-Length"] = content_length

    client = await get_http_client()
    try:
        async with client.stream(
            "PUT",
            f"{base}/transfer/upload",
            content=request.stream(),
            headers=headers,
            timeout=httpx.Timeout(TIMEOUT_CODE_READ),
        ) as backend_resp:
            body = await backend_resp.aread()
            return Response(
                content=body,
                status_code=backend_resp.status_code,
                media_type=backend_resp.headers.get("content-type", "application/json"),
            )
    except (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout) as e:
        logger.warning(f"transfer/upload proxy: JADX backend unreachable: {e}")
        return JSONResponse(
            {"error": "INSTANCE_UNAVAILABLE", "message": "JADX backend unreachable"},
            status_code=502,
        )
    except httpx.TimeoutException:
        return JSONResponse({"error": "TIMEOUT", "message": "Upload proxy timed out"}, status_code=504)


async def _proxy_transfer_status(request: Request) -> Response:
    base, error = _backend_url_or_error()
    if error is not None:
        return error

    rejected = _reject_token_in_query(request)
    if rejected is not None:
        return rejected

    headers = {}
    token_header = request.headers.get("X-Transfer-Token")
    if token_header is not None:
        headers["X-Transfer-Token"] = token_header
    client = await get_http_client()
    try:
        backend_resp = await client.get(
            f"{base}/transfer/status",
            headers=headers,
            timeout=httpx.Timeout(TIMEOUT_METADATA),
        )
        return Response(
            content=backend_resp.content,
            status_code=backend_resp.status_code,
            media_type=backend_resp.headers.get("content-type", "application/json"),
        )
    except (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout) as e:
        logger.warning(f"transfer/status proxy: JADX backend unreachable: {e}")
        return JSONResponse(
            {"error": "INSTANCE_UNAVAILABLE", "message": "JADX backend unreachable"},
            status_code=502,
        )
    except httpx.TimeoutException:
        return JSONResponse({"error": "TIMEOUT", "message": "Status proxy timed out"}, status_code=504)


def register_transfer_proxy_routes(mcp):
    """Register the PUT /transfer/upload and GET /transfer/status reverse-proxy
    routes on the gateway's Starlette app (see module docstring for why these
    bypass MCP bearer auth)."""
    mcp.custom_route("/transfer/upload", methods=["PUT"])(_proxy_transfer_upload)
    mcp.custom_route("/transfer/status", methods=["GET"])(_proxy_transfer_status)
    logger.info("Transfer proxy routes registered: PUT /transfer/upload, GET /transfer/status")
