"""
delamain Gateway - DEX String Literal Search Tools
"""

from typing import Optional

from ..logging_config import get_logger
from ..routing.request_router import get_from_jadx
from ..types import format_error_response
from ..busy_tracker import with_busy_check

logger = get_logger("string_literal_tools")


async def search_string_literals(
    pattern: str,
    regex: bool = False,
    min_length: int = 8,
    class_filter: str = "",
    limit: int = 100,
    force_decompile: bool = False,
    instance_id: Optional[str] = None,
) -> dict:
    params: dict = {
        "pattern": pattern,
        "regex": str(regex).lower(),
        "min_length": str(min_length),
        "limit": str(limit),
    }
    if class_filter:
        params["class"] = class_filter
    if force_decompile:
        params["force_decompile"] = "true"

    return await get_from_jadx("search-string-literals", params, instance_id=instance_id)


_search_string_literals = search_string_literals


def register_string_literal_tools(mcp):
    """Register DEX string literal search tools with the MCP server."""

    @mcp.tool()
    @with_busy_check
    async def search_string_literals(
        pattern: str,
        regex: bool = False,
        min_length: int = 8,
        class_filter: str = "",
        limit: int = 100,
        force_decompile: bool = False,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Search for string constants (URL, API key, SQL, etc.) embedded in DEX bytecode.

        This tool scans Java/Kotlin string literals compiled directly into the
        DEX — NOT Android resource strings from strings.xml (use get_strings for those).

        IMPORTANT COVERAGE LIMITATION: By default, only already-decompiled classes are
        scanned. If coverage_pct in the response is low (e.g. < 80%), an empty result
        does NOT mean the string is absent — it may simply not be decompiled yet.
        Check coverage_note in the response for details.

        To get complete coverage for a specific package, use class_filter + force_decompile=True.
        force_decompile requires class_filter to prevent full-APK decompilation.

        Typical use cases:
        - Find hardcoded API endpoints:       pattern="https://api.", regex=False
        - Find potential API keys/secrets:    pattern="[A-Za-z0-9]{32,}", regex=True
        - Complete scan of a package:         class_filter="com.example.auth", force_decompile=True

        Args:
            pattern: String to search for, or a regex pattern when regex=True.
            regex: When True, treat pattern as a Java regular expression (default False).
            min_length: Skip string literals shorter than this (default 8).
            class_filter: Restrict to classes whose fully-qualified name contains this substring.
                          Required when force_decompile=True.
            limit: Maximum results to return (default 100, max 200 server-side).
            force_decompile: When True, trigger decompilation of undecompiled classes in the
                             filtered set (requires class_filter). Slower but ensures complete
                             coverage of the target package (default False).
            instance_id: Target JADX instance name.
        Returns:
            dict: {
                results: [{class_name: str, literal: str, line_number: int}],
                total: int, truncated: bool,
                scanned_classes: int,  # classes actually searched
                total_classes: int,    # total classes in APK
                coverage_pct: int,     # scanned / total * 100
                coverage_note: str,    # human-readable coverage assessment
                cached_percentage_at_scan: int  # % already decompiled at scan time
            }
        """
        if not pattern or not pattern.strip():
            return format_error_response(
                "INVALID_INPUT",
                "pattern is required and cannot be empty",
                {"hint": "Provide a search string or regex pattern to match string literals"},
            )
        if min_length < 0:
            return format_error_response(
                "INVALID_INPUT",
                "min_length must be non-negative",
                {"hint": "Use 0 to include all strings, or 8 (default) to skip trivial constants"},
            )
        if limit < 1:
            return format_error_response(
                "INVALID_INPUT",
                "limit must be at least 1",
                {"hint": "Use limit=100 (default) or up to 200"},
            )

        return await _search_string_literals(
            pattern=pattern,
            regex=regex,
            min_length=min_length,
            class_filter=class_filter,
            limit=limit,
            force_decompile=force_decompile,
            instance_id=instance_id,
        )

    logger.info("String literal tools registered: search_string_literals")
