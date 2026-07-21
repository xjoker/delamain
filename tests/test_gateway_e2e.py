"""
E2E integration tests through the Python MCP gateway.
Tests the full chain: MCP tool call → gateway routing → Java instance → response.
"""
import pytest
from .conftest import SIMPLE_CLASS, MAIN_CLASS, TEST_METHOD, mcp_call  # noqa: F401


# ── MCP Protocol ─────────────────────────────────────────────────────────────

@pytest.fixture(scope="module", autouse=True)
def ensure_java_idle(wait_java_idle):
    """Ensure Java server is idle before running gateway E2E tests."""


class TestMCPProtocol:
    def test_initialize(self, gateway_client):
        import json
        resp = gateway_client.post("/mcp", json={
            "jsonrpc": "2.0",
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "pytest", "version": "1.0"},
            },
            "id": 1,
        })
        assert resp.status_code == 200
        for line in resp.text.splitlines():
            if line.startswith("data: "):
                data = json.loads(line[6:])
                result = data.get("result", {})
                assert result.get("serverInfo", {}).get("name")
                instructions = result.get("instructions", "")
                assert "DECISION GUIDE" in instructions
                assert "CODE SEARCH HARD LIMITS" in instructions
                return
        pytest.fail("No SSE data in initialize response")

    def test_tools_list_count(self, gateway_client):
        import json
        resp = gateway_client.post("/mcp", json={
            "jsonrpc": "2.0", "method": "tools/list", "params": {}, "id": 2
        })
        assert resp.status_code == 200
        for line in resp.text.splitlines():
            if line.startswith("data: "):
                data = json.loads(line[6:])
                tools = data.get("result", {}).get("tools", [])
                assert len(tools) >= 50, f"Expected 50+ tools, got {len(tools)}"
                names = {t["name"] for t in tools}
                # Spot-check critical tools exist
                for required in ("get_decompile_status", "get_class_source",
                                 "search_classes_by_keyword", "get_xrefs",
                                 "generate_frida_hook", "get_jadx_guide"):
                    assert required in names, f"Missing tool: {required}"
                return
        pytest.fail("No SSE data in tools/list response")

    def test_gateway_health(self, gateway_client):
        resp = gateway_client.get("/health")
        assert resp.status_code == 200
        assert resp.json()["status"] == "healthy"
        assert resp.json()["instances"] >= 1


# ── Tool Call Chain: Pre-flight ───────────────────────────────────────────────

class TestPreflight:
    def test_get_decompile_status(self, gateway_client):
        d = mcp_call(gateway_client, "get_decompile_status", {})
        assert "memory" in d
        assert "usage_percentage" in d["memory"]
        assert not d.get("error")

    def test_get_index_stats(self, gateway_client):
        d = mcp_call(gateway_client, "get_index_stats", {})
        assert "trigram_index" in d
        assert "indexed_classes" in d["trigram_index"]

    def test_get_file_info(self, gateway_client):
        d = mcp_call(gateway_client, "get_file_info", {})
        assert not d.get("error")


# ── Tool Call Chain: Class Analysis ──────────────────────────────────────────

class TestClassAnalysis:
    def test_get_class_source(self, gateway_client):
        d = mcp_call(gateway_client, "get_class_source", {"class_name": SIMPLE_CLASS})
        assert not d.get("error")
        source = d.get("response", d.get("source", ""))
        assert len(source) > 10

    def test_get_class_info(self, gateway_client):
        d = mcp_call(gateway_client, "get_class_info", {"class_name": SIMPLE_CLASS})
        assert not d.get("error")

    def test_get_methods_of_class(self, gateway_client):
        d = mcp_call(gateway_client, "get_methods_of_class", {"class_name": SIMPLE_CLASS})
        assert not d.get("error")
        assert d.get("methods") is not None or d.get("count") is not None

    def test_get_all_classes_pagination(self, gateway_client):
        d = mcp_call(gateway_client, "get_all_classes", {"offset": 0, "count": 5})
        assert not d.get("error")
        assert d.get("pagination", {}).get("total", 0) > 1000

    def test_list_packages(self, gateway_client):
        d = mcp_call(gateway_client, "list_packages", {})
        assert not d.get("error")
        assert d.get("total_packages", 0) > 0


# ── Tool Call Chain: Search ───────────────────────────────────────────────────

class TestSearch:
    def test_search_metadata(self, gateway_client):
        d = mcp_call(gateway_client, "search_classes_by_keyword", {
            "search_term": "Activity",
            "search_in": "class",
            "count": 5
        })
        assert not d.get("error")

    def test_search_string_literals(self, gateway_client):
        d = mcp_call(gateway_client, "search_string_literals", {
            "pattern": "https", "limit": 5
        })
        assert not d.get("error")

    def test_search_native_methods(self, gateway_client):
        d = mcp_call(gateway_client, "search_native_methods", {"count": 5})
        assert not d.get("error")


# ── Tool Call Chain: Xrefs ────────────────────────────────────────────────────

class TestXrefs:
    def test_get_xrefs_class(self, gateway_client):
        d = mcp_call(gateway_client, "get_xrefs", {
            "target_type": "class", "class_name": SIMPLE_CLASS, "count": 5
        })
        assert not d.get("error")

    def test_get_xrefs_method(self, gateway_client):
        d = mcp_call(gateway_client, "get_xrefs", {
            "target_type": "method",
            "class_name": MAIN_CLASS,
            "member_name": TEST_METHOD,
            "count": 5,
        })
        assert not d.get("error")


# ── Tool Call Chain: Frida ────────────────────────────────────────────────────

class TestFrida:
    def test_generate_frida_hook(self, gateway_client):
        d = mcp_call(gateway_client, "generate_frida_hook", {
            "class_name": MAIN_CLASS, "method_name": TEST_METHOD
        })
        assert not d.get("error")
        script = d.get("script", d.get("frida_script", d.get("hook", "")))
        assert len(script) > 50

    def test_generate_frida_trace(self, gateway_client):
        d = mcp_call(gateway_client, "generate_frida_trace", {"class_name": SIMPLE_CLASS})
        assert not d.get("error")

    def test_generate_frida_enum(self, gateway_client):
        d = mcp_call(gateway_client, "generate_frida_enum", {"class_name": SIMPLE_CLASS})
        assert not d.get("error")


# ── Tool Call Chain: get_jadx_guide ──────────────────────────────────────────

class TestJadxGuide:
    def test_default_guide(self, gateway_client):
        d = mcp_call(gateway_client, "get_jadx_guide", {})
        assert not d.get("error")
        assert len(d.get("guide", "")) > 100
        assert d.get("hint")

    def test_verbose_guide(self, gateway_client):
        d = mcp_call(gateway_client, "get_jadx_guide", {"verbose": True})
        assert not d.get("error")
        guide = d.get("guide", "")
        assert len(guide) > 500
        assert "Pre-Flight" in guide or "DECISION" in guide

    def test_install_skills_claude(self, gateway_client):
        d = mcp_call(gateway_client, "get_jadx_guide", {
            "install_skills": True, "target": "claude"
        })
        assert not d.get("error")
        files = d.get("files_to_write", [])
        assert len(files) == 1
        assert "claude/commands" in files[0]["path"]
        assert len(files[0]["content"]) > 100
        # Must require user authorization before writing
        assert "MUST" in d.get("instruction", "") or "confirm" in d.get("instruction", "").lower()

    def test_install_skills_requires_target(self, gateway_client):
        d = mcp_call(gateway_client, "get_jadx_guide", {"install_skills": True})
        # Should fail gracefully without target
        assert d.get("error") or not d.get("files_to_write")
