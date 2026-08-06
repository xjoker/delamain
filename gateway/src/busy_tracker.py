"""
Busy tracking and per-lane scheduling for MCP tool calls against the single
configured JADX backend.

Used as an optional decorator (no GUI state required).
"""

from __future__ import annotations

import asyncio
import functools
import inspect
import re
import time
import uuid
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable

from .logging_config import get_logger
from .types import ErrorCode


logger = get_logger("busy_tracker")

DEFAULT_CODE_READ_QUEUE_LIMIT = 8
DEFAULT_CODE_READ_ACTIVE_LIMIT = 4
DEFAULT_ANALYSIS_QUEUE_LIMIT = 4
DEFAULT_ANALYSIS_ACTIVE_LIMIT = 2
DEFAULT_EXCLUSIVE_WAIT_SECONDS = 2.0
# Metadata has no active_limit (it's cheap), but it must still not hang
# forever behind a long-running exclusive op: give it the same order of
# magnitude wait budget as the exclusive lane, then return INSTANCE_BUSY so
# the caller can retry instead of hanging silently until the 300s prune.
DEFAULT_METADATA_WAIT_SECONDS = 2.0

LANE_METADATA = "metadata"
LANE_CODE_READ = "code_read"
LANE_ANALYSIS = "analysis"
LANE_EXCLUSIVE = "exclusive"

_SEARCH_IN_SPLIT_RE = re.compile(r"[,/]")

# Operations tracked by `with_busy_check` but exempt from the lane-coverage
# assertion below because they have a bespoke policy branch in
# get_operation_policy() rather than a static set membership.
_LANE_COVERAGE_EXEMPT = {"search_classes_by_keyword"}

# Populated by with_busy_check() as tool modules are imported/registered.
# validate_lane_coverage() checks this against the declared lane sets so that
# a newly added @with_busy_check tool with no explicit lane fails fast at
# startup instead of silently falling back to LANE_EXCLUSIVE.
_DECORATED_OPERATIONS: set[str] = set()

METADATA_OPERATIONS = {
    "get_all_classes",
    "get_methods_of_class",
    "get_fields_of_class",
    "get_file_info",
    "get_package_classes",
    "get_class_info",
    "get_decompile_status",
    "get_method_signature",
    "search_native_methods",
    "get_all_resource_file_names",
    "get_code_search_result",
    "get_task_result",
    # Pure-read / local-generation operations: no reason to serialize these
    # behind the exclusive lane just because they aren't in the two lists
    # above. Without an explicit lane, get_operation_policy()'s fallback puts
    # them in LANE_EXCLUSIVE (see below), which needlessly blocks them behind
    # writes and each other.
    "generate_frida_hook",
    "generate_frida_trace",
    "generate_frida_enum",
    "generate_apk_digest",
    "save_analysis_session",
    "load_analysis_session",
    "list_analysis_sessions",
    "get_tags",
    "get_annotations",
    "list_bookmarks",
    # Audited (task F4, item 1): these were unclassified reads that fell back
    # to LANE_EXCLUSIVE and got needlessly serialized behind writes/each
    # other. get_xref_result in particular is the poll half of the same
    # submit/poll ticket pattern as get_code_search_result/get_task_result
    # (both metadata), so it belongs here for consistency.
    "get_xref_result",
    "get_index_stats",
    "get_memory_diagnostics",
    "get_decompile_diag",
    "list_packages",
    "resolve_resource_id",
    "list_resources_by_type",
    "search_string_literals",
    "get_native_surface",
    "get_analysis_summary",
    "get_decompile_priority_list",
    "export_analysis_report",
    "export_rename_mappings",
}

CODE_READ_OPERATIONS = {
    "get_class_source",
    "get_method_by_name",
    "get_smali_of_class",
    "get_android_manifest",
    "get_resource_file",
    "jar_get_manifest",
    "jar_get_services",
    "jar_get_entry_points",
    "jar_get_dependencies",
    "jar_get_bytecode",
    "get_main_activity_class",
    "get_strings",
    "get_config_strings",
    "get_method_callees",
    "batch_get_class_source",
    "batch_get_method_by_name",
    # Audited (task F4, item 1): unclassified reads that belong in code_read.
    "get_method_source",
    "get_smali_of_method",
    "decompile_with_mode",
    "smart_decompile",
    "get_code_metadata",
    "get_decoded_resource",
}

