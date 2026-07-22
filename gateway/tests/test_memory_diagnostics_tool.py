"""P1 memory: the GC-then-measure diagnostic must be reachable from the MCP side.

The Java backend binds 127.0.0.1:8650 inside the container and is never published, so a
diagnostic that only exists there cannot be used to answer "what is delamain's clean steady
state on this APK?" from outside. The gateway must proxy it, and must pass the gc flag through
(measuring without collecting first is the default-off case that produces misleading numbers).

Offline test — get_from_jadx is monkeypatched, no Java backend needed.
"""

import pytest

from src.tools import diagnostics_tools


@pytest.mark.asyncio
async def test_proxies_to_the_backend_endpoint_with_gc_on_by_default(monkeypatch):
    seen = {}

    async def fake_get(endpoint, params=None, instance_id=None):
        seen["endpoint"] = endpoint
        seen["params"] = params
        return {"heap": {"used_mb": 4321}, "gc": {"ran": True}}

    monkeypatch.setattr(diagnostics_tools, "get_from_jadx", fake_get)

    result = await diagnostics_tools.get_memory_diagnostics()

    assert seen["endpoint"] == "memory-diagnostics"
    assert seen["params"] == {"gc": "true"}, "GC must default ON — that is the point of the tool"
    assert result["heap"]["used_mb"] == 4321


@pytest.mark.asyncio
async def test_gc_can_be_turned_off_for_a_pure_observation(monkeypatch):
    seen = {}

    async def fake_get(endpoint, params=None, instance_id=None):
        seen["params"] = params
        return {}

    monkeypatch.setattr(diagnostics_tools, "get_from_jadx", fake_get)

    await diagnostics_tools.get_memory_diagnostics(gc=False)

    assert seen["params"] == {"gc": "false"}


@pytest.mark.asyncio
async def test_backend_failure_is_reported_not_raised(monkeypatch):
    async def boom(endpoint, params=None, instance_id=None):
        raise RuntimeError("connection refused")

    monkeypatch.setattr(diagnostics_tools, "get_from_jadx", boom)

    result = await diagnostics_tools.get_memory_diagnostics()

    assert result["error"] == "JADX_ERROR"
    assert "connection refused" in result["message"]
