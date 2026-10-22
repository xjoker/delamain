"""回归：all-classes 的结构化分页响应必须可用于反编译优先级排序。"""

import pytest

from src.tools import decompile_tools


@pytest.mark.asyncio
async def test_priority_list_uses_alias_names_from_paginated_class_entries(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        if endpoint == "all-classes":
            return {
                "classes": [
                    {"name": "com.example.MainActivity", "raw_name": "a.b"},
                    {"name": "com.google.analytics.Tracker", "raw_name": "c.d"},
                ],
                "pagination": {
                    "total": 2,
                    "offset": 0,
                    "limit": 2,
                    "count": 2,
                    "has_more": False,
                },
            }
        assert endpoint == "decompile-status"
        return {"processed_classes": 1}

    monkeypatch.setattr(decompile_tools, "get_from_jadx", fake_get_from_jadx)

    result = await decompile_tools.get_decompile_priority_list(package="com.example")

    assert calls[0] == ("all-classes", {"offset": 0, "count": 0})
    assert result["total_classes"] == 2
    assert result["already_cached"] == 1
    assert result["priority_list"] == [
        {
            "class_name": "com.example.MainActivity",
            "priority": "high",
            "reason": "Android component (Activity)",
        },
        {
            "class_name": "com.google.analytics.Tracker",
            "priority": "low",
            "reason": "Third-party SDK (com.google)",
        },
    ]


@pytest.mark.asyncio
async def test_priority_list_rejects_malformed_class_entries(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        if endpoint == "all-classes":
            return {
                "classes": [{"raw_name": "a.b"}],
                "pagination": {
                    "total": 1,
                    "offset": 0,
                    "limit": 1,
                    "count": 1,
                    "has_more": False,
                },
            }
        assert endpoint == "decompile-status"
        return {"processed_classes": 0}

    monkeypatch.setattr(decompile_tools, "get_from_jadx", fake_get_from_jadx)

    result = await decompile_tools.get_decompile_priority_list()

    assert result["error"] == "INVALID_RESPONSE"
    assert "classes[0].name" in result["message"]


@pytest.mark.asyncio
async def test_priority_list_follows_next_offset_until_all_pages_are_collected(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        if endpoint == "decompile-status":
            return {"processed_classes": 0}
        assert endpoint == "all-classes"
        if params["offset"] == 0:
            return {
                "classes": [
                    {"name": "com.example.First", "raw_name": "a.a"},
                    {"name": "com.example.Second", "raw_name": "a.b"},
                ],
                "pagination": {
                    "total": 4,
                    "offset": 0,
                    "limit": 2,
                    "count": 2,
                    "has_more": True,
                    "next_offset": 2,
                },
            }
        assert params["offset"] == 2
        return {
            "classes": [
                {"name": "com.example.Third", "raw_name": "a.c"},
                {"name": "com.example.Fourth", "raw_name": "a.d"},
            ],
            "pagination": {
                "total": 4,
                "offset": 2,
                "limit": 2,
                "count": 2,
                "has_more": False,
            },
        }

    monkeypatch.setattr(decompile_tools, "get_from_jadx", fake_get_from_jadx)

    result = await decompile_tools.get_decompile_priority_list(package="com.example")

    assert calls[:2] == [
        ("all-classes", {"offset": 0, "count": 0}),
        ("all-classes", {"offset": 2, "count": 0}),
    ]
    assert result["total_classes"] == 4
    assert [entry["class_name"] for entry in result["priority_list"]] == [
        "com.example.First",
        "com.example.Fourth",
        "com.example.Second",
        "com.example.Third",
    ]


@pytest.mark.asyncio
async def test_priority_list_rejects_pagination_without_next_offset(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        if endpoint == "all-classes":
            return {
                "classes": [{"name": "com.example.First", "raw_name": "a.a"}],
                "pagination": {
                    "total": 2,
                    "offset": 0,
                    "limit": 1,
                    "count": 1,
                    "has_more": True,
                },
            }
        assert endpoint == "decompile-status"
        return {"processed_classes": 0}

    monkeypatch.setattr(decompile_tools, "get_from_jadx", fake_get_from_jadx)

    result = await decompile_tools.get_decompile_priority_list()

    assert result["error"] == "INVALID_RESPONSE"
    assert "pagination.next_offset" in result["message"]
