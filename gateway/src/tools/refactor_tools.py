"""
delamain Gateway - Code Refactoring Tools
"""

from typing import Optional

from ..routing.request_router import get_from_jadx
from ..types import format_error_response
from ..busy_tracker import with_busy_check


async def rename_class(class_name: str, new_name: str, instance_id: Optional[str] = None) -> dict:
    return await get_from_jadx(
        "rename-class",
        instance_id=instance_id,
        method="POST",
        json_body={"class_name": class_name, "new_name": new_name},
    )


async def rename_method(
    class_name: str,
    method_name: str,
    new_name: str,
    method_signature: Optional[str] = None,
    instance_id: Optional[str] = None,
) -> dict:
    json_body: dict = {
        "class_name": class_name,
        "method_name": method_name,
        "new_name": new_name,
    }
    if method_signature:
        json_body["method_signature"] = method_signature
    return await get_from_jadx(
        "rename-method",
        instance_id=instance_id,
        method="POST",
        json_body=json_body,
    )


async def rename_field(
    class_name: str,
    field_name: str,
    new_name: str,
    instance_id: Optional[str] = None,
) -> dict:
    return await get_from_jadx(
        "rename-field",
        instance_id=instance_id,
        method="POST",
        json_body={
            "class_name": class_name,
            "field_name": field_name,
            "new_field_name": new_name,
        },
    )


async def rename_variable(
    class_name: str,
    method_name: str,
    variable_name: str,
    new_name: str,
    reg: Optional[str] = None,
    ssa: Optional[str] = None,
    instance_id: Optional[str] = None,
) -> dict:
    body: dict = {
        "class_name": class_name,
        "method_name": method_name,
        "variable_name": variable_name,
        "new_name": new_name,
    }
    if reg is not None:
        body["reg"] = reg
    if ssa is not None:
        body["ssa"] = ssa
    return await get_from_jadx(
        "rename-variable", instance_id=instance_id, method="POST", json_body=body,
    )


async def export_rename_mappings(instance_id: Optional[str] = None) -> dict:
    return await get_from_jadx("export-rename-mappings", instance_id=instance_id)


async def import_rename_mappings(mappings: list, instance_id: Optional[str] = None) -> dict:
    return await get_from_jadx(
        "import-rename-mappings",
        instance_id=instance_id,
        method="POST",
        json_body={"mappings": mappings},
    )


async def apply_proguard_mapping(mapping_content: str, instance_id: Optional[str] = None) -> dict:
    return await get_from_jadx(
        "apply-proguard-mapping",
        instance_id=instance_id,
        method="POST",
        json_body={"mapping_content": mapping_content},
    )


async def rename_package(
    old_package_name: str,
    new_package_name: str,
    instance_id: Optional[str] = None,
) -> dict:
    return await get_from_jadx(
        "rename-package",
        instance_id=instance_id,
        method="POST",
        json_body={
            "old_package_name": old_package_name,
            "new_package_name": new_package_name,
        },
    )


