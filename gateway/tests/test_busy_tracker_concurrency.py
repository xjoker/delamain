"""Real-concurrency tests for the three/four-lane scheduler in busy_tracker.py.

Unlike test_registry_and_busy_tracker_fixes.py (which only checks operation-name
-> lane classification), these tests use asyncio.gather / asyncio.create_task to
make genuine concurrent callers contend for the same lock, so that arbitration
bugs (wrong active_limit, broken FIFO, metadata not blocked by exclusive, prune
not releasing, CancelledError leaving a ghost queue entry) cannot pass silently.
"""

from __future__ import annotations

import asyncio

import pytest

from src.busy_tracker import (
    ANALYSIS_OPERATIONS,
    CODE_READ_OPERATIONS,
    DEFAULT_ANALYSIS_ACTIVE_LIMIT,
    DEFAULT_CODE_READ_ACTIVE_LIMIT,
    DEFAULT_CODE_READ_QUEUE_LIMIT,
    EXCLUSIVE_OPERATIONS,
    LANE_METADATA,
    METADATA_OPERATIONS,
    InstanceBusyTracker,
    _DECORATED_OPERATIONS,
    validate_lane_coverage,
    with_busy_check,
)


@pytest.fixture(autouse=True)
def reset_state():
    InstanceBusyTracker.force_release_all()
    yield
    InstanceBusyTracker.force_release_all()


async def _settle():
    # Give queued coroutines a chance to reach their first await point.
    await asyncio.sleep(0.05)


async def _drain(tasks):
    for t in tasks:
        if not t.done():
            t.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)


# ---------------------------------------------------------------------------
# code_read lane: active_limit / queueing / no leak
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_code_read_fifth_concurrent_call_is_queued_not_rejected():
    hold = asyncio.Event()

    async def worker():
        result = await InstanceBusyTracker.try_acquire("get_class_source")
        if result.get("success"):
            await hold.wait()
            await InstanceBusyTracker.release(result.get("token"))
        return result

    tasks = [asyncio.create_task(worker()) for _ in range(5)]
    await _settle()

    snapshot = InstanceBusyTracker.get_snapshot()
    assert snapshot["code_read_inflight"] == DEFAULT_CODE_READ_ACTIVE_LIMIT == 4
    assert snapshot["queue_depth"] == 1

    hold.set()
    results = await asyncio.gather(*tasks)
    assert all(r.get("success") for r in results)

    final = InstanceBusyTracker.get_snapshot()
    assert final["code_read_inflight"] == 0
    assert final["queue_depth"] == 0


@pytest.mark.asyncio
async def test_code_read_queue_full_returns_instance_busy():
    async def worker():
        return await InstanceBusyTracker.try_acquire("get_class_source")

    total = DEFAULT_CODE_READ_ACTIVE_LIMIT + DEFAULT_CODE_READ_QUEUE_LIMIT + 1
    tasks = [asyncio.create_task(worker()) for _ in range(total)]
    await _settle()

    done_results = [t.result() for t in tasks if t.done()]
    busy = [r for r in done_results if r.get("error")]
    assert len(busy) == 1
    assert busy[0]["busy_reason"] == "code_read_queue_full"

    await _drain(tasks)


@pytest.mark.asyncio
async def test_code_read_queue_fifo_and_no_leak_after_sequential_release():
    hold_events = [asyncio.Event() for _ in range(6)]
    acquired_order: list[int] = []

    async def worker(idx: int):
        result = await InstanceBusyTracker.try_acquire("get_class_source")
        if result.get("success"):
            acquired_order.append(idx)
            await hold_events[idx].wait()
            await InstanceBusyTracker.release(result.get("token"))
        return result

    tasks = [asyncio.create_task(worker(i)) for i in range(6)]
    await _settle()
    assert InstanceBusyTracker.get_snapshot()["queue_depth"] == 2

    # Release active ones one at a time; queued ones (4, 5) must join in FIFO order.
    for i in range(4):
        hold_events[i].set()
        await _settle()

    assert acquired_order[:4] == [0, 1, 2, 3]
    assert acquired_order[4] == 4
    assert acquired_order[5] == 5

    for i in range(4, 6):
        hold_events[i].set()

    results = await asyncio.gather(*tasks)
    assert all(r.get("success") for r in results)

    final = InstanceBusyTracker.get_snapshot()
    assert final["code_read_inflight"] == 0
    assert final["queue_depth"] == 0


@pytest.mark.asyncio
async def test_cancelling_a_queued_operation_removes_it_from_the_queue():
    hold = asyncio.Event()

    async def blocker():
        result = await InstanceBusyTracker.try_acquire("get_class_source")
        await hold.wait()
        await InstanceBusyTracker.release(result.get("token"))

    blockers = [asyncio.create_task(blocker()) for _ in range(DEFAULT_CODE_READ_ACTIVE_LIMIT)]
    await _settle()

    queued = asyncio.create_task(InstanceBusyTracker.try_acquire("get_class_source"))
    await _settle()
    assert InstanceBusyTracker.get_snapshot()["queue_depth"] == 1

    queued.cancel()
    with pytest.raises(asyncio.CancelledError):
        await queued

    assert InstanceBusyTracker.get_snapshot()["queue_depth"] == 0

    hold.set()
    await asyncio.gather(*blockers)
    assert InstanceBusyTracker.get_snapshot()["code_read_inflight"] == 0


