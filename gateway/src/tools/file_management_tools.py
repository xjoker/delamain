"""
delamain Gateway - File Management Tools
"""

from typing import Optional

from ..logging_config import get_logger
from ..routing.request_router import get_from_jadx
from ..busy_tracker import with_busy_check

logger = get_logger("file_management_tools")


async def list_available_files(
    subdir: Optional[str] = None,
    pattern: Optional[str] = None,
    recursive: bool = False,
    instance_id: Optional[str] = None,
) -> dict:
    params: dict = {"recursive": "true" if recursive else "false"}
    if subdir:
        params["subdir"] = subdir
    if pattern:
        params["pattern"] = pattern
    return await get_from_jadx("list-available-files", params, instance_id=instance_id)


async def load_file(
    path: str,
    mode: str = "replace",
    instance_id: Optional[str] = None,
) -> dict:
    if mode not in ("replace", "append"):
        return {"error": f"mode must be 'replace' or 'append', got: {mode}"}
    return await get_from_jadx(
        "load-file",
        instance_id=instance_id,
        method="POST",
        json_body={"path": path, "mode": mode},
    )

def register_file_management_tools(mcp):
    """Register file-management tools with the MCP server."""

    @mcp.tool()
    async def list_available_files_tool(
        subdir: Optional[str] = None,
        pattern: Optional[str] = None,
        recursive: bool = False,
        instance_id: Optional[str] = None,
    ) -> dict:
        """List APK/JAR/AAR/DEX files available in the JADX sandbox. Use before load_file.

        Args:
            subdir: Optional sub-path to scope listing. pattern: Glob like "*.apk" (default "*").
            recursive: Recurse into subdirs. instance_id: Target JADX instance.
        Returns:
            dict: {root, files: [{path, size_bytes, extension, loadable}], count}
        """
        return await list_available_files(
            subdir=subdir, pattern=pattern, recursive=recursive, instance_id=instance_id,
        )

    @mcp.tool()
    @with_busy_check
    async def load_file_tool(
        path: str,
        mode: str = "replace",
        instance_id: Optional[str] = None,
    ) -> dict:
        """Load an APK/JAR/AAR/DEX into JADX without touching the GUI. Returns immediately (202).

        Loading is async, and index warmup starts automatically after it. The response carries a
        warmup snapshot (phase, eta_seconds, capabilities) plus _ai_instruction — read them instead
        of guessing when to start work:
          - poll get_decompile_status until the class tree is loaded;
          - metadata search (search_in='class'/'method'/'field'), get_class_source and smali are
            usable from that moment;
          - poll get_warmup_status and wait for capabilities.code_search == "ready" before any
            search_in='code' or high-fan-in xref call. Issuing those during warmup is the slowest
            possible timing: the content index does not exist yet and warmup owns the CPU.

        Args:
            path: Sandbox-relative path (e.g. "target.apk"). mode: replace|append.
            instance_id: Target JADX instance.
        Returns:
            dict: {dispatched: bool, mode, path, ready: false, poll_with: "get_decompile_status",
                   auto_warmup: bool, warmup_status_endpoint,
                   warmup: {phase, eta_seconds, capabilities}, _ai_instruction}
        """
        return await load_file(path=path, mode=mode, instance_id=instance_id)

    logger.info("File management tools registered: list_available_files_tool, load_file_tool")
