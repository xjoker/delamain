"""行为契约测试：list_loaded_files 报告的「忙」必须真的是忙。

历史缺陷：字段名叫 available，docstring 承诺 "whether it is free"，实际算的是
`_instance_has_no_file()` —— 即「后端没加载任何文件」。APK 一旦加载完成它就恒为
false，与忙闲无关。这条不是假想：2026-08-05 一次真实的并发架构分析据此推导出
「多 Executor 会排队阻塞」的结论，排队是真的，但读出排队的那个信号是假的。

而且 available 从来不提供新信息——它就是 `loaded_file is None` 的镜像。

现在拆成两个各自说真话的字段：
  file_loaded — 后端是否已加载文件（原 available 的取反，语义写进名字）
  busy        — 是否有正在执行的操作（取自 BusyTracker 的 lane inflight 计数）
"""

import pytest

from src.busy_tracker import InstanceBusyTracker
from src.registry.instance_registry import InstanceRegistry
from src.tools import workflow_tools


@pytest.fixture(autouse=True)
def reset_state():
    InstanceRegistry.clear_all()
    InstanceBusyTracker.force_release_all()
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx")
    yield
    InstanceBusyTracker.force_release_all()
    InstanceRegistry.clear_all()


def _backend(monkeypatch, file_info: dict, percentage: float = 100.0):
    async def fake_get_from_jadx(endpoint, *args, **kwargs):
        if endpoint == "file-info":
            return file_info
        if endpoint == "warmup-status":
            return {"percentage": percentage}
        return {}

    monkeypatch.setattr(workflow_tools, "get_from_jadx", fake_get_from_jadx)


LOADED = {"loaded": True, "file_name": "target.apk", "version_name": "9.28.1"}
EMPTY: dict = {}


@pytest.mark.asyncio
async def test_loaded_backend_is_not_reported_as_unavailable(monkeypatch):
    """加载完 APK 的空闲后端，必须报告为「已加载且不忙」。

    这正是旧 available 字段答错的那个场景：它会说 false，调用方读成「忙」。
    """
    _backend(monkeypatch, LOADED)

    result = await workflow_tools.list_loaded_files()

    assert result["file_loaded"] is True
    assert result["busy"] is False
    assert result["loaded_file"] == "target.apk"


@pytest.mark.asyncio
async def test_empty_backend_reports_no_file_loaded(monkeypatch):
    _backend(monkeypatch, EMPTY)

    result = await workflow_tools.list_loaded_files()

    assert result["file_loaded"] is False
    assert result["loaded_file"] is None
    assert result["busy"] is False


@pytest.mark.asyncio
async def test_busy_reflects_an_actually_running_operation(monkeypatch):
    """有 in-flight 操作时 busy=True —— 这是旧字段完全没有的信号。"""
    _backend(monkeypatch, LOADED)

    acquired = await InstanceBusyTracker.try_acquire("get_class_source")
    assert "error" not in acquired, f"测试自身前提失败，没能占住车道: {acquired}"
    try:
        result = await workflow_tools.list_loaded_files()
        assert result["busy"] is True
    finally:
        await InstanceBusyTracker.release(acquired["token"])

    after = await workflow_tools.list_loaded_files()
    assert after["busy"] is False, "操作释放后 busy 必须回落"


@pytest.mark.asyncio
async def test_misleading_available_field_is_gone(monkeypatch):
    """available 必须不再出现——保留它等于把陷阱永久留在公开响应里。

    它既不提供 loaded_file 之外的信息，名字又与实际含义相反。
    """
    _backend(monkeypatch, LOADED)

    result = await workflow_tools.list_loaded_files()

    assert "available" not in result


@pytest.mark.asyncio
async def test_docstring_describes_every_field_the_tool_returns(monkeypatch):
    """docstring 的 Returns 段必须列全真实返回的字段——这条缺陷的根源就是两者脱节。

    只看 Returns 段，不看整个 docstring：字段名（busy、file_loaded 这种常见词）很容易
    在前面的散文里出现，整篇子串检查会因此放行一个 Returns 段其实漏讲了该字段的
    docstring。这不是假设——本测试的第一版就是那样写的，sabotage 时没能变红。
    """
    _backend(monkeypatch, LOADED)
    result = await workflow_tools.list_loaded_files()

    collected = {}

    class _Collector:
        def tool(self, *a, **kw):
            def deco(fn):
                collected[fn.__name__] = fn
                return fn
            return deco

    workflow_tools.register_workflow_tools(_Collector())
    doc = collected["list_loaded_files_tool"].__doc__ or ""
    assert "Returns:" in doc, "docstring 没有 Returns 段，无法校验字段"
    returns_section = doc.split("Returns:", 1)[1]

    missing = [key for key in result if key not in returns_section]
    assert not missing, f"docstring 的 Returns 段没有列出这些真实返回的字段: {missing}"
