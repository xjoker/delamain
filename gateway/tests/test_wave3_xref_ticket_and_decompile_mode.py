"""2026-07 审计 Wave3 回归测试：xref 异步 ticket (submit_xref/get_xref_result) +
decompile_with_mode 接线。

离线测试（不需要真实 Java 实例）。端到端验证见 test-harness/validate.py +
tests/test_java_endpoints.py（连 48651/48650）。
"""

import pytest

from src.tools import xrefs_tools, decompile_tools


# ---------------------------------------------------------------------------
# Registration: the 3 new tools must be exposed to MCP clients.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_wave3_tools_are_registered():
    from src.mcp_server import build_mcp_app

    tools = await build_mcp_app().list_tools()
    tool_names = {tool.name for tool in tools}

    assert "submit_xref" in tool_names
    assert "get_xref_result" in tool_names
    assert "decompile_with_mode" in tool_names


# ---------------------------------------------------------------------------
# A. submit_xref param mapping
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_submit_xref_single_target_maps_params(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params, kwargs.get("method")))
        return {"ticket": "abc123", "status": "submitted", "retry_after_seconds": 3}

    monkeypatch.setattr(xrefs_tools, "get_from_jadx", fake_get_from_jadx)

    await xrefs_tools.submit_xref(
        target_type="class", class_name="com.example.Foo", instance_id="inst-1",
    )
    endpoint, params, method = calls[-1]
    assert endpoint == "submit-xref"
    assert params == {"target_type": "class", "class_name": "com.example.Foo"}
    assert method == "POST"

    await xrefs_tools.submit_xref(
        target_type="method", class_name="com.example.Foo", member_name="bar",
        include_snippet=True, context_lines=5, instance_id="inst-1",
    )
    endpoint, params, method = calls[-1]
    assert params == {
        "target_type": "method", "class_name": "com.example.Foo",
        "member_name": "bar", "include_snippet": "true", "context_lines": "5",
    }


@pytest.mark.asyncio
async def test_submit_xref_batch_mode_ignores_single_target_params(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        return {"ticket": "abc123", "status": "submitted"}

    monkeypatch.setattr(xrefs_tools, "get_from_jadx", fake_get_from_jadx)

    await xrefs_tools.submit_xref(
        targets=["class:com.example.Foo", "method:com.example.Bar:baz"],
        target_type="class", class_name="should-be-ignored",
        instance_id="inst-1",
    )
    endpoint, params = calls[-1]
    assert endpoint == "submit-xref"
    assert params == {"targets": "class:com.example.Foo,method:com.example.Bar:baz"}


# ---------------------------------------------------------------------------
# B. get_xref_result param mapping
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_get_xref_result_maps_params(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        return {"status": "running", "retry_after_seconds": 3}

    monkeypatch.setattr(xrefs_tools, "get_from_jadx", fake_get_from_jadx)

    await xrefs_tools.get_xref_result("abc123", instance_id="inst-1")
    assert calls[-1] == ("xref-status", {"ticket": "abc123", "offset": 0, "count": 100})

    await xrefs_tools.get_xref_result("abc123", offset=20, count=10, instance_id="inst-1")
    assert calls[-1] == ("xref-status", {"ticket": "abc123", "offset": 20, "count": 10})


# ---------------------------------------------------------------------------
# C. decompile_with_mode param mapping
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_decompile_with_mode_maps_params(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        return {"response": "class Foo {}", "mode": "FALLBACK", "ephemeral": True}

    monkeypatch.setattr(decompile_tools, "get_from_jadx", fake_get_from_jadx)

    await decompile_tools.decompile_with_mode("com.example.Foo", instance_id="inst-1")
    assert calls[-1] == ("decompile-with-mode", {"class_name": "com.example.Foo", "mode": "FALLBACK"})

    await decompile_tools.decompile_with_mode(
        "com.example.Foo", mode="SIMPLE", comments_level="ERROR", chunk=2, instance_id="inst-1",
    )
    assert calls[-1] == ("decompile-with-mode", {
        "class_name": "com.example.Foo", "mode": "SIMPLE",
        "comments_level": "ERROR", "chunk": "2",
    })


@pytest.mark.asyncio
async def test_decompile_with_mode_result_preserves_ephemeral_and_note(monkeypatch):
    """The gateway must pass through ephemeral/note/raw_class untouched — the tool's docstring
    promises callers this is a non-persistent view, and that promise depends on these fields
    reaching the caller as-is."""
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        return {
            "response": "class Foo {}", "mode": "FALLBACK", "ephemeral": True,
            "raw_class": "com.example.Foo", "class_name": "com.example.Foo",
            "note": "Single-class, non-persistent re-decompile in the requested mode.",
        }

    monkeypatch.setattr(decompile_tools, "get_from_jadx", fake_get_from_jadx)

    result = await decompile_tools.decompile_with_mode("com.example.Foo", instance_id="inst-1")
    assert result["ephemeral"] is True
    assert "note" in result
    assert result["raw_class"] == "com.example.Foo"
