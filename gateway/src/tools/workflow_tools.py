"""
delamain Gateway - Workflow Tools

High-level tools that compose existing primitives into AI-friendly single-call operations.
Two tools are exposed:
- analyze_apk_tool(path, strategy) — smart file routing on the single JADX backend
- list_loaded_files_tool()          — what the backend currently holds

Single-instance gateway: strategy is one of auto|replace|append. There is only
ever one backend, so there is no "route to a different instance" option —
callers who want a fresh load use replace, and dependency JARs use append.
"""

from ..busy_tracker import InstanceBusyTracker, with_busy_check
from ..registry.instance_registry import InstanceRegistry
from ..routing.request_router import get_from_jadx
from ..logging_config import get_logger
from ..types import ErrorCode

logger = get_logger("workflow_tools")

_NO_FILE_SIGNALS = ("no file", "no apk", "not loaded", "")


def _instance_has_no_file(file_info: dict) -> bool:
    """Whether the backend currently has nothing loaded.

    Sourced from /file-info (the identity authority — see ApkIdentity.java),
    not the legacy /apk-info shape, which never carried file_name/apk_package
    and made this always look empty even when /file-info showed a file
    loaded (the split-brain this fixes).
    """
    if not file_info:
        return True
    loaded = file_info.get("loaded")
    if isinstance(loaded, bool):
        return not loaded
    # Fallback for a response without an explicit `loaded` flag.
    pkg = str(file_info.get("apk_package") or "").lower().strip()
    fname = str(file_info.get("file_name") or "").lower().strip()
    return pkg in _NO_FILE_SIGNALS and fname in _NO_FILE_SIGNALS


async def _fetch_file_info(instance) -> dict:
    result = await get_from_jadx("file-info")
    if not result or result.get("error"):
        logger.debug(f"_fetch_file_info({instance.name}): {result.get('message') if result else 'no result'}")
        return {}
    return result


async def _fetch_warmup_status(instance) -> dict:
    # /decompile-status carries no "percentage"/"cached_percentage" field; the real
    # decompile-progress number lives on /warmup-status.
    result = await get_from_jadx("warmup-status")
    if not result or result.get("error"):
        logger.debug(f"_fetch_warmup_status({instance.name}): {result.get('message') if result else 'no result'}")
        return {}
    return result


async def _load_file_on_instance(instance, path: str, mode: str = "replace") -> dict:
    result = await get_from_jadx(
        "load-file",
        method="POST",
        json_body={"path": path, "mode": mode},
    )
    if result.get("error"):
        return {"error": result.get("message") or result["error"]}
    return result


def _instance_summary(instance) -> dict:
    return {
        "name": instance.name,
        "host": instance.host,
        "port": instance.port,
    }


_NO_INSTANCE_NEXT_STEPS = ["No JADX instance available. Configure one in Compose or Gateway TOML."]


async def analyze_apk(
    path: str,
    strategy: str = "auto",
) -> dict:
    if strategy not in ("auto", "replace", "append"):
        return {
            "status": "error",
            "instance": None,
            "path": path,
            "ready": False,
            "poll_with": None,
            "next_steps": [
                f"strategy must be one of: auto, replace, append — got '{strategy}'"
            ],
        }

    target = InstanceRegistry.get_default()

    if strategy in ("replace", "append"):
        if not target:
            return {
                "status": "error",
                "instance": None,
                "path": path,
                "ready": False,
                "poll_with": None,
                "next_steps": _NO_INSTANCE_NEXT_STEPS,
            }
        mode = "replace" if strategy == "replace" else "append"
        result = await _load_file_on_instance(target, path, mode=mode)
        if "error" in result:
            return {
                "status": "error",
                "instance": _instance_summary(target),
                "path": path,
                "ready": False,
                "poll_with": None,
                "next_steps": [result["error"]],
            }
        note = (
            "File is loading. Poll get_warmup_status until percentage reaches 100."
            if strategy == "replace"
            else "Dependency appended to open project. "
            "Poll get_warmup_status until percentage reaches 100."
        )
        return {
            "status": "loaded",
            "instance": _instance_summary(target),
            "path": path,
            "ready": False,
            "poll_with": "get_warmup_status",
            "next_steps": [note],
        }

    # strategy="auto"
    if not target:
        return {
            "status": "error",
            "instance": None,
            "path": path,
            "ready": False,
            "poll_with": None,
            "next_steps": _NO_INSTANCE_NEXT_STEPS,
        }

    file_info = await _fetch_file_info(target)
    if _instance_has_no_file(file_info):
        result = await _load_file_on_instance(target, path, mode="replace")
        if "error" in result:
            return {
                "status": "error",
                "instance": _instance_summary(target),
                "path": path,
                "ready": False,
                "poll_with": None,
                "next_steps": [result["error"]],
            }
        return {
            "status": "loaded",
            "instance": _instance_summary(target),
            "path": path,
            "ready": False,
            "poll_with": "get_warmup_status",
            "next_steps": ["File is loading. Poll get_warmup_status until percentage reaches 100."],
        }

    loaded_name = file_info.get("file_name") or file_info.get("apk_package") or "unknown"
    return {
        "status": "ambiguous",
        "instance": _instance_summary(target),
        "path": path,
        "ready": False,
        "poll_with": None,
        "next_steps": [
            f"The backend already has '{loaded_name}' loaded.",
            "Choose one of:",
            f"  analyze_apk('{path}', strategy='replace') — replace '{loaded_name}'",
            f"  analyze_apk('{path}', strategy='append')   — add as dependency to current project",
        ],
    }


