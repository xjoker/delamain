"""帮助度功能批（4 项，加法式）回归测试。

离线测试（不需要真实 Java 实例），风格仿 test_smali_degradation_signal.py /
test_wave3_xref_ticket_and_decompile_mode.py。

1. 空源分类: get_class_source 把 Java 端 source_status_hint 转成 _ai_instruction。
2. 按方法名取 smali: get_smali_of_method 新工具，映射到 /smali-of-method。
3. 高层工具可发现性: get_class_source 在 referrer_count > 5 时附加 _ai_instruction。
4. get_native_surface: 新工具，映射到 /native-surface。
"""

import pytest

from src.tools import class_tools, search_tools, decompile_tools, analysis_surface_tools


# ---------------------------------------------------------------------------
# Registration
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_new_tools_are_registered():
    from src.mcp_server import build_mcp_app

    tools = await build_mcp_app().list_tools()
    tool_names = {tool.name for tool in tools}

    assert "get_smali_of_method" in tool_names
    assert "get_native_surface" in tool_names


# ---------------------------------------------------------------------------
# Item 1: empty-source classification -> _ai_instruction
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_get_class_source_surfaces_source_status_hint_as_ai_instruction(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        return {
            "response": "",
            "source_status": "inner_class_inlined",
            "source_status_hint": "call get_class_source on the outer class instead",
        }

    monkeypatch.setattr(class_tools, "get_from_jadx", fake_get_from_jadx)

    result = await class_tools.get_class_source("com.example.Foo$1")
    assert result["_ai_instruction"] == "call get_class_source on the outer class instead"


@pytest.mark.asyncio
async def test_get_class_source_no_ai_instruction_when_status_has_no_hint(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        return {"response": "", "source_status": "unknown_empty"}

    monkeypatch.setattr(class_tools, "get_from_jadx", fake_get_from_jadx)

    result = await class_tools.get_class_source("com.example.Foo")
    assert "_ai_instruction" not in result


@pytest.mark.asyncio
async def test_get_class_source_no_ai_instruction_on_continuation_chunk(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append(params)
        return {
            "response": "",
            "source_status": "inner_class_inlined",
            "source_status_hint": "should not appear on chunk>0",
        }

    monkeypatch.setattr(class_tools, "get_from_jadx", fake_get_from_jadx)

    result = await class_tools.get_class_source("com.example.Foo$1", chunk=2)
    assert "_ai_instruction" not in result
    assert calls[-1]["chunk"] == "2"


# ---------------------------------------------------------------------------
# Item 3: referrer_count -> _ai_instruction pointing at find_callers_chain/export_callgraph
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_get_class_source_suggests_callgraph_tools_for_high_fanin_class(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        return {"response": "class Foo {}", "referrer_count": 42}

    monkeypatch.setattr(class_tools, "get_from_jadx", fake_get_from_jadx)

    result = await class_tools.get_class_source("com.example.Foo")
    assert "_ai_instruction" in result
    assert "find_callers_chain" in result["_ai_instruction"]
    assert "export_callgraph" in result["_ai_instruction"]


@pytest.mark.asyncio
async def test_get_class_source_no_callgraph_hint_for_low_fanin_class(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        return {"response": "class Foo {}", "referrer_count": 3}

    monkeypatch.setattr(class_tools, "get_from_jadx", fake_get_from_jadx)

    result = await class_tools.get_class_source("com.example.Foo")
    assert "_ai_instruction" not in result


@pytest.mark.asyncio
async def test_get_class_source_no_referrer_count_field_when_index_not_ready(monkeypatch):
    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        return {"response": "class Foo {}"}  # Java omits referrer_count entirely

    monkeypatch.setattr(class_tools, "get_from_jadx", fake_get_from_jadx)

    result = await class_tools.get_class_source("com.example.Foo")
    assert "referrer_count" not in result
    assert "_ai_instruction" not in result


# ---------------------------------------------------------------------------
# Item 2: get_smali_of_method
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_get_smali_of_method_maps_params(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        return {"smali": ".method public a()V\n.end method", "descriptor": "()V"}

    monkeypatch.setattr(search_tools, "get_from_jadx", fake_get_from_jadx)

    await search_tools.get_smali_of_method(
        "com.example.Foo", "onCreate", method_signature="(Landroid/os/Bundle;)V", instance_id="inst-1",
    )
    endpoint, params = calls[-1]
    assert endpoint == "smali-of-method"
    assert params == {
        "class_name": "com.example.Foo",
        "method_name": "onCreate",
        "method_signature": "(Landroid/os/Bundle;)V",
    }


@pytest.mark.asyncio
async def test_get_smali_of_method_omits_signature_when_not_given(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        return {"matches": []}

    monkeypatch.setattr(search_tools, "get_from_jadx", fake_get_from_jadx)

    await search_tools.get_smali_of_method("com.example.Foo", "onCreate")
    endpoint, params = calls[-1]
    assert endpoint == "smali-of-method"
    assert params == {"class_name": "com.example.Foo", "method_name": "onCreate"}


# ---------------------------------------------------------------------------
# Item 4: get_native_surface
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_get_native_surface_maps_to_endpoint(monkeypatch):
    calls = []

    async def fake_get_from_jadx(endpoint, params=None, *args, **kwargs):
        calls.append((endpoint, params))
        return {
            "native_methods": [{"class_name": "Foo", "method_name": "init", "jni_name_candidate": "Java_Foo_init"}],
            "native_method_count": 1,
            "loaded_libraries": [{"name": "foo", "found_in_class": "Foo"}],
            "loaded_library_count": 1,
        }

    # get_native_surface may live in class_tools or its own module; patch wherever it resolves.
    module = None
    for mod in (class_tools, search_tools, decompile_tools, analysis_surface_tools):
        if hasattr(mod, "get_native_surface"):
            module = mod
            break
    assert module is not None, "get_native_surface must be defined in one of the tools modules"
    monkeypatch.setattr(module, "get_from_jadx", fake_get_from_jadx)

    result = await module.get_native_surface(instance_id="inst-1")
    endpoint, params = calls[-1]
    assert endpoint == "native-surface"
    assert result["native_method_count"] == 1