# ---------------------------------------------------------------------------
# exclusive vs metadata arbitration
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_metadata_is_blocked_while_exclusive_holds_and_released_after():
    exclusive_result = await InstanceBusyTracker.try_acquire("rename")
    assert exclusive_result.get("success")

    metadata_task = asyncio.create_task(InstanceBusyTracker.try_acquire("get_all_classes"))
    await _settle()
    assert not metadata_task.done()

    await InstanceBusyTracker.release(exclusive_result.get("token"))
    metadata_result = await metadata_task
    assert metadata_result.get("success")
    await InstanceBusyTracker.release(metadata_result.get("token"))


@pytest.mark.asyncio
async def test_metadata_wait_for_exclusive_has_a_total_budget_and_returns_busy():
    original_budget = InstanceBusyTracker._metadata_wait_seconds
    InstanceBusyTracker._metadata_wait_seconds = 0.3
    try:
        exclusive_result = await InstanceBusyTracker.try_acquire("rename")
        assert exclusive_result.get("success")

        metadata_result = await InstanceBusyTracker.try_acquire("get_all_classes")
        assert metadata_result.get("error")
        assert metadata_result["busy_reason"] == "metadata_wait_exclusive_timeout"

        await InstanceBusyTracker.release(exclusive_result.get("token"))
    finally:
        InstanceBusyTracker._metadata_wait_seconds = original_budget


# ---------------------------------------------------------------------------
# new analysis lane (active_limit=2, queued beyond that)
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_analysis_lane_has_its_own_active_limit_of_two():
    assert DEFAULT_ANALYSIS_ACTIVE_LIMIT == 2
    hold = asyncio.Event()

    async def worker():
        result = await InstanceBusyTracker.try_acquire("get_xrefs")
        if result.get("success"):
            await hold.wait()
            await InstanceBusyTracker.release(result.get("token"))
        return result

    tasks = [asyncio.create_task(worker()) for _ in range(3)]
    await _settle()

    snapshot = InstanceBusyTracker.get_snapshot()
    assert snapshot["analysis_inflight"] == 2
    assert snapshot["analysis_queue_depth"] == 1

    hold.set()
    results = await asyncio.gather(*tasks)
    assert all(r.get("success") for r in results)
    final = InstanceBusyTracker.get_snapshot()
    assert final["analysis_inflight"] == 0
    assert final["analysis_queue_depth"] == 0


@pytest.mark.asyncio
async def test_analysis_operations_membership():
    for op in ("get_xrefs", "batch_get_xrefs", "run_security_scan", "trace_data_flow",
               "find_callers_chain", "get_attack_surface", "export_callgraph"):
        assert op in ANALYSIS_OPERATIONS
    assert ANALYSIS_OPERATIONS.isdisjoint(METADATA_OPERATIONS)


# ---------------------------------------------------------------------------
# bind_partial default-filling fix for search_classes_by_keyword
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_search_classes_by_keyword_defaults_to_metadata_lane_when_search_in_omitted():
    observed = {}

    @with_busy_check
    async def search_classes_by_keyword_tool(keyword: str, search_in: str = "class,method,field"):
        snapshot = InstanceBusyTracker.get_snapshot()
        observed["lane"] = (
            LANE_METADATA if snapshot["metadata_inflight"] else "not_metadata"
        )
        return {"ok": True}

    await search_classes_by_keyword_tool("Foo")
    assert observed["lane"] == LANE_METADATA


# ---------------------------------------------------------------------------
# (6) startup lane-coverage assertion
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_all_with_busy_check_tools_have_an_explicit_lane_declaration():
    from fastmcp import FastMCP
    from src.tools import register_all_tools

    register_all_tools(FastMCP())

    # Must not raise: every @with_busy_check-decorated operation name is
    # covered by one of the four lane sets (or explicitly allowlisted).
    validate_lane_coverage()

    known = METADATA_OPERATIONS | CODE_READ_OPERATIONS | ANALYSIS_OPERATIONS | EXCLUSIVE_OPERATIONS
    uncovered = {
        name for name in _DECORATED_OPERATIONS
        if name not in known and name != "search_classes_by_keyword"
    }
    assert uncovered == set()


def test_validate_lane_coverage_fails_fast_on_undeclared_operation():
    _DECORATED_OPERATIONS.add("__totally_undeclared_probe__")
    try:
        with pytest.raises(RuntimeError):
            validate_lane_coverage()
    finally:
        _DECORATED_OPERATIONS.discard("__totally_undeclared_probe__")
