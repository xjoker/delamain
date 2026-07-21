"""Tools package.

BACKLOG: diff_tools.compare_versions() was removed with the multi-instance
registry (it needed two live JADX instances to diff against each other).
Redo it for the single-instance gateway later (e.g. diff two saved
analysis-session snapshots) if cross-version comparison is still wanted.
"""
from fastmcp import FastMCP

from .analysis_surface_tools import register_analysis_surface_tools
from .annotation_tools import register_annotation_tools
from .class_tools import register_class_tools
from .dataflow_tools import register_dataflow_tools
from .decompile_tools import register_decompile_tools
from .diagnostics_tools import register_diagnostics_tools
from .digest_tools import register_digest_tools
from .export_tools import register_export_tools
from .file_management_tools import register_file_management_tools
from .frida_tools import register_frida_tools
from .init_tools import register_init_tools
from .instance_tools import register_instance_tools
from .refactor_tools import register_refactor_tools
from .resource_tools import register_resource_tools
from .search_tools import register_search_tools
from .security_tools import register_security_tools
from .session_tools import register_session_tools
from .string_literal_tools import register_string_literal_tools
from .task_tools import register_task_tools
from .workflow_tools import register_workflow_tools
from .xrefs_tools import register_xrefs_tools


def register_all_tools(mcp: FastMCP):
    register_instance_tools(mcp)
    register_analysis_surface_tools(mcp)
    register_annotation_tools(mcp)
    register_class_tools(mcp)
    register_dataflow_tools(mcp)
    register_decompile_tools(mcp)
    register_diagnostics_tools(mcp)
    register_digest_tools(mcp)
    register_export_tools(mcp)
    register_file_management_tools(mcp)
    register_frida_tools(mcp)
    register_init_tools(mcp)
    register_refactor_tools(mcp)
    register_resource_tools(mcp)
    register_search_tools(mcp)
    register_security_tools(mcp)
    register_session_tools(mcp)
    register_string_literal_tools(mcp)
    register_task_tools(mcp)
    register_workflow_tools(mcp)
    register_xrefs_tools(mcp)
