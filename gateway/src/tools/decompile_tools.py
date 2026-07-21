"""
delamain Gateway - Smart Decompilation Management Tools
"""

import asyncio
import time
from typing import Optional

from ..logging_config import get_logger
from ..routing.request_router import get_from_jadx
from ..busy_tracker import with_busy_check

logger = get_logger("decompile_tools")

_CONCURRENCY = {"high": 5, "normal": 3, "low": 1}

_THIRD_PARTY_PREFIXES = (
    "android.", "androidx.", "com.google.", "com.facebook.", "com.squareup.",
    "okhttp3.", "retrofit2.", "io.reactivex.", "org.apache.", "kotlin.",
    "kotlinx.", "com.bumptech.glide.", "com.github.", "org.greenrobot.",
    "com.alibaba.", "com.tencent.", "io.flutter.", "com.unity3d.",
    "org.reactnative.", "com.airbnb.", "dagger.", "javax.", "org.json.",
)

_COMPONENT_SUFFIXES = ("Activity", "Service", "Receiver", "Provider", "Fragment")


async def _decompile_single_class(
    class_name: str,
    instance_id: Optional[str],
) -> dict:
    start = time.monotonic()
    try:
        result = await get_from_jadx(
            "class-source", {"class_name": class_name}, instance_id=instance_id,
        )
        elapsed_ms = (time.monotonic() - start) * 1000

        if "error" in result:
            return {
                "class_name": class_name,
                "status": "failed",
                "time_ms": round(elapsed_ms, 1),
                "size_bytes": 0,
                "error": result.get("error", "unknown"),
            }

        content = result.get("response", result.get("content", ""))
        size_bytes = len(content.encode("utf-8")) if isinstance(content, str) else 0
        status = "cached" if elapsed_ms < 50 else "decompiled"

        return {
            "class_name": class_name,
            "status": status,
            "time_ms": round(elapsed_ms, 1),
            "size_bytes": size_bytes,
            "error": None,
        }
    except Exception as exc:
        elapsed_ms = (time.monotonic() - start) * 1000
        logger.warning(f"Decompile failed for {class_name}: {exc}")
        return {
            "class_name": class_name,
            "status": "failed",
            "time_ms": round(elapsed_ms, 1),
            "size_bytes": 0,
            "error": str(exc),
        }


async def smart_decompile(
    class_names: list[str],
    priority: str = "normal",
    instance_id: Optional[str] = None,
) -> dict:
    logger.info(f"smart_decompile: {len(class_names)} classes, priority={priority}")

    if not class_names:
        return {"error": "INVALID_INPUT", "message": "class_names cannot be empty"}

    if priority not in _CONCURRENCY:
        return {
            "error": "INVALID_INPUT",
            "message": f"priority must be one of: {list(_CONCURRENCY.keys())}",
        }

    concurrency = _CONCURRENCY[priority]
    semaphore = asyncio.Semaphore(concurrency)

    async def _limited(cls: str) -> dict:
        async with semaphore:
            return await _decompile_single_class(cls, instance_id)

    overall_start = time.monotonic()
    results = await asyncio.gather(*[_limited(cls) for cls in class_names])
    total_time = time.monotonic() - overall_start

    succeeded = sum(1 for r in results if r["status"] == "decompiled")
    cached = sum(1 for r in results if r["status"] == "cached")
    failed = sum(1 for r in results if r["status"] == "failed")

    decompile_status_after = {}
    try:
        decompile_status_after = await get_from_jadx("decompile-status", instance_id=instance_id)
    except Exception as exc:
        logger.warning(f"Failed to fetch decompile-status: {exc}")
        decompile_status_after = {"error": str(exc)}

    tips: list[str] = []
    if failed > 0:
        tips.append(f"{failed} class(es) failed. Check class names and JADX memory usage.")
    if cached == len(class_names):
        tips.append("All classes were already cached. No decompilation needed.")
    avg_ms = sum(r["time_ms"] for r in results) / len(results) if results else 0
    if avg_ms > 2000:
        tips.append(
            "Average decompile time >2s. Consider reducing batch size or "
            "increasing JADX heap memory."
        )
    pct = decompile_status_after.get("percentage", 0)
    if isinstance(pct, (int, float)) and pct < 50:
        tips.append(
            f"Cache rate is {pct}%. Use get_decompile_priority_list to identify "
            "high-value classes to decompile next."
        )

    return {
        "requested": len(class_names),
        "succeeded": succeeded,
        "failed": failed,
        "already_cached": cached,
        "total_time_seconds": round(total_time, 2),
        "results": list(results),
        "decompile_status_after": decompile_status_after,
        "optimization_tips": tips,
    }


