"""2026-07 审计 Wave2 回归测试：code-metadata + 资源三件套接线 + 统一错误契约。

离线测试（不需要真实 Java 实例）。端到端验证见 tests/integration/test_new_tools_live.py。
"""

import pytest

from src.registry.instance_registry import InstanceRegistry
from src.routing.request_router import get_from_jadx
from src.tools import class_tools, resource_tools


@pytest.fixture(autouse=True)
def reset_wave2_state():
    InstanceRegistry.clear_all()
    yield
    InstanceRegistry.clear_all()


# ---------------------------------------------------------------------------
# Registration: the 4 new tools must be exposed to MCP clients.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_wave2_tools_are_registered():
    from src.mcp_server import build_mcp_app

    tools = await build_mcp_app().list_tools()
    tool_names = {tool.name for tool in tools}

    assert "get_code_metadata" in tool_names
    assert "list_resources_by_type" in tool_names
    assert "get_decoded_resource" in tool_names
    assert "resolve_resource_id" in tool_names


# ---------------------------------------------------------------------------
# A. get_code_metadata param mapping
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_get_code_metadata_maps_params_to_java_endpoint(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        return {"has_metadata": True, "reference_count": 0, "references": []}

    monkeypatch.setattr(class_tools, "get_from_jadx", fake_get_from_jadx)

    await class_tools.get_code_metadata("com.example.Foo", instance_id="inst-1")
    assert calls[-1] == ("code-metadata", {"class_name": "com.example.Foo"})

    await class_tools.get_code_metadata("com.example.Foo", position=42, max=10, instance_id="inst-1")
    assert calls[-1] == ("code-metadata", {"class_name": "com.example.Foo", "position": "42", "max": "10"})


# ---------------------------------------------------------------------------
# B. resource trio param mapping
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_list_resources_by_type_maps_params(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        return {"status": "summary"}

    monkeypatch.setattr(resource_tools, "get_from_jadx", fake_get_from_jadx)

    await resource_tools.list_resources_by_type(instance_id="inst-1")
    assert calls[-1] == ("list-resources-by-type", {"offset": "0", "limit": "50"})

    await resource_tools.list_resources_by_type(resource_type="layout", offset=10, limit=5, instance_id="inst-1")
    assert calls[-1] == ("list-resources-by-type", {"offset": "10", "limit": "5", "type": "layout"})


@pytest.mark.asyncio
async def test_get_decoded_resource_maps_params(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        return {"status": "success", "content": "<xml/>"}

    monkeypatch.setattr(resource_tools, "get_from_jadx", fake_get_from_jadx)

    await resource_tools.get_decoded_resource("res/layout/a.xml", instance_id="inst-1")
    assert calls[-1] == ("get-decoded-resource", {"file_name": "res/layout/a.xml"})

    await resource_tools.get_decoded_resource("res/layout/a.xml", chunk=2, instance_id="inst-1")
    assert calls[-1] == ("get-decoded-resource", {"file_name": "res/layout/a.xml", "chunk": "2"})


@pytest.mark.asyncio
async def test_resolve_resource_id_maps_params(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        return {"status": "success"}

    monkeypatch.setattr(resource_tools, "get_from_jadx", fake_get_from_jadx)

    await resource_tools.resolve_resource_id(instance_id="inst-1")
    assert calls[-1] == ("resolve-resource-id", {"offset": "0", "limit": "100"})

    await resource_tools.resolve_resource_id(resource_id="0x7f0a0000", instance_id="inst-1")
    assert calls[-1] == ("resolve-resource-id", {"offset": "0", "limit": "100", "id": "0x7f0a0000"})

    await resource_tools.resolve_resource_id(name="ic_launcher", offset=5, limit=20, instance_id="inst-1")
    assert calls[-1] == ("resolve-resource-id", {"offset": "5", "limit": "20", "name": "ic_launcher"})


# ---------------------------------------------------------------------------
# C. Unified error contract: bare router errors now also carry ok/error_code,
# without dropping the legacy "error"/"message" fields existing callers key off.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_no_instance_error_has_both_legacy_and_unified_fields():
    result = await get_from_jadx("class-info", {"class_name": "X"})

    assert result["error"] == "NO_INSTANCE"
    assert result["ok"] is False
    assert result["error_code"] == "NO_INSTANCE"


@pytest.mark.asyncio
async def test_instance_unavailable_error_has_both_legacy_and_unified_fields():
    """H1/L1: an unreachable backend (connection refused) must map to a clean
    INSTANCE_UNAVAILABLE, not leak the raw httpx ConnectError (which embeds
    host:port) to the MCP client. Port 1 is a privileged/unused port that
    reliably refuses connections without needing a fake server.
    """
    InstanceRegistry.configure(host="127.0.0.1", port=1)

    result = await get_from_jadx("class-info", {"class_name": "X"})

    assert result["error"] == "INSTANCE_UNAVAILABLE"
    assert result["ok"] is False
    assert result["error_code"] == "INSTANCE_UNAVAILABLE"
    # L1: response body must not leak internal backend topology.
    body = str(result)
    assert "127.0.0.1" not in body
    assert ":1" not in body
