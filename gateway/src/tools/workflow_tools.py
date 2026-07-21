"""
delamain Gateway - Workflow Tools

High-level tools that compose existing primitives into AI-friendly single-call operations.
Two tools are exposed:
- analyze_apk_tool(path, strategy) — smart file routing on the single JADX backend
- list_loaded_files_tool()          — what the backend currently holds

Single-instance gateway: strategy="new_instance" has no second backend to route
to, so it returns the same "all_busy" shape as an exhausted auto-route instead
of spinning up a new JADX worker.
"""

from typing import Optional

from ..registry.instance_registry import InstanceRegistry
from ..routing.request_router import get_from_jadx
from ..logging_config import get_logger

logger = get_logger("workflow_tools")

_NO_FILE_SIGNALS = ("no file", "no apk", "not loaded", "")


def _instance_has_no_file(apk_info: dict) -> bool:
    if not apk_info:
        return True
    pkg = apk_info.get("apk_package", "").lower().strip()
    fname = apk_info.get("file_name", "").lower().strip()
    status = apk_info.get("status", "").lower().strip()
    if pkg in _NO_FILE_SIGNALS or status in ("no_file", "no file", "empty"):
        return True
    if not pkg and not fname:
        return True
    return False


async def _fetch_apk_info(instance) -> dict:
    result = await get_from_jadx("apk-info", instance_id=instance.name)
    if not result or result.get("error"):
        logger.debug(f"_fetch_apk_info({instance.name}): {result.get('message') if result else 'no result'}")
        return {}
    return result


async def _fetch_decompile_status(instance) -> dict:
    result = await get_from_jadx("decompile-status", instance_id=instance.name)
    if not result or result.get("error"):
        logger.debug(f"_fetch_decompile_status({instance.name}): {result.get('message') if result else 'no result'}")
        return {}
    return result


async def _load_file_on_instance(instance, path: str, mode: str = "replace") -> dict:
    result = await get_from_jadx(
        "load-file",
        instance_id=instance.name,
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
    instance_id: Optional[str] = None,
) -> dict:
    if strategy not in ("auto", "replace", "new_instance", "append"):
        return {
            "status": "error",
            "instance": None,
            "path": path,
            "ready": False,
            "poll_with": None,
            "next_steps": [
                f"strategy must be one of: auto, replace, new_instance, append — got '{strategy}'"
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
            "File is loading. Poll get_decompile_status until cached_percentage stabilises."
            if strategy == "replace"
            else "Dependency appended to open project. "
            "Poll get_decompile_status until cached_percentage stabilises."
        )
        return {
            "status": "loaded",
            "instance": _instance_summary(target),
            "path": path,
            "ready": False,
            "poll_with": "get_decompile_status",
            "next_steps": [note],
        }

    if strategy == "new_instance":
        return {
            "status": "all_busy",
            "instance": None,
            "path": path,
            "ready": False,
            "poll_with": None,
            "next_steps": [
                "This gateway is wired to a single JADX backend — there is no second "
                "instance to route to.",
                "1. Call analyze_apk(path, strategy='replace') to overwrite the current file.",
                "2. Call analyze_apk(path, strategy='append') to add it as a dependency instead.",
            ],
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

    apk_info = await _fetch_apk_info(target)
    if _instance_has_no_file(apk_info):
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
            "poll_with": "get_decompile_status",
            "next_steps": ["File is loading. Poll get_decompile_status until cached_percentage stabilises."],
        }

    loaded_name = apk_info.get("file_name") or apk_info.get("apk_package") or "unknown"
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


async def list_loaded_files(instance_id: Optional[str] = None) -> dict:
    target = InstanceRegistry.get_default()
    if not target:
        return {"instances": [], "count": 0, "free_count": 0}

    apk_info = await _fetch_apk_info(target)
    decompile_status = await _fetch_decompile_status(target)

    loaded_file = apk_info.get("file_name") or apk_info.get("apk_package") or None
    if loaded_file and _instance_has_no_file(apk_info):
        loaded_file = None

    progress = decompile_status.get("cached_percentage") or decompile_status.get("percentage") or 0.0
    decompile_progress = round(float(progress) / 100.0, 2) if isinstance(progress, (int, float)) else 0.0

    available = _instance_has_no_file(apk_info)

    result = {
        "name": target.name,
        "host": target.host,
        "port": target.port,
        "loaded_file": loaded_file,
        "decompile_progress": decompile_progress,
        "available": available,
    }
    return {
        "instances": [result],
        "count": 1,
        "free_count": 1 if available else 0,
    }


def register_workflow_tools(mcp):
    """Register workflow tools with the MCP server."""

    @mcp.tool()
    async def analyze_apk_tool(
        path: str,
        strategy: str = "auto",
        instance_id: Optional[str] = None,
    ) -> dict:
        """Load an APK/JAR into the JADX backend with smart routing.

        strategy="auto" (default): load if the backend is free; return "ambiguous"
          when it already has a file loaded (human guidance needed).
        strategy="replace": load, replacing whatever is currently open.
        strategy="new_instance": no-op in this single-backend gateway — returns
          all_busy pointing at replace/append instead.
        strategy="append": append to current project (useful for dep JARs).

        Args:
            path: Sandbox-relative file path (e.g. "target.apk"). strategy: auto|replace|new_instance|append.
            instance_id: Unused — this gateway has a single fixed JADX backend.
        Returns:
            dict: {status: loaded|ambiguous|all_busy|error, instance, path, ready: false,
                   poll_with, next_steps}
        """
        return await analyze_apk(path=path, strategy=strategy, instance_id=instance_id)

    @mcp.tool()
    async def list_loaded_files_tool(
        instance_id: Optional[str] = None,
    ) -> dict:
        """Show what APK/JAR the JADX backend is currently analyzing and whether it is free.

        Args:
            instance_id: Unused — this gateway has a single fixed JADX backend.
        Returns:
            dict: {instances: [{name, loaded_file, decompile_progress, available}], count, free_count}
        """
        return await list_loaded_files(instance_id=instance_id)

    logger.info("Workflow tools registered: analyze_apk_tool, list_loaded_files_tool")
