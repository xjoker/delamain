"""
delamain Gateway - Type Definitions

Common type definitions for consistent typing across all modules.
"""

from typing import TypedDict, Optional, Any, Dict, Union


# =============================================================================
# Tool Result Types
# =============================================================================

class SuccessResult(TypedDict, total=False):
    """Standard success response from MCP tools."""
    success: bool  # Always True
    data: Any
    message: str
    instance: str  # Target JADX instance name


class ErrorResult(TypedDict, total=False):
    """Standard error response from MCP tools."""
    error: str  # Error code (e.g., "INSTANCE_BUSY")
    message: str  # Human-readable error message
    instance: str  # Target JADX instance name (if known)


# Union type for tool return values
ToolResult = Union[SuccessResult, ErrorResult, Dict[str, Any]]


# =============================================================================
# Error Codes
# =============================================================================

class ErrorCode:
    """Standard error codes for consistent error handling."""
    INSTANCE_BUSY = "INSTANCE_BUSY"
    NO_INSTANCE = "NO_INSTANCE"
    INVALID_INPUT = "INVALID_INPUT"
    TIMEOUT = "TIMEOUT"


def format_error_response(
    error_code: str,
    message: str,
    details: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Build a unified error response for MCP tool return values."""
    result: Dict[str, Any] = {
        "ok": False,
        "error_code": error_code,
        "message": message,
        "details": details or {},
    }
    return result
