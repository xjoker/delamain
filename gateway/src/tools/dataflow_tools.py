"""
delamain Gateway - Simplified Data-Flow Tracing Tools
"""

from collections import deque
from typing import Optional

from ..logging_config import get_logger
from ..routing.request_router import get_from_jadx
from ..busy_tracker import with_busy_check
from ..types import format_error_response

logger = get_logger("dataflow_tools")

_ABSOLUTE_MAX_DEPTH = 5
_MAX_CHILDREN_PER_LEVEL = 10

# Sink definitions using fully qualified class names for precise matching.
# Format: {category: {"severity": str, "sinks": [(full_class, method_name)]}}
_SINK_DEFINITIONS: dict[str, dict] = {
    "crypto": {
        "severity": "high",
        "sinks": [
            ("javax.crypto.Cipher", "doFinal"),
            ("javax.crypto.Cipher", "update"),
            ("javax.crypto.Mac", "doFinal"),
            ("javax.crypto.KeyGenerator", "generateKey"),
            ("java.security.MessageDigest", "digest"),
        ],
    },
    "network": {
        "severity": "high",
        "sinks": [
            ("java.net.URL", "openConnection"),
            ("okhttp3.OkHttpClient", "newCall"),
            ("retrofit2.Retrofit", "create"),
            ("android.net.http.AndroidHttpClient", "execute"),
        ],
    },
    "webview": {
        "severity": "high",
        "sinks": [
            ("android.webkit.WebView", "loadUrl"),
            ("android.webkit.WebView", "loadDataWithBaseURL"),
            ("android.webkit.WebView", "evaluateJavascript"),
            ("android.webkit.WebView", "addJavascriptInterface"),
        ],
    },
    "command": {
        "severity": "critical",
        "sinks": [
            ("java.lang.Runtime", "exec"),
            ("java.lang.ProcessBuilder", "start"),
            ("java.lang.reflect.Method", "invoke"),
            ("java.lang.Class", "forName"),
        ],
    },
    "storage": {
        "severity": "medium",
        "sinks": [
            ("java.io.FileOutputStream", "write"),
            ("android.content.SharedPreferences$Editor", "putString"),
            ("android.database.sqlite.SQLiteDatabase", "execSQL"),
            ("android.database.sqlite.SQLiteDatabase", "rawQuery"),
        ],
    },
    "ipc": {
        "severity": "medium",
        "sinks": [
            ("android.content.Context", "sendBroadcast"),
            ("android.content.Context", "startActivity"),
            ("android.content.Context", "startService"),
            ("android.app.PendingIntent", "getActivity"),
        ],
    },
    "logging": {
        "severity": "low",
        "sinks": [
            ("android.util.Log", "d"),
            ("android.util.Log", "e"),
            ("android.util.Log", "i"),
            ("android.util.Log", "v"),
            ("android.util.Log", "w"),
        ],
    },
}

_SOURCE_PATTERNS: dict[str, list[str]] = {
    "user_input": [
        "EditText.getText", "Intent.getExtra", "Intent.getStringExtra",
        "Intent.getIntExtra", "Bundle.get", "Bundle.getString",
        "onActivityResult", "onNewIntent",
    ],
    "network_input": [
        "InputStream.read", "BufferedReader.readLine",
        "Response.body", "JsonReader", "HttpURLConnection.getInputStream",
    ],
    "storage_input": [
        "SharedPreferences.get", "SharedPreferences.getString",
        "Cursor.getString", "Cursor.getInt",
        "ContentResolver.query", "FileInputStream.read",
    ],
}


def _match_sink(class_name: str, method_name: str) -> tuple[bool, Optional[str], Optional[str]]:
    """
    Exact match: only hits when class_name and method_name are fully identical to a sink definition.
    Uses equality matching for Android/Java framework classes (javax./android./java.) to avoid
    false positives from short-name matches.
    Returns (matched, category, severity)
    """
    for category, info in _SINK_DEFINITIONS.items():
        for sink_cls, sink_mth in info["sinks"]:
            if method_name == sink_mth and class_name == sink_cls:
                return True, category, info["severity"]
    return False, None, None


def _match_source(method_label: str) -> tuple[bool, Optional[str]]:
    for src_type, patterns in _SOURCE_PATTERNS.items():
        for pat in patterns:
            if pat in method_label:
                return True, src_type
    return False, None


def _method_label(class_name: str, method_name: str) -> str:
    short_class = class_name.rsplit(".", 1)[-1] if "." in class_name else class_name
    return f"{short_class}.{method_name}"