def register_refactor_tools(mcp):
    """Register refactoring tools to MCP Server."""

    @mcp.tool()
    @with_busy_check
    async def rename_variable_tool(
        class_name: str,
        method_name: str,
        variable_name: str,
        new_name: str,
        reg: Optional[str] = None,
        ssa: Optional[str] = None,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Rename a local variable inside a method using JADX SSA tracking.

        Args:
            class_name: Fully qualified class name. method_name: Method name.
            variable_name: Current variable name. new_name: New name.
            reg: Register number (disambiguation). ssa: SSA version (disambiguation).
            instance_id: Target JADX instance name.
        Returns:
            dict: {result: str} on success or {error: str, status: 404} if not found.
        """
        return await rename_variable(
            class_name=class_name,
            method_name=method_name,
            variable_name=variable_name,
            new_name=new_name,
            reg=reg,
            ssa=ssa,
            instance_id=instance_id,
        )

    @mcp.tool()
    @with_busy_check
    async def export_rename_mappings_tool(instance_id: Optional[str] = None) -> dict:
        """Export all rename mappings (classes, methods, fields) from the current JADX session.

        Args:
            instance_id: Target JADX instance name.
        Returns:
            dict: {mappings: [{type, original_name, new_name, class_context}], total: int}
        """
        return await export_rename_mappings(instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def import_rename_mappings_tool(
        mappings: list,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Batch-apply rename mappings (same format as export_rename_mappings output).

        Args:
            mappings: List of {type, original_name, new_name, class_context} dicts.
            instance_id: Target JADX instance name.
        Returns:
            dict: {success: bool, total: int, applied: int, failed: int, errors: [str]}
        """
        return await import_rename_mappings(mappings=mappings, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def rename(
        target_type: str,
        old_name: str,
        new_name: str,
        class_name: str = "",
        method_signature: Optional[str] = None,
        dry_run: bool = False,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Unified rename for classes, methods, fields, and packages. Triggers 30s cache cooldown.

        Args:
            target_type: class|method|field|package. old_name: Current name. new_name: New name.
            class_name: Required for method/field. method_signature: JVM descriptor for overloads.
            dry_run: Preview without renaming. instance_id: Target JADX instance name.
        Returns:
            dict: {success: bool, message: str} or dry_run: {target_exists: bool, target_info: {...}}
        """
        target_type_lower = target_type.lower()

        if dry_run:
            lookup_class = old_name if target_type_lower in ("class", "package") else class_name
            if not lookup_class:
                return {"dry_run": True, "target_exists": False, "error": "class_name required"}
            info = await get_from_jadx(
                "class-info", {"class_name": lookup_class}, instance_id=instance_id
            )
            exists = not isinstance(info, dict) or "error" not in info
            return {
                "dry_run": True,
                "target_exists": exists,
                "target_type": target_type_lower,
                "target_info": info if exists else None,
            }

        if target_type_lower == "class":
            return await rename_class(old_name, new_name, instance_id=instance_id)
        elif target_type_lower == "method":
            if not class_name:
                return {"success": False, "error": "class_name required for method rename"}
            return await rename_method(
                class_name, old_name, new_name,
                method_signature=method_signature,
                instance_id=instance_id,
            )
        elif target_type_lower == "field":
            if not class_name:
                return {"success": False, "error": "class_name required for field rename"}
            return await rename_field(class_name, old_name, new_name, instance_id=instance_id)
        elif target_type_lower == "package":
            return await rename_package(old_name, new_name, instance_id=instance_id)
        else:
            return {
                "success": False,
                "error": f"Invalid target_type: {target_type}. Use: class, method, field, package",
            }

    @mcp.tool()
    @with_busy_check
    async def apply_proguard_mapping_tool(
        mapping_content: str,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Apply a ProGuard/R8 mapping.txt to batch-rename all obfuscated classes at once.

        Args:
            mapping_content: Complete contents of a ProGuard/R8 mapping.txt file.
            instance_id: Target JADX instance name.
        Returns:
            dict: {applied: int, failed: int, errors: [str], total: int, format: str}
        """
        if not mapping_content or not mapping_content.strip():
            return format_error_response(
                "INVALID_INPUT",
                "mapping_content cannot be empty",
                {"hint": "Provide the full contents of a ProGuard/R8 mapping.txt file"},
            )
        if "->" not in mapping_content:
            return format_error_response(
                "INVALID_INPUT",
                "mapping_content does not appear to be a valid ProGuard mapping file",
                {
                    "hint": (
                        "ProGuard/R8 mapping files contain '->' arrows, e.g.:\n"
                        "  com.example.RealName -> a.b:\n"
                        "      void realMethod() -> c\n"
                        "Ensure you are pasting the complete mapping.txt content."
                    )
                },
            )
        return await apply_proguard_mapping(
            mapping_content=mapping_content, instance_id=instance_id,
        )
