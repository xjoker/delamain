"""
delamain Gateway - Code Refactoring Tools
"""

from typing import Optional

from ..routing.request_router import get_from_jadx
from ..types import format_error_response
from ..busy_tracker import with_busy_check


async def rename_class(class_name: str, new_name: str) -> dict:
    return await get_from_jadx(
        "rename-class",
        method="POST",
        json_body={"class_name": class_name, "new_name": new_name},
    )


async def rename_method(
    class_name: str,
    method_name: str,
    new_name: str,
    method_signature: Optional[str] = None,
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
        method="POST",
        json_body=json_body,
    )


async def rename_field(
    class_name: str,
    field_name: str,
    new_name: str,
) -> dict:
    return await get_from_jadx(
        "rename-field",
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
        "rename-variable", method="POST", json_body=body,
    )


async def export_rename_mappings() -> dict:
    return await get_from_jadx("export-rename-mappings")


async def import_rename_mappings(mappings: list) -> dict:
    return await get_from_jadx(
        "import-rename-mappings",
        method="POST",
        json_body={"mappings": mappings},
    )


async def apply_proguard_mapping(mapping_content: str) -> dict:
    return await get_from_jadx(
        "apply-proguard-mapping",
        method="POST",
        json_body={"mapping_content": mapping_content},
    )


async def rename_package(
    old_package_name: str,
    new_package_name: str,
) -> dict:
    return await get_from_jadx(
        "rename-package",
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
    ) -> dict:
        """Rename a local variable inside a method using JADX SSA tracking.

        Args:
            class_name: Fully qualified class name. method_name: Method name.
            variable_name: Current variable name. new_name: New name.
            reg: Register number (disambiguation). ssa: SSA version (disambiguation).
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
        )

    @mcp.tool()
    @with_busy_check
    async def export_rename_mappings_tool() -> dict:
        """Export all rename mappings (classes, methods, fields) from the current JADX session.

        Returns:
            dict: {mappings: [{type, original_name, new_name, class_context}], total: int}
        """
        return await export_rename_mappings()

    @mcp.tool()
    @with_busy_check
    async def import_rename_mappings_tool(
        mappings: list,
    ) -> dict:
        """Batch-apply rename mappings (same format as export_rename_mappings output).

        Args:
            mappings: List of {type, original_name, new_name, class_context} dicts.
        Returns:
            dict: {success: bool, total: int, applied: int, failed: int, errors: [str]}
        """
        return await import_rename_mappings(mappings=mappings)

    @mcp.tool()
    @with_busy_check
    async def rename(
        target_type: str,
        old_name: str,
        new_name: str,
        class_name: str = "",
        method_signature: Optional[str] = None,
        dry_run: bool = False,
    ) -> dict:
        """Unified rename for classes, methods, fields, and packages. Triggers 30s cache cooldown.

        This only changes the JADX display alias, never the class/method/field's actual
        (bytecode) name — for class/method/field the response echoes the raw name so you keep
        the one identifier Frida/Xposed can still target after the rename.

        Args:
            target_type: class|method|field|package. old_name: Current name. new_name: New name.
            class_name: Required for method/field. method_signature: JVM descriptor for overloads.
            dry_run: Preview without renaming.
        Returns:
            The shape depends on target_type — package is a bulk operation and does NOT follow
            the single-target shape:
            - class:   {result: str, raw_name: str}
            - method:  {result: str, raw_class_name: str, raw_method_name: str}
            - field:   {result: str, raw_class_name: str, raw_field_name: str}
            - package: {renamed: int, total: int, errors: [str], interrupted: bool} — renames
                       every class under the package; names no individual class, so it carries
                       no raw name. interrupted=true means an orderly partial batch (everything
                       counted in 'renamed' is committed), not a failure.
            dry_run (any target_type): {dry_run: true, target_exists: bool, target_type: str,
                       target_info: {...} | None}
            Rejected input (missing class_name, unknown target_type): {success: false, error: str}.
        """
        target_type_lower = target_type.lower()

        if dry_run:
            lookup_class = old_name if target_type_lower in ("class", "package") else class_name
            if not lookup_class:
                return {"dry_run": True, "target_exists": False, "error": "class_name required"}
            info = await get_from_jadx(
                "class-info", {"class_name": lookup_class}
            )
            exists = not isinstance(info, dict) or "error" not in info
            return {
                "dry_run": True,
                "target_exists": exists,
                "target_type": target_type_lower,
                "target_info": info if exists else None,
            }

        if target_type_lower == "class":
            return await rename_class(old_name, new_name)
        elif target_type_lower == "method":
            if not class_name:
                return {"success": False, "error": "class_name required for method rename"}
            return await rename_method(
                class_name, old_name, new_name,
                method_signature=method_signature,
            )
        elif target_type_lower == "field":
            if not class_name:
                return {"success": False, "error": "class_name required for field rename"}
            return await rename_field(class_name, old_name, new_name)
        elif target_type_lower == "package":
            return await rename_package(old_name, new_name)
        else:
            return {
                "success": False,
                "error": f"Invalid target_type: {target_type}. Use: class, method, field, package",
            }

    @mcp.tool()
    @with_busy_check
    async def apply_proguard_mapping_tool(
        mapping_content: str,
    ) -> dict:
        """Apply a ProGuard/R8 mapping.txt to batch-rename all obfuscated classes at once.

        Args:
            mapping_content: Complete contents of a ProGuard/R8 mapping.txt file.
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
            mapping_content=mapping_content,
        )
