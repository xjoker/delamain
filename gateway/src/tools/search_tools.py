"""
delamain Gateway - Code Search Tools
"""

from typing import Optional

from ..logging_config import get_logger
from ..routing.request_router import get_from_jadx, TIMEOUT_CODE_READ
from ..types import format_error_response
from ..busy_tracker import with_busy_check

_VALID_MATCH_MODES = frozenset({"substring", "exact", "prefix", "regex"})

logger = get_logger("search_tools")


async def get_method_by_name(
    class_name: str,
    method_name: str,
    method_signature: Optional[str] = None,
    instance_id: Optional[str] = None,
) -> dict:
    logger.info(
        f"get_method_by_name: class={class_name}, method={method_name}, "
        f"method_signature={method_signature}, instance={instance_id}"
    )
    params: dict = {"class_name": class_name, "method_name": method_name}
    if method_signature:
        params["method_signature"] = method_signature
    result = await get_from_jadx("method-by-name", params, instance_id=instance_id)
    if "error" in result:
        logger.warning(f"get_method_by_name failed: {result.get('error')}")
    return result


async def get_method_source(
    class_name: str,
    method_name: str,
    method_signature: str = "",
    instance_id: Optional[str] = None,
) -> dict:
    logger.info(
        f"get_method_source: class={class_name}, method={method_name}, "
        f"method_signature={method_signature!r}, instance={instance_id}"
    )
    params: dict = {"class_name": class_name, "method_name": method_name}
    if method_signature:
        params["method_signature"] = method_signature
    result = await get_from_jadx("method-source", params, instance_id=instance_id)
    if "error" in result:
        logger.warning(f"get_method_source failed: {result.get('error')}")
    return result


async def _execute_batch_method_request(
    methods: list[str],
    chunk: int,
    instance_id: Optional[str],
) -> dict:
    params = {"methods": ",".join(methods)}
    if chunk > 0:
        params["chunk"] = str(chunk)

    result = await get_from_jadx("batch-method-by-name", params, instance_id=instance_id)

    if "_chunking" in result and result["_chunking"].get("has_more"):
        chunk_info = result["_chunking"]
        result["_ai_instruction"] = (
            f"Response chunked ({chunk_info['current_chunk']}/{chunk_info['total_chunks']}). "
            f"Call batch_get_method_by_name(methods={methods}, chunk={chunk_info['next_chunk']}) to get next chunk."
        )

    return result


async def batch_get_method_by_name(
    methods: list[str],
    chunk: int = 0,
    force: bool = False,
    instance_id: Optional[str] = None,
) -> dict:
    logger.info(f"batch_get_method_by_name: methods={methods}, chunk={chunk}, force={force}")

    if chunk > 0:
        return await _execute_batch_method_request(methods, chunk, instance_id)

    estimated_size = len(methods) * 3000

    if estimated_size > 50000 and not force:
        return {
            "error": "BATCH_TOO_LARGE",
            "estimated_size_bytes": estimated_size,
            "estimated_size_kb": round(estimated_size / 1024, 1),
            "methods_count": len(methods),
            "suggestions": {
                "option1": "Reduce batch size to 5-10 methods maximum",
                "option2": "Fetch methods individually with get_method_by_name",
                "option3": f"Add force=True to proceed: batch_get_method_by_name(methods={methods[:5]}, force=True)",
            },
        }

    result = await _execute_batch_method_request(methods, chunk, instance_id)

    if estimated_size > 20000:
        result["_performance_warning"] = {
            "estimated_size_kb": round(estimated_size / 1024, 1),
            "message": "Large batch request. Response may be chunked.",
            "optimization_tip": "Consider fetching methods individually if only specific ones are needed",
        }

    return result


async def search_classes_by_keyword(
    search_term: str,
    package: str = "",
    exclude: str = "",
    search_in: str = "code",
    offset: int = 0,
    count: int = 20,
    match_mode: str = "substring",
    instance_id: Optional[str] = None,
) -> dict:
    if match_mode not in _VALID_MATCH_MODES:
        return format_error_response(
            "INVALID_INPUT",
            f"Invalid match_mode: '{match_mode}'",
            {"valid_values": sorted(_VALID_MATCH_MODES)},
        )

    params = {
        "search_term": search_term,
        "package": package,
        "exclude": exclude,
        "search_in": search_in,
        "offset": offset,
        "count": count,
        "match_mode": match_mode,
    }
    req_timeout = TIMEOUT_CODE_READ if "code" in search_in.split(",") else None
    return await get_from_jadx(
        "search-classes-by-keyword", params, instance_id=instance_id, timeout=req_timeout
    )


async def get_method_signature(
    class_name: str,
    method_name: str,
    instance_id: Optional[str] = None,
) -> dict:
    logger.info(f"get_method_signature: class={class_name}, method={method_name}, instance={instance_id}")
    result = await get_from_jadx(
        "method-signature", {"class_name": class_name, "method_name": method_name},
        instance_id=instance_id,
    )
    if "error" in result:
        logger.warning(f"get_method_signature error: {result.get('error')}")
    return result


