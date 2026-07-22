"""
delamain Gateway - Cross-Reference Analysis Tools

Provides MCP tools for finding cross-references (xrefs) to classes,
methods, and fields in decompiled Android applications.
"""

from typing import Optional

from ..routing.request_router import get_from_jadx
from ..types import format_error_response
from ..pagination_utils import PaginationUtils
from ..busy_tracker import with_busy_check


def _augment_xref_timeout(result: dict, async_hint: dict) -> dict:
    """Turn a dead-end xref TIMEOUT into actionable guidance.

    A sync xref on a high-fan-in class with the usage-graph index still cold falls
    back to live-decompiling every referrer, which blows past the gateway's 120s
    HTTP ceiling (TIMEOUT_CODE_READ) and returns a bare {"error":"TIMEOUT"}. The
    same answer is reachable without blocking — the async ticket path
    (submit_xref → get_xref_result) returns immediately, and a warm usage graph
    makes xref near-instant — but the caller can't know that from the raw error.
    Point them at both. Only fires on TIMEOUT, so successful results are untouched.
    """
    if not isinstance(result, dict):
        return result
    if result.get("error") == "TIMEOUT" or result.get("error_code") == "TIMEOUT":
        result["suggestion"] = (
            "This xref timed out at the 120s ceiling: the usage-graph index isn't warm "
            "yet, so it fell back to live-decompiling every referrer. Get the same result "
            "without blocking: (1) call submit_xref(...) with the same target — the async "
            "ticket path returns immediately, then poll get_xref_result; or (2) run warmup "
            "(check get_warmup_status) so the precomputed index makes xref near-instant."
        )
        result["next_action"] = {
            "tool": "submit_xref",
            "args": async_hint,
            "why": "the async ticket path never hits the 120s HTTP timeout",
        }
    return result


async def get_xrefs_to_class(
    class_name: str,
    offset: int = 0,
    count: int = 20,
    include_snippet: bool = False,
    context_lines: int = 3,
    instance_id: Optional[str] = None,
) -> dict:
    additional_params: dict = {"class_name": class_name}
    if include_snippet:
        additional_params["include_snippet"] = "true"
        additional_params["context_lines"] = str(context_lines)

    result = await PaginationUtils.get_paginated_data(
        endpoint="xrefs-to-class",
        offset=offset,
        count=count,
        additional_params=additional_params,
        data_extractor=lambda parsed: parsed.get("references", []),
        fetch_function=lambda ep, params={}: get_from_jadx(ep, params, instance_id=instance_id),
    )
    return _augment_xref_timeout(result, {"target_type": "class", "class_name": class_name})


async def get_xrefs_to_method(
    class_name: str,
    method_name: str,
    offset: int = 0,
    count: int = 20,
    include_snippet: bool = False,
    context_lines: int = 3,
    instance_id: Optional[str] = None,
) -> dict:
    additional_params: dict = {"class_name": class_name, "method_name": method_name}
    if include_snippet:
        additional_params["include_snippet"] = "true"
        additional_params["context_lines"] = str(context_lines)

    result = await PaginationUtils.get_paginated_data(
        endpoint="xrefs-to-method",
        offset=offset,
        count=count,
        additional_params=additional_params,
        data_extractor=lambda parsed: parsed.get("references", []),
        fetch_function=lambda ep, params={}: get_from_jadx(ep, params, instance_id=instance_id),
    )
    return _augment_xref_timeout(
        result,
        {"target_type": "method", "class_name": class_name, "member_name": method_name},
    )


async def get_xrefs_to_field(
    class_name: str,
    field_name: str,
    offset: int = 0,
    count: int = 20,
    instance_id: Optional[str] = None,
) -> dict:
    result = await PaginationUtils.get_paginated_data(
        endpoint="xrefs-to-field",
        offset=offset,
        count=count,
        additional_params={"class_name": class_name, "field_name": field_name},
        data_extractor=lambda parsed: parsed.get("references", []),
        fetch_function=lambda ep, params={}: get_from_jadx(ep, params, instance_id=instance_id),
    )
    return _augment_xref_timeout(
        result,
        {"target_type": "field", "class_name": class_name, "member_name": field_name},
    )


async def batch_get_xrefs(targets: list[str], instance_id: Optional[str] = None) -> dict:
    targets_str = ",".join(targets)
    result = await get_from_jadx("batch-xrefs", {"targets": targets_str}, instance_id=instance_id)
    return _augment_xref_timeout(result, {"targets": targets})


