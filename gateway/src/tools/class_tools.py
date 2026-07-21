"""
delamain Gateway - Class Analysis Tools

NOTE: fetch_current_class is excluded (GUI-only, requires active JADX tab).
"""

from typing import Optional

from ..logging_config import get_logger
from ..routing.request_router import get_from_jadx
from ..busy_tracker import with_busy_check
from ..pagination_utils import PaginationUtils

logger = get_logger("class_tools")


async def get_class_source(class_name: str, chunk: int = 0, instance_id: Optional[str] = None) -> dict:
    params = {"class_name": class_name}
    if chunk > 0:
        params["chunk"] = str(chunk)
    return await get_from_jadx("class-source", params, instance_id=instance_id)


def _estimate_batch_size(class_names: list[str]) -> int:
    return len(class_names) * 5000


async def _execute_batch_request(class_names: list[str], chunk: int, instance_id: Optional[str]) -> dict:
    params = {"class_names": ",".join(class_names)}
    if chunk > 0:
        params["chunk"] = str(chunk)
    result = await get_from_jadx("batch-class-source", params, instance_id=instance_id)
    if result.get("response_too_large"):
        size_kb = round(result.get("size_bytes", 0) / 1024, 1)
        result.pop("transfer_endpoint", None)
        result["_ai_instruction"] = (
            f"The batch response is {size_kb} KB which exceeds the inline threshold. "
            "This oversized-response transfer path is not exposed (uploads via "
            "create_transfer_token are supported; large-response downloads are not). "
            "Reduce class_names to 2-3 items, request individual classes with "
            "get_class_source(), or retry batch_get_class_source() with chunk pagination "
            "when the response includes _chunking."
        )
        return result
    if "_chunking" in result and result["_chunking"].get("has_more"):
        chunk_info = result["_chunking"]
        result["_ai_instruction"] = (
            f"Response chunked ({chunk_info['current_chunk']}/{chunk_info['total_chunks']}). "
            f"Call batch_get_class_source(class_names={class_names}, chunk={chunk_info['next_chunk']}) to get next chunk."
        )
    return result


async def batch_get_class_source(
    class_names: list[str],
    chunk: int = 0,
    force: bool = False,
    instance_id: Optional[str] = None,
) -> dict:
    logger.info(f"batch_get_class_source: classes={class_names}, chunk={chunk}, force={force}")
    if len(class_names) > 20:
        return {
            "error": "TOO_MANY_CLASSES",
            "message": "Maximum 20 classes per request",
            "requested": len(class_names),
            "maximum": 20,
        }
    if chunk > 0:
        return await _execute_batch_request(class_names, chunk, instance_id)
    estimated_size = _estimate_batch_size(class_names)
    if estimated_size > 50000 and not force:
        class_infos = []
        for class_name in class_names:
            try:
                info = await get_class_info(class_name, instance_id)
                class_infos.append(info)
            except Exception:
                class_infos.append({"class_name": class_name, "error": "Failed to get info"})
        return {
            "error": "BATCH_TOO_LARGE",
            "estimated_size_bytes": estimated_size,
            "estimated_size_kb": round(estimated_size / 1024, 1),
            "classes_count": len(class_names),
            "class_summaries": class_infos,
            "suggestions": {
                "option1": "Reduce batch size to 2-3 classes maximum",
                "option2": "Use get_class_info + get_method_by_name for targeted analysis",
                "option3": "Fetch classes individually with get_class_source (supports chunking)",
                "option4": f"Add force=True to proceed anyway: batch_get_class_source(class_names={class_names[:2]}, force=True)",
            },
        }
    result = await _execute_batch_request(class_names, chunk, instance_id)
    if estimated_size > 20000:
        result["_performance_warning"] = {
            "estimated_size_kb": round(estimated_size / 1024, 1),
            "message": "Large batch request. Response may be chunked.",
            "optimization_tip": "Consider using get_class_info first to check if you really need full source code",
        }
    return result


async def get_all_classes(offset: int = 0, count: int = 0, instance_id: Optional[str] = None) -> dict:
    return await PaginationUtils.get_paginated_data(
        endpoint="all-classes",
        offset=offset,
        count=count,
        data_extractor=lambda parsed: parsed.get("classes", []),
        fetch_function=lambda ep, params={}: get_from_jadx(ep, params, instance_id=instance_id),
    )


async def get_methods_of_class(class_name: str, instance_id: Optional[str] = None) -> dict:
    return await get_from_jadx("methods-of-class", {"class_name": class_name}, instance_id=instance_id)


async def get_fields_of_class(class_name: str, instance_id: Optional[str] = None) -> dict:
    return await get_from_jadx("fields-of-class", {"class_name": class_name}, instance_id=instance_id)