def _classify_class(class_name: str, package: str) -> tuple[str, str]:
    simple_name = class_name.rsplit(".", 1)[-1] if "." in class_name else class_name

    for prefix in _THIRD_PARTY_PREFIXES:
        if class_name.startswith(prefix):
            return "low", f"Third-party SDK ({prefix.rstrip('.')})"

    for suffix in _COMPONENT_SUFFIXES:
        if simple_name.endswith(suffix):
            return "high", f"Android component ({suffix})"

    if package and class_name.startswith(package):
        return "high", "Application own package"

    if len(simple_name) <= 3:
        return "medium", "Short name (possible obfuscated core logic)"

    return "medium", "General class"


async def get_decompile_priority_list(
    analysis_goal: str = "",
    package: str = "",
    instance_id: Optional[str] = None,
) -> dict:
    logger.info(f"get_decompile_priority_list: goal='{analysis_goal}', package='{package}'")

    try:
        all_classes_resp = await get_from_jadx("all-classes", instance_id=instance_id)
    except Exception as exc:
        return {"error": "JADX_ERROR", "message": f"Failed to fetch classes: {exc}"}

    all_classes: list[str] = all_classes_resp.get("classes", [])
    if not all_classes:
        return {
            "error": "NO_CLASSES",
            "message": "No classes found. Ensure a file is loaded in JADX.",
        }

    decompile_status = {}
    try:
        decompile_status = await get_from_jadx("decompile-status", instance_id=instance_id)
    except Exception as exc:
        logger.warning(f"Failed to fetch decompile-status: {exc}")

    cached_count = decompile_status.get("processed_classes", 0)
    total_count = len(all_classes)

    priority_order = {"high": 0, "medium": 1, "low": 2}
    classified: list[dict] = []

    for cls in all_classes:
        prio, reason = _classify_class(cls, package)
        classified.append({"class_name": cls, "priority": prio, "reason": reason})

    classified.sort(key=lambda x: (priority_order.get(x["priority"], 99), x["class_name"]))
    priority_list = classified[:100]

    if total_count > 10000:
        suggested_batch = 3
    elif total_count > 1000:
        suggested_batch = 5
    else:
        suggested_batch = 10

    return {
        "total_classes": total_count,
        "already_cached": cached_count,
        "uncached": max(total_count - cached_count, 0),
        "priority_list": priority_list,
        "suggested_batch_size": suggested_batch,
    }


async def warm_cache(
    skip_libraries: bool = True,
    instance_id: Optional[str] = None,
) -> dict:
    logger.info(f"warm_cache: skip_libraries={skip_libraries}, instance_id={instance_id}")
    try:
        # delamain java exposes POST /start-warmup reading a JSON body {skip_libraries: bool}
        # (the previous /cache/warmup path + query-param form 404'd / failed body parse).
        result = await get_from_jadx(
            "start-warmup", json_body={"skip_libraries": skip_libraries},
            instance_id=instance_id, method="POST",
        )
        return result
    except Exception as exc:
        logger.warning(f"warm_cache failed: {exc}")
        return {"error": "JADX_ERROR", "message": str(exc)}


async def get_warmup_status(instance_id: Optional[str] = None) -> dict:
    try:
        # delamain java exposes GET /warmup-status (not the old /cache/warmup-status).
        return await get_from_jadx("warmup-status", instance_id=instance_id)
    except Exception as exc:
        return {"error": "JADX_ERROR", "message": str(exc)}


async def decompile_with_mode(
    class_name: str,
    mode: str = "FALLBACK",
    comments_level: Optional[str] = None,
    chunk: int = 0,
    instance_id: Optional[str] = None,
) -> dict:
    params: dict = {"class_name": class_name, "mode": mode}
    if comments_level:
        params["comments_level"] = comments_level
    if chunk > 0:
        params["chunk"] = str(chunk)
    return await get_from_jadx("decompile-with-mode", params, instance_id=instance_id)