# New lane (task F4, item 3): these are full-repo-traversal-grade operations
# (documented 60s+ on a main Activity) that used to sit in the unlimited
# metadata lane, where N concurrent callers could saturate the Java-side
# thread pool and silently bypass code_read's active_limit=4/queue_limit=8.
# Give them their own bounded concurrency instead.
ANALYSIS_OPERATIONS = {
    "get_xrefs",
    "batch_get_xrefs",
    "run_security_scan",
    "trace_data_flow",
    "find_callers_chain",
    "get_attack_surface",
    "export_callgraph",
}

EXCLUSIVE_OPERATIONS = {
    "rename",
    # Audited (task F4, item 1): write operations. These already fell back to
    # LANE_EXCLUSIVE correctly by default; declared explicitly here so the
    # lane-coverage assertion (item 6) doesn't flag them as drift.
    "add_annotation",
    "delete_annotation",
    "add_bookmark",
    "delete_bookmark",
    "add_tag",
    "delete_tag",
    "load_file",
    # Same write as load_file, reached through the higher-level workflow entry point.
    # It went unscheduled until 2026-08-06: the lane-coverage check only sees operations
    # with_busy_check registered, so a tool missing the decorator entirely was invisible
    # to it — and this is the load path an AI is more likely to pick.
    "analyze_apk",
    "rename_variable",
    "import_rename_mappings",
    "apply_proguard_mapping",
}


@dataclass(frozen=True)
class OperationPolicy:
    lane: str
    queue_limit: int = 0
    active_limit: int = 1


@dataclass
class ActiveOperation:
    token: str
    operation: str
    lane: str
    started_monotonic: float
    started_at: str


@dataclass
class QueuedOperation:
    token: str
    operation: str
    requested_monotonic: float
    requested_at: str


@dataclass
class InstanceSchedulerState:
    metadata_active: dict[str, ActiveOperation] = field(default_factory=dict)
    code_read_active: dict[str, ActiveOperation] = field(default_factory=dict)
    analysis_active: dict[str, ActiveOperation] = field(default_factory=dict)
    exclusive_active: dict[str, ActiveOperation] = field(default_factory=dict)
    code_read_queue: deque[QueuedOperation] = field(default_factory=deque)
    analysis_queue: deque[QueuedOperation] = field(default_factory=deque)
    last_busy_reason: str = ""
    last_busy_at: str | None = None
    condition: asyncio.Condition = field(default_factory=asyncio.Condition)


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _split_search_in(value: Any) -> set[str]:
    if not isinstance(value, str) or not value.strip():
        return {"code"}
    tokens = {
        token.strip().lower()
        for token in _SEARCH_IN_SPLIT_RE.split(value)
        if token.strip()
    }
    return tokens or {"code"}


def get_operation_policy(operation_name: str, kwargs: dict[str, Any] | None = None) -> OperationPolicy:
    if operation_name == "search_classes_by_keyword":
        search_in = _split_search_in((kwargs or {}).get("search_in"))
        if search_in & {"code", "comment"}:
            return OperationPolicy(lane=LANE_EXCLUSIVE)
        return OperationPolicy(lane=LANE_METADATA)

    if operation_name in METADATA_OPERATIONS:
        return OperationPolicy(lane=LANE_METADATA)

    if operation_name in CODE_READ_OPERATIONS:
        return OperationPolicy(
            lane=LANE_CODE_READ,
            queue_limit=DEFAULT_CODE_READ_QUEUE_LIMIT,
            active_limit=DEFAULT_CODE_READ_ACTIVE_LIMIT,
        )

    if operation_name in ANALYSIS_OPERATIONS:
        return OperationPolicy(
            lane=LANE_ANALYSIS,
            queue_limit=DEFAULT_ANALYSIS_QUEUE_LIMIT,
            active_limit=DEFAULT_ANALYSIS_ACTIVE_LIMIT,
        )

    if operation_name in EXCLUSIVE_OPERATIONS:
        return OperationPolicy(lane=LANE_EXCLUSIVE)

    return OperationPolicy(lane=LANE_EXCLUSIVE)


