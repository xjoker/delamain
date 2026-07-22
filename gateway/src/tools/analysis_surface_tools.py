"""
delamain Gateway - Attack Surface & Call Graph Analysis Tools
"""

from typing import Optional

from ..logging_config import get_logger
from ..routing.request_router import get_from_jadx
from ..types import format_error_response
from ..busy_tracker import with_busy_check

logger = get_logger("analysis_surface_tools")

_MAX_CALLGRAPH_DEPTH = 6


async def get_attack_surface(instance_id: Optional[str] = None) -> dict:
    result = await get_from_jadx("attack-surface", instance_id=instance_id)
    if isinstance(result, dict) and "error" not in result:
        total_exported = result.get("total_exported", 0)
        deeplinks = result.get("deeplink_summary", [])
        deeplink_count = len(deeplinks) if isinstance(deeplinks, list) else 0
        dangerous_perms = result.get("dangerous_permissions_used", [])
        dangerous_count = len(dangerous_perms) if isinstance(dangerous_perms, list) else 0
        custom_perms = result.get("custom_permissions", [])
        custom_count = len(custom_perms) if isinstance(custom_perms, list) else 0

        result["summary"] = (
            f"{total_exported} exported components found, "
            f"{deeplink_count} deeplink(s), "
            f"{dangerous_count} dangerous permission(s) used, "
            f"{custom_count} custom permission(s) declared."
        )
    return result


async def export_callgraph(
    class_name: str,
    method_name: str,
    depth: int = 3,
    output_format: str = "json",
    instance_id: Optional[str] = None,
) -> dict:
    effective_depth = max(1, min(depth, _MAX_CALLGRAPH_DEPTH))
    params: dict = {
        "class": class_name,
        "method": method_name,
        "depth": str(effective_depth),
        "format": output_format,
    }
    return await get_from_jadx("export-callgraph", params, instance_id=instance_id)


async def get_native_surface(instance_id: Optional[str] = None) -> dict:
    return await get_from_jadx("native-surface", instance_id=instance_id)


_get_attack_surface = get_attack_surface
_export_callgraph = export_callgraph
_get_native_surface = get_native_surface


def register_analysis_surface_tools(mcp):
    """Register attack surface and call graph tools with the MCP server."""

    @mcp.tool()
    @with_busy_check
    async def get_attack_surface(instance_id: Optional[str] = None) -> dict:
        """Analyze the Android attack surface: exported components, deep-links, and dangerous permissions.

        This is the recommended first tool for security-focused analysis.
        No warmup required — results are derived from the manifest and metadata.

        Args:
            instance_id: Target JADX instance name.
        Returns:
            dict: {
                activities, services, receivers, providers (exported),
                custom_permissions, dangerous_permissions_used,
                deeplink_summary: [{scheme, host, path}],
                total_exported: int, summary: str
            }
        """
        return await _get_attack_surface(instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def export_callgraph(
        class_name: str,
        method_name: str,
        depth: int = 3,
        format: str = "json",
        instance_id: Optional[str] = None,
    ) -> dict:
        """Export the call graph for a method as structured JSON or Graphviz DOT.

        Use this instead of assembling a call graph by hand from repeated get_xrefs /
        method-callees calls — it returns the full multi-method neighborhood in one call.

        Depth is capped at 6 client-side. For deep call chains, start with depth=2
        and increase incrementally.

        Args:
            class_name: Fully qualified class name (e.g. "com.example.LoginActivity").
            method_name: Method name to root the graph at (e.g. "onCreate").
            depth: Traversal depth 1-6 (default 3). Values above 6 are clamped to 6.
            format: "json" (default, nodes+edges dict) or "dot" (Graphviz DOT language string).
            instance_id: Target JADX instance name.
        Returns:
            When format=json: {format, root, depth, nodes:[{id,class,method}], edges:[{from,to}], truncated}
            When format=dot:  {format, dot:"digraph { ... }"}
        """
        if not class_name or not class_name.strip():
            return format_error_response(
                "INVALID_INPUT",
                "class_name is required and cannot be empty",
                {"hint": "Provide a fully qualified class name, e.g. 'com.example.MyClass'"},
            )
        if not method_name or not method_name.strip():
            return format_error_response(
                "INVALID_INPUT",
                "method_name is required and cannot be empty",
                {"hint": "Provide the method name, e.g. 'onCreate' or 'handleRequest'"},
            )
        if format not in ("json", "dot"):
            return format_error_response(
                "INVALID_INPUT",
                f"Invalid format: '{format}'. Must be 'json' or 'dot'.",
                {"valid_values": ["json", "dot"]},
            )

        return await _export_callgraph(
            class_name=class_name,
            method_name=method_name,
            depth=depth,
            output_format=format,
            instance_id=instance_id,
        )

    @mcp.tool()
    @with_busy_check
    async def get_native_surface(instance_id: Optional[str] = None) -> dict:
        """Aggregate a native/JNI reverse-engineering handoff worklist: every declared native
        method (with a JNI mangled-name candidate) plus every System.loadLibrary/System.load
        target found in the APK. Use this to build the Ghidra/unidbg worklist instead of
        manually collecting native methods and library names class by class.

        jni_name_candidate is the short-form JNI symbol (Java_<class>_<method>) — an overloaded
        native may actually be exported under the long-form (signature-suffixed) symbol instead
        if the short form is ambiguous in the native library's export table.

        Args:
            instance_id: Target JADX instance name.
        Returns:
            dict: {native_methods: [{class_name, raw_class_name, method_name, raw_method_name,
            param_types_frida, jni_name_candidate}], native_method_count, loaded_libraries:
            [{name, found_in_class}], loaded_library_count, note}
        """
        return await _get_native_surface(instance_id=instance_id)

    logger.info("Analysis surface tools registered: get_attack_surface, export_callgraph, get_native_surface")
