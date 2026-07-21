"""首发安全边界和 MCP/Java 路由契约的离线回归测试。

B'' 简化后：网关是单机+单实例+单用户(多等价 token)。已删除的多用户/多实例专属测试
（动态实例注册、health monitor warmup、按 owner 过滤实例可见性等）随对应功能一起
移除；下面覆盖新模型的等价契约：token 白名单放行/拒绝、单后端直通。
"""

import sys

import pytest

import main
from src.registry.instance_registry import InstanceRegistry
from src.auth.mcp_auth import build_auth_provider
from src.routing import request_router


@pytest.fixture(autouse=True)
def reset_release_contract_state():
    InstanceRegistry.clear_all()
    yield
    InstanceRegistry.clear_all()


def test_gateway_rejects_missing_mcp_auth_token(monkeypatch, capsys):
    """Gateway 不能在没有任何白名单 token 时启动（无 DELAMAIN_AUTH_TOKENS，无 config allowed_tokens）。"""
    monkeypatch.delenv("DELAMAIN_AUTH_TOKENS", raising=False)
    monkeypatch.setattr(sys, "argv", ["main.py"])

    with pytest.raises(SystemExit) as exc_info:
        main.main()

    assert exc_info.value.code == 2
    assert "MCP authentication is required" in capsys.readouterr().err


# ---------------------------------------------------------------------------
# (a) Token whitelist: any listed token is accepted, unknown tokens are
# rejected. Any token in the allowlist grants identical, full access — there
# is no per-token identity/role in this single-user gateway.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_allowlisted_token_is_accepted():
    provider = build_auth_provider(["tok-a", "tok-b"])
    access = await provider.verify_token("tok-a")
    assert access is not None
    assert access.token == "tok-a"


@pytest.mark.asyncio
async def test_unknown_token_is_rejected():
    provider = build_auth_provider(["tok-a", "tok-b"])
    assert await provider.verify_token("not-in-the-list") is None


def test_empty_token_list_builds_no_provider():
    assert build_auth_provider([]) is None


# ---------------------------------------------------------------------------
# (b) Single-backend passthrough: get_from_jadx must route to the one
# configured JADX backend regardless of the (now vestigial) instance_id arg.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_get_from_jadx_routes_to_the_single_configured_backend(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650, token="java-token")

    calls = []

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"ok": True}

    class FakeClient:
        async def get(self, url, params=None, headers=None, timeout=None):
            calls.append((url, headers))
            return FakeResponse()

    async def fake_get_http_client():
        return FakeClient()

    monkeypatch.setattr(request_router, "get_http_client", fake_get_http_client)

    result = await request_router.get_from_jadx("file-info", instance_id="whatever-is-ignored")

    assert result == {"ok": True}
    assert calls == [("http://127.0.0.1:8650/file-info", {"Authorization": "Bearer java-token"})]


@pytest.mark.asyncio
async def test_get_from_jadx_without_configured_backend_returns_no_instance():
    result = await request_router.get_from_jadx("file-info")
    assert result["error"] == "NO_INSTANCE"
    assert result["ok"] is False


@pytest.mark.asyncio
async def test_cancel_search_tool_is_not_registered():
    """Java 没有取消搜索端点、也没有多实例/动态实例管理时，Gateway 不得公开这些工具。"""
    from src.mcp_server import build_mcp_app

    tools = await build_mcp_app().list_tools()
    tool_names = {tool.name for tool in tools}

    assert "cancel_search" not in tool_names
    assert "add_jadx_instance" not in tool_names
    assert "list_jadx_instances" not in tool_names
    assert "remove_jadx_instance" not in tool_names
    assert "set_default_jadx_instance" not in tool_names
    assert "health_check_jadx_instances" not in tool_names
    assert "get_jadx_instance_info" not in tool_names
    assert "compare_versions" not in tool_names
    assert "get_jadx_guide" in tool_names
