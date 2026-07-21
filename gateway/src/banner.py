"""
delamain Gateway - Version

Single source of truth is the repository-root VERSION file (see repo CLAUDE.md
versioning convention: YYYYMMDD.N). Read once at import time so /health and the
startup banner in main.py always agree.
"""

from pathlib import Path

_VERSION_FILE = Path(__file__).resolve().parent.parent.parent / "VERSION"
_FALLBACK_VERSION = "0.0.0-unknown"


def _read_version() -> str:
    try:
        return _VERSION_FILE.read_text(encoding="utf-8").strip() or _FALLBACK_VERSION
    except OSError:
        return _FALLBACK_VERSION


SERVER_VERSION = _read_version()