_smart_decompile = smart_decompile
_get_decompile_priority_list = get_decompile_priority_list
_warm_cache = warm_cache
_get_warmup_status = get_warmup_status
_decompile_with_mode = decompile_with_mode


def register_decompile_tools(mcp):
    """Register smart decompilation tools with the MCP server."""

    @mcp.tool()
    @with_busy_check
    async def smart_decompile(
        class_names: list[str],
        priority: str = "normal",
        instance_id: Optional[str] = None,
    ) -> dict:
        """Batch-decompile classes with concurrency control (high=5, normal=3, low=1 concurrent).

        Args:
            class_names: Fully qualified class names. priority: high|normal|low concurrency tier.
            instance_id: Target JADX instance name.
        Returns:
            dict: {succeeded, failed, already_cached, results: [{class_name, status, time_ms}]}
        """
        return await _smart_decompile(class_names, priority=priority, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def get_decompile_priority_list(
        analysis_goal: str = "",
        package: str = "",
        instance_id: Optional[str] = None,
    ) -> dict:
        """Rank uncached classes by importance: Android components > app package > obfuscated > SDKs.

        Args:
            analysis_goal: Optional hint for logging (e.g. "find network APIs").
            package: App package prefix to boost own-package classes. instance_id: Target JADX instance.
        Returns:
            dict: {priority_list: [{class_name, priority, reason}], suggested_batch_size}
        """
        return await _get_decompile_priority_list(
            analysis_goal=analysis_goal, package=package, instance_id=instance_id,
        )

    @mcp.tool()
    async def warm_cache(
        skip_libraries: bool = True,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Warm the full decompile cache in the background so code search works without restrictions.

        After calling this, cached_percentage in get_decompile_status() will rise toward 100%.
        Use get_warmup_status() to poll progress. No busy-check: starts immediately and returns.

        Args:
            skip_libraries: Skip known SDK packages (android.*, kotlin.*, etc.). Default True.
            instance_id: Target JADX instance name.
        Returns:
            dict: {started, total_classes, skipped_libraries, message}
        """
        return await _warm_cache(skip_libraries=skip_libraries, instance_id=instance_id)

    @mcp.tool()
    async def get_warmup_status(instance_id: Optional[str] = None) -> dict:
        """Poll progress of a background cache warmup started by warm_cache().

        Returns:
            dict: {phase, running, total, processed, failed, percentage, elapsed_seconds}
        """
        return await _get_warmup_status(instance_id=instance_id)

    @mcp.tool(name="decompile_with_mode")
    @with_busy_check
    async def decompile_with_mode_tool(
        class_name: str,
        mode: str = "FALLBACK",
        comments_level: Optional[str] = None,
        chunk: int = 0,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Re-decompile a single class in an explicit mode — a temporary, non-persistent fallback view.

        Use this when the normal (RESTRUCTURE) output from get_class_source is garbled, partial, or
        fails to decompile cleanly (check decompile-diag first) — retry it here in SIMPLE or FALLBACK
        mode to get a usable, if less readable, view instead. This applies to ONE class only, and the
        result is NOT cached and NOT persisted: get_class_source keeps returning the normal
        RESTRUCTURE output for this class afterward — call this again each time you need the
        alternate-mode view.

        Args:
            class_name: Fully qualified class name.
            mode: RESTRUCTURE|SIMPLE|FALLBACK (default FALLBACK — skips the restructuring pipeline
                that most often produces garbled output on obfuscated/complex control flow).
            comments_level: Optional JADX CommentsLevel name, applied only for this call.
            chunk: Pagination for large responses (0 = first chunk), same semantics as get_class_source.
            instance_id: Target JADX instance name.
        Returns:
            dict: {response, mode, class_name, raw_class, ephemeral: true, note}
        """
        return await _decompile_with_mode(
            class_name, mode=mode, comments_level=comments_level, chunk=chunk, instance_id=instance_id,
        )

    logger.info(
        "Decompile tools registered: smart_decompile, get_decompile_priority_list, "
        "warm_cache, get_warmup_status, decompile_with_mode"
    )
