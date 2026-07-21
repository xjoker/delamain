"""
delamain Gateway - Analysis Session Persistence Tools
"""

import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from ..logging_config import get_logger
from ..routing.request_router import get_from_jadx
from ..busy_tracker import with_busy_check

logger = get_logger("session_tools")

_SESSION_NAME_PATTERN = re.compile(r"^[a-zA-Z0-9_-]{1,100}$")
_SESSIONS_DIR = Path.home() / ".delamain" / "sessions"


def _get_sessions_dir() -> Path:
    return _SESSIONS_DIR


def _validate_session_name(name: str) -> Optional[str]:
    if not name:
        return "session_name cannot be empty"
    if not _SESSION_NAME_PATTERN.match(name):
        return (
            "session_name must contain only letters, digits, underscores, "
            "and hyphens (1-100 characters)"
        )
    return None


async def save_analysis_session(
    session_name: str,
    context: dict,
    instance_id: Optional[str] = None,
) -> dict:
    logger.info(f"save_analysis_session: name={session_name}")

    error = _validate_session_name(session_name)
    if error:
        return {"error": "INVALID_SESSION_NAME", "message": error}

    file_info: dict = {}
    try:
        file_info = await get_from_jadx("file-info", instance_id=instance_id)
        if isinstance(file_info, str):
            file_info = {}
    except Exception as exc:
        logger.warning(f"save_analysis_session: failed to fetch file-info: {exc}")

    sessions_dir = _get_sessions_dir()
    sessions_dir.mkdir(parents=True, exist_ok=True)

    file_path = sessions_dir / f"{session_name}.json"
    now_iso = datetime.now(timezone.utc).isoformat()

    previous_save_time: Optional[str] = None
    if file_path.exists():
        try:
            existing = json.loads(file_path.read_text(encoding="utf-8"))
            previous_save_time = existing.get("metadata", {}).get("saved_at")
        except (json.JSONDecodeError, OSError) as exc:
            logger.warning(f"save_analysis_session: failed to read existing session: {exc}")

    session_data = {
        "metadata": {
            "session_name": session_name,
            "saved_at": now_iso,
            "previous_save_time": previous_save_time,
            "instance_id": instance_id,
            "file_info": file_info,
        },
        "context": context,
    }

    file_path.write_text(
        json.dumps(session_data, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    logger.info(f"save_analysis_session: saved to {file_path}")
    return {
        "success": True,
        "session_name": session_name,
        "saved_at": now_iso,
        "file_path": str(file_path),
    }


async def load_analysis_session(session_name: str) -> dict:
    logger.info(f"load_analysis_session: name={session_name}")

    error = _validate_session_name(session_name)
    if error:
        return {"error": "INVALID_SESSION_NAME", "message": error}

    file_path = _get_sessions_dir() / f"{session_name}.json"

    if not file_path.exists():
        return {
            "error": "SESSION_NOT_FOUND",
            "message": f"Session '{session_name}' not found",
            "sessions_dir": str(_get_sessions_dir()),
        }

    try:
        data = json.loads(file_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        return {
            "error": "READ_ERROR",
            "message": f"Failed to read session file: {exc}",
        }

    logger.info(f"load_analysis_session: loaded from {file_path}")
    return data


async def list_analysis_sessions() -> dict:
    logger.info("list_analysis_sessions")

    sessions_dir = _get_sessions_dir()
    if not sessions_dir.exists():
        return {
            "sessions": [],
            "count": 0,
            "sessions_dir": str(sessions_dir),
        }

    sessions: list[dict] = []
    for fp in sorted(sessions_dir.glob("*.json")):
        try:
            data = json.loads(fp.read_text(encoding="utf-8"))
            metadata = data.get("metadata", {})
            context = data.get("context", {})
            notes = context.get("notes", "")

            file_info = metadata.get("file_info", {})
            file_type = file_info.get("file_type", file_info.get("type", "unknown"))

            sessions.append({
                "session_name": metadata.get("session_name", fp.stem),
                "saved_at": metadata.get("saved_at", ""),
                "file_type": file_type,
                "notes_preview": notes[:100] if notes else "",
            })
        except (json.JSONDecodeError, OSError) as exc:
            logger.warning(f"list_analysis_sessions: skipping {fp.name}: {exc}")

    return {
        "sessions": sessions,
        "count": len(sessions),
        "sessions_dir": str(sessions_dir),
    }


_save_analysis_session = save_analysis_session
_load_analysis_session = load_analysis_session
_list_analysis_sessions = list_analysis_sessions


def register_session_tools(mcp):
    """Register session persistence tools with the MCP server."""

    @mcp.tool()
    @with_busy_check
    async def save_analysis_session(
        session_name: str,
        context: dict,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Save AI analysis context to a local JSON file for later restoration.

        Args:
            session_name: Name (alphanumeric, hyphens, underscores; 1-100 chars).
            context: Dict with keys: analyzed_classes, findings, notes, current_focus, next_steps.
            instance_id: Target JADX instance name.
        Returns:
            dict: {success, session_name, saved_at, file_path}
        """
        return await _save_analysis_session(session_name, context, instance_id=instance_id)

    @mcp.tool()
    @with_busy_check
    async def load_analysis_session(session_name: str) -> dict:
        """Load a previously saved analysis session to restore context.

        Args:
            session_name: Session name to load.
        Returns:
            dict: {metadata: {saved_at, file_info}, context: {...}} or SESSION_NOT_FOUND error.
        """
        return await _load_analysis_session(session_name)

    @mcp.tool()
    @with_busy_check
    async def list_analysis_sessions() -> dict:
        """List all saved analysis sessions with names, save times, and notes previews.

        Returns:
            dict: {sessions: [{session_name, saved_at, file_type, notes_preview}], count}
        """
        return await _list_analysis_sessions()

    logger.info(
        "Session tools registered: save_analysis_session, "
        "load_analysis_session, list_analysis_sessions"
    )
