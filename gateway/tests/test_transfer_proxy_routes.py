"""Phase 3 Part A: gateway reverse-proxy for PUT /transfer/upload and
GET /transfer/status. The Java backend binds these to 127.0.0.1:8650 only in the
fused deployment, so the gateway (the only externally-exposed port) must relay
the raw bytes/headers through to it — streamed, not buffered whole in memory.

Uses httpx.ASGITransport twice: once to drive the real gateway ASGI app (so the
custom_route wiring in mcp_server.py is exercised end-to-end, including bypassing
MCP bearer auth), and once inside the gateway process itself, standing in for the
real Java backend so no real socket/subprocess is needed.
"""

import httpx
import pytest
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route

from src.registry.instance_registry import InstanceRegistry
from src.tools import transfer_tools


@pytest.fixture(autouse=True)
def reset_registry():
    InstanceRegistry.clear_all()
    InstanceRegistry.configure(host="127.0.0.1", port=8650)
    yield
    InstanceRegistry.clear_all()


def _fake_backend(received: dict, upload_status: int = 200, upload_body: dict | None = None):
    """A minimal Starlette app standing in for the Java TransferRoutes backend."""

    async def backend_upload(request):
        received["upload_headers"] = dict(request.headers)
        received["upload_body"] = await request.body()
        body = upload_body if upload_body is not None else {
            "status": "complete", "path": "test.apk", "bytes": len(received["upload_body"]),
        }
        return JSONResponse(body, status_code=upload_status)

    async def backend_status(request):
        received["status_headers"] = dict(request.headers)
        received["status_query"] = dict(request.query_params)
        return JSONResponse({"filename": "test.apk", "bytes_received": 123, "consumed": False})

    return Starlette(routes=[
        Route("/transfer/upload", backend_upload, methods=["PUT"]),
        Route("/transfer/status", backend_status, methods=["GET"]),
    ])


def _patch_backend(monkeypatch, backend_app):
    async def fake_get_http_client():
        return httpx.AsyncClient(transport=httpx.ASGITransport(app=backend_app))

    monkeypatch.setattr(transfer_tools, "get_http_client", fake_get_http_client)


async def _gateway_client():
    from src.mcp_server import build_mcp_app

    app = build_mcp_app().http_app()
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://gateway")


# ---------------------------------------------------------------------------
# PUT /transfer/upload
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_upload_streams_body_and_forwards_headers(monkeypatch):
    received: dict = {}
    _patch_backend(monkeypatch, _fake_backend(received))

    payload = b"x" * (256 * 1024)  # 256 KiB — big enough to matter if buffered wrong
    async with await _gateway_client() as client:
        resp = await client.put(
            "/transfer/upload",
            content=payload,
            headers={
                "X-Transfer-Token": "tok-123",
                "X-Chunk-Offset": "0",
                "X-Chunk-Final": "true",
                "X-Content-Sha256": "deadbeef",
            },
        )

    assert resp.status_code == 200
    assert resp.json()["status"] == "complete"
    assert received["upload_body"] == payload
    assert received["upload_headers"]["x-transfer-token"] == "tok-123"
    assert received["upload_headers"]["x-chunk-offset"] == "0"
    assert received["upload_headers"]["x-chunk-final"] == "true"
    assert received["upload_headers"]["x-content-sha256"] == "deadbeef"


@pytest.mark.asyncio
async def test_upload_does_not_require_mcp_bearer_auth(monkeypatch):
    """These routes bypass the gateway's MCP token-whitelist auth entirely — the
    build_mcp_app() used in these tests never has mcp.auth set, but the important
    behavioral guarantee (see main.py) is that mcp.auth only wraps the /mcp
    transport, never custom_route handlers like this one."""
    received: dict = {}
    _patch_backend(monkeypatch, _fake_backend(received))

    async with await _gateway_client() as client:
        resp = await client.put(
            "/transfer/upload",
            content=b"no-auth-header-needed",
            headers={"X-Transfer-Token": "tok-456", "X-Chunk-Final": "true"},
        )

    assert resp.status_code == 200


@pytest.mark.asyncio
async def test_upload_passes_through_backend_error_status_and_body(monkeypatch):
    received: dict = {}
    _patch_backend(
        monkeypatch,
        _fake_backend(received, upload_status=409, upload_body={"error": "offset_mismatch", "bytes_received": 42}),
    )

    async with await _gateway_client() as client:
        resp = await client.put(
            "/transfer/upload",
            content=b"stale-chunk",
            headers={"X-Transfer-Token": "tok-789", "X-Chunk-Offset": "10", "X-Chunk-Final": "false"},
        )

    assert resp.status_code == 409
    assert resp.json() == {"error": "offset_mismatch", "bytes_received": 42}


@pytest.mark.asyncio
async def test_upload_returns_503_when_no_backend_configured(monkeypatch):
    InstanceRegistry.clear_all()
    received: dict = {}
    _patch_backend(monkeypatch, _fake_backend(received))

    async with await _gateway_client() as client:
        resp = await client.put(
            "/transfer/upload",
            content=b"irrelevant",
            headers={"X-Transfer-Token": "tok-000", "X-Chunk-Final": "true"},
        )

    assert resp.status_code == 503
    assert resp.json()["error"] == "NO_INSTANCE"
    assert "upload_body" not in received  # never reached the backend


@pytest.mark.asyncio
async def test_upload_returns_502_when_backend_unreachable(monkeypatch):
    async def fake_get_http_client_raises():
        class _RaisingClient:
            def stream(self, *args, **kwargs):
                raise httpx.ConnectError("refused")

        return _RaisingClient()

    monkeypatch.setattr(transfer_tools, "get_http_client", fake_get_http_client_raises)

    async with await _gateway_client() as client:
        resp = await client.put(
            "/transfer/upload",
            content=b"irrelevant",
            headers={"X-Transfer-Token": "tok-111", "X-Chunk-Final": "true"},
        )

    assert resp.status_code == 502
    assert resp.json()["error"] == "INSTANCE_UNAVAILABLE"


# ---------------------------------------------------------------------------
# GET /transfer/status
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_status_forwards_token_header(monkeypatch):
    received: dict = {}
    _patch_backend(monkeypatch, _fake_backend(received))

    async with await _gateway_client() as client:
        resp = await client.get("/transfer/status", headers={"X-Transfer-Token": "tok-abc"})

    assert resp.status_code == 200
    assert resp.json()["filename"] == "test.apk"
    assert received["status_headers"]["x-transfer-token"] == "tok-abc"


@pytest.mark.asyncio
async def test_status_forwards_token_query_param(monkeypatch):
    received: dict = {}
    _patch_backend(monkeypatch, _fake_backend(received))

    async with await _gateway_client() as client:
        resp = await client.get("/transfer/status", params={"token": "tok-xyz"})

    assert resp.status_code == 200
    assert received["status_query"]["token"] == "tok-xyz"


@pytest.mark.asyncio
async def test_status_returns_503_when_no_backend_configured(monkeypatch):
    InstanceRegistry.clear_all()
    received: dict = {}
    _patch_backend(monkeypatch, _fake_backend(received))

    async with await _gateway_client() as client:
        resp = await client.get("/transfer/status", params={"token": "tok-xyz"})

    assert resp.status_code == 503
    assert resp.json()["error"] == "NO_INSTANCE"
