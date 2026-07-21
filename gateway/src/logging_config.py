"""
delamain Gateway - Logger Factory

The multi-user context-enrichment machinery (per-request instance/user log
context) was dropped along with the multi-instance/multi-user architecture —
see the repo's Phase 2.5 audit. Actual stream/level configuration lives in
main.py's logging.basicConfig() call.
"""

import logging
from typing import Optional


def get_logger(name: Optional[str] = None) -> logging.Logger:
    """Get a logger instance with unified configuration."""
    if name:
        full_name = f"delamain.{name}"
    else:
        full_name = "delamain"
    return logging.getLogger(full_name)


# Convenience: Pre-configured main logger
logger = get_logger()
