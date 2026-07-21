"""
delamain Gateway - FastMCP Application Builder

Builds and configures the FastMCP HTTP server with all registered tools.
"""

from contextlib import asynccontextmanager

from fastmcp import FastMCP
from .tools import register_all_tools
from .tools.transfer_tools import register_transfer_tools, register_transfer_proxy_routes

MCP_INSTRUCTIONS = """
Delamain Gateway - Android APK Reverse Engineering

IMPORTANT: Call get_decompile_status() before any resource-intensive operation.

DECISION GUIDE:
1. get_decompile_status() — memory.usage_percentage > 85% → reduce batch size to ≤5,
   avoid get_smali_of_class; search_lock.locked = true → wait 5s and retry.
2. get_index_stats() — the authoritative signal for whether search_in='code' is viable
   (shard_index.built/covered_classes). Full hard-limit list (hex/UUID/base64/long paths
   are never indexed) and broad_term handling live in get_index_stats' own docstring —
   check there before using code search.
3. Prefer search_classes_by_keyword(search_in='class,method,field') first (always fast);
   use submit_code_search for async code search on large APKs.

RENAME: all rename ops trigger a 30s global cache cooldown; revert uses the ORIGINAL
class name, not the alias.

Call get_jadx_guide(verbose=True) for the full workflow guide (performance table,
step-by-step recon workflow, async code-search example).
Call get_jadx_guide(install_skills=True, target="claude") to install the local skill file.
"""


@asynccontextmanager
async def _gateway_lifespan(server: FastMCP):
    """Close the shared HTTP client when the ASGI app shuts down.

    Single process, single event loop: there is exactly one httpx.AsyncClient
    (request_router's module-level client) to close on shutdown — no
    cross-thread/cross-loop dispatch needed (that machinery existed only for
    the now-removed background health-monitor thread's second event loop).
    """
    try:
        yield {}
    finally:
        from .routing.request_router import close_http_client
        await close_http_client()


def build_mcp_app() -> FastMCP:
    mcp = FastMCP(
        "Delamain Gateway",
        instructions=MCP_INSTRUCTIONS,
        lifespan=_gateway_lifespan,
    )
    register_all_tools(mcp)
    register_transfer_tools(mcp)
    register_transfer_proxy_routes(mcp)

    # Health check custom route (no auth required)
    @mcp.custom_route("/health", methods=["GET"])
    async def health(request):
        from starlette.responses import JSONResponse
        from .banner import SERVER_VERSION
        # Health probes are intentionally unauthenticated. Do not expose global
        # instance topology to callers outside the authenticated MCP transport.
        payload = {"status": "healthy", "version": SERVER_VERSION}
        # Best-effort: surface the bundled jadx version (from the Java backend's
        # own /health). Kept non-fatal — the gateway stays healthy even if the
        # backend is momentarily unreachable.
        try:
            from .routing.request_router import get_from_jadx
            backend = await get_from_jadx("health", timeout=3)
            jadx_version = backend.get("jadx_version")
            if jadx_version:
                payload["jadx_version"] = jadx_version
        except Exception:
            pass
        return JSONResponse(payload)

    return mcp
