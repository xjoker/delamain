"""Security-scan readiness contract against the Java warmup-status protocol."""

import pytest

from src.tools import security_tools


@pytest.mark.asyncio
async def test_security_scan_runs_code_rules_and_returns_finding_when_code_search_is_ready(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params, kwargs))
        if endpoint == "decompile-status":
            # DelamainServer: this endpoint has no cached_percentage field.
            return {"status": "idle", "total_classes": 42, "search_lock": {"locked": False}}
        if endpoint == "warmup-status":
            # WarmupManager#getStatus: the real code-search readiness signal.
            return {"capabilities": {"code_search": "ready"}}
        if endpoint == "search-classes-by-keyword":
            if params["search_term"] == "password":
                return {"classes": [{"class_name": "com.example.Config", "snippet": 'String password = "secret";'}]}
            return {"classes": []}
        raise AssertionError(f"unexpected endpoint: {endpoint}")

    monkeypatch.setattr(security_tools, "get_from_jadx", fake_get_from_jadx)

    result = await security_tools._run_security_scan(scan_type="secrets")

    assert result["status"] == "complete"
    assert result["skipped_rules"] == []
    assert result["scan_coverage"] == {
        "code_search_available": True,
        "code_search_state": "ready",
    }
    assert result["findings"][0]["matches"][0]["matched_term"] == "password"
    assert any(
        endpoint == "search-classes-by-keyword" and params["search_in"] == "code"
        for endpoint, params, _ in calls
    )


@pytest.mark.asyncio
async def test_security_scan_marks_code_coverage_incomplete_but_keeps_metadata_rules_when_warming(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        if endpoint == "decompile-status":
            # DelamainServer: this endpoint has no cached_percentage field.
            return {"status": "idle", "total_classes": 42, "search_lock": {"locked": False}}
        if endpoint == "warmup-status":
            return {"capabilities": {"code_search": "warming"}}
        if endpoint == "search-classes-by-keyword":
            if params["search_in"] == "class" and params["search_term"] == "DES":
                return {"classes": [{"class_name": "com.example.DESHelper"}]}
            return {"classes": []}
        raise AssertionError(f"unexpected endpoint: {endpoint}")

    monkeypatch.setattr(security_tools, "get_from_jadx", fake_get_from_jadx)

    result = await security_tools._run_security_scan(scan_type="crypto")

    assert result["status"] == "incomplete"
    assert result["scan_coverage"] == {
        "code_search_available": False,
        "code_search_state": "warming",
    }
    assert result["skipped_rules"] == [{
        "rule_id": "insecure_crypto",
        "reason": "Code search skipped (capabilities.code_search='warming', not 'ready'). "
                  "Skipped patterns: ECB, AES/ECB",
    }]
    assert result["findings"][0]["matches"][0]["matched_term"] == "DES"


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["error", "exception"])
async def test_security_scan_is_incomplete_when_ready_code_search_fails(monkeypatch, failure):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        if endpoint == "warmup-status":
            return {"capabilities": {"code_search": "ready"}}
        if endpoint == "search-classes-by-keyword":
            if params["search_in"] != "code":
                return {"classes": []}
            if failure == "exception":
                raise RuntimeError("backend unavailable")
            return {"error": "TIMEOUT", "message": "backend unavailable"}
        raise AssertionError(f"unexpected endpoint: {endpoint}")

    monkeypatch.setattr(security_tools, "get_from_jadx", fake_get_from_jadx)

    result = await security_tools._run_security_scan(scan_type="secrets")

    assert result["status"] == "incomplete"
    assert result["summary"]["total_findings"] == 0
    assert result["skipped_rules"] == [{
        "rule_id": "hardcoded_secrets",
        "reason": f"Code search failed ({failure}). Skipped patterns: password, secret, api_key, apikey, Bearer , -----BEGIN",
    }, {
        "rule_id": "data_leakage",
        "reason": f"Code search failed ({failure}). Skipped patterns: Log.d, Log.v, MODE_WORLD_READABLE, MODE_WORLD_WRITEABLE",
    }]
