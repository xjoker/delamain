"""
JADX Backend Registry (single fixed backend)

Simplified from a multi-instance/multi-user registry (867 lines, dynamic
add/remove, owner-based access control, per-loop httpx client pools) down to
a single fixed JADX Java backend holder. The gateway now proxies to exactly
one backend, configured once at startup via `configure()`.

Kept as a small class (rather than inlining into request_router) because
several modules (busy_tracker, workflow_tools, transfer_tools, request_router)
share the same "what backend do we talk to" question and some tests still
poke at instance status directly.
"""

import logging
from dataclasses import dataclass
from typing import Optional

logger = logging.getLogger(__name__)

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8650


@dataclass
class JadxInstance:
    """The single JADX backend this gateway process talks to.

    No status/health state machine here — connectivity is checked live on
    every request (see request_router.get_from_jadx()'s httpx exception
    handling), not cached on this object. See H1 in the Phase 2.5 audit: the
    old status field was only ever written by a background health-monitor
    thread that no longer exists, so it was permanently stuck at "connected"
    and the INSTANCE_UNAVAILABLE branch that read it was unreachable.
    """
    name: str
    host: str
    port: int
    token: str = ""

    @property
    def url(self) -> str:
        return f"http://{self.host}:{self.port}"


class InstanceRegistry:
    """
    Holds the single fixed JADX backend for this gateway process.

    Single machine + single instance + single event loop: no owner/access
    control, no dynamic add/remove, no per-loop httpx client pools. The
    module-level httpx.AsyncClient used to talk to this backend lives in
    request_router.py.
    """

    DEFAULT_HOST = DEFAULT_HOST
    DEFAULT_PORT = DEFAULT_PORT

    _instance: Optional[JadxInstance] = None
    _shared_auth_token: str = ""

    @classmethod
    def configure(
        cls,
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        token: Optional[str] = None,
        name: str = "jadx",
    ) -> None:
        """Register the single JADX backend this gateway proxies to."""
        cls._instance = JadxInstance(
            name=name,
            host=host,
            port=port,
            token=token or cls._shared_auth_token or "",
        )
        logger.info(f"Configured single JADX backend: {name} ({host}:{port})")

    @classmethod
    def set_auth_token(cls, token: str) -> None:
        """Set the shared JADX plugin authentication token."""
        cls._shared_auth_token = token
        logger.info("Authentication token has been set")

    @classmethod
    def get_auth_token(cls) -> str:
        """Get the shared JADX plugin authentication token."""
        return cls._shared_auth_token

    @classmethod
    def get_default(cls) -> Optional[JadxInstance]:
        """Get the single configured backend, or None if not yet configured."""
        return cls._instance

    @classmethod
    def clear_all(cls) -> None:
        """Reset registry state (for testing)."""
        cls._instance = None
