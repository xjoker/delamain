"""
delamain Gateway - Internal Index Diagnostics Tools
"""

from typing import Optional

from ..logging_config import get_logger
from ..routing.request_router import get_from_jadx
from ..busy_tracker import with_busy_check

logger = get_logger("diagnostics_tools")


async def get_index_stats(instance_id: Optional[str] = None) -> dict:
    logger.debug("get_index_stats called, instance_id=%s", instance_id)
    try:
        result = await get_from_jadx("index-stats", instance_id=instance_id)
        return result
    except Exception as exc:
        logger.warning("get_index_stats failed: %s", exc)
        return {"error": "JADX_ERROR", "message": str(exc)}


_get_index_stats = get_index_stats


async def get_decompile_diag(class_name: str, instance_id: Optional[str] = None) -> dict:
    logger.debug("get_decompile_diag called, class_name=%s, instance_id=%s", class_name, instance_id)
    try:
        return await get_from_jadx("decompile-diag", {"class_name": class_name}, instance_id=instance_id)
    except Exception as exc:
        logger.warning("get_decompile_diag failed: %s", exc)
        return {"error": "JADX_ERROR", "message": str(exc)}


_get_decompile_diag = get_decompile_diag


def register_diagnostics_tools(mcp):
    """Register diagnostics/index-health tools with the MCP server."""

    @mcp.tool()
    @with_busy_check
    async def get_index_stats(instance_id: Optional[str] = None) -> dict:
        """Internal index health snapshot (name indices, shard index, trigram index, snapshot cache).

        Use this to decide whether code-search (search_in='code') is available, or whether to
        stick to metadata (class/method/field name) searches.

        The primary signal is shard_index (mmap'd, sharded RoaringBitmap index):
          - shard_index.built == true and shard_index.covered_classes > 0 → code-search is
            viable. Coverage is decoupled from heap size — this works even on low-memory
            instances where the old heap-resident index never got there.
          - shard_index not yet built → prefer metadata searches until it finishes.
        trigram_index is now a residual/self-heal layer (library classes + rename recovery
        cache), not the coverage signal for code-search — its saturation_percent can stay low
        or even 0% on low-memory setups while shard_index is fully covered and code-search
        works normally.
        index_prebaked reports whether a pre-baked index volume was loaded at startup (see
        docs/prebaked-index.md); index_prebaked.complete/reason explain partial or missing loads.
        Even with full coverage, broad/common search terms return partial_results (see
        search_info.broad_term in code-search results) instead of exhaustively scanning.

        CODE SEARCH HARD LIMITS — never use search_in='code' for: hex strings ≥8 chars (AES
        keys, hashes, device IDs), UUID/random-looking alphanumeric tokens, base64 blobs, or
        long URL paths ("/api/v1/some/deep/path"). These either live in native libs (invisible
        to JADX) or in classes excluded from the shard index due to size — use search_in=
        'class'/'method' to find the owning class, then get_class_source() on it instead.

        Args:
            instance_id: Target JADX instance; omit for default.
        Returns:
            dict: {name_indices: {class_name_buckets, method_name_buckets,
                                  field_name_buckets, raw_name_map_size, index_ready},
                   shard_index: {built, covered_classes, ...},
                   trigram_index: {enabled, indexed_classes, trigram_count, max_trigrams,
                                   max_class_size_bytes, estimated_memory_mb,
                                   saturation_percent},
                   index_prebaked: {complete, reason},
                   snapshot_cache: {method_snapshot_classes, field_snapshot_classes},
                   code_cache: {class_index_size, delegates_to_jadx_icodecache}}
        """
        return await _get_index_stats(instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def get_decompile_diag(class_name: str, instance_id: Optional[str] = None) -> dict:
        """Authoritative decompilation-health diagnostic for one class — call this whenever
        get_class_source's output looks suspicious (truncated, garbled, oddly short, or the
        response carries decompile_quality != "ok").

        Unlike a naive text scan, every signal here comes from JADX's own internal ClassNode
        state, so it cannot be fooled by ordinary comments that merely look like warnings.

        Returns:
            dict: {
              process_state: str, process_complete: bool,  # did decompilation actually finish
              decompile_mode_override: str|None, is_fallback_mode: bool,  # JADX force-downgraded to a simpler pass
              jadx_error_count: int, jadx_errors: [{message, cause}],  # hard per-class failures
              jadx_comment_count: int, jadx_comments_by_level: {...},  # internal JADX diagnostics
              source_available_for_scan: bool,
              source_warning_markers: {...},  # best-effort text markers; SUPPLEMENTARY ONLY —
                                                # never authoritative on their own; jadx_error_count /
                                                # is_fallback_mode / process_complete are ground truth
              alternative_views: {smali_endpoint, source_endpoint}
            }

        Next steps when this reports trouble:
            - jadx_error_count > 0 or process_complete == false → decompilation genuinely failed;
              call get_smali_of_class for the raw Dalvik bytecode instead.
            - is_fallback_mode == true → readable but possibly structurally incomplete; cross-check
              against get_smali_of_class, or call decompile_with_mode to retry in another mode.

        Args:
            class_name: Fully qualified class name. instance_id: Target JADX instance name.
        """
        return await _get_decompile_diag(class_name, instance_id=instance_id)

    logger.info("Diagnostics tools registered: get_index_stats, get_decompile_diag")
