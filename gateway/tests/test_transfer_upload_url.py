"""create_transfer_token's upload_url resolution.

Covers src.tools.transfer_tools.resolve_upload_base priority:
  env (JADX_TRANSFER_PUBLIC_URL) > toml ([server] transfer_public_url)
  > gateway bind host:port (non-wildcard only) > Host of the current MCP request.

Issue #9: with the Docker image's default bind (0.0.0.0:8651) and no public URL
configured, upload_url used to be http://0.0.0.0:8651/transfer/upload.
"""

import pytest
from starlette.requests import Request

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


# ---------------------------------------------------------------------------
# Issue #9: never advertise a wildcard bind address
# ---------------------------------------------------------------------------

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


def _http_request(host: str, scheme: str = "http") -> Request:
    return Request({
        "type": "http", "method": "POST", "scheme": scheme, "path": "/mcp",
        "raw_path": b"/mcp", "query_string": b"", "root_path": "",
        "headers": [(b"host", host.encode())], "server": ("0.0.0.0", 8651),
    })


@pytest.mark.asyncio
async def test_wildcard_bind_is_never_advertised_as_upload_url(monkeypatch, restore_gateway_base):
    """Docker default: gateway bound on 0.0.0.0:8651, no JADX_TRANSFER_PUBLIC_URL."""
    _fake_backend_token(monkeypatch)
    transfer_tools.configure_gateway_public_base("0.0.0.0", 8651)

    result = await transfer_tools._create_transfer_token_impl("app.apk", None, None)

    assert "0.0.0.0" not in str(result)
    # No request context here (direct call), so nothing reachable is known -> hint only.
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


@pytest.mark.parametrize("host", ["0.0.0.0:8651", "[::]:8651", "evil.com/path", "a b", "u@h", "", "h\r\nX: y"])
def test_request_host_rejects_wildcard_and_malformed_values(monkeypatch, host):
    import fastmcp.server.dependencies as deps
    monkeypatch.setattr(deps, "get_http_request", lambda: _http_request(host))
    assert transfer_tools._request_base_url() is None


def test_request_host_keeps_scheme_and_ipv6(monkeypatch):
    import fastmcp.server.dependencies as deps
    monkeypatch.setattr(deps, "get_http_request", lambda: _http_request("[::1]:8651", scheme="https"))
    assert transfer_tools._request_base_url() == "https://[::1]:8651"


def test_specific_bind_host_still_used(restore_gateway_base):
    transfer_tools.configure_gateway_public_base("10.0.0.5", 8651)
    assert transfer_tools._gateway_public_base == "http://10.0.0.5:8651"


def test_resolve_skips_wildcard_derived_addresses_but_honours_explicit_config():
    assert resolve_upload_base(None, None, "http://0.0.0.0:8651", "http://gw.example:8651") == "http://gw.example:8651"
    assert resolve_upload_base(None, None, "http://0.0.0.0:8651", None) is None
    assert resolve_upload_base(None, None, "http://[::]:8651", None) is None
    # An explicitly configured public URL is the operator's decision and is used as-is.
    assert resolve_upload_base("http://env.example:8651", None, "http://0.0.0.0:8651") == "http://env.example:8651"