async def get_smali_of_class(class_name: str, chunk: int = 0, instance_id: Optional[str] = None) -> dict:
    logger.info(f"get_smali_of_class: class={class_name}, chunk={chunk}, instance={instance_id}")
    params = {"class_name": class_name}
    if chunk > 0:
        params["chunk"] = str(chunk)
    result = await get_from_jadx("smali-of-class", params, instance_id=instance_id)
    if "error" in result:
        logger.warning(f"get_smali_of_class error: {result.get('error')}")
    return result


async def get_main_activity_class(chunk: int = 0, instance_id: Optional[str] = None) -> dict:
    params = {}
    if chunk > 0:
        params["chunk"] = str(chunk)
    return await get_from_jadx("main-activity", params, instance_id=instance_id)


async def get_class_info(class_name: str, instance_id: Optional[str] = None) -> dict:
    logger.info(f"get_class_info: class={class_name}, instance={instance_id}")
    result = await get_from_jadx("class-info", {"class_name": class_name}, instance_id=instance_id)
    if "error" in result:
        logger.warning(f"get_class_info error: {result.get('error')}")
    return result


async def get_decompile_status(instance_id: Optional[str] = None) -> dict:
    logger.info(f"get_decompile_status: instance={instance_id}")
    result = await get_from_jadx("decompile-status", instance_id=instance_id)
    if "error" in result:
        logger.warning(f"get_decompile_status error: {result.get('error')}")
    elif result.get("status") == "loading":
        logger.info("get_decompile_status: JADX still initializing")
        result["retry_after_seconds"] = 3
        result["suggestion"] = "JADX is still loading the file. Retry in 3 seconds."
    else:
        status = result.get("status", "unknown")
        pct = result.get("percentage", 0)
        logger.info(f"get_decompile_status: {status} ({pct}%)")
    return result


async def list_packages(instance_id: Optional[str] = None) -> dict:
    return await get_from_jadx("package-tree", instance_id=instance_id)


async def get_code_metadata(
    class_name: str,
    position: Optional[int] = None,
    max: Optional[int] = None,
    instance_id: Optional[str] = None,
) -> dict:
    params = {"class_name": class_name}
    if position is not None:
        params["position"] = str(position)
    if max is not None:
        params["max"] = str(max)
    return await get_from_jadx("code-metadata", params, instance_id=instance_id)


# Module-level references to avoid shadowing inside register
_get_all_classes = get_all_classes
_get_class_source = get_class_source
_batch_get_class_source = batch_get_class_source
_get_methods_of_class = get_methods_of_class
_get_fields_of_class = get_fields_of_class
_get_smali_of_class = get_smali_of_class
_get_main_activity_class = get_main_activity_class
_get_class_info = get_class_info
_get_decompile_status = get_decompile_status
_list_packages = list_packages
_get_code_metadata = get_code_metadata