def validate_lane_coverage() -> None:
    """Fail fast if any @with_busy_check-decorated tool has no explicit lane.

    Must be called after all tool modules have registered their tools (i.e.
    after register_all_tools(mcp) has run), since with_busy_check() only
    records an operation name into _DECORATED_OPERATIONS at decoration time,
    which for this codebase happens when the enclosing register_*_tools(mcp)
    function executes.

    Not wired into main.py by this change (out of scope for busy_tracker.py);
    the caller should invoke this once at startup, right after
    register_all_tools(mcp).
    """
    known = METADATA_OPERATIONS | CODE_READ_OPERATIONS | ANALYSIS_OPERATIONS | EXCLUSIVE_OPERATIONS
    missing = sorted(_DECORATED_OPERATIONS - known - _LANE_COVERAGE_EXEMPT)
    if missing:
        raise RuntimeError(
            "busy_tracker.validate_lane_coverage: the following @with_busy_check "
            "operations have no explicit lane declaration and would silently "
            "fall back to LANE_EXCLUSIVE: "
            f"{missing}. Add each to METADATA_OPERATIONS, CODE_READ_OPERATIONS, "
            "ANALYSIS_OPERATIONS, or EXCLUSIVE_OPERATIONS in src/busy_tracker.py."
        )


class InstanceBusyTracker:
    """Per-lane scheduler for the single configured JADX backend.

    Single fixed backend: one scheduler state for the whole process, no
    per-instance bucketing.
    """

    _state: InstanceSchedulerState = InstanceSchedulerState()
    _timeout_seconds: int = 300
    _exclusive_wait_seconds: float = DEFAULT_EXCLUSIVE_WAIT_SECONDS
    _metadata_wait_seconds: float = DEFAULT_METADATA_WAIT_SECONDS

    @classmethod
    def force_release_all(cls) -> None:
        cls._state = InstanceSchedulerState()

    @classmethod
    def _get_state(cls) -> InstanceSchedulerState:
        return cls._state

    @classmethod
    def _serialize_active(cls, op: ActiveOperation) -> dict[str, Any]:
        return {
            "token": op.token,
            "operation": op.operation,
            "lane": op.lane,
            "started_at": op.started_at,
            "age_seconds": round(max(0.0, time.monotonic() - op.started_monotonic), 3),
        }

    @classmethod
    def _active_operations(cls, state: InstanceSchedulerState) -> list[ActiveOperation]:
        return [
            *state.metadata_active.values(),
            *state.code_read_active.values(),
            *state.analysis_active.values(),
            *state.exclusive_active.values(),
        ]

    @classmethod
    def _prune_expired_locked(cls, state: InstanceSchedulerState) -> bool:
        now = time.monotonic()
        released = False

        for bucket in (
            state.metadata_active,
            state.code_read_active,
            state.analysis_active,
            state.exclusive_active,
        ):
            expired_tokens = [
                token
                for token, operation in bucket.items()
                if now - operation.started_monotonic >= cls._timeout_seconds
            ]
            for token in expired_tokens:
                bucket.pop(token, None)
                released = True

        if released:
            state.last_busy_reason = "auto_release_timeout"
            state.last_busy_at = _utc_now_iso()
        return released

    @classmethod
    def _record_busy_locked(cls, state: InstanceSchedulerState, reason: str) -> None:
        state.last_busy_reason = reason
        state.last_busy_at = _utc_now_iso()

    @classmethod
    def _conflicting_active_locked(
        cls,
        state: InstanceSchedulerState,
        lane: str,
    ) -> list[ActiveOperation]:
        if lane == LANE_METADATA:
            return list(state.exclusive_active.values())
        if lane == LANE_CODE_READ:
            return list(state.exclusive_active.values()) + list(state.code_read_active.values())
        if lane == LANE_ANALYSIS:
            return list(state.exclusive_active.values()) + list(state.analysis_active.values())
        return cls._active_operations(state)

    @classmethod
    def _bounded_active_locked(cls, state: InstanceSchedulerState, lane: str) -> dict[str, ActiveOperation]:
        return state.code_read_active if lane == LANE_CODE_READ else state.analysis_active

    @classmethod
    def _bounded_queue_locked(cls, state: InstanceSchedulerState, lane: str) -> deque[QueuedOperation]:
        return state.code_read_queue if lane == LANE_CODE_READ else state.analysis_queue

    @classmethod
    def _set_bounded_queue_locked(cls, state: InstanceSchedulerState, lane: str, new_queue: deque[QueuedOperation]) -> None:
        if lane == LANE_CODE_READ:
            state.code_read_queue = new_queue
        else:
            state.analysis_queue = new_queue

    @classmethod
    def _can_start_bounded_locked(cls, state: InstanceSchedulerState, lane: str, active_limit: int) -> bool:
        return not state.exclusive_active and len(cls._bounded_active_locked(state, lane)) < active_limit

    @classmethod
    def _can_start_exclusive_locked(cls, state: InstanceSchedulerState) -> bool:
        return (
            not state.metadata_active
            and not state.code_read_active
            and not state.analysis_active
            and not state.exclusive_active
            and not state.code_read_queue
            and not state.analysis_queue
        )

    @classmethod
    def _build_busy_result(
        cls,
        *,
        state: InstanceSchedulerState,
        lane: str,
        operation_name: str,
        queue_limit: int,
        active_limit: int,
        reason: str,
        waited_seconds: float = 0.0,
        queue: deque[QueuedOperation] | None = None,
    ) -> dict[str, Any]:
        cls._record_busy_locked(state, reason)

        conflicts = cls._conflicting_active_locked(state, lane)
        current = min(conflicts, key=lambda item: item.started_monotonic, default=None)
        if current is None and queue:
            head = queue[0]
            current_operation = f"queued:{head.operation}"
            busy_since = head.requested_at
            elapsed_seconds = round(max(0.0, time.monotonic() - head.requested_monotonic), 3)
        elif current is None:
            current_operation = operation_name
            busy_since = state.last_busy_at or _utc_now_iso()
            elapsed_seconds = 0.0
        else:
            current_operation = current.operation
            busy_since = current.started_at
            elapsed_seconds = round(max(0.0, time.monotonic() - current.started_monotonic), 3)

        return {
            "error": ErrorCode.INSTANCE_BUSY,
            "message": f"JADX backend is busy with {current_operation}",
            "lane": lane,
            "current_operation": current_operation,
            "busy_since": busy_since,
            "elapsed_seconds": elapsed_seconds,
            "timeout_seconds": cls._timeout_seconds,
            "queue_depth": len(queue) if queue is not None else 0,
            "queue_limit": queue_limit,
            "active_limit": active_limit,
            "busy_reason": reason,
            "waited_seconds": round(max(0.0, waited_seconds), 3),
        }

    @classmethod
    def _success_result(
        cls,
        *,
        token: str,
        state: InstanceSchedulerState,
        policy: OperationPolicy,
        queue: deque[QueuedOperation] | None = None,
    ) -> dict[str, Any]:
        return {
            "success": True,
            "token": token,
            "lane": policy.lane,
            "queue_depth": len(queue) if queue is not None else 0,
            "queue_limit": policy.queue_limit,
            "active_limit": policy.active_limit,
        }

    @classmethod
    async def _acquire_bounded_lane(
        cls,
        state: InstanceSchedulerState,
        lane: str,
        operation_name: str,
        token: str,
        policy: OperationPolicy,
    ) -> dict[str, Any]:
        active = cls._bounded_active_locked(state, lane)

        if cls._can_start_bounded_locked(state, lane, policy.active_limit):
            active[token] = ActiveOperation(
                token=token,
                operation=operation_name,
                lane=lane,
                started_monotonic=time.monotonic(),
                started_at=_utc_now_iso(),
            )
            return cls._success_result(token=token, state=state, policy=policy, queue=cls._bounded_queue_locked(state, lane))

        queue = cls._bounded_queue_locked(state, lane)
        if len(queue) >= policy.queue_limit:
            return cls._build_busy_result(
                state=state,
                lane=lane,
                operation_name=operation_name,
                queue_limit=policy.queue_limit,
                active_limit=policy.active_limit,
                reason=f"{lane}_queue_full",
                queue=queue,
            )

        queued = QueuedOperation(
            token=token,
            operation=operation_name,
            requested_monotonic=time.monotonic(),
            requested_at=_utc_now_iso(),
        )
        queue.append(queued)

        try:
            while True:
                if cls._prune_expired_locked(state):
                    state.condition.notify_all()

                queue = cls._bounded_queue_locked(state, lane)
                is_head = bool(queue) and queue[0].token == token
                if is_head and cls._can_start_bounded_locked(state, lane, policy.active_limit):
                    queue.popleft()
                    cls._bounded_active_locked(state, lane)[token] = ActiveOperation(
                        token=token,
                        operation=operation_name,
                        lane=lane,
                        started_monotonic=time.monotonic(),
                        started_at=_utc_now_iso(),
                    )
                    return cls._success_result(token=token, state=state, policy=policy, queue=queue)
                try:
                    await asyncio.wait_for(state.condition.wait(), timeout=1.0)
                except TimeoutError:
                    pass
        except asyncio.CancelledError:
            queue = cls._bounded_queue_locked(state, lane)
            cls._set_bounded_queue_locked(
                state, lane, deque(item for item in queue if item.token != token)
            )
            state.condition.notify_all()
            raise

    @classmethod
    async def try_acquire(
        cls,
        operation_name: str,
        kwargs: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        state = cls._get_state()
        policy = get_operation_policy(operation_name, kwargs)
        token = uuid.uuid4().hex

        async with state.condition:
            if cls._prune_expired_locked(state):
                state.condition.notify_all()

            if policy.lane == LANE_METADATA:
                wait_started = time.monotonic()
                while state.exclusive_active:
                    waited = time.monotonic() - wait_started
                    remaining = cls._metadata_wait_seconds - waited
                    if remaining <= 0:
                        return cls._build_busy_result(
                            state=state,
                            lane=policy.lane,
                            operation_name=operation_name,
                            queue_limit=policy.queue_limit,
                            active_limit=policy.active_limit,
                            reason="metadata_wait_exclusive_timeout",
                            waited_seconds=waited,
                        )
                    try:
                        await asyncio.wait_for(state.condition.wait(), timeout=min(1.0, remaining))
                    except TimeoutError:
                        pass
                    if cls._prune_expired_locked(state):
                        state.condition.notify_all()
                state.metadata_active[token] = ActiveOperation(
                    token=token,
                    operation=operation_name,
                    lane=policy.lane,
                    started_monotonic=time.monotonic(),
                    started_at=_utc_now_iso(),
                )
                return cls._success_result(token=token, state=state, policy=policy)

            if policy.lane in (LANE_CODE_READ, LANE_ANALYSIS):
                return await cls._acquire_bounded_lane(state, policy.lane, operation_name, token, policy)

            exclusive_wait_started = time.monotonic()
            while True:
                if cls._can_start_exclusive_locked(state):
                    state.exclusive_active[token] = ActiveOperation(
                        token=token,
                        operation=operation_name,
                        lane=policy.lane,
                        started_monotonic=time.monotonic(),
                        started_at=_utc_now_iso(),
                    )
                    return cls._success_result(token=token, state=state, policy=policy)

                waited_seconds = time.monotonic() - exclusive_wait_started
                remaining = cls._exclusive_wait_seconds - waited_seconds
                if remaining <= 0:
                    break

                try:
                    await asyncio.wait_for(state.condition.wait(), timeout=min(1.0, remaining))
                except TimeoutError:
                    pass
                if cls._prune_expired_locked(state):
                    state.condition.notify_all()

            return cls._build_busy_result(
                state=state,
                lane=policy.lane,
                operation_name=operation_name,
                queue_limit=policy.queue_limit,
                active_limit=policy.active_limit,
                reason="exclusive_lane_blocked",
                waited_seconds=time.monotonic() - exclusive_wait_started,
            )

    @classmethod
    async def release(cls, token: str | None) -> None:
        if not token:
            return

        state = cls._get_state()

        async with state.condition:
            removed = False
            for bucket in (
                state.metadata_active,
                state.code_read_active,
                state.analysis_active,
                state.exclusive_active,
            ):
                if token in bucket:
                    bucket.pop(token, None)
                    removed = True

            if removed:
                state.condition.notify_all()

    @classmethod
    def get_snapshot(cls) -> dict[str, Any]:
        state = cls._get_state()
        metadata_active = [cls._serialize_active(op) for op in state.metadata_active.values()]
        code_read_active = [cls._serialize_active(op) for op in state.code_read_active.values()]
        analysis_active = [cls._serialize_active(op) for op in state.analysis_active.values()]
        exclusive_active = [cls._serialize_active(op) for op in state.exclusive_active.values()]
        queue_entries = [
            {
                "token": item.token,
                "operation": item.operation,
                "requested_at": item.requested_at,
                "age_seconds": round(max(0.0, time.monotonic() - item.requested_monotonic), 3),
            }
            for item in list(state.code_read_queue)
        ]
        analysis_queue_entries = [
            {
                "token": item.token,
                "operation": item.operation,
                "requested_at": item.requested_at,
                "age_seconds": round(max(0.0, time.monotonic() - item.requested_monotonic), 3),
            }
            for item in list(state.analysis_queue)
        ]
        return {
            "metadata_inflight": len(metadata_active),
            "code_read_inflight": len(code_read_active),
            "analysis_inflight": len(analysis_active),
            "exclusive_inflight": len(exclusive_active),
            "queue_depth": len(queue_entries),
            "queue_limit": DEFAULT_CODE_READ_QUEUE_LIMIT,
            "active_limit": DEFAULT_CODE_READ_ACTIVE_LIMIT,
            "analysis_queue_depth": len(analysis_queue_entries),
            "analysis_queue_limit": DEFAULT_ANALYSIS_QUEUE_LIMIT,
            "analysis_active_limit": DEFAULT_ANALYSIS_ACTIVE_LIMIT,
            "active_operations": {
                "metadata": metadata_active,
                "code_read": code_read_active,
                "analysis": analysis_active,
                "exclusive": exclusive_active,
            },
            "queued_operations": queue_entries,
            "analysis_queued_operations": analysis_queue_entries,
            "last_busy_reason": state.last_busy_reason or "",
            "last_busy_at": state.last_busy_at,
        }


def with_busy_check(func: Callable[..., Awaitable[Any]]) -> Callable[..., Awaitable[Any]]:
    signature = inspect.signature(func)
    # Tool wrapper functions are conventionally named "<registered_name>_tool"
    # (see search_tools.py, xrefs_tools.py) so they can share a bare name with
    # their inner implementation function, while class_tools.py/resource_tools.py
    # register tools with no suffix at all. METADATA_OPERATIONS/CODE_READ_OPERATIONS
    # are keyed by the *registered* tool name, so func.__name__ must be
    # normalized the same way before being used as a policy lookup key —
    # otherwise every "_tool"-suffixed function silently misses its policy and
    # falls back to the exclusive-lock default.
    operation_name = (
        func.__name__[: -len("_tool")] if func.__name__.endswith("_tool") else func.__name__
    )
    _DECORATED_OPERATIONS.add(operation_name)

    @functools.wraps(func)
    async def wrapper(*args: Any, **kwargs: Any) -> Any:
        # bind_partial (not raw kwargs) so a policy-relevant parameter such as
        # search_in is found by get_operation_policy() regardless of whether
        # the caller passed it positionally or by keyword. apply_defaults()
        # additionally fills in parameters the caller omitted entirely (e.g.
        # an AI client omitting search_in because it matches the schema
        # default) — without it, get_operation_policy() sees no search_in key
        # at all and _split_search_in(None) falls back to {"code"}, which
        # misroutes the hottest, cheapest search call into LANE_EXCLUSIVE.
        bound = signature.bind_partial(*args, **kwargs)
        bound.apply_defaults()
        acquisition = await InstanceBusyTracker.try_acquire(
            operation_name, kwargs=dict(bound.arguments)
        )
        if not acquisition.get("success"):
            return acquisition

        token = acquisition.get("token")
        try:
            return await func(*args, **kwargs)
        finally:
            await InstanceBusyTracker.release(token)

    return wrapper
