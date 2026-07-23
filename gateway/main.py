#!/usr/bin/env python3
"""delamain-gateway - HTTP MCP gateway for a single delamain backend.

Deployment guardrail: this process holds singleton in-memory state (a single
InstanceRegistry backend, a single shared httpx.AsyncClient, a single
InstanceBusyTracker table) and assumes exactly one asyncio event loop for its
whole lifetime. Always run it with a single uvicorn worker — the FastMCP
default `mcp.run(...)` call below already does this; do not add `workers=` or
front it with a multi-worker process manager, or this in-memory state
silently forks into inconsistent copies.
"""

import argparse
import logging
import os
import re
import sys

# Unbuffered, stderr-only logging so the many logger.info() calls scattered
# across src/ actually surface (see repo CLAUDE.md logging convention: no
# file logs, stdout/stderr only, level from env).
logging.basicConfig(
    stream=sys.stderr,
    level=os.getenv("DELAMAIN_LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)


def _resolve_allowed_tokens(env_value: str | None, config_tokens: list[str]) -> list[str]:
    """MCP client token whitelist: env DELAMAIN_AUTH_TOKENS (comma/newline separated)
    merged with config.toml [server] allowed_tokens. Any listed token grants identical,
    full access — there is no per-token identity/role in this single-user gateway.
    """
    tokens = list(config_tokens or [])
    if env_value:
        for tok in re.split(r"[,\n]", env_value):
            tok = tok.strip()
            if tok and tok not in tokens:
                tokens.append(tok)
    return tokens


def resolve_jadx_token(cli_token: str | None, config_token: str | None, env_value: str | None) -> str:
    """Internal JADX-plugin handshake token: CLI --auth-token > config.toml
    [defaults] jadx_token > env DELAMAIN_AUTH_TOKEN, matching the Java side's
    priority (Main.java: CLI wins, env is only a fallback when CLI is empty).

    Kept in this order deliberately: docker/entrypoint.sh passes the same
    generated JADX_INTERNAL_TOKEN via --auth-token to both processes and does
    not set DELAMAIN_AUTH_TOKEN. If the two sides disagreed on priority, an
    operator setting DELAMAIN_AUTH_TOKEN directly (as older docs suggested)
    would desync the gateway from Java and cause every backend call to 401.
    """
    return cli_token or config_token or env_value or ""


def main():
    parser = argparse.ArgumentParser("delamain-gateway")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8651)
    parser.add_argument("--config", default=None, help="TOML config file path")
    parser.add_argument("--auth-token", default=None, help="Shared JADX plugin token")
    parser.add_argument(
        "--jadx", default=None,
        help="host:port of the single JADX backend (default 127.0.0.1:8650)",
    )
    args = parser.parse_args()

    from src.mcp_server import build_mcp_app
    from src.config.config_loader import AppConfig, ConfigLoader, set_config_loader
    from src.registry.instance_registry import InstanceRegistry
    from src.auth.mcp_auth import build_auth_provider

    # 1. Load TOML config (if provided)
    config = AppConfig()
    if args.config:
        from pathlib import Path
        _loader = ConfigLoader(Path(args.config))
        config = _loader.load()
        set_config_loader(_loader)

    # 2. Resolve JADX plugin token (CLI > config > env; see resolve_jadx_token
    #    docstring for why this must match the Java side's priority).
    jadx_token = resolve_jadx_token(
        cli_token=args.auth_token,
        config_token=config.defaults.jadx_token,
        env_value=os.environ.get("DELAMAIN_AUTH_TOKEN"),
    )
    InstanceRegistry.set_auth_token(jadx_token)

    # 3. Resolve the MCP client token whitelist (env DELAMAIN_AUTH_TOKENS + config
    #    [server] allowed_tokens). Any of these tokens grants full access.
    allowed_tokens = _resolve_allowed_tokens(
        os.environ.get("DELAMAIN_AUTH_TOKENS"), config.server.allowed_tokens,
    )
    if not allowed_tokens:
        parser.error(
            "MCP authentication is required: set DELAMAIN_AUTH_TOKENS "
            "(comma/newline separated) or [server] allowed_tokens in the gateway TOML"
        )

    # 4. Configure the single fixed JADX backend (CLI --jadx > config [jadx] > default).
    host, port = config.jadx.host, config.jadx.port
    if args.jadx:
        parts = args.jadx.strip().split(":")
        if parts[0]:
            host = parts[0]
        if len(parts) > 1 and parts[1]:
            port = int(parts[1])
    InstanceRegistry.configure(host=host, port=port, token=config.jadx.token or jadx_token)

    # 4b. Record this gateway's own bind host:port as the default fallback address
    #     for create_transfer_token's upload_url (see transfer_tools.py) — the JADX
    #     Java backend's /transfer/* endpoints are proxied through this gateway, not
    #     reachable directly from outside the fused container.
    from src.tools.transfer_tools import configure_gateway_public_base
    configure_gateway_public_base(args.host, args.port)

    # 5. Build FastMCP app and wire the token-whitelist auth provider.
    mcp = build_mcp_app()
    mcp.auth = build_auth_provider(allowed_tokens)

    from src.banner import SERVER_VERSION
    print(
        f"[delamain-gateway] v{SERVER_VERSION} | HTTP {args.host}:{args.port} "
        f"| JADX backend {host}:{port}"
    )

    # STRICT HTTP-only — no stdio fallback. Single uvicorn worker — see module
    # docstring; do not pass workers>1.
    mcp.run(transport="http", host=args.host, port=args.port, stateless_http=True,
            show_banner=False)


if __name__ == "__main__":
    main()
