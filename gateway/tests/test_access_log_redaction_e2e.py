"""SAF-90 end-to-end: start the real gateway (main.py -> FastMCP -> uvicorn, the
same code path docker/entrypoint.sh uses) as a subprocess, send requests with
sensitive query parameters, and check uvicorn's access log on the process's
stdout/stderr: sensitive values masked, path and all other parameters intact.

Deliberately does not import src.log_redaction, so on a tree without the fix it
fails on the log assertions rather than on an import error. Only fake tokens.
"""

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


# The gateway binds port 0 (the kernel picks a free one: no bind-then-release race)
# and the real port is read from uvicorn's startup line.
_RUNNING_RE = re.compile(r"Uvicorn running on http://127\.0\.0\.1:(\d+)")
# Port 1 on loopback: nothing listens there, backend calls fail fast with 502.
_DEAD_BACKEND = "127.0.0.1:1"


# L5 (Elliot, #19 review): forms the 400 ?token= check does not catch, which
# must still never reach the log in clear text.
RAW_TARGETS = (
    f"/health?TOKEN={FAKE_TRANSFER}&Token=b-{FAKE_TRANSFER}&a=1",
    f"/health?token[]={FAKE_TRANSFER}&%74oken=d-{FAKE_TRANSFER}&b=2",
    f"/health?a=1;token={FAKE_TRANSFER};c=3",
    # M1 (Elliot, #20 review): h11 keeps a raw '#' in the query string and Starlette
    # parses params after it, so it is not a fragment
    f"/transfer/status?a=1#z&token={FAKE_TRANSFER}",
    f"/health?token=A#{FAKE_SIG}&b=1",
    # L1: the whole value is masked (raw ';' in a query value, '/' in a matrix value)
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
    env = {
        **os.environ,
        "DELAMAIN_AUTH_TOKENS": FAKE_MCP,
        "PYTHONUNBUFFERED": "1",
    }
    env.pop("DELAMAIN_AUTH_TOKEN", None)
    env.pop("JADX_TRANSFER_PUBLIC_URL", None)
    proc = subprocess.Popen(
        [sys.executable, "main.py", "--host", "127.0.0.1", "--port", "0", "--jadx", _DEAD_BACKEND],
        cwd=GATEWAY_DIR, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    # Drain the child's output continuously so it can never block on a full pipe.
    lines: list[str] = []
    started = threading.Event()
    port_box: list[int] = []

    def drain():
        for line in proc.stdout:
            lines.append(line)
            m = _RUNNING_RE.search(line)
            if m and not port_box:
                port_box.append(int(m.group(1)))
                started.set()
        started.set()  # EOF: child exited

    reader = threading.Thread(target=drain, daemon=True)
    reader.start()
    statuses = {}
    try:
        if not started.wait(60) or not port_box:
            pytest.fail("gateway did not start within 60s:\n" + "".join(lines))
        port = port_box[0]
        base = f"http://127.0.0.1:{port}"
        with httpx.Client(base_url=base, timeout=10) as client:
            # raw query strings so the exact bytes on the wire are what we assert on
            for target in (
                f"/transfer/status?token={FAKE_TRANSFER}&foo=bar",
                f"/health?access_token={FAKE_ACCESS}&verbose=1&Signature={FAKE_SIG}",
                "/health?mode=get&key=app_name",
                f"/no-such-route?x=1&api_key={FAKE_ACCESS}",
            ):
                statuses[target] = client.get(target).status_code
        # odd forms, sent over a raw socket so no client re-encodes the target
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

    # The request reached uvicorn and was answered (502 on main: the fake backend is
    # down; 400 once ?token= is rejected by the gateway, issue #8) — either way it is
    # access-logged, and the logged line must not carry the secret.
    assert statuses[f"/transfer/status?token={FAKE_TRANSFER}&foo=bar"] in (400, 502)
    assert '"GET /transfer/status?token=***&foo=bar HTTP/1.1"' in lines, out
    assert '"GET /health?access_token=***&verbose=1&Signature=*** HTTP/1.1" 200' in lines, out
    assert '"GET /no-such-route?x=1&api_key=*** HTTP/1.1" 404' in lines, out
    # non-sensitive params, including the backend's non-secret `key`, stay readable
    assert '"GET /health?mode=get&key=app_name HTTP/1.1" 200' in lines, out

    # L5 forms
    assert '"GET /health?TOKEN=***&Token=***&a=1 HTTP/1.1" 200' in lines, out
    assert '"GET /health?token[]=***&%74oken=***&b=2 HTTP/1.1" 200' in lines, out
    # ';' is a name boundary; the value runs to the next '&' like the server reads it
    assert '"GET /health?a=1;token=*** HTTP/1.1" 200' in lines, out
    # M1
    assert '"GET /transfer/status?a=1#z&token=*** HTTP/1.1"' in lines, out
    assert '"GET /health?token=***&b=1 HTTP/1.1" 200' in lines, out
    # L1
    assert '"GET /health?token=***&c=1 HTTP/1.1" 200' in lines, out
    assert '"GET /health%3Btoken%3D*** HTTP/1.1" 404' in lines, out
    # uvicorn re-quotes the path in the access log: ";" -> %3B, "=" -> %3D
    assert '"GET /transfer/status%3Btoken%3D***?foo=bar HTTP/1.1"' in lines, out
    # the server percent-decodes the path first, so %74oken is logged as token
    assert '"GET /health%3Btoken%3D*** HTTP/1.1"' in lines, out

    for secret in (FAKE_TRANSFER, FAKE_ACCESS, FAKE_SIG, FAKE_MCP):
        assert secret not in out, f"{secret} leaked into gateway output:\n{out}"
