"""缺口 A：batch_get_class_source 类粒度分页（gateway 侧）。

离线测试（不需要真实 Java 实例），风格仿 test_helpfulness_batch.py。

覆盖：
1. Java 返回 has_more=true 时，_ai_instruction 提示用 next_offset 续取。
2. offset>0 时按 offset 直接透传给 Java（取指定页）。
3. 死代码分支 response_too_large / _chunking 已移除，不再触发（新分页字段透传即可，不额外处理）。
"""

import pytest

from src.tools import class_tools


@pytest.mark.asyncio
async def test_has_more_sets_ai_instruction_with_next_offset(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        return {
            "status": "success",
            "classes": [{"name": "com.example.A", "found": True, "content": "class A {}"}],
            "total": 5,
            "returned": 1,
            "offset": 0,
            "found": 1,
            "has_more": True,
            "next_offset": 1,
        }

    monkeypatch.setattr(class_tools, "get_from_jadx", fake_get_from_jadx)

    result = await class_tools.batch_get_class_source(
        class_names=["com.example.A", "com.example.B", "com.example.C", "com.example.D", "com.example.E"],
        force=True,
    )

    assert "_ai_instruction" in result
    assert "offset=1" in result["_ai_instruction"]
    assert "batch_get_class_source" in result["_ai_instruction"]


@pytest.mark.asyncio
async def test_no_ai_instruction_when_has_more_false(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        return {
            "status": "success",
            "classes": [{"name": "com.example.A", "found": True, "content": "class A {}"}],
            "total": 1,
            "returned": 1,
            "offset": 0,
            "found": 1,
            "has_more": False,
        }

    monkeypatch.setattr(class_tools, "get_from_jadx", fake_get_from_jadx)

    result = await class_tools.batch_get_class_source(class_names=["com.example.A"], force=True)
    assert "_ai_instruction" not in result


@pytest.mark.asyncio
async def test_offset_forwarded_to_java(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        return {
            "status": "success",
            "classes": [{"name": "com.example.C", "found": True, "content": "class C {}"}],
            "total": 5,
            "returned": 1,
            "offset": 2,
            "found": 1,
            "has_more": True,
            "next_offset": 3,
        }

    monkeypatch.setattr(class_tools, "get_from_jadx", fake_get_from_jadx)

    await class_tools.batch_get_class_source(
        class_names=["com.example.A", "com.example.B", "com.example.C", "com.example.D", "com.example.E"],
        offset=2,
        force=True,
    )
    endpoint, params = calls[-1]
    assert endpoint == "batch-class-source"
    assert params["offset"] == "2"


@pytest.mark.asyncio
async def test_response_too_large_and_chunking_dead_branches_removed(monkeypatch):
    """Java 不再返回 response_too_large/_chunking（已被分页取代）；即使残留字段出现，
    gateway 也不应再特判它们（不弹出旧的转移提示/旧的 chunk 续取指令）。"""

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        return {
            "status": "success",
            "classes": [{"name": "com.example.A", "found": True, "content": "class A {}"}],
            "total": 1,
            "returned": 1,
            "offset": 0,
            "found": 1,
            "has_more": False,
            # Legacy fields a stale Java build might still send; gateway must ignore them now.
            "response_too_large": True,
            "_chunking": {"has_more": True, "current_chunk": 1, "total_chunks": 2, "next_chunk": 2},
        }

    monkeypatch.setattr(class_tools, "get_from_jadx", fake_get_from_jadx)

    result = await class_tools.batch_get_class_source(class_names=["com.example.A"], force=True)
    assert "_ai_instruction" not in result
    assert "transfer_endpoint" not in result
