"""
delamain Gateway - Frida Hook Generation Tools
"""

from typing import Optional

from ..logging_config import get_logger
from ..routing.request_router import get_from_jadx
from ..busy_tracker import with_busy_check

logger = get_logger("frida_tools")

_FRIDA_HOOK_METADATA = {
    "frida_min_version": "16.0",
    "requires": ["Java"],
    "tested_arch": ["arm64-v8a", "x86_64"],
    "notes": "Uses Java.use() — requires ART runtime. Will not work on native-only targets.",
}

_FRIDA_TRACE_METADATA = {
    "frida_min_version": "16.0",
    "requires": ["Java"],
    "tested_arch": ["arm64-v8a", "x86_64"],
    "notes": (
        "Uses Java.use() with method.implementation overrides — requires ART runtime. "
        "Include subclasses with include_subclasses=True to capture polymorphic calls."
    ),
}

_FRIDA_ENUM_METADATA = {
    "frida_min_version": "16.0",
    "requires": ["Java"],
    "tested_arch": ["arm64-v8a", "x86_64"],
    "notes": (
        "Uses Java.choose() to enumerate live instances — requires ART runtime. "
        "Static field reads happen immediately; instance enumeration may block briefly on large heaps."
    ),
}


async def generate_frida_hook(
    class_name: str,
    method_name: Optional[str] = None,
    hook_type: str = "both",
    instance_id: Optional[str] = None,
) -> dict:
    params: dict = {"class_name": class_name, "hook_type": hook_type}
    if method_name:
        params["method_name"] = method_name

    result = await get_from_jadx("generate-frida-hook", params, instance_id=instance_id)
    if "error" in result:
        logger.warning(f"generate_frida_hook error: {result.get('error')}")
    else:
        logger.info(f"generate_frida_hook: class={class_name}, method={method_name}, type={hook_type}")
        result["metadata"] = _FRIDA_HOOK_METADATA
    return result


async def generate_frida_trace(
    class_name: str,
    include_subclasses: bool = False,
    instance_id: Optional[str] = None,
) -> dict:
    params: dict = {
        "class_name": class_name,
        "include_subclasses": "true" if include_subclasses else "false",
    }
    result = await get_from_jadx("generate-frida-trace", params, instance_id=instance_id)
    if "error" in result:
        logger.warning(f"generate_frida_trace error: {result.get('error')}")
    else:
        logger.info(f"generate_frida_trace: class={class_name}, subclasses={include_subclasses}")
        result["metadata"] = _FRIDA_TRACE_METADATA
    return result


async def generate_frida_enum(
    class_name: str,
    instance_id: Optional[str] = None,
) -> dict:
    params: dict = {"class_name": class_name}
    result = await get_from_jadx("generate-frida-enum", params, instance_id=instance_id)
    if "error" in result:
        logger.warning(f"generate_frida_enum error: {result.get('error')}")
    else:
        logger.info(f"generate_frida_enum: class={class_name}")
        result["metadata"] = _FRIDA_ENUM_METADATA
    return result


_generate_frida_hook = generate_frida_hook
_generate_frida_trace = generate_frida_trace
_generate_frida_enum = generate_frida_enum


def register_frida_tools(mcp):
    """Register Frida script generation tools with the MCP Server."""

    @mcp.tool()
    @with_busy_check
    async def generate_frida_hook(
        class_name: str,
        method_name: Optional[str] = None,
        hook_type: str = "both",
        instance_id: Optional[str] = None,
    ) -> dict:
        """Generate a ready-to-run Frida hook script with overload support. Omit method_name to use all_methods.

        Args:
            class_name: Fully qualified class name. method_name: Method name (optional).
            hook_type: method_enter|method_exit|both|constructor|all_methods (default: both).
            instance_id: Target JADX instance name.
        Returns:
            dict: {script: str, class_name, method_name, hook_type}
        """
        return await _generate_frida_hook(
            class_name, method_name=method_name, hook_type=hook_type, instance_id=instance_id,
        )

    @mcp.tool()
    @with_busy_check
    async def generate_frida_trace(
        class_name: str,
        include_subclasses: bool = False,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Generate a Frida tracing script that logs all method calls on a class with args and return values.

        Args:
            class_name: Fully qualified class name. include_subclasses: Also trace direct subclasses.
            instance_id: Target JADX instance name.
        Returns:
            dict: {script: str, class_name, include_subclasses}
        """
        return await _generate_frida_trace(
            class_name, include_subclasses=include_subclasses, instance_id=instance_id,
        )

    @mcp.tool()
    @with_busy_check
    async def generate_frida_enum(
        class_name: str,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Generate a Frida script to enumerate class instances, static fields, and enum constants at runtime.

        Args:
            class_name: Fully qualified class name. instance_id: Target JADX instance name.
        Returns:
            dict: {script: str, class_name}
        """
        return await _generate_frida_enum(class_name, instance_id=instance_id)

    logger.info("Frida tools registered: generate_frida_hook, generate_frida_trace, generate_frida_enum")
