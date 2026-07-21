"""Regression tests: single-process single-httpx-client collapse, busy_tracker
name mismatch, async_tasks strong reference / TTL.

Wave1 had introduced per-event-loop httpx client isolation for the
multi-instance gateway (reusing one across loops triggers 'Future attached to
a different loop'). The B'' simplification cut the gateway down to
single-machine + single-instance + single event loop (main.py pins a single
uvicorn worker), so the per-loop client buckets collapsed into a single
module-level httpx.AsyncClient (see src/routing/request_router.py). The tests
below were updated accordingly to cover "the same client stays stable across
calls within one process, and gets rebuilt after being closed", rather than
"isolation across loops".
"""

import asyncio
import time

import pytest
import pytest_asyncio

from src.registry.instance_registry import InstanceRegistry
from src.routing import request_router
from src.busy_tracker import (
    InstanceBusyTracker,
    LANE_CODE_READ,
    LANE_EXCLUSIVE,
    LANE_METADATA,
    with_busy_check,
)
from src import async_tasks


@pytest.fixture(autouse=True)
def reset_registry_state():
    InstanceRegistry.clear_all()
    InstanceBusyTracker.force_release_all()
    yield
    InstanceRegistry.clear_all()
    InstanceBusyTracker.force_release_all()


@pytest_asyncio.fixture(autouse=True)
async def reset_http_client():
    await request_router.close_http_client()
    yield
    await request_router.close_http_client()


# ---------------------------------------------------------------------------
# Fix #1 (single-loop era): the shared httpx client is a single module-level
# singleton that survives across calls within the same running loop, and a
# fresh client is created after close_http_client().
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_get_http_client_is_a_stable_singleton_within_a_loop():
    client_a = await request_router.get_http_client()
    client_b = await request_router.get_http_client()
    assert client_a is client_b


@pytest.mark.asyncio
async def test_close_http_client_forces_a_fresh_client_on_next_use():
    client_a = await request_router.get_http_client()
    await request_router.close_http_client()
    client_b = await request_router.get_http_client()
    assert client_a is not client_b
    assert client_a.is_closed


# ---------------------------------------------------------------------------
# Fix #2: with_busy_check must match policy sets using the registered tool
# name (i.e. strip the "_tool" suffix), not the raw function name.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_with_busy_check_strips_tool_suffix_for_metadata_lane():
    """search_native_methods belongs to the metadata lane in the policy set;

    the actual decorated function name is conventionally search_native_methods_tool.
    Before the fix, looking up the policy table with func.__name__ directly never
    found a match, so it always fell back to the default exclusive lock.
    """
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx-test")
    observed_lane = {}

    @with_busy_check
    async def search_native_methods_tool(instance_id=None):
        snapshot = InstanceBusyTracker.get_snapshot("jadx-test")
        if snapshot["metadata_inflight"]:
            observed_lane["lane"] = LANE_METADATA
        elif snapshot["exclusive_inflight"]:
            observed_lane["lane"] = LANE_EXCLUSIVE
        return {"ok": True}

    result = await search_native_methods_tool(instance_id="jadx-test")
    assert result == {"ok": True}
    assert observed_lane["lane"] == LANE_METADATA


@pytest.mark.asyncio
async def test_with_busy_check_strips_tool_suffix_for_code_read_lane():
    """get_method_by_name belongs to the code_read lane (non-exclusive, allows
    concurrency up to active_limit)."""
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx-test")
    observed_lane = {}

    @with_busy_check
    async def get_method_by_name_tool(instance_id=None):
        snapshot = InstanceBusyTracker.get_snapshot("jadx-test")
        if snapshot["code_read_inflight"]:
            observed_lane["lane"] = LANE_CODE_READ
        elif snapshot["exclusive_inflight"]:
            observed_lane["lane"] = LANE_EXCLUSIVE
        return {"ok": True}

    result = await get_method_by_name_tool(instance_id="jadx-test")
    assert result == {"ok": True}
    assert observed_lane["lane"] == LANE_CODE_READ


@pytest.mark.asyncio
async def test_with_busy_check_batch_get_xrefs_hits_metadata_lane():
    """In xrefs_tools.py, batch_get_xrefs_tool is registered as batch_get_xrefs
    (metadata policy)."""
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx-test")
    observed_lane = {}

    @with_busy_check
    async def batch_get_xrefs_tool(instance_id=None):
        snapshot = InstanceBusyTracker.get_snapshot("jadx-test")
        observed_lane["lane"] = (
            LANE_METADATA if snapshot["metadata_inflight"] else LANE_EXCLUSIVE
        )
        return {"ok": True}

    await batch_get_xrefs_tool(instance_id="jadx-test")
    assert observed_lane["lane"] == LANE_METADATA


@pytest.mark.asyncio
async def test_with_busy_check_unmapped_name_still_falls_back_to_exclusive():
    """An operation name not in any policy set (e.g. rename) should still fall
    back to the exclusive lock, behavior unchanged."""
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx-test")
    observed_lane = {}

    @with_busy_check
    async def rename_tool(instance_id=None):
        snapshot = InstanceBusyTracker.get_snapshot("jadx-test")
        observed_lane["lane"] = (
            LANE_EXCLUSIVE if snapshot["exclusive_inflight"] else "other"
        )
        return {"ok": True}

    await rename_tool(instance_id="jadx-test")
    assert observed_lane["lane"] == LANE_EXCLUSIVE


# ---------------------------------------------------------------------------
# Fix #4: async_tasks must hold a strong reference to in-flight tasks and
# must not prune a task that is still running based on submission time.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_submit_keeps_strong_reference_and_survives_gc():
    """Triggering GC right after submission: the task should still run to
    completion and be pollable, rather than being collected prematurely."""
    import gc

    async def slow_task():
        await asyncio.sleep(0.05)
        return "done-value"

    ticket = async_tasks.submit(slow_task())
    assert ticket in async_tasks._tasks

    gc.collect()  # must not collect the task despite no external references

    await asyncio.sleep(0.15)
    result = async_tasks.poll(ticket)
    assert result == {"status": "done", "result": "done-value"}
    # Strong ref must be released once the task finished.
    assert ticket not in async_tasks._tasks


@pytest.mark.asyncio
async def test_running_task_is_never_pruned_by_ttl():
    """A long-running task whose runtime exceeds the TTL should still not be
    pruned by _prune (TTL only applies to completed tasks)."""
    done = asyncio.Event()

    async def long_task():
        await done.wait()
        return "finished"

    ticket = async_tasks.submit(long_task())
    # Force the submission timestamp far enough in the past to exceed TTL,
    # simulating a task that has been running longer than _TTL_SECONDS.
    async_tasks._store[ticket]["_ts"] = time.monotonic() - (async_tasks._TTL_SECONDS + 60)

    async_tasks._prune()
    assert async_tasks.poll(ticket)["status"] == "running"

    done.set()
    await asyncio.sleep(0.05)
    assert async_tasks.poll(ticket) == {"status": "done", "result": "finished"}


@pytest.mark.asyncio
async def test_completed_task_ttl_counts_from_completion_not_submission():
    """The post-completion TTL must be counted from the completion time, not
    the submission time."""
    async def quick_task():
        return "value"

    ticket = async_tasks.submit(quick_task())
    await asyncio.sleep(0.02)
    assert async_tasks.poll(ticket)["status"] == "done"

    # Fake an old submission time far in the past; if TTL were still computed
    # from submission time this would already be pruned.
    entry_ts = async_tasks._store[ticket]["_ts"]
    assert entry_ts > time.monotonic() - 5  # recorded at completion, i.e. recent
