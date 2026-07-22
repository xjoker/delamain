"""Regression: a cold-index xref that times out at the 120s HTTP ceiling must
return ACTIONABLE guidance, not a dead-end {"error":"TIMEOUT"}.

Empirically reproduced on the 244,933-class XHS APK: cold-state sync get_xrefs on
a high-fan-in class falls back to live-decompiling every referrer and hits the
gateway's TIMEOUT_CODE_READ=120s ceiling. The async submit_xref path returns a
ticket in ~0.03s and never blocks, but the AI agent has no way to know that from
the bare timeout error. These tests pin the guidance so the timeout points the
caller at (1) submit_xref (async ticket) and (2) warmup.

Offline tests — get_from_jadx is monkeypatched, no Java backend needed.
"""

import pytest

from src.tools import xrefs_tools


def _timeout_from_router(endpoint):
    """Shape the router returns on httpx timeout (see request_router._routing_error)."""
    return {
        "error": "TIMEOUT",
        "message": f"Request to {endpoint} timed out after 120s",
        "ok": False,
        "error_code": "TIMEOUT",
    }


def _assert_actionable(result):
    # bare error must be upgraded to actionable guidance
    assert result.get("error") == "TIMEOUT"
    suggestion = (result.get("suggestion") or "").lower()
    assert "submit_xref" in suggestion, f"no async-path guidance in: {result!r}"
    assert "warmup" in suggestion or "warm" in suggestion
    nxt = result.get("next_action")
    assert isinstance(nxt, dict) and nxt.get("tool") == "submit_xref"
    return nxt


@pytest.mark.asyncio
async def test_class_xref_timeout_points_to_async_and_warmup(monkeypatch):
    async def fake(endpoint, params=None, *a, **k):
        return _timeout_from_router(endpoint)

    monkeypatch.setattr(xrefs_tools, "get_from_jadx", fake)

    result = await xrefs_tools.get_xrefs_to_class("com.xingin.xhs.app.XhsApplication")
    nxt = _assert_actionable(result)
    assert nxt["args"]["target_type"] == "class"
    assert nxt["args"]["class_name"] == "com.xingin.xhs.app.XhsApplication"


@pytest.mark.asyncio
async def test_method_xref_timeout_carries_member_name(monkeypatch):
    async def fake(endpoint, params=None, *a, **k):
        return _timeout_from_router(endpoint)

    monkeypatch.setattr(xrefs_tools, "get_from_jadx", fake)

    result = await xrefs_tools.get_xrefs_to_method("com.example.Foo", "doWork")
    nxt = _assert_actionable(result)
    assert nxt["args"]["target_type"] == "method"
    assert nxt["args"]["class_name"] == "com.example.Foo"
    assert nxt["args"]["member_name"] == "doWork"


@pytest.mark.asyncio
async def test_field_xref_timeout_carries_member_name(monkeypatch):
    async def fake(endpoint, params=None, *a, **k):
        return _timeout_from_router(endpoint)

    monkeypatch.setattr(xrefs_tools, "get_from_jadx", fake)

    result = await xrefs_tools.get_xrefs_to_field("com.example.Foo", "sInstance")
    nxt = _assert_actionable(result)
    assert nxt["args"]["target_type"] == "field"
    assert nxt["args"]["member_name"] == "sInstance"


@pytest.mark.asyncio
async def test_batch_xref_timeout_is_actionable(monkeypatch):
    async def fake(endpoint, params=None, *a, **k):
        return _timeout_from_router(endpoint)

    monkeypatch.setattr(xrefs_tools, "get_from_jadx", fake)

    result = await xrefs_tools.batch_get_xrefs(["class:com.example.Foo"])
    _assert_actionable(result)


@pytest.mark.asyncio
async def test_successful_xref_is_not_augmented(monkeypatch):
    """A normal (non-timeout) result must be returned untouched — no false hints."""
    async def fake(endpoint, params=None, *a, **k):
        return {"references": [{"class": "com.example.Caller"}], "resolution": "class-level"}

    monkeypatch.setattr(xrefs_tools, "get_from_jadx", fake)

    result = await xrefs_tools.get_xrefs_to_class("com.example.Foo")
    assert "next_action" not in result
    assert result.get("error") != "TIMEOUT"
