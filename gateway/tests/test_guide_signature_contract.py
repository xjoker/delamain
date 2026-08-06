"""行为契约测试：get_jadx_guide 教的每个调用都必须真的能调用。

MCP_INSTRUCTIONS 主动把 AI 导向 get_jadx_guide(verbose=True)，所以 guide 里的示例
就是 AI 的第一手 API 参考。示例名错或首参错 → AI 第一次调用必然失败，然后不再信任
guide 转为全靠猜。这类漂移靠人读两份文本对不出来（漂了 4 个工具名、3 处参数，
上一轮人工审查只看出其中 3 处），必须由签名反射来钉。

校验两件事：
1. guide 里出现的每个工具名都在真实注册表里（抓「注册名带 _tool 后缀但 guide 没写」）
2. 示例的位置参数若写成标识符（guide 惯例：用参数名当占位符），必须与签名中该位置的
   参数名一致（抓 get_xrefs(class_name) 这种——arity 够、但首参其实是 target_type，
   sig.bind 抓不到，只有比对名字才抓得到）
"""

import ast
import inspect
import re

from src.tools import guide_tools


# guide 的 Python 示例里出现的非工具调用，以及 Markdown 表格里的通配写法。
_NOT_TOOLS = frozenset({"sleep", "get", "range", "print", "len", "open"})


def _registered_tools() -> dict:
    """注册所有 MCP 工具，返回 {注册名: Signature}。

    注册名 = 被 @mcp.tool() 装饰的函数名，这才是 MCP 客户端看到的名字；模块级同名
    辅助函数（如 search_tools.py 的 search_classes_by_keyword）不对外暴露。
    """
    import importlib
    import pkgutil

    import src.tools as pkg

    collected: dict = {}

    class _Collector:
        def tool(self, *a, **kw):
            def deco(fn):
                collected[fn.__name__] = inspect.signature(fn)
                return fn
            return deco

        def custom_route(self, *a, **kw):
            # transfer_tools 注册的是 HTTP 路由，不是 MCP 工具
            def deco(fn):
                return fn
            return deco

    collector = _Collector()
    for mod_info in pkgutil.iter_modules(pkg.__path__):
        mod = importlib.import_module(f"src.tools.{mod_info.name}")
        for attr in dir(mod):
            if attr.startswith("register_") and callable(getattr(mod, attr)):
                getattr(mod, attr)(collector)
    return collected


def _guide_texts() -> dict:
    """guide 的四份文本：短版、详版，以及两份可安装的 skill。

    skill 会被写进用户的 ~/.claude / ~/.codex 并在此后每个会话自动加载，所以它们的
    漂移比 guide 本身活得更久，必须一起校验。
    """
    return {
        "short": guide_tools._JADX_SHORT_GUIDE,
        "verbose": guide_tools._JADX_VERBOSE_GUIDE,
        "skill_claude": guide_tools._JADX_SKILL_CLAUDE,
        "skill_codex": guide_tools._JADX_SKILL_CODEX,
    }


# 形如 name(...) 的调用；要求 name 前不是 '.'，排除 r.get(...) 这类方法调用。
_CALL_RE = re.compile(r"(?<![\w.])([a-z_][a-z0-9_]*)\(([^()]*)\)")


def _extract_calls(text: str) -> list:
    """抽出 guide 文本里的示例调用，返回 [(工具名, 参数串, 原文片段)]。"""
    out = []
    for m in _CALL_RE.finditer(text):
        name, args = m.group(1), m.group(2)
        if name in _NOT_TOOLS:
            continue
        out.append((name, args.strip(), m.group(0)))
    return out