async def trace_data_flow(
    source_class: str,
    source_method: str,
    max_depth: int = 3,
    instance_id: Optional[str] = None,
) -> dict:
    """Trace the call graph forward from a method (callee direction) using BFS reachability."""
    max_depth = max(1, min(max_depth, _ABSOLUTE_MAX_DEPTH))
    logger.info(f"trace_data_flow: {source_class}#{source_method}, depth={max_depth}")

    root_label = _method_label(source_class, source_method)
    root_node: dict = {
        "method": root_label, "full_class": source_class, "depth": 0,
        "is_sink": False, "sink_type": None, "children": [],
    }
    sinks_found: list[dict] = []
    visited: set[str] = {f"{source_class}#{source_method}"}
    nodes_explored = 0
    actual_max_depth = 0

    queue: deque[tuple[list, str, str, int, list[str]]] = deque()
    queue.append((root_node["children"], source_class, source_method, 1, [root_label]))

    while queue:
        parent_children, cls, method, depth, path = queue.popleft()
        if depth > max_depth:
            continue
        try:
            result = await get_from_jadx(
                "method-callees", {"class_name": cls, "method_name": method}, instance_id=instance_id,
            )
        except Exception as exc:
            logger.debug(f"method-callees failed for {cls}#{method}: {exc}")
            continue

        callees = result.get("callees", [])
        expanded = 0
        for callee in callees:
            if expanded >= _MAX_CHILDREN_PER_LEVEL:
                break
            if isinstance(callee, dict):
                c_class = callee.get("class_name", "")
                c_method = callee.get("method_name", callee.get("name", ""))
            else:
                parts = str(callee).rsplit(".", 1)
                c_class = parts[0] if len(parts) == 2 else ""
                c_method = parts[-1]

            label = _method_label(c_class, c_method) if c_class else str(callee)
            visit_key = f"{c_class}#{c_method}"
            # Match sinks by fully-qualified class name + method name to avoid false positives from short-name matches
            is_sink, sink_type, sink_severity = _match_sink(c_class, c_method)
            child_node: dict = {
                "method": label, "full_class": c_class, "depth": depth,
                "is_sink": is_sink, "sink_type": sink_type, "sink_severity": sink_severity, "children": [],
            }
            parent_children.append(child_node)
            nodes_explored += 1
            actual_max_depth = max(actual_max_depth, depth)

            if is_sink:
                sinks_found.append({"method": label, "full_class": c_class, "sink_type": sink_type, "sink_severity": sink_severity, "path": path + [label]})

            if visit_key not in visited and depth < max_depth:
                visited.add(visit_key)
                if c_class:
                    queue.append((child_node["children"], c_class, c_method, depth + 1, path + [label]))
            expanded += 1

    return {
        "source": {"class_name": source_class, "method_name": source_method},
        "analysis_type": "call_graph_bfs",
        "analysis_note": (
            "This is a call-graph BFS traversal, NOT taint/data-flow analysis. "
            "Results show reachable call paths, not actual data propagation."
        ),
        "max_depth": max_depth,
        "call_tree": root_node,
        "sinks_found": sinks_found,
        "stats": {"nodes_explored": nodes_explored, "max_depth_reached": actual_max_depth, "sinks_count": len(sinks_found)},
        "limitations": (
            "This is a simplified call-graph reachability analysis. "
            "It does not perform true data-flow or taint tracking. "
            "Results may include false positives and miss indirect calls."
        ),
    }


async def find_callers_chain(
    target_class: str,
    target_method: str,
    max_depth: int = 3,
    instance_id: Optional[str] = None,
) -> dict:
    """Trace the call graph backward from a method (caller direction) using xrefs BFS."""
    max_depth = max(1, min(max_depth, _ABSOLUTE_MAX_DEPTH))
    logger.info(f"find_callers_chain: {target_class}#{target_method}, depth={max_depth}")

    root_label = _method_label(target_class, target_method)
    root_node: dict = {
        "method": root_label, "full_class": target_class, "depth": 0,
        "is_source": False, "source_type": None, "callers": [],
    }
    sources_found: list[dict] = []
    visited: set[str] = {f"{target_class}#{target_method}"}
    nodes_explored = 0
    actual_max_depth = 0

    queue: deque[tuple[list, str, str, int, list[str]]] = deque()
    queue.append((root_node["callers"], target_class, target_method, 1, [root_label]))

    while queue:
        parent_callers, cls, method, depth, path = queue.popleft()
        if depth > max_depth:
            continue
        try:
            result = await get_from_jadx(
                "xrefs-to-method", {"class_name": cls, "method_name": method}, instance_id=instance_id,
            )
        except Exception as exc:
            logger.debug(f"xrefs-to-method failed for {cls}#{method}: {exc}")
            continue

        references = result.get("references", [])
        expanded = 0
        for ref in references:
            if expanded >= _MAX_CHILDREN_PER_LEVEL:
                break
            if isinstance(ref, dict):
                r_class = ref.get("class_name", ref.get("cls", ""))
                r_method = ref.get("method_name", ref.get("mth_name", ref.get("name", "")))
            else:
                parts = str(ref).rsplit(".", 1)
                r_class = parts[0] if len(parts) == 2 else ""
                r_method = parts[-1]

            label = _method_label(r_class, r_method) if r_class else str(ref)
            visit_key = f"{r_class}#{r_method}"
            is_source, source_type = _match_source(label)
            caller_node: dict = {
                "method": label, "full_class": r_class, "depth": depth,
                "is_source": is_source, "source_type": source_type, "callers": [],
            }
            parent_callers.append(caller_node)
            nodes_explored += 1
            actual_max_depth = max(actual_max_depth, depth)

            if is_source:
                sources_found.append({"method": label, "source_type": source_type, "path": list(reversed(path + [label]))})

            if visit_key not in visited and depth < max_depth:
                visited.add(visit_key)
                if r_class:
                    queue.append((caller_node["callers"], r_class, r_method, depth + 1, path + [label]))
            expanded += 1

    return {
        "target": {"class_name": target_class, "method_name": target_method},
        "max_depth": max_depth,
        "caller_tree": root_node,
        "sources_found": sources_found,
        "stats": {"nodes_explored": nodes_explored, "max_depth_reached": actual_max_depth, "sources_count": len(sources_found)},
        "limitations": (
            "This is a simplified, pattern-based caller-chain analysis using xrefs. "
            "It does not perform true data-flow or taint tracking. "
            "Results may include false positives and miss indirect/reflective calls."
        ),
    }


