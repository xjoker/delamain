"""jadx_init MCP tool: returns {tool_version, jadx_version} for client-side
compatibility/identity checks. Offline test — Java backend is mocked via
get_from_jadx; end-to-end coverage lives in tests/integration.
"""

import pytest

from src.tools import init_tools


# ---------------------------------------------------------------------------
# Registration: the tool must be exposed to MCP clients.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_jadx_init_is_registered():
    from src.mcp_server import build_mcp_app

    tools = await build_mcp_app().list_tools()
    tool_names = {tool.name for tool in tools}

    assert "jadx_init" in tool_names


# ---------------------------------------------------------------------------
# Behavior: reads tool_version from banner.SERVER_VERSION, jadx_version from
# the Java /health response.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_jadx_init_returns_tool_and_jadx_version(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        assert endpoint == "health"
        return {"status": "healthy", "jadx_version": "1.5.6"}

    monkeypatch.setattr(init_tools, "get_from_jadx", fake_get_from_jadx)
    monkeypatch.setattr(init_tools, "SERVER_VERSION", "0.0.0-test")

    result = await init_tools.jadx_init()

    assert result == {
        "tool_version": "0.0.0-test",
        "jadx_version": "1.5.6",
        "server": "delamain",
    }


@pytest.mark.asyncio
async def test_jadx_init_reports_unknown_jadx_version_when_java_unreachable(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        raise ConnectionError("refused")

    monkeypatch.setattr(init_tools, "get_from_jadx", fake_get_from_jadx)
    monkeypatch.setattr(init_tools, "SERVER_VERSION", "0.0.0-test")

    result = await init_tools.jadx_init()

    assert result["tool_version"] == "0.0.0-test"
    assert result["jadx_version"] == "unknown"
    assert result["server"] == "delamain"


@pytest.mark.asyncio
async def test_jadx_init_reports_unknown_when_health_response_has_no_jadx_version(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        return {"status": "healthy"}

    monkeypatch.setattr(init_tools, "get_from_jadx", fake_get_from_jadx)
    monkeypatch.setattr(init_tools, "SERVER_VERSION", "0.0.0-test")

    result = await init_tools.jadx_init()

    assert result["jadx_version"] == "unknown"