async def submit_xref(
    target_type: str = "",
    class_name: str = "",
    member_name: str = "",
    targets: Optional[list[str]] = None,
    include_snippet: bool = False,
    context_lines: int = 3,
    instance_id: Optional[str] = None,
) -> dict:
    if targets:
        params: dict = {"targets": ",".join(targets)}
    else:
        params = {"target_type": target_type, "class_name": class_name}
        if member_name:
            params["member_name"] = member_name
        if include_snippet:
            params["include_snippet"] = "true"
            params["context_lines"] = str(context_lines)
    return await get_from_jadx("submit-xref", params, method="POST", instance_id=instance_id, timeout=15)


async def get_xref_result(
    ticket: str,
    offset: int = 0,
    count: int = 100,
    instance_id: Optional[str] = None,
) -> dict:
    params = {"ticket": ticket, "offset": offset, "count": count}
    return await get_from_jadx("xref-status", params, instance_id=instance_id, timeout=15)


_get_xrefs_to_class = get_xrefs_to_class
_get_xrefs_to_method = get_xrefs_to_method
_get_xrefs_to_field = get_xrefs_to_field
_batch_get_xrefs = batch_get_xrefs
_submit_xref = submit_xref
_get_xref_result = get_xref_result


def register_xrefs_tools(mcp):
    """Register cross-reference tools with the MCP server."""

    @mcp.tool()
    @with_busy_check
    async def get_xrefs(
        target_type: str,
        class_name: str,
        member_name: str = "",
        offset: int = 0,
        count: int = 20,
        include_snippet: bool = False,
        context_lines: int = 3,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Find cross-references to a class, method, or field.

        Args:
            target_type: class|method|field. class_name: Fully qualified class name.
            member_name: Method or field name (required for method/field). offset/count: Pagination.
            include_snippet: If True, attach the surrounding source code snippet at each call site.
                Useful for quickly understanding usage context without opening each referencing class.
                Each result entry will contain a 'snippet' field (null if retrieval failed).
            context_lines: Lines of context before/after the reference in the snippet (default: 3).
                Only used when include_snippet=True.
            instance_id: Target JADX instance name.
        Returns:
            dict: {references: [{class, raw_class, method, raw_method, from_method, raw_from_method,
                source_line[, decompiled_line, code_snippet, snippet]}], pagination: {total, offset,
                limit, count, has_more, ...}, resolution, via, distinct_referrer_class_count[, hint]}.

                resolution/via describe how the result was computed and its row granularity:
                - "class-level"/"usage-graph" (target_type=class, include_snippet=False): one row
                  per distinct referrer class, no line numbers. Fastest, no caller decompile.
                - "precise"/"use-places-store" (target_type=class, include_snippet=True, index warm):
                  one row per call site (exact line), from a persisted index — instant.
                - "live-method-level"/"live-decompile": fast indices missing or incomplete; computed
                  by live-decompiling each referrer. Mixed granularity — a row per call site where a
                  precise position was resolvable, else per referencing method, else per class.
                - "method-level"/"use-graph" (target_type=method, include_snippet=False): one row per
                  distinct caller method, no line numbers.
                - target_type=field never sets resolution/via (always live, method-level rows).

                pagination.total counts rows in the CHOSEN granularity, so it can legitimately differ
                across include_snippet values for the same target (e.g. 3 with include_snippet=False
                vs 4 with include_snippet=True) without any reference being lost — that's class-level
                dedup vs per-call-site rows, not a bug. distinct_referrer_class_count is the number of
                distinct referrer classes across the FULL result regardless of row granularity, so you
                can confirm the underlying referrer set is unchanged even when the row count differs.
        """
        target_type_lower = target_type.lower()

        if target_type_lower == "class":
            return await _get_xrefs_to_class(
                class_name, offset, count,
                include_snippet=include_snippet, context_lines=context_lines,
                instance_id=instance_id,
            )
        elif target_type_lower == "method":
            if not member_name:
                return format_error_response(
                    "INVALID_INPUT",
                    "member_name is required for method xrefs",
                    {"hint": "Provide the method name via the member_name parameter"},
                )
            return await _get_xrefs_to_method(
                class_name, member_name, offset, count,
                include_snippet=include_snippet, context_lines=context_lines,
                instance_id=instance_id,
            )
        elif target_type_lower == "field":
            if not member_name:
                return format_error_response(
                    "INVALID_INPUT",
                    "member_name is required for field xrefs",
                    {"hint": "Provide the field name via the member_name parameter"},
                )
            return await _get_xrefs_to_field(
                class_name, member_name, offset, count, instance_id=instance_id,
            )
        else:
            return format_error_response(
                "INVALID_INPUT",
                f"Invalid target_type: {target_type}",
                {"valid_values": ["class", "method", "field"]},
            )

    @mcp.tool(name="batch_get_xrefs")
    @with_busy_check
    async def batch_get_xrefs_tool(
        targets: list[str],
        instance_id: Optional[str] = None,
    ) -> dict:
        """Fetch xrefs for multiple targets in one request (max 10). Format: "type:class[:member]".

        Args:
            targets: e.g. ["class:com.example.Foo", "method:com.example.Bar:myMethod"]. Max 10.
            instance_id: Target JADX instance name.
        Returns:
            dict: {results: [{target, found, xrefs_count, xrefs: [{class, raw_class, method,
                raw_method, from_method, raw_from_method, source_line[, decompiled_line,
                code_snippet]}], distinct_referrer_class_count, resolution, via}
                | {target, found: false, error}], total: int}. No pagination — always full lists.

                Same resolution/via/distinct_referrer_class_count semantics as get_xrefs (see its
                docstring) — always precise/per-call-site granularity, never the class-level dedup
                get_xrefs(include_snippet=False) uses, so xrefs_count and distinct_referrer_class_count
                can legitimately differ; both are correct.
        """
        return await _batch_get_xrefs(targets, instance_id=instance_id)

    @mcp.tool(name="submit_xref")
    async def submit_xref_tool(
        target_type: str = "",
        class_name: str = "",
        member_name: str = "",
        targets: Optional[list[str]] = None,
        include_snippet: bool = False,
        context_lines: int = 3,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Submit a background xref resolution. Returns a ticket immediately — no 120s timeout risk.

        Prefer this over get_xrefs / batch_get_xrefs on large APKs, or whenever include_snippet=True
        is needed and the class's referrers haven't been decompiled yet (the sync endpoints fall back
        to a full live decompile of every caller, which can hit the 120s HTTP timeout on big APKs).

        Two mutually exclusive request shapes:
        - single target: target_type=class|method|field, class_name, member_name (method/field name).
        - batch: targets=["class:com.example.Foo", "method:com.example.Bar:myMethod"] (max 10) — same
          format as batch_get_xrefs. When targets is given, target_type/class_name are ignored.

        Async workflow:
        1. ticket = submit_xref(target_type="class", class_name=...).ticket
        2. Wait retry_after_seconds, then:
        3. result = get_xref_result(ticket) — repeat until status="done"
        If the underlying precomputed index is already warm, this returns status="done" on the very
        first call (no polling needed) — check status before polling.

        Args:
            target_type: class|method|field (single-target mode; ignored if targets is set).
            class_name: Fully qualified class name (single-target mode).
            member_name: Method or field name (required for target_type=method|field).
            targets: Batch mode target list, format "type:class[:member]" (max 10).
            include_snippet: Attach surrounding source at each call site (single-target mode only).
            context_lines: Snippet context lines (default 3, only used with include_snippet=True).
            instance_id: Target JADX instance name.
        Returns:
            dict: {ticket, status: "submitted"|"done", retry_after_seconds}. Never contains the
                references themselves, even when status="done" — always follow up with
                get_xref_result(ticket) to fetch the data (retry_after_seconds is 0 in that case,
                so it's safe to call immediately without waiting).
        """
        return await _submit_xref(
            target_type=target_type, class_name=class_name, member_name=member_name,
            targets=targets, include_snippet=include_snippet, context_lines=context_lines,
            instance_id=instance_id,
        )

    @mcp.tool(name="get_xref_result")
    @with_busy_check
    async def get_xref_result_tool(
        ticket: str,
        offset: int = 0,
        count: int = 100,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Poll the result of an xref resolution submitted via submit_xref().

        Args:
            ticket: Ticket ID from submit_xref(). offset/count: Pagination over the reference list
                (single-target mode only — batch results aren't paginated, same as batch_get_xrefs).
            instance_id: Target JADX instance name.
        Returns:
            dict: status="running" (poll again, see retry_after_seconds) |
                  status="done" — single-target mode: {references: [...], pagination: {total, offset,
                  limit, count, has_more, ...}, resolution, via, distinct_referrer_class_count[, hint]}
                  (same field meanings as get_xrefs — see its docstring for what resolution/via/
                  distinct_referrer_class_count mean and why row counts can differ by granularity);
                  batch mode: {results: [...], total} (same shape as batch_get_xrefs, unpaginated) |
                  status="not_found" (ticket expired, or target class/method/field doesn't exist —
                  check message) | status="error" (check message)
        """
        return await _get_xref_result(ticket, offset, count, instance_id=instance_id)
