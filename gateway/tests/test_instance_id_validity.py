"""RED→GREEN regression tests for the instance_id validity contract.

Root cause (see task brief): request_router.get_from_jadx silently ignored
instance_id and always routed to the single fixed backend, and
busy_tracker._resolve_instance_name mirrored the same silent override —
passing an unknown instance_id (e.g. a stale APK filename from a previous
session) silently returned results from whatever is actually loaded instead
of erroring. Both must now reject an instance_id that does not match the
backend's own name ("jadx") or the identity of whatever APK/JAR is currently
loaded (per /file-info), instead of silently proceeding.

list_loaded_files must source loaded/identity state from /file-info (the
identity authority per ApkIdentity.java), not the legacy /apk-info shape
that never carried file_name/apk_package — using it created a split-brain
where /file-info said "loaded" and list_loaded_files said "nothing loaded".
"""

import pytest

from src.registry.instance_registry import InstanceRegistry
from src.routing import request_router
from src.busy_tracker import InstanceBusyTracker, with_busy_check
from src.tools import workflow_tools


FILE_INFO_LOADED = {
    "type": "file-info",
    "loaded": True,
    "file_name": "9290803.apk",
    "apk_package": "com.example.target",
    "version_name": "3.2.1",
    "version_code": 321,
    "class_count": 4200,
    "status": "success",
}


@pytest.fixture(autouse=True)
def reset_instance_id_validity_state():
    InstanceRegistry.clear_all()
    InstanceBusyTracker.force_release_all()
    request_router.reset_identity_cache()
    yield
    InstanceRegistry.clear_all()
    InstanceBusyTracker.force_release_all()
    request_router.reset_identity_cache()


def _install_fake_backend(monkeypatch, responses):
    """Fake httpx client keyed by the last URL path segment (endpoint name)."""

    class FakeResponse:
        def __init__(self, payload):
            self._payload = payload

        def raise_for_status(self):
            return None

        def json(self):
            return self._payload

    class FakeClient:
        async def get(self, url, params=None, headers=None, timeout=None):
            path = url.rsplit("/", 1)[-1]
            return FakeResponse(responses.get(path, {}))

        async def request(self, method, url, json=None, params=None, headers=None, timeout=None):
            path = url.rsplit("/", 1)[-1]
            return FakeResponse(responses.get(path, {}))

    async def fake_get_http_client():
        return FakeClient()

    monkeypatch.setattr(request_router, "get_http_client", fake_get_http_client)


# ---------------------------------------------------------------------------
# a. Unknown instance_id must be rejected, not silently routed to whatever is
#    actually loaded.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_unknown_instance_id_returns_instance_not_found(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx")
    _install_fake_backend(monkeypatch, {"file-info": FILE_INFO_LOADED})

    result = await request_router.get_from_jadx("class-info", instance_id="xhs-9281803.apk")

    assert result["error_code"] == "INSTANCE_NOT_FOUND"
    assert result["ok"] is False
    assert "9290803.apk" in result["message"]
    assert "3.2.1" in result["message"]


# ---------------------------------------------------------------------------
# b. instance_id == the configured backend name always passes through, no
#    identity round trip needed.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_instance_id_matching_backend_name_passes_through(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx")
    calls = []

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"ok": True}

    class FakeClient:
        async def get(self, url, params=None, headers=None, timeout=None):
            calls.append(url)
            return FakeResponse()

    async def fake_get_http_client():
        return FakeClient()

    monkeypatch.setattr(request_router, "get_http_client", fake_get_http_client)

    result = await request_router.get_from_jadx("class-info", instance_id="jadx")

    assert result == {"ok": True}
    assert calls == ["http://127.0.0.1:8650/class-info"]


# ---------------------------------------------------------------------------
# c. instance_id matching the currently loaded file's file_name passes.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_instance_id_matching_loaded_file_name_passes_through(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx")
    _install_fake_backend(monkeypatch, {
        "file-info": FILE_INFO_LOADED,
        "class-info": {"status": "success"},
    })

    result = await request_router.get_from_jadx("class-info", instance_id="9290803.apk")

    assert result == {"status": "success"}


# ---------------------------------------------------------------------------
# d. instance_id omitted (None) behaves exactly as before: zero extra round
#    trip — no identity lookup call is ever made.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_instance_id_none_takes_no_extra_round_trip(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx")
    calls = []

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {"ok": True}

    class FakeClient:
        async def get(self, url, params=None, headers=None, timeout=None):
            calls.append(url)
            return FakeResponse()

    async def fake_get_http_client():
        return FakeClient()

    monkeypatch.setattr(request_router, "get_http_client", fake_get_http_client)

    result = await request_router.get_from_jadx("class-info")

    assert result == {"ok": True}
    assert calls == ["http://127.0.0.1:8650/class-info"]


# ---------------------------------------------------------------------------
# e. list_loaded_files must source loaded/identity from /file-info, not the
#    legacy /apk-info shape (split-brain fix), and echo version metadata.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_list_loaded_files_reflects_file_info_identity(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx")
    _install_fake_backend(monkeypatch, {
        "file-info": FILE_INFO_LOADED,
        "decompile-status": {"cached_percentage": 42},
    })

    result = await workflow_tools.list_loaded_files()

    assert result["count"] == 1
    entry = result["instances"][0]
    assert entry["loaded_file"] == "9290803.apk"
    assert entry["available"] is False
    assert result["free_count"] == 0
    assert entry["version_name"] == "3.2.1"
    assert entry["version_code"] == 321


@pytest.mark.asyncio
async def test_list_loaded_files_reports_available_when_nothing_loaded(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx")
    _install_fake_backend(monkeypatch, {
        "file-info": {"type": "file-info", "loaded": False, "error": "No file loaded"},
        "decompile-status": {"cached_percentage": 0},
    })

    result = await workflow_tools.list_loaded_files()

    entry = result["instances"][0]
    assert entry["loaded_file"] is None
    assert entry["available"] is True
    assert result["free_count"] == 1


# ---------------------------------------------------------------------------
# busy_tracker must not silently accept an unknown instance_id either — it
# should reject before ever acquiring a lane lock.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_busy_tracker_rejects_unknown_instance_id(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx")
    _install_fake_backend(monkeypatch, {"file-info": FILE_INFO_LOADED})

    @with_busy_check
    async def get_class_info_tool(instance_id=None):
        return {"ok": True}

    result = await get_class_info_tool(instance_id="xhs-9281803.apk")

    assert result["error_code"] == "INSTANCE_NOT_FOUND"
    snapshot = InstanceBusyTracker.get_snapshot("jadx")
    assert snapshot["metadata_inflight"] == 0
    assert snapshot["exclusive_inflight"] == 0


@pytest.mark.asyncio
async def test_busy_tracker_accepts_backend_name_instance_id(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx")

    @with_busy_check
    async def get_class_info_tool(instance_id=None):
        return {"ok": True}

    result = await get_class_info_tool(instance_id="jadx")

    assert result == {"ok": True}
