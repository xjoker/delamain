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
        """Manually trigger a full background cache warmup (decompile + trigram + xref graph).

        The server already auto-triggers this warmup right after a file is loaded (unless disabled
        via DELAMAIN_WARMUP_ON_START=false on the Java side). Call this tool yourself only when: the
        server was started with warmup-on-load disabled, a previous warmup was cancelled/failed and
        needs a restart, or you want to redo it with a different skip_libraries setting. Calling it
        while a warmup is already running is a no-op / returns the current run's state (it does not
        start a second overlapping pass). No busy-check: starts immediately and returns; poll
        progress with get_warmup_status().

        Args:
            skip_libraries: Skip known third-party SDK packages (android.*, kotlin.*, etc.) to warm
                only app-owned classes. Default True.
            instance_id: Target JADX instance name.
        Returns:
            dict: {started, total_classes, skipped_libraries, message}
        """
        return await _warm_cache(skip_libraries=skip_libraries, instance_id=instance_id)

    @mcp.tool()
    async def get_warmup_status(instance_id: Optional[str] = None) -> dict:
        """Poll progress of the background cache warmup (auto-started on file load, or via warm_cache()).

        Use this to decide what you can do RIGHT NOW vs what you must still wait for — check
        `capabilities` first, then use `eta_seconds`/`eta_basis` if you need to wait.

        Returns:
            dict:
            - phase: current warmup stage name, one of ENGINE_INIT | PHASE1_DECOMPILE | PHASE2_INDEX |
              PHASE3_GRAPH | PHASE4_USEPLACES | FAST_RESTORE | DONE | IDLE | CANCELLED | ERROR.
            - running: whether the Phase-1..4 warmup pipeline is actively running right now.
            - total / processed / failed: Phase-1 decompile counters (classes queued / done / errored).
            - skipped_libraries: classes excluded because skip_libraries=True was used to start warmup.
            - percentage: Phase-1 decompile-only completion, 0-100 (processed/total). NOT the overall
              warmup progress — use overall_progress_pct for that.
            - elapsed_seconds: seconds since this warmup run started (or total run time if finished).
            - trigram_build_running: whether the in-memory trigram index is currently being built.
            - trigram_count: number of trigram entries built so far (0 if using the mmap shard index
              instead — see capabilities.code_search for the authoritative readiness signal).
            - trigram_skip_reason: present only if trigram build was skipped, e.g. "low-heap"; absent
              (key missing) otherwise.
            - use_places_ready: whether the precise xref-with-snippet index has finished its one-time
              harvest.
            - use_places_harvest_running / use_places_harvest_processed / use_places_harvest_total:
              background harvest progress for precise cross-reference snippets (runs after core
              warmup finishes; does not block other capabilities).
            - use_places_skip_reason: present only if the harvest was skipped, e.g. "low-heap"; absent
              (key missing) otherwise.
            - warming_up: single boolean = "is anything still warming" (core pipeline OR the
              use-places harvest). False means everything below has settled into its final state.
            - overall_progress_pct: 0-100 blended progress across ALL phases (decompile + trigram +
              graph + use-places harvest), weighted by each phase's typical cost share. This is the
              number to show a human/progress bar — percentage above is only Phase-1.
            - eta_seconds: rough estimate of seconds remaining until warmup fully settles (null while
              there isn't enough data yet, e.g. right at the start of a phase). Extrapolated from
              elapsed time and current progress rate, not a hard guarantee.
            - eta_basis: what eta_seconds was computed from — "overall-progress-rate" (normal
              extrapolation from Phase-1..3 progress), "fast-restore-estimate" (hot-start path
              restoring from existing cache), "useplaces-harvest" (core warmup done, only the
              background snippet harvest remains), "starting" (just began, no rate yet), "idle"
              (nothing running), or "done".
            - capabilities: dict mapping capability name -> readiness string. Check this BEFORE
              calling the corresponding tool:
                - "ready": safe to use now.
                - "warming": still being built; the corresponding tool call may be slow, return
                  partial results, or should be retried later.
                - "skipped:low-heap": permanently unavailable this run because the host doesn't have
                  enough heap; do not poll waiting for "ready" — it will never arrive.
              Keys:
                - metadata_search: class/method name and structure search. Ready as soon as the APK
                  is loaded (does not depend on warmup).
                - class_source: decompiled Java source lookup (get_class_source). Ready
                  quickly — served from cache once warm, falls back to lazy live-decompile before that.
                - smali: smali disassembly lookup. Same readiness story as class_source.
                - live_decompile: on-demand decompilation of an arbitrary class. "warming" until the
                  one-time JADX engine init completes (a real cost paid once, ~13s on a large APK);
                  after that, individual live-decompile calls are fast.
                - code_search: full-text/regex search over decompiled code bodies
                  (search_in='code' on the search tool). Do NOT issue search_in='code' calls while
                  this is not "ready" — results will be empty or incomplete, not just slow. If this
                  is "skipped:low-heap", code-body search is unavailable for the whole session; use
                  metadata_search (name/structure) instead.
                - xref_class_level: class-level cross-reference graph (who calls/uses this class).
                  "warming" until the usage graph index finishes building.
                - precise_xref_snippets: cross-references with exact call-site source snippets (as
                  opposed to class-level-only). "warming" while the one-time use-places harvest is
                  still running; falls back to a slower live lookup path in the meantime, so calls
                  don't fail, they're just not instant.
            - effective_decompile_workers: number of parallel decompile worker threads this warmup
              run is actually using. Higher = warmup finishes faster (more CPU/heap spent now);
              use this to sanity-check why a run is faster/slower than expected on a given host.
            - decompile_workers_source: where effective_decompile_workers came from — "auto" (sized
              automatically from available heap and CPU cores) or "override" (pinned by the
              DELAMAIN_WARMUP_DECOMPILE_WORKERS environment variable, ignoring auto-sizing). Useful
              for spotting an operator-imposed speed limit vs. a hardware-limited one.
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