async def get_method_callees(
    class_name: str,
    method_name: str,
    instance_id: Optional[str] = None,
) -> dict:
    logger.info(f"get_method_callees: class={class_name}, method={method_name}, instance={instance_id}")
    result = await get_from_jadx(
        "method-callees", {"class_name": class_name, "method_name": method_name},
        instance_id=instance_id,
    )
    if "error" in result:
        logger.warning(f"get_method_callees error: {result.get('error')}")
    return result


async def search_native_methods(
    package: str = "",
    offset: int = 0,
    count: int = 50,
    instance_id: Optional[str] = None,
) -> dict:
    logger.info(
        f"search_native_methods: package={package}, offset={offset}, count={count}, instance={instance_id}"
    )
    params = {"offset": offset, "count": count}
    if package:
        params["package"] = package
    result = await get_from_jadx("search-native-methods", params, instance_id=instance_id)
    if "error" in result:
        logger.warning(f"search_native_methods error: {result.get('error')}")
    return result


async def submit_code_search(
    search_term: str,
    package: str = "",
    exclude: str = "",
    search_in: str = "code",
    match_mode: str = "substring",
    instance_id: Optional[str] = None,
) -> dict:
    params = {
        "search_term": search_term,
        "package": package,
        "exclude": exclude,
        "search_in": search_in,
        "match_mode": match_mode,
    }
    return await get_from_jadx(
        "submit-code-search", params, method="POST", instance_id=instance_id, timeout=15
    )


async def get_code_search_result(
    ticket: str,
    offset: int = 0,
    count: int = 20,
    instance_id: Optional[str] = None,
) -> dict:
    params = {"ticket": ticket, "offset": offset, "count": count}
    return await get_from_jadx("code-search-status", params, instance_id=instance_id, timeout=15)


