"""行为契约测试：workflow_tools 单后端收窄（C1）。

覆盖：
- analyze_apk(strategy="new_instance") 被拒绝为非法枚举
- analyze_apk 合法 strategy（auto/replace/append）行为不变
- list_loaded_files() 响应形状从 {instances:[...]} 平铺到顶层
- 后端未配置时 list_loaded_files() 返回 NO_INSTANCE 错误
"""

import pytest

from src.registry.instance_registry import InstanceRegistry
from src.tools import workflow_tools


@pytest.fixture(autouse=True)
def reset_registry():
    InstanceRegistry.clear_all()
    yield
    InstanceRegistry.clear_all()


# ---------------------------------------------------------------------------
# strategy 枚举收窄
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_new_instance_strategy_is_rejected():
    InstanceRegistry.configure(host="127.0.0.1", port=8650)

    result = await workflow_tools.analyze_apk("target.apk", strategy="new_instance")

    assert result["status"] == "error"
    joined = " ".join(result["next_steps"])
    assert "auto" in joined and "replace" in joined and "append" in joined


@pytest.mark.asyncio
async def test_legal_strategies_still_work(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650)

    async def fake_get_from_jadx(endpoint, *args, **kwargs):
        if endpoint == "load-file":
            return {"status": "ok"}
        if endpoint == "file-info":
            return {"loaded": False}
        return {}

    monkeypatch.setattr(workflow_tools, "get_from_jadx", fake_get_from_jadx)

    result = await workflow_tools.analyze_apk("target.apk", strategy="replace")
    assert result["status"] == "loaded"

    result = await workflow_tools.analyze_apk("target.apk", strategy="append")
    assert result["status"] == "loaded"

    result = await workflow_tools.analyze_apk("target.apk", strategy="auto")
    assert result["status"] == "loaded"


# ---------------------------------------------------------------------------
# list_loaded_files() 响应形状
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_list_loaded_files_returns_flat_shape(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650)

    async def fake_get_from_jadx(endpoint, *args, **kwargs):
        if endpoint == "file-info":
            return {
                "loaded": True,
                "file_name": "target.apk",
                "version_name": "1.0",
                "version_code": 1,
                "apk_identity": {"input_hash": "abc123"},
            }
        if endpoint == "warmup-status":
            return {"percentage": 50}
        return {}

    monkeypatch.setattr(workflow_tools, "get_from_jadx", fake_get_from_jadx)

    result = await workflow_tools.list_loaded_files()

    assert "instances" not in result
    assert "count" not in result
    assert "free_count" not in result
    assert result["loaded_file"] == "target.apk"
    assert result["file_loaded"] is True
    assert result["input_hash"] == "abc123"
    assert result["name"] == "jadx"
    assert result["decompile_progress"] == 0.5


@pytest.mark.asyncio
async def test_list_loaded_files_reports_no_instance_when_unconfigured():
    result = await workflow_tools.list_loaded_files()

    assert result["error"] == "NO_INSTANCE"
    assert "message" in result


# ---------------------------------------------------------------------------
# identity source: /file-info, not the legacy /apk-info shape (split-brain
# fix — /file-info said "loaded" while /apk-info never carried
# file_name/apk_package, so list_loaded_files reported "nothing loaded").
# Migrated from the now-deleted instance-id-validity regression test file.
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_list_loaded_files_reflects_file_info_identity(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx")

    async def fake_get_from_jadx(endpoint, *args, **kwargs):
        if endpoint == "file-info":
            return {
                "type": "file-info",
                "loaded": True,
                "file_name": "9290803.apk",
                "apk_package": "com.example.target",
                "version_name": "3.2.1",
                "version_code": 321,
                "class_count": 4200,
                "status": "success",
            }
        if endpoint == "warmup-status":
            return {"percentage": 42}
        return {}

    monkeypatch.setattr(workflow_tools, "get_from_jadx", fake_get_from_jadx)

    result = await workflow_tools.list_loaded_files()

    assert result["loaded_file"] == "9290803.apk"
    assert result["file_loaded"] is True
    assert result["version_name"] == "3.2.1"
    assert result["version_code"] == 321


@pytest.mark.asyncio
async def test_list_loaded_files_reports_no_file_loaded_when_nothing_loaded(monkeypatch):
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx")

    async def fake_get_from_jadx(endpoint, *args, **kwargs):
        if endpoint == "file-info":
            return {"type": "file-info", "loaded": False, "error": "No file loaded"}
        if endpoint == "warmup-status":
            return {"percentage": 0}
        return {}

    monkeypatch.setattr(workflow_tools, "get_from_jadx", fake_get_from_jadx)

    result = await workflow_tools.list_loaded_files()

    assert result["loaded_file"] is None
    assert result["file_loaded"] is False
