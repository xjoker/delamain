"""smali 降级信号 Layer 1 回归测试：get_decompile_diag 工具接线到 /decompile-diag 端点。

离线测试（不需要真实 Java 实例），风格仿 test_wave2_resource_wiring.py。
"""

import pytest

from src.registry.instance_registry import InstanceRegistry
from src.tools import diagnostics_tools


@pytest.fixture(autouse=True)
def reset_state():
    InstanceRegistry.clear_all()
    yield
    InstanceRegistry.clear_all()


@pytest.mark.asyncio
async def test_get_decompile_diag_is_registered():
    from src.mcp_server import build_mcp_app

    tools = await build_mcp_app().list_tools()
    tool_names = {tool.name for tool in tools}

    assert "get_decompile_diag" in tool_names


@pytest.mark.asyncio
async def test_get_decompile_diag_maps_params_to_java_endpoint(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        return {"process_state": "GENERATED_AND_UNLOADED", "process_complete": True, "jadx_errors": []}

    monkeypatch.setattr(diagnostics_tools, "get_from_jadx", fake_get_from_jadx)

    await diagnostics_tools.get_decompile_diag("com.example.Foo", instance_id="inst-1")
    assert calls[-1] == ("decompile-diag", {"class_name": "com.example.Foo"})


@pytest.mark.asyncio
async def test_get_decompile_diag_wraps_backend_exception(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr(diagnostics_tools, "get_from_jadx", fake_get_from_jadx)

    result = await diagnostics_tools.get_decompile_diag("com.example.Foo")
    assert result["error"] == "JADX_ERROR"
