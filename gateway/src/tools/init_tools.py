"""
delamain Gateway - jadx_init tool

Lets an MCP client fetch both version numbers it needs for an identity/
compatibility check in a single call: this gateway's own release (VERSION
file) and the jadx version actually running in the Java backend.
"""

from ..banner import SERVER_VERSION
from ..logging_config import get_logger
from ..routing.request_router import get_from_jadx

logger = get_logger("init_tools")


async def jadx_init() -> dict:
    logger.debug("jadx_init called")
    jadx_version = "unknown"
    try:
        health = await get_from_jadx("health")
        jadx_version = health.get("jadx_version") or "unknown"
    except Exception as exc:
        logger.warning("jadx_init: failed to reach Java backend: %s", exc)

    return {
        "tool_version": SERVER_VERSION,
        "jadx_version": jadx_version,
        "server": "delamain",
    }


_jadx_init = jadx_init


def register_init_tools(mcp):
    """Register the jadx_init tool with the MCP server."""

    @mcp.tool()
    async def jadx_init() -> dict:
        """Return this gateway's tool version and the running jadx version, for a client-side compatibility check."""
        return await _jadx_init()

    logger.info("Init tools registered: jadx_init")
