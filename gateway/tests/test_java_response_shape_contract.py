"""行为契约测试：gateway 对 Java 后端响应字段的真实形状（F1 审计修复）。

这些 mock 反映 Java 侧真实返回（已核实的源码位置见各测试注释），不是训练数据里的假设：
- /warmup-status: com.zin.delamain.index.WarmupManager#getStatus (percentage/processed/total,
  capabilities.code_search)
- /decompile-status: com.zin.delamain.server.DelamainServer 约 376-413 行（无 cached_percentage /
  processed_classes / percentage）
- /all-classes: com.zin.delamain.server.routes.ClassRoutes#handleAllClasses（classes 为
  [{name, raw_name}] 对象数组），total 嵌套在 pagination 里
  （com.zin.delamain.utils.PaginationUtils#buildPaginationResponse）

覆盖：
1. run_security_scan 在 capabilities.code_search == "ready" 时不跳过 search_in="code" 规则；
   未 ready 时跳过且给出真实依据（不是编造的 cached_percentage）
2. get_decompile_priority_list 在 classes 为 [{name, raw_name}] 时正常返回而非 AttributeError
3. smart_decompile 的 decompile_status_after 取自 /warmup-status 的 percentage（非恒 0）；
   list_loaded_files 的 decompile_progress 同样来自 /warmup-status（非恒 0）
4. generate_apk_digest 的 total_classes 取自 pagination.total，而非顶层不存在的 "total"
"""

import pytest

from src.registry.instance_registry import InstanceRegistry
from src.tools import security_tools, decompile_tools, digest_tools, workflow_tools


@pytest.fixture(autouse=True)
def reset_registry():
    InstanceRegistry.clear_all()
    yield
    InstanceRegistry.clear_all()


# ---------------------------------------------------------------------------
# (1) run_security_scan: code_search readiness from /warmup-status capabilities
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_security_scan_runs_code_rules_when_code_search_ready(monkeypatch):
    async def fake_get_from_jadx(endpoint, *args, **kwargs):
        if endpoint == "warmup-status":
            return {"capabilities": {"code_search": "ready"}}
        if endpoint == "search-classes-by-keyword":
            return {"classes": []}
        return {}

    monkeypatch.setattr(security_tools, "get_from_jadx", fake_get_from_jadx)

    result = await security_tools._run_security_scan(scan_type="secrets")

    # hardcoded_secrets rule is entirely search_in="code" — it must NOT be skipped.
    assert result["skipped_rules"] == []
    assert result["scan_coverage"]["code_search_available"] is True
    assert result["scan_coverage"]["code_search_state"] == "ready"


@pytest.mark.asyncio
async def test_security_scan_skips_code_rules_with_real_reason_when_not_ready(monkeypatch):
    async def fake_get_from_jadx(endpoint, *args, **kwargs):
        if endpoint == "warmup-status":
            return {"capabilities": {"code_search": "warming"}}
        if endpoint == "search-classes-by-keyword":
            return {"classes": []}
        return {}

    monkeypatch.setattr(security_tools, "get_from_jadx", fake_get_from_jadx)

    result = await security_tools._run_security_scan(scan_type="secrets")

    # scan_type="secrets" matches two rules that share the "secrets" category
    # (hardcoded_secrets, data_leakage) — both are entirely search_in="code".
    assert len(result["skipped_rules"]) == 2
    reason = result["skipped_rules"][0]["reason"]
    assert "cached_percentage" not in reason
    assert "code_search" in reason and "warming" in reason
    assert result["scan_coverage"]["code_search_available"] is False
    assert result["scan_coverage"]["code_search_state"] == "warming"


# ---------------------------------------------------------------------------
# (2) get_decompile_priority_list: classes are [{name, raw_name}] objects
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_get_decompile_priority_list_handles_object_shaped_classes(monkeypatch):
    async def fake_get_from_jadx(endpoint, *args, **kwargs):
        if endpoint == "all-classes":
            return {
                "classes": [
                    {"name": "com.example.MainActivity", "raw_name": "com.example.a"},
                    {"name": "kotlin.jvm.internal.Intrinsics", "raw_name": "kotlin.jvm.internal.Intrinsics"},
                ],
                "pagination": {"total": 2},
            }
        if endpoint == "warmup-status":
            return {"processed": 1}
        return {}

    monkeypatch.setattr(decompile_tools, "get_from_jadx", fake_get_from_jadx)

    result = await decompile_tools.get_decompile_priority_list()

    assert "error" not in result
    assert result["total_classes"] == 2
    assert result["already_cached"] == 1
    names = {c["class_name"] for c in result["priority_list"]}
    assert "com.example.MainActivity" in names


# ---------------------------------------------------------------------------
# (3) progress numbers come from /warmup-status, not the non-existent fields on
#     /decompile-status
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_smart_decompile_reports_nonzero_percentage_from_warmup_status(monkeypatch):
    async def fake_get_from_jadx(endpoint, *args, **kwargs):
        if endpoint == "class-source":
            return {"response": "class Foo {}"}
        if endpoint == "warmup-status":
            return {"percentage": 37}
        return {}

    monkeypatch.setattr(decompile_tools, "get_from_jadx", fake_get_from_jadx)

    result = await decompile_tools.smart_decompile(["com.example.Foo"])

    assert result["decompile_status_after"]["percentage"] == 37
    assert any("37%" in tip for tip in result["optimization_tips"])


@pytest.mark.asyncio
async def test_list_loaded_files_progress_is_nonzero_from_warmup_status(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx")

    async def fake_get_from_jadx(endpoint, *args, **kwargs):
        if endpoint == "file-info":
            return {"loaded": True, "file_name": "target.apk"}
        if endpoint == "warmup-status":
            return {"percentage": 80}
        return {}

    monkeypatch.setattr(workflow_tools, "get_from_jadx", fake_get_from_jadx)

    result = await workflow_tools.list_loaded_files()

    assert result["decompile_progress"] == 0.8


# ---------------------------------------------------------------------------
# (4) generate_apk_digest: total_classes from pagination.total
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_generate_apk_digest_total_classes_from_pagination(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        if endpoint == "file-info":
            return {"file_type": "apk", "file_size": 1024}
        if endpoint == "manifest":
            return "<manifest package=\"com.example\"></manifest>"
        if endpoint == "all-classes":
            # top level has no "total" — only pagination.total, per PaginationUtils.java
            return {
                "classes": [{"name": "com.example.MainActivity", "raw_name": "com.example.a"}],
                "pagination": {"total": 500},
            }
        if endpoint == "decompile-status":
            return {"total_classes": 500}
        return {}

    monkeypatch.setattr(digest_tools, "get_from_jadx", fake_get_from_jadx)

    result = await digest_tools._generate_apk_digest()

    assert result["class_stats"]["total_classes"] == 500