def _positional_identifiers(args: str) -> list:
    """解析示例参数串，返回位置参数里写成标识符的那些（保留位置下标）。

    guide 惯例是用参数名当占位符（get_class_info(class_name)）。字面量（"OkHttp"、5）
    不参与校验——它们表达的是取值而非参数名。
    """
    if not args:
        return []
    try:
        # 包成调用表达式来借用 Python 自己的参数解析，避免手写逗号切分踩到嵌套
        node = ast.parse(f"_f({args})", mode="eval").body
    except SyntaxError:
        return []
    out = []
    for idx, arg in enumerate(node.args):
        if isinstance(arg, ast.Name):
            out.append((idx, arg.id))
    return out


def _keyword_names(args: str) -> list:
    if not args:
        return []
    try:
        node = ast.parse(f"_f({args})", mode="eval").body
    except SyntaxError:
        return []
    return [kw.arg for kw in node.keywords if kw.arg]


def test_every_tool_named_in_the_guide_is_actually_registered():
    """guide 提到的工具名必须能在 MCP 工具表里找到。

    真实漂移：注册名是 search_classes_by_keyword_tool，guide 一直写不带后缀的版本
    （那是模块内部函数名，不对外暴露）。AI 照抄会拿到 tool not found。
    """
    registered = _registered_tools()
    missing = []
    for source, text in _guide_texts().items():
        for name, _args, snippet in _extract_calls(text):
            if name not in registered:
                missing.append(f"{source}: {snippet} — 未注册（真实注册表里没有 '{name}'）")

    assert not missing, "guide 提到了不存在的工具名：\n" + "\n".join(missing)


def test_guide_positional_placeholders_match_real_parameter_names():
    """示例的位置占位符必须与签名同位置的参数名一致。

    真实漂移：guide 写 get_xrefs(class_name)，而签名是
    get_xrefs(target_type, class_name, ...) —— 首参是 target_type 且必填。
    位置参数个数是够的，所以 sig.bind 不会报错；只有比对名字才抓得到这种语义错位。
    """
    registered = _registered_tools()
    wrong = []
    for source, text in _guide_texts().items():
        for name, args, snippet in _extract_calls(text):
            sig = registered.get(name)
            if sig is None:
                continue  # 由上一个测试负责
            params = list(sig.parameters)
            for idx, ident in _positional_identifiers(args):
                if idx >= len(params):
                    wrong.append(f"{source}: {snippet} — 第 {idx + 1} 个参数超出 {name} 的签名")
                elif ident != params[idx]:
                    wrong.append(
                        f"{source}: {snippet} — 第 {idx + 1} 个参数写的是 '{ident}'，"
                        f"但 {name} 该位置是 '{params[idx]}'"
                    )

    assert not wrong, "guide 的示例参数与真实签名错位：\n" + "\n".join(wrong)


def test_guide_keyword_arguments_exist_in_real_signatures():
    """示例里的关键字参数名必须真的存在于签名中。"""
    registered = _registered_tools()
    wrong = []
    for source, text in _guide_texts().items():
        for name, args, snippet in _extract_calls(text):
            sig = registered.get(name)
            if sig is None:
                continue
            for kw in _keyword_names(args):
                if kw not in sig.parameters:
                    wrong.append(f"{source}: {snippet} — {name} 没有参数 '{kw}'")

    assert not wrong, "guide 使用了不存在的关键字参数：\n" + "\n".join(wrong)


def test_extractor_actually_sees_the_guide_calls():
    """守住上面三个测试的前提：提取器真的抽到了调用。

    如果正则哪天失配，前三个测试会静默变成空循环恒绿——那比没有测试更糟。
    """
    calls = _extract_calls(guide_tools._JADX_VERBOSE_GUIDE)
    assert len(calls) >= 10, f"详版 guide 只抽到 {len(calls)} 个调用，提取器可能失配了"
    assert any(name == "get_xrefs" for name, _, _ in calls)


def test_method_call_syntax_is_not_mistaken_for_a_tool():
    """r.get(...) 这类方法调用不能被当成工具名。"""
    assert _extract_calls('r.get("retry_after_seconds", 5)') == []
