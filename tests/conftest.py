"""
pytest fixtures for delamain integration tests.
Requires:
  - Java instance running on JADX_JAVA_URL (default http://localhost:28650)
  - Python gateway running on GATEWAY_URL (default http://localhost:8651)
  - JADX_AUTH_TOKEN and MCP_AUTH_TOKEN env vars (or defaults below)
"""
import os
import pytest
import httpx

JADX_JAVA_URL = os.getenv("JADX_JAVA_URL", "http://localhost:28650")
GATEWAY_URL = os.getenv("GATEWAY_URL", "http://localhost:8651")
JADX_AUTH_TOKEN = os.getenv("JADX_AUTH_TOKEN", "test-token")
MCP_AUTH_TOKEN = os.getenv("MCP_AUTH_TOKEN", "mcp-test-token")

# Defaults are placeholders — override with real class/method/package names that
# exist in whatever APK is loaded on the Java instance under test.
MAIN_CLASS = os.getenv("TEST_MAIN_CLASS", "com.example.app.MainActivity")
SIMPLE_CLASS = os.getenv("TEST_SIMPLE_CLASS", "com.example.app.Config")
TEST_METHOD = os.getenv("TEST_METHOD", "onCreate")
TEST_PACKAGE = os.getenv("TEST_PACKAGE", "com.example.app")


@pytest.fixture(scope="session")
def java_client():
    headers = {"Authorization": f"Bearer {JADX_AUTH_TOKEN}"}
    with httpx.Client(base_url=JADX_JAVA_URL, headers=headers, timeout=30) as client:
        yield client


@pytest.fixture(scope="session")
def java_url():
    return JADX_JAVA_URL


@pytest.fixture(scope="session")
def mcp_headers():
    return {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {MCP_AUTH_TOKEN}",
    }


@pytest.fixture(scope="session")
def wait_java_idle(java_client):
    """Wait up to 30s for any ongoing code search lock to clear."""
    import time
    for _ in range(15):
        try:
            r = java_client.get("/decompile-status", timeout=5)
            if r.status_code == 200:
                lock = r.json().get("search_lock", {})
                if not lock.get("locked"):
                    return
        except Exception:
            pass
        time.sleep(2)


@pytest.fixture(scope="session")
def gateway_client(mcp_headers):
    with httpx.Client(
        base_url=GATEWAY_URL, headers=mcp_headers, timeout=30
    ) as client:
        yield client


def mcp_call(client, tool_name, arguments):
    """Call an MCP tool and return the parsed result dict."""
    import json
    resp = client.post(
        "/mcp",
        json={
            "jsonrpc": "2.0",
            "method": "tools/call",
            "params": {"name": tool_name, "arguments": arguments},
            "id": 1,
        },
    )
    resp.raise_for_status()
    for line in resp.text.splitlines():
        if line.startswith("data: "):
            data = json.loads(line[6:])
            result = data.get("result", {})
            content = result.get("content", [])
            if content and content[0].get("type") == "text":
                text = content[0]["text"]
                if result.get("isError"):
                    raise RuntimeError(f"Tool '{tool_name}' returned error: {text}")
                return json.loads(text)
            return data.get("result", data)
    raise ValueError(f"No SSE data in response: {resp.text[:200]}")
