"""行为契约测试：每个会改变后端状态的 MCP 工具都必须经过车道调度。

真实缺陷：analyze_apk_tool 只有 @mcp.tool()，没有 @with_busy_check，却会 POST
/load-file 把整个 APK 换掉。exclusive 车道存在的全部意义就是防这件事，而它绕过了。
更糟的是 analyze_apk_tool 是高层入口，比 load_file_tool 更常被 AI 选中——无保护的
那条反而是主路径。

为什么既有的守卫抓不到：validate_lane_coverage() 只扫 _DECORATED_OPERATIONS，
而那个集合是 with_busy_check 在装饰时填的。没被装饰的函数从定义上就进不了检查范围。
"未声明车道"查得出，"根本没进车道系统"查不出。

所以这里换一个不依赖装饰器的判据：直接从源码 AST 找出哪些工具会触达写端点，
再要求它们必须被装饰。
"""

import ast
import pathlib

import pytest

from src.busy_tracker import (
    EXCLUSIVE_OPERATIONS,
    InstanceBusyTracker,
    _DECORATED_OPERATIONS,
)
from src.registry.instance_registry import InstanceRegistry
from src.tools import workflow_tools

_TOOLS_DIR = pathlib.Path(__file__).resolve().parent.parent / "src" / "tools"

# 会改变后端状态的端点。命中这些端点的工具必须走 exclusive 车道。
_WRITE_ENDPOINTS = frozenset({
    "load-file", "rename-class", "rename-method", "rename-field", "rename-package",
    "rename-variable", "import-rename-mappings", "apply-proguard-mapping",
    "annotations", "bookmarks", "tags",
})


def _writes_to_backend(node: ast.AST) -> bool:
    """函数体里是否直接对写端点发起了非 GET 请求。"""
    for sub in ast.walk(node):
        if not isinstance(sub, ast.Call):
            continue
        fn = sub.func
        name = fn.attr if isinstance(fn, ast.Attribute) else getattr(fn, "id", None)
        if name != "get_from_jadx":
            continue
        method = next(
            (kw.value.value for kw in sub.keywords
             if kw.arg == "method" and isinstance(kw.value, ast.Constant)),
            "GET",
        )
        if method.upper() == "GET":
            continue
        endpoint = sub.args[0].value if sub.args and isinstance(sub.args[0], ast.Constant) else None
        if endpoint in _WRITE_ENDPOINTS:
            return True
    return False


def _calls(node: ast.AST) -> set:
    out = set()
    for sub in ast.walk(node):
        if isinstance(sub, ast.Call):
            fn = sub.func
            n = fn.attr if isinstance(fn, ast.Attribute) else getattr(fn, "id", None)
            if n:
                out.add(n)
    return out


def _registered_tools_that_write() -> dict:
    """{注册名: 是否被 with_busy_check 装饰}，只含会触达写端点的工具。

    传递闭包迭代到不动点，不是一跳：真实调用链可以更深
    （analyze_apk_tool → analyze_apk → _load_file_on_instance）。一跳版本会把
    这条链判成"不写"，让整个断言退化成空集恒绿。
    """
    result = {}
    for path in sorted(_TOOLS_DIR.glob("*.py")):
        tree = ast.parse(path.read_text())
        funcs = [n for n in ast.walk(tree)
                 if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))]
        writers = {n.name for n in funcs if _writes_to_backend(n)}
        # 不动点：只要还有函数调用了已知的写函数，它自己也是写函数
        changed = True
        while changed:
            changed = False
            for n in funcs:
                if n.name not in writers and (_calls(n) & writers):
                    writers.add(n.name)
                    changed = True
        for node in ast.walk(tree):
            if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            decorators = {
                d.func.attr if isinstance(d, ast.Call) and isinstance(d.func, ast.Attribute)
                else d.attr if isinstance(d, ast.Attribute)
                else getattr(d, "id", "")
                for d in node.decorator_list
            }
            if "tool" not in decorators:  # 只看 @mcp.tool() 注册出去的
                continue
            if node.name in writers or (_calls(node) & writers):
                result[node.name] = "with_busy_check" in decorators
    return result


def test_every_write_tool_goes_through_the_scheduler():
    """任何能改后端状态的已注册工具都必须带 @with_busy_check。"""
    unguarded = sorted(name for name, guarded in _registered_tools_that_write().items() if not guarded)

    assert not unguarded, (
        "这些工具会写后端状态却没有经过车道调度，可以与正在进行的读操作并发："
        f"{unguarded}。给它们加 @with_busy_check 并在 busy_tracker 里声明车道。"
    )


def test_the_detector_actually_finds_the_write_tools():
    """守住上面那条的前提：探测器真的识别出了写工具。

    如果 AST 形状哪天变了导致探测器返回空集，上面的断言会静默恒绿——比没有测试更糟。
    """
    found = _registered_tools_that_write()
    assert "analyze_apk_tool" in found, f"探测器没认出 analyze_apk_tool: {sorted(found)}"
    assert "load_file_tool" in found, f"探测器没认出 load_file_tool: {sorted(found)}"


def test_analyze_apk_declares_the_exclusive_lane():
    """声明了车道，才不会只是落进默认兜底。

    _DECORATED_OPERATIONS 是 with_busy_check 在装饰时填的，而装饰发生在
    register_workflow_tools() 执行的那一刻——所以必须先注册再断言，否则这条会
    依赖别的测试是否碰巧先注册过（单独跑红、全量跑绿的那种顺序依赖）。
    """
    assert "analyze_apk" in EXCLUSIVE_OPERATIONS

    class _Collector:
        def tool(self, *a, **kw):
            return lambda fn: fn

    workflow_tools.register_workflow_tools(_Collector())

    assert "analyze_apk" in _DECORATED_OPERATIONS, (
        "operation 名是剥掉 _tool 后缀的结果；不在这个集合里说明装饰器没生效"
    )


@pytest.mark.asyncio
async def test_analyze_apk_is_refused_while_the_exclusive_lane_is_held(monkeypatch):
    """行为判据：有互斥操作在跑时，换 APK 必须被挡下而不是直接执行。

    这是这条 bug 的真实后果——APK 被换掉时，正在进行的 get_class_source / search
    会读到一个已经不是它们开始时那个 APK 的后端。
    """
    InstanceRegistry.clear_all()
    InstanceBusyTracker.force_release_all()
    InstanceRegistry.configure(host="127.0.0.1", port=8650, name="jadx")

    reached_backend = False

    async def fake_get_from_jadx(endpoint, *args, **kwargs):
        nonlocal reached_backend
        if endpoint == "load-file":
            reached_backend = True
        return {}

    monkeypatch.setattr(workflow_tools, "get_from_jadx", fake_get_from_jadx)

    collected = {}

    class _Collector:
        def tool(self, *a, **kw):
            def deco(fn):
                collected[fn.__name__] = fn
                return fn
            return deco

    workflow_tools.register_workflow_tools(_Collector())

    held = await InstanceBusyTracker.try_acquire("rename")
    assert "error" not in held, f"测试前提失败，没占住 exclusive 车道: {held}"
    try:
        result = await collected["analyze_apk_tool"](path="target.apk", strategy="replace")
    finally:
        await InstanceBusyTracker.release(held["token"])
        InstanceBusyTracker.force_release_all()
        InstanceRegistry.clear_all()

    assert result.get("error") == "INSTANCE_BUSY", (
        f"exclusive 车道被占用时换 APK 必须被拒绝，实际返回: {result}"
    )
    assert not reached_backend, "请求不该在车道被占用时打到后端"
