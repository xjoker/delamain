"""
delamain Gateway - Token Whitelist Authentication

Single-user, multi-equivalent-token model: any token present in the
allowlist (env `DELAMAIN_AUTH_TOKENS` + config.toml `[server] allowed_tokens`)
grants full, identical access. There is no per-token identity/role/admin
distinction — this replaced the multi-user auth system (see git history for
`src/auth/user_auth.py`).
"""

from __future__ import annotations

from fastmcp.server.auth import StaticTokenVerifier

_CLIENT_ID = "delamain-client"


def build_token_table(tokens: list[str]) -> dict[str, dict]:
    """Build the FastMCP static-token table from a flat token allowlist."""
    return {
        token: {"client_id": _CLIENT_ID, "scopes": ["mcp:user"]}
        for token in tokens
        if token
    }


def build_auth_provider(tokens: list[str]) -> StaticTokenVerifier | None:
    """Build a FastMCP static-token auth provider from the allowlist.

    Returns None only when the resolved list is empty; main.py refuses to
    start the gateway in that case (MCP auth is mandatory).
    """
    table = build_token_table(tokens)
    if not table:
        return None
    return StaticTokenVerifier(tokens=table)
