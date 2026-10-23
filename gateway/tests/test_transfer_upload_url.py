"""Bug #3 regression: create_transfer_token's upload_url must point at the JADX Java
backend (default port 8650), never at the gateway's own address (MCP_SERVER_URL, 8651).

Covers src.tools.transfer_tools.resolve_upload_base priority:
  env (JADX_TRANSFER_PUBLIC_URL) > toml ([server] transfer_public_url) > instance host:port.
"""

import pytest
from starlette.requests import Request
from types import SimpleNamespace

from src.tools import transfer_tools
from src.tools.transfer_tools import resolve_upload_base


def test_env_value_wins_over_everything():
    assert resolve_upload_base(
        "http://env.example:8650", "http://toml.example:8650", "http://10.0.0.5:8650",
    ) == "http://env.example:8650"


def test_toml_value_used_when_env_absent():
    assert resolve_upload_base(
        None, "http://toml.example:8650", "http://10.0.0.5:8650",
    ) == "http://toml.example:8650"


def test_falls_back_to_resolved_instance_address():
    assert resolve_upload_base(None, None, "http://10.0.0.5:8650") == "http://10.0.0.5:8650"


def test_none_when_nothing_configured():
    assert resolve_upload_base(None, None, None) is None


def test_trailing_slash_is_stripped():
    assert resolve_upload_base("http://env.example:8650/", None, None) == "http://env.example:8650"


def test_empty_string_values_are_treated_as_absent():
    # e.g. TOML field left as "" (default) must not shadow a lower-priority source.
    assert resolve_upload_base("", "", "http://10.0.0.5:8650") == "http://10.0.0.5:8650"


@pytest.fixture
def restore_gateway_base():
    saved = transfer_tools._gateway_public_base
    yield
    transfer_tools._gateway_public_base = saved


def _fake_backend_token(monkeypatch):
    async def fake_get_from_jadx(*args, **kwargs):
        return {"token": "f" * 64, "filename": "app.apk", "upload_path": "/transfer/upload"}

    monkeypatch.setattr(transfer_tools, "get_from_jadx", fake_get_from_jadx)
    monkeypatch.delenv("JADX_TRANSFER_PUBLIC_URL", raising=False)
    monkeypatch.setattr(transfer_tools, "get_config_loader", lambda: None)


def _configure_explicit_public_url(monkeypatch, env_value, toml_value):
    monkeypatch.delenv("JADX_TRANSFER_PUBLIC_URL", raising=False)
    if env_value is not None:
        monkeypatch.setenv("JADX_TRANSFER_PUBLIC_URL", env_value)
    if toml_value is None:
        monkeypatch.setattr(transfer_tools, "get_config_loader", lambda: None)
    else:
        monkeypatch.setattr(
            transfer_tools,
            "get_config_loader",
            lambda: SimpleNamespace(
                config=SimpleNamespace(
                    server=SimpleNamespace(transfer_public_url=toml_value)
                )
            ),
        )


def _http_request(host: str, scheme: str = "http") -> Request:
    return Request({
        "type": "http", "method": "POST", "scheme": scheme, "path": "/mcp",
        "raw_path": b"/mcp", "query_string": b"", "root_path": "",
        "headers": [(b"host", host.encode())], "server": ("0.0.0.0", 8651),
    })


@pytest.mark.asyncio
async def test_wildcard_bind_is_never_advertised_as_upload_url(monkeypatch, restore_gateway_base):
    _fake_backend_token(monkeypatch)
    transfer_tools.configure_gateway_public_base("0.0.0.0", 8651)

    result = await transfer_tools._create_transfer_token_impl("app.apk", None, None)

    assert "0.0.0.0" not in str(result)
    assert "upload_url" not in result
    assert "JADX_TRANSFER_PUBLIC_URL" in result["upload_url_hint"]


@pytest.mark.asyncio
@pytest.mark.parametrize("bind_host", ["0.0.0.0", "::"])
async def test_wildcard_bind_falls_back_to_request_host(monkeypatch, restore_gateway_base, bind_host):
    _fake_backend_token(monkeypatch)
    transfer_tools.configure_gateway_public_base(bind_host, 8651)
    import fastmcp.server.dependencies as deps
    monkeypatch.setattr(deps, "get_http_request", lambda: _http_request("127.0.0.1:18651"))

    result = await transfer_tools._create_transfer_token_impl("app.apk", None, None)
    assert result["upload_url"] == "http://127.0.0.1:18651/transfer/upload"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("env_value", "toml_value"),
    [
        ("http://0.0.0.0:8651", None),
        ("http://[::]:8651", None),
        (None, "http://0.0.0.0:8651"),
        (None, "http://[::]:8651"),
    ],
    ids=["env-ipv4", "env-ipv6", "toml-ipv4", "toml-ipv6"],
)
async def test_explicit_wildcard_public_url_returns_hint_without_request_host_fallback(
    monkeypatch, restore_gateway_base, env_value, toml_value,
):
    _fake_backend_token(monkeypatch)
    _configure_explicit_public_url(monkeypatch, env_value, toml_value)
    transfer_tools.configure_gateway_public_base("0.0.0.0", 8651)
    import fastmcp.server.dependencies as deps
    monkeypatch.setattr(deps, "get_http_request", lambda: _http_request("gateway.example:8651"))

    result = await transfer_tools._create_transfer_token_impl("app.apk", None, None)

    assert "upload_url" not in result
    assert "upload_url_hint" in result
    assert "gateway.example:8651" not in str(result)


@pytest.mark.asyncio
async def test_valid_explicit_public_url_keeps_priority_over_request_host(monkeypatch, restore_gateway_base):
    _fake_backend_token(monkeypatch)
    _configure_explicit_public_url(monkeypatch, "https://public.example:9443", "http://toml.example:8651")
    transfer_tools.configure_gateway_public_base("0.0.0.0", 8651)
    import fastmcp.server.dependencies as deps
    monkeypatch.setattr(deps, "get_http_request", lambda: _http_request("gateway.example:8651"))

    result = await transfer_tools._create_transfer_token_impl("app.apk", None, None)

    assert result["upload_url"] == "https://public.example:9443/transfer/upload"


@pytest.mark.parametrize("host", ["0.0.0.0:8651", "[::]:8651", "evil.com/path", "a b", "u@h", "", "h\r\nX: y"])
def test_request_host_rejects_wildcard_and_malformed_values(monkeypatch, host):
    import fastmcp.server.dependencies as deps
    monkeypatch.setattr(deps, "get_http_request", lambda: _http_request(host))
    assert transfer_tools._request_base_url() is None


def test_request_host_keeps_scheme_and_ipv6(monkeypatch):
    import fastmcp.server.dependencies as deps
    monkeypatch.setattr(deps, "get_http_request", lambda: _http_request("[::1]:8651", scheme="https"))
    assert transfer_tools._request_base_url() == "https://[::1]:8651"


def test_resolve_skips_wildcard_derived_addresses_but_honours_explicit_config():
    assert resolve_upload_base(None, None, "http://0.0.0.0:8651", "http://gw.example:8651") == "http://gw.example:8651"
    assert resolve_upload_base(None, None, "http://0.0.0.0:8651", None) is None
    assert resolve_upload_base(None, None, "http://[::]:8651", None) is None
