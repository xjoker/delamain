"""SAF-90 end-to-end: start the real gateway and assert access-log redaction."""

import os
import re
import socket
import subprocess
import sys
import threading
from pathlib import Path

import httpx
import pytest

GATEWAY_DIR = Path(__file__).resolve().parent.parent
FAKE_TRANSFER = "FAKE-SECRET-123"
FAKE_ACCESS = "FAKE-ACCESS-456"
FAKE_SIG = "FAKE-SIG-789"
FAKE_MCP = "fake-mcp-token-for-tests"

_RUNNING_RE = re.compile(r"Uvicorn running on http://127\.0\.0\.1:(\d+)")
_DEAD_BACKEND = "127.0.0.1:1"

RAW_TARGETS = (
    f"/health?TOKEN={FAKE_TRANSFER}&Token=b-{FAKE_TRANSFER}&a=1",
    f"/health?token[]={FAKE_TRANSFER}&%74oken=d-{FAKE_TRANSFER}&b=2",
    f"/health?a=1;token={FAKE_TRANSFER};c=3",
    f"/transfer/status?a=1#z&token={FAKE_TRANSFER}",
    f"/health?token=A#{FAKE_SIG}&b=1",
    f"/health?token=A;{FAKE_SIG}&c=1",
    f"/health;token=AAA%2F{FAKE_SIG}",
    f"/transfer/status;token={FAKE_TRANSFER}?foo=bar",
    f"/health;%74oken={FAKE_TRANSFER}",
)


def _raw_get(port: int, target: str) -> int:
    with socket.create_connection(("127.0.0.1", port), timeout=10) as s:
        s.sendall(
            f"GET {target} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n".encode()
        )
        data = b""
        while chunk := s.recv(65536):
            data += chunk
    return int(data.split(b" ", 2)[1])


@pytest.fixture
def gateway_logs():
    env = {**os.environ, "DELAMAIN_AUTH_TOKENS": FAKE_MCP, "PYTHONUNBUFFERED": "1"}
    env.pop("DELAMAIN_AUTH_TOKEN", None)
    env.pop("JADX_TRANSFER_PUBLIC_URL", None)
    proc = subprocess.Popen(
        [sys.executable, "main.py", "--host", "127.0.0.1", "--port", "0", "--jadx", _DEAD_BACKEND],
        cwd=GATEWAY_DIR,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    lines: list[str] = []
    started = threading.Event()
    port_box: list[int] = []

    def drain():
        for line in proc.stdout:
            lines.append(line)
            match = _RUNNING_RE.search(line)
            if match and not port_box:
                port_box.append(int(match.group(1)))
                started.set()
        started.set()

    reader = threading.Thread(target=drain, daemon=True)
    reader.start()
    statuses = {}
    try:
        if not started.wait(60) or not port_box:
            pytest.fail("gateway did not start within 60s:\n" + "".join(lines))
        port = port_box[0]
        with httpx.Client(base_url=f"http://127.0.0.1:{port}", timeout=10) as client:
            for target in (
                f"/transfer/status?token={FAKE_TRANSFER}&foo=bar",
                f"/health?access_token={FAKE_ACCESS}&verbose=1&Signature={FAKE_SIG}",
                "/health?mode=get&key=app_name",
                f"/no-such-route?x=1&api_key={FAKE_ACCESS}",
            ):
                statuses[target] = client.get(target).status_code
        for target in RAW_TARGETS:
            statuses[target] = _raw_get(port, target)
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
        reader.join(timeout=10)
    return "".join(lines), statuses


def _access_lines(out: str) -> list[str]:
    return [line for line in out.splitlines() if '" ' in line and "HTTP/1.1" in line]


def test_real_uvicorn_access_log_masks_token_query_values(gateway_logs):
    out, statuses = gateway_logs
    lines = "\n".join(_access_lines(out))

    assert statuses[f"/transfer/status?token={FAKE_TRANSFER}&foo=bar"] in (400, 502)
    assert '"GET /transfer/status?token=***&foo=bar HTTP/1.1"' in lines, out
    assert '"GET /health?access_token=***&verbose=1&Signature=*** HTTP/1.1" 200' in lines, out
    assert '"GET /no-such-route?x=1&api_key=*** HTTP/1.1" 404' in lines, out
    assert '"GET /health?mode=get&key=app_name HTTP/1.1" 200' in lines, out

    assert '"GET /health?TOKEN=***&Token=***&a=1 HTTP/1.1" 200' in lines, out
    assert '"GET /health?token[]=***&%74oken=***&b=2 HTTP/1.1" 200' in lines, out
    assert '"GET /health?a=1;token=*** HTTP/1.1" 200' in lines, out
    assert '"GET /transfer/status?a=1#z&token=*** HTTP/1.1"' in lines, out
    assert '"GET /health?token=***&b=1 HTTP/1.1" 200' in lines, out
    assert '"GET /health?token=***&c=1 HTTP/1.1" 200' in lines, out
    assert '"GET /health%3Btoken%3D*** HTTP/1.1" 404' in lines, out
    assert '"GET /transfer/status%3Btoken%3D***?foo=bar HTTP/1.1"' in lines, out
    assert '"GET /health%3Btoken%3D*** HTTP/1.1"' in lines, out

    for secret in (FAKE_TRANSFER, FAKE_ACCESS, FAKE_SIG, FAKE_MCP):
        assert secret not in out, f"{secret} leaked into gateway output:\n{out}"
