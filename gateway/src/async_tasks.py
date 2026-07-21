"""
Generic Python-side async task store for long-running MCP operations.

Usage:
    ticket = async_tasks.submit(some_coroutine(...))   # returns immediately
    result = async_tasks.poll(ticket)                   # check status
"""

import asyncio
import time
import uuid
from typing import Any

_store: dict[str, dict[str, Any]] = {}
# Strong references to in-flight tasks. asyncio.create_task() only holds a
# weak reference internally, so a task with no other referent can be garbage
# collected mid-run; keeping it here until it finishes prevents that.
_tasks: dict[str, "asyncio.Task[Any]"] = {}
_TTL_SECONDS = 300  # 5 min TTL, counted from completion — not submission


def _prune() -> None:
    now = time.monotonic()
    # Only prune terminal (done/error) entries. A long-running task must
    # never be pruned mid-flight just because it started more than TTL ago —
    # that used to make poll() return "not_found" for work still in progress.
    expired = [
        k for k, v in _store.items()
        if v.get("status") != "running" and now - v.get("_ts", 0) > _TTL_SECONDS
    ]
    for k in expired:
        del _store[k]


async def _run(ticket: str, coro) -> None:
    try:
        result = await coro
        _store[ticket] = {"status": "done", "result": result, "_ts": time.monotonic()}
    except Exception as exc:
        _store[ticket] = {"status": "error", "message": str(exc), "_ts": time.monotonic()}
    finally:
        _tasks.pop(ticket, None)


def submit(coro) -> str:
    """Submit a coroutine as a background asyncio task. Returns an opaque ticket."""
    _prune()
    ticket = uuid.uuid4().hex[:16]
    _store[ticket] = {"status": "running", "_ts": time.monotonic()}
    task = asyncio.create_task(_run(ticket, coro))
    _tasks[ticket] = task
    return ticket


def poll(ticket: str) -> dict[str, Any]:
    """Poll the result of a submitted task by ticket."""
    _prune()
    entry = _store.get(ticket)
    if entry is None:
        return {
            "status": "not_found",
            "message": f"Ticket '{ticket}' not found or expired (TTL {_TTL_SECONDS}s). Resubmit.",
        }
    status = entry.get("status", "unknown")
    if status == "done":
        return {"status": "done", "result": entry.get("result")}
    if status == "error":
        return {"status": "error", "message": entry.get("message")}
    return {"status": "running", "retry_after_seconds": 5, "message": "Task in progress."}
