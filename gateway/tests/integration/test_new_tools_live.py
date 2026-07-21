"""End-to-end integration tests: require a real running delamain Java instance.

Not part of the default `uv run pytest` scope (pyproject.toml's addopts
excludes tests/integration); run it explicitly:

    JADX_LIVE_HOST=127.0.0.1 JADX_LIVE_PORT=48670 JADX_LIVE_TOKEN=test-token \
        uv run pytest tests/integration -q

Verifies that the 4 MCP tools newly wired up in the 2026-07 audit's Wave2
(get_code_metadata / list_resources_by_type / get_decoded_resource /
resolve_resource_id) genuinely get data back from the Java side, not just
that params are passed through correctly.

NOTE: The B'' simplification cut the gateway down to single-machine +
single-instance + single event loop; request_router's httpx client likewise
collapsed from per-loop buckets to a single module-level singleton
(src/routing/request_router.py). The fixture calls close_http_client() before
each test to make sure a client tied to a previously-closed loop doesn't get
reused by the next test's new loop; this is purely test-isolation hygiene and
no longer corresponds to any per-loop fix.
"""

import os

import pytest
import pytest_asyncio

from src.registry.instance_registry import InstanceRegistry
from src.routing import request_router
from src.tools import class_tools, resource_tools

LIVE_HOST = os.environ.get("JADX_LIVE_HOST", "127.0.0.1")
LIVE_PORT = int(os.environ.get("JADX_LIVE_PORT", "48670"))
LIVE_TOKEN = os.environ.get("JADX_LIVE_TOKEN", "test-token")
INSTANCE_NAME = "live-wave2"

# UnCrackable-Level2 class known to exist once the harness APK is loaded.
KNOWN_CLASS = "sg.vantagepoint.uncrackable2.MainActivity"


@pytest_asyncio.fixture(autouse=True)
async def live_instance():
    """Register the externally-started Java instance as the single configured backend."""
    InstanceRegistry.clear_all()
    InstanceRegistry.configure(
        name=INSTANCE_NAME, host=LIVE_HOST, port=LIVE_PORT, token=LIVE_TOKEN,
    )
    await request_router.close_http_client()
    yield
    InstanceRegistry.clear_all()


@pytest.mark.asyncio
async def test_get_code_metadata_list_and_position_resolve():
    listing = await class_tools.get_code_metadata(KNOWN_CLASS, instance_id=INSTANCE_NAME)

    assert listing.get("has_metadata") is True
    assert listing["reference_count"] > 0
    assert isinstance(listing["references"], list) and listing["references"]
    first = listing["references"][0]
    assert "raw_name" in first and "alias_name" in first
    assert "kind" in first and "position" in first

    position = first["position"]
    resolved = await class_tools.get_code_metadata(
        KNOWN_CLASS, position=position, instance_id=INSTANCE_NAME
    )
    assert "at" in resolved
    assert resolved["at"]["position"] == position
    assert resolved["at"]["resolved_by"] in ("exact", "enclosing", "closest_up")


@pytest.mark.asyncio
async def test_list_resources_by_type_summary_and_filter():
    summary = await resource_tools.list_resources_by_type(instance_id=INSTANCE_NAME)
    assert summary["status"] == "summary"
    assert summary["total_files"] > 0
    assert "layout" in summary["type_distribution"]

    filtered = await resource_tools.list_resources_by_type(
        resource_type="layout", limit=5, instance_id=INSTANCE_NAME
    )
    assert filtered["status"] == "success"
    assert filtered["resource_type"] == "layout"
    assert filtered["total"] > 0
    assert len(filtered["files"]) <= 5
    assert all(f.startswith("res/layout") for f in filtered["files"])


@pytest.mark.asyncio
async def test_get_decoded_resource_found_and_not_found():
    listing = await resource_tools.list_resources_by_type(
        resource_type="layout", limit=1, instance_id=INSTANCE_NAME
    )
    file_name = listing["files"][0]

    found = await resource_tools.get_decoded_resource(file_name, instance_id=INSTANCE_NAME)
    assert found["status"] == "success"
    assert found["file_name"] == file_name
    assert "<" in found["content"]  # decoded XML, not raw binary

    missing = await resource_tools.get_decoded_resource(
        "res/layout/does_not_exist_xyz.xml", instance_id=INSTANCE_NAME
    )
    assert missing["status"] == "not_found"


@pytest.mark.asyncio
async def test_resolve_resource_id_name_id_roundtrip_and_list_all():
    by_name = await resource_tools.resolve_resource_id(name="ic_launcher", instance_id=INSTANCE_NAME)
    assert by_name["status"] == "success"
    assert by_name["query_type"] == "name_to_id"
    assert by_name["matches"], "expected at least one ic_launcher match"

    match = by_name["matches"][0]
    by_id = await resource_tools.resolve_resource_id(
        resource_id=match["id_hex"], instance_id=INSTANCE_NAME
    )
    assert by_id["status"] == "success"
    assert by_id["query_type"] == "id_to_name"
    assert by_id["found"] is True
    assert by_id["key_name"] == match["key_name"]

    all_page = await resource_tools.resolve_resource_id(limit=3, instance_id=INSTANCE_NAME)
    assert all_page["status"] == "success"
    assert all_page["query_type"] == "list_all"
    assert len(all_page["entries"]) == 3
    assert all_page["has_more"] is True