def register_search_tools(mcp):
    """Register search-related tools to MCP Server."""

    @mcp.tool(name="get_method_by_name")
    @with_busy_check
    async def get_method_by_name_tool(
        class_name: str,
        method_name: str,
        method_signature: Optional[str] = None,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Fetch source code of a specific method. Use method_signature (JVM descriptor) to pick an overload.

        Args:
            class_name: Fully qualified class name. method_name: Method name.
            method_signature: JVM short descriptor, e.g. 'process(Ljava/lang/String;I)Z' (optional).
            instance_id: Target JADX instance name.
        Returns:
            dict: {code: str} or {available_descriptors: [...]} if overloads exist.
        """
        return await get_method_by_name(
            class_name, method_name, method_signature=method_signature, instance_id=instance_id,
        )

    @mcp.tool(name="get_method_source")
    @with_busy_check
    async def get_method_source_tool(
        class_name: str,
        method_name: str,
        method_signature: str = "",
        instance_id: Optional[str] = None,
    ) -> dict:
        """Get decompiled source for a single method, including line numbers and import list.

        Preferred over get_class_source when you need one specific method — avoids loading
        the entire class (which can be thousands of lines for large APKs).

        Args:
            class_name: Fully qualified class name.
            method_name: Method name (deobfuscated or raw).
            method_signature: JVM short descriptor to disambiguate overloads, e.g.
                'process(Ljava/lang/String;I)Z' (optional). Required when multiple
                overloads exist.
            instance_id: Target JADX instance name.
        Returns:
            dict: {class_name, method_name, raw_method_name, frida_overload,
                   start_line, end_line, imports, source}
            On multiple overloads without method_signature: returns HTTP 300 with
            available_descriptors list (same behaviour as get_method_by_name).
            start_line/end_line are -1 when line info is unavailable (class not
            yet decompiled or JADX metadata missing).
        """
        return await get_method_source(
            class_name, method_name, method_signature=method_signature, instance_id=instance_id,
        )

    @mcp.tool(name="batch_get_method_by_name")
    @with_busy_check
    async def batch_get_method_by_name_tool(
        methods: list[str],
        chunk: int = 0,
        force: bool = False,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Fetch multiple method sources in one request using "class:method" format. Auto size-managed.

        Args:
            methods: List of "class_name:method_name" strings (max 20). chunk: 0=first, N=continue.
            force: Bypass size guard. instance_id: Target JADX instance name.
        Returns:
            dict: {methods: [{class_name, method_name, code, found}]} or BATCH_TOO_LARGE error.
        """
        return await batch_get_method_by_name(
            methods, chunk=chunk, force=force, instance_id=instance_id,
        )

    @mcp.tool(name="search_classes_by_keyword")
    @with_busy_check
    async def search_classes_by_keyword_tool(
        search_term: str,
        package: str = "",
        exclude: str = "",
        search_in: str = "class,method,field",
        offset: int = 0,
        count: int = 20,
        match_mode: str = "substring",
        instance_id: Optional[str] = None,
    ) -> dict:
        """Search classes by keyword. Default searches class/method/field names (fast, <100ms).

        search_in options:
        - 'class,method,field' (default) — metadata-only, always fast, no lock needed
        - 'code' — mmap shard index search inside source; call get_index_stats() first and check
          shard_index.built/covered_classes for availability. Broad/common terms return
          partial_results (search_info.broad_term=true + candidate_count/hint) instead of an
          exhaustive scan — narrow the term or use search_in='class'/'method' instead of retrying.

        Hard limits (hex/UUID/base64/long paths → never indexed): see get_index_stats docstring.

        For code/comment search on large APKs, prefer submit_code_search (async, no timeout risk).

        Args:
            search_term: Keyword. package: Package filter (recommended to reduce noise).
            exclude: Comma-separated package prefixes to exclude.
            search_in: 'class,method,field' (default) | 'code' | 'comment' | any combination.
            match_mode: substring (default)|exact|prefix|regex. count: Max results (default 20).
            instance_id: Target JADX instance name.
        Returns:
            dict: {classes: [...], total: int, has_more: bool}
        """
        return await search_classes_by_keyword(
            search_term, package, exclude, search_in, offset, count,
            match_mode=match_mode, instance_id=instance_id,
        )

    @mcp.tool(name="get_method_signature")
    @with_busy_check
    async def get_method_signature_tool(
        class_name: str,
        method_name: str,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Get structured method signatures with Frida-compatible types and frida_overload strings.

        Args:
            class_name: Fully qualified class name. method_name: Method name.
            instance_id: Target JADX instance name.
        Returns:
            dict: {signatures: [{return_type, parameters, frida_overload, ...}], overloads: int}
        """
        return await get_method_signature(class_name, method_name, instance_id=instance_id)

    @mcp.tool(name="get_method_callees")
    @with_busy_check
    async def get_method_callees_tool(
        class_name: str,
        method_name: str,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Get methods called by the specified method (pattern-based callee analysis).

        Args:
            class_name: Fully qualified class name. method_name: Method name.
            instance_id: Target JADX instance name.
        Returns:
            dict: {callees: [str, ...], callees_count: int}
        """
        return await get_method_callees(class_name, method_name, instance_id=instance_id)

    @mcp.tool(name="search_native_methods")
    @with_busy_check
    async def search_native_methods_tool(
        package: str = "",
        offset: int = 0,
        count: int = 50,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Find all native (JNI) methods across the APK — reads DEX metadata, no decompilation needed.

        Args:
            package: Optional package filter. offset: Pagination start.
            count: Max results (max 200, default 50). instance_id: Target JADX instance name.
        Returns:
            dict: {native_methods: [{class_name, method_name, param_types_frida}], has_more: bool}
        """
        return await search_native_methods(package, offset, count, instance_id=instance_id)

    @mcp.tool(name="submit_code_search")
    async def submit_code_search_tool(
        search_term: str,
        package: str = "",
        exclude: str = "",
        search_in: str = "code",
        match_mode: str = "substring",
        instance_id: Optional[str] = None,
    ) -> dict:
        """Submit a background code/comment search. Returns a ticket ID immediately — no timeout risk.

        Preferred over search_classes_by_keyword(search_in='code') for large APKs (>10k classes).

        Async workflow:
        1. ticket = submit_code_search(search_term, package=...).ticket
        2. Wait retry_after_seconds (usually 3-10s), then:
        3. result = get_code_search_result(ticket) — repeat until status="done"
        4. result.classes contains matching classes; paginate with offset=

        Hard limits (hex/UUID/base64/long paths → never indexed): see get_index_stats docstring.
        Broad/common terms return partial_results (search_info.broad_term=true) instead of an
        exhaustive scan — narrow the term or use search_in='class'/'method' rather than retrying.

        Args:
            search_term: Short human-readable token (≤20 chars ideal). package: Package filter.
            search_in: code (default) | comment. match_mode: substring|exact|prefix|regex.
            instance_id: Target JADX instance name.
        Returns:
            dict: {ticket, status: "submitted"|"done", retry_after_seconds}
        """
        return await submit_code_search(
            search_term, package, exclude, search_in, match_mode, instance_id=instance_id,
        )

    @mcp.tool(name="get_code_search_result")
    @with_busy_check
    async def get_code_search_result_tool(
        ticket: str,
        offset: int = 0,
        count: int = 20,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Poll the result of a code search submitted via submit_code_search().

        Args:
            ticket: Ticket ID from submit_code_search(). offset: Pagination start. count: Max results.
            instance_id: Target JADX instance name.
        Returns:
            dict: status="running" (poll again) | status="done" + classes list | status="timed_out"|"cancelled"|"not_found"
        """
        return await get_code_search_result(ticket, offset, count, instance_id=instance_id)

    logger.info(
        "Search tools registered: get_method_by_name, get_method_source, "
        "batch_get_method_by_name, search_classes_by_keyword, get_method_signature, "
        "get_method_callees, search_native_methods, submit_code_search, get_code_search_result"
    )