async def list_loaded_files() -> dict:
    target = InstanceRegistry.get_default()
    if not target:
        return {"error": ErrorCode.NO_INSTANCE, "message": "No JADX backend configured"}

    file_info = await _fetch_file_info(target)
    warmup_status = await _fetch_warmup_status(target)

    has_no_file = _instance_has_no_file(file_info)
    identity = file_info.get("apk_identity") or {}
    loaded_file = None if has_no_file else (
        file_info.get("file_name") or identity.get("file_name") or file_info.get("apk_package") or None
    )

    progress = warmup_status.get("percentage") or 0.0
    decompile_progress = round(float(progress) / 100.0, 2) if isinstance(progress, (int, float)) else 0.0

    # "Has a file" and "is free" are different questions; this tool used to answer the first
    # while calling it the second. Whether the backend can take work now comes from the lane
    # scheduler, which is the only component that actually knows.
    snapshot = InstanceBusyTracker.get_snapshot()
    busy = any(
        snapshot.get(lane, 0) > 0
        for lane in ("metadata_inflight", "code_read_inflight", "analysis_inflight", "exclusive_inflight")
    )

    return {
        "name": target.name,
        "host": target.host,
        "port": target.port,
        "loaded_file": loaded_file,
        "decompile_progress": decompile_progress,
        "file_loaded": not has_no_file,
        "busy": busy,
        "version_name": file_info.get("version_name") or identity.get("version_name"),
        "version_code": (
            file_info.get("version_code")
            if file_info.get("version_code") is not None
            else identity.get("version_code")
        ),
        "input_hash": identity.get("input_hash") or file_info.get("input_hash"),
    }


def register_workflow_tools(mcp):
    """Register workflow tools with the MCP server."""

    @mcp.tool()
    @with_busy_check
    async def analyze_apk_tool(
        path: str,
        strategy: str = "auto",
    ) -> dict:
        """Load an APK/JAR into the JADX backend with smart routing.

        strategy="auto" (default): load if the backend is free; return "ambiguous"
          when it already has a file loaded (human guidance needed).
        strategy="replace": load, replacing whatever is currently open.
        strategy="append": append to current project (useful for dep JARs).

        Args:
            path: Sandbox-relative file path (e.g. "target.apk"). strategy: auto|replace|append.
        Returns:
            dict: {status: loaded|ambiguous|error, instance, path, ready: false,
                   poll_with, next_steps}
        """
        return await analyze_apk(path=path, strategy=strategy)

    @mcp.tool()
    async def list_loaded_files_tool() -> dict:
        """Show what APK/JAR the JADX backend is currently analyzing and whether it is free.

        file_loaded and busy answer different questions and are both needed to decide
        whether to send work now: a backend can hold a fully warmed APK (file_loaded=true)
        and still be free (busy=false), which is the normal steady state.

        Returns:
            dict: {name, host, port, loaded_file, decompile_progress,
                   file_loaded: bool — a file is open on the backend,
                   busy: bool — an operation is in flight right now (any lane); when true,
                     a heavy call may come back INSTANCE_BUSY,
                   version_name, version_code, input_hash} — or
                  {error: "NO_INSTANCE", message} when no backend is configured.
        """
        return await list_loaded_files()

    logger.info("Workflow tools registered: analyze_apk_tool, list_loaded_files_tool")