def register_class_tools(mcp):
    """Register class analysis tools to MCP Server."""

    @mcp.tool()
    @with_busy_check
    async def get_all_classes(offset: int = 0, count: int = 0, instance_id: Optional[str] = None) -> dict:
        """List all classes in the project with pagination support.

        Args:
            offset: Pagination start index. count: Max results (0=all).
            instance_id: Target JADX instance name.
        Returns:
            dict: {classes: [str, ...], total: int}
        """
        return await _get_all_classes(offset, count, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def get_class_source(class_name: str, chunk: int = 0, instance_id: Optional[str] = None) -> dict:
        """Fetch decompiled Java source of a class. Large classes are auto-chunked (>8KB).

        The first chunk (chunk=0) may carry a decompile_quality flag: "ok" (normal),
        "degraded" (JADX fell back to a simpler decompile mode — output may be structurally
        incomplete), "failed" (JADX recorded a hard per-class error or never finished
        processing — treat this source as unreliable), or "unknown" (no quality verdict cached
        for this class yet, e.g. a disk-restored/cold-cache entry — not a signal either way).
        When degraded/failed, a "hint" field is included with the recommended next step. On
        degraded/failed, fall back to get_smali_of_class for the raw bytecode, call
        get_decompile_diag for the full structured diagnostic, or retry via decompile_with_mode.
        decompile_quality/hint are only attached on chunk=0, not on continuation chunks.

        Args:
            class_name: Fully qualified class name. chunk: 0=first, N=continue chunked response.
            instance_id: Target JADX instance name.
        Returns:
            dict: {response: str, _chunking: {...}, decompile_quality?: str, hint?: str} — call
            again with chunk=N if has_more=true.
        """
        return await _get_class_source(class_name, chunk=chunk, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def batch_get_class_source(
        class_names: list[str],
        chunk: int = 0,
        force: bool = False,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Fetch 2-20 class sources in one request with auto size-management. Use get_class_source for a single class.

        Args:
            class_names: List of fully qualified class names (max 20). chunk: 0=first, N=continue.
            force: Bypass size guard. instance_id: Target JADX instance name.
        Returns:
            dict: {classes: [{class_name, content, found}]} or BATCH_TOO_LARGE error with suggestions.
        """
        return await _batch_get_class_source(class_names, chunk=chunk, force=force, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def get_methods_of_class(class_name: str, instance_id: Optional[str] = None) -> dict:
        """List all methods of a class with Frida-friendly metadata (is_static, overload_count, etc.).

        Args:
            class_name: Fully qualified class name. instance_id: Target JADX instance name.
        Returns:
            dict: {methods: [{name, is_static, is_native, overload_count, return_type, ...}], count}
        """
        return await _get_methods_of_class(class_name, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def get_fields_of_class(class_name: str, instance_id: Optional[str] = None) -> dict:
        """List all fields of a class with Frida-compatible type information (type_frida).

        Args:
            class_name: Fully qualified class name. instance_id: Target JADX instance name.
        Returns:
            dict: {fields: [{name, type, type_frida, is_static, is_final, modifiers}], count}
        """
        return await _get_fields_of_class(class_name, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def get_smali_of_class(class_name: str, chunk: int = 0, instance_id: Optional[str] = None) -> dict:
        """Fetch Dalvik smali bytecode of a class. Auto-chunked for large output (>8KB).

        Args:
            class_name: Fully qualified class name (use $ for inner classes). chunk: 0=first, N=continue.
            instance_id: Target JADX instance name.
        Returns:
            dict: {response: str, _chunking: {...}} — call again with chunk=N if has_more=true.
        """
        return await _get_smali_of_class(class_name, chunk=chunk, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def get_main_activity_class(chunk: int = 0, instance_id: Optional[str] = None) -> dict:
        """Fetch the app entry-point Activity class as defined in AndroidManifest.xml.

        Args:
            chunk: 0=first, N=continue chunked response. instance_id: Target JADX instance name.
        Returns:
            dict: {content: str, _chunking: {...}} — call again with chunk=N if has_more=true.
        """
        return await _get_main_activity_class(chunk=chunk, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def get_class_info(class_name: str, instance_id: Optional[str] = None) -> dict:
        """Get structured class metadata: inheritance, interfaces, method/field names, native methods.

        Args:
            class_name: Fully qualified class name. instance_id: Target JADX instance name.
        Returns:
            dict: {class_name, package, super_class, interfaces, method_names, field_names, native_count}
        """
        return await _get_class_info(class_name, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def get_decompile_status(instance_id: Optional[str] = None) -> dict:
        """Check live JADX runtime state: decompile progress, memory usage, and search lock.

        ALWAYS call this before resource-intensive operations (get_class_source, xrefs, code search).

        Decision rules:
        - memory.usage_percentage > 85% → reduce batch sizes to ≤5, skip get_smali_of_class
        - search_lock.locked = true → wait 5s and retry (another operation holds the write lock)

        Args:
            instance_id: Target JADX instance name.
        Returns:
            dict: {cached_percentage, memory.usage_percentage, search_lock.locked, status}
        """
        return await _get_decompile_status(instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def list_packages(instance_id: Optional[str] = None) -> dict:
        """List all packages in the APK/JAR sorted by class count with library-detection flags.

        Args:
            instance_id: Target JADX instance name.
        Returns:
            dict: {total_classes, total_packages, packages: [{name, class_count, is_likely_library}]}
        """
        return await _list_packages(instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def get_code_metadata(
        class_name: str,
        position: Optional[int] = None,
        max: Optional[int] = None,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Get precise symbol reference metadata for a class: which class/method/field is
        referenced at every source position. This is the basis for "go to definition" / "what
        symbol is here" navigation, and for locating exactly where a class is referenced by
        another class (pair with get_class_source to know the surrounding code).

        Without position: returns the reference list for the whole class (capped by max).
        With position: resolves the single symbol at that char-offset (exact match, else the
        enclosing node, else the nearest node before it).

        Names follow the raw/alias dual-track: raw_name is the original/runtime name (use for
        Frida hooks), alias_name is the deobfuscated display name.

        Args:
            class_name: Fully qualified class name. position: Char-offset to resolve a single
            symbol at. max: Cap on the reference list size (default 300, ignored when position
            is set). instance_id: Target JADX instance name.
        Returns:
            dict: {has_metadata, reference_count, references: [{kind, raw_name, alias_name,
            position, line, ...}], truncated} or {has_metadata: false, hint} if not yet
            decompiled. With position: {at: {kind, raw_name, alias_name, resolved_by, ...}}.
        """
        return await _get_code_metadata(class_name, position=position, max=max, instance_id=instance_id)