async def callers_chain_classes(
    target_class: str,
    max_depth: int = 3,
    max_nodes: int = 2000,
    instance_id: Optional[str] = None,
) -> dict:
    """Instant class-level multi-hop reachability ("which classes can reach X") via the
    server-side precomputed usage graph — a pure BFS lookup, no decompilation. Use this for a
    fast, broad "what reaches this class" overview before drilling into method-level detail."""
    return await get_from_jadx(
        "callers-chain",
        {"class_name": target_class, "depth": str(max_depth), "max_nodes": str(max_nodes)},
        instance_id=instance_id,
    )


_trace_data_flow = trace_data_flow
_find_callers_chain = find_callers_chain
_callers_chain_classes = callers_chain_classes


def register_dataflow_tools(mcp):
    """Register data-flow tracing tools with the MCP server."""

    @mcp.tool()
    @with_busy_check
    async def trace_data_flow(
        source_class: str,
        source_method: str,
        max_depth: int = 3,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Trace forward call-graph reachability via BFS and flag Android sinks (network, webview, command, storage, IPC, crypto).

        Uses exact full-qualified class + method matching for sink detection to avoid false positives.

        Args:
            source_class: Fully qualified class name. source_method: Starting method name.
            max_depth: BFS depth 1-5 (default 3). instance_id: Target JADX instance name.
        Returns:
            dict: {call_tree, sinks_found: [{method, full_class, sink_type, sink_severity, path}],
                   analysis_type, analysis_note, stats}
        """
        return await _trace_data_flow(
            source_class=source_class, source_method=source_method,
            max_depth=max_depth, instance_id=instance_id,
        )

    @mcp.tool()
    @with_busy_check
    async def find_callers_chain(
        target_class: str,
        target_method: str = "",
        max_depth: int = 3,
        granularity: str = "class",
        max_nodes: int = 2000,
        instance_id: Optional[str] = None,
    ) -> dict:
        """Trace backward "who can reach this" call chains.

        Two granularities:
        - granularity='class' (default, FAST): instant N-hop BFS over the precomputed usage
          graph — "which classes can reach target_class", returned as per-depth layers. No
          decompilation; scales to thousands of nodes in tens of ms. target_method is ignored.
          Use this first for a broad reachability overview on large APKs.
        - granularity='method' (detailed): per-method caller tree with input-source flagging
          (user input / network / storage). Requires target_method. Heavier (per-hop xref) but
          each hop is fast now; keep max_depth small.

        Args:
            target_class: Fully qualified class name.
            target_method: Method name (required only for granularity='method').
            max_depth: BFS depth 1-12 for class, 1-5 for method (default 3).
            granularity: 'class' | 'method'.
            max_nodes: class-mode safety cap on discovered classes (default 2000).
            instance_id: Target JADX instance name.
        Returns:
            class: {target, layers:[{depth,count,classes}], total_callers, truncated, via}
            method: {caller_tree, sources_found, stats}
        """
        if granularity == "method":
            if not target_method:
                return format_error_response(
                    "MISSING_PARAM", "target_method is required when granularity='method'")
            return await _find_callers_chain(
                target_class=target_class, target_method=target_method,
                max_depth=max_depth, instance_id=instance_id,
            )
        return await _callers_chain_classes(
            target_class=target_class, max_depth=max_depth,
            max_nodes=max_nodes, instance_id=instance_id,
        )

    logger.info("Dataflow tools registered: trace_data_flow, find_callers_chain")
