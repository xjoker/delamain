# MCP tool design guidelines

Lessons from building out delamain's MCP tool set. An AI agent consumes MCP
tools very differently from how a human consumes an API — these guidelines
are written specifically for that usage pattern.

---

## Core principle

**The user of an MCP tool is an AI, not a human.** The AI will:
- decide autonomously which tool to call based on its description
- have no way to handle errors interactively (no dialog box to show)
- burn tokens on every description, so keep them short but precise
- potentially call several tools concurrently

---

## 1. Tool descriptions (the part that most shapes AI behavior)

### Length

Keep descriptions **under 200 characters**. Long descriptions add token
overhead to every single call.

```python
# ❌ Too long
"""
This tool retrieves the Java source code for a specified class by its fully
qualified class name. It will decompile the class on demand if not cached,
and returns the decompiled Java source code as a string. The class_name
parameter should be the fully qualified name...
"""

# ✅ Concise
"""Get decompiled Java source for a class. Use full class name (e.g. com.example.Foo)."""
```

### Content priority

1. **What it does** (required)
2. **Parameter format** (if it has special requirements)
3. **When to use it** (disambiguates from similar tools)
4. **What fields it returns** (key field names)

```python
@mcp.tool()
async def get_class_source(class_name: str, instance_id: str = None) -> dict:
    """Get decompiled Java source for a class.

    Args:
        class_name: Full class name (e.g. "com.example.MyClass") or simple name.
        instance_id: Target JADX instance; omit for default.
    Returns:
        dict: {response: java_source_code}
    """
```

---

## 2. Return-value conventions

### Always return a dict; never let an exception reach the AI

The AI cannot catch exceptions — the tool must handle every error itself
and return a structured result.

```python
# ❌ The AI receives an unformatted error
async def bad_tool(class_name: str) -> dict:
    result = await get_from_jadx("class-source", params={"class_name": class_name})
    return result["content"]  # a KeyError propagates straight to the AI

# ✅ Defensive return
async def good_tool(class_name: str) -> dict:
    try:
        result = await get_from_jadx("class-source", params={"class_name": class_name})
        return result
    except Exception as e:
        return {"error": "JADX_ERROR", "message": str(e)}
```

### Consistent error shape

```python
# Standard error shape
{"error": "ERROR_CODE", "message": "human-readable description"}

# Common error codes
INVALID_INPUT     # parameter validation failed
NOT_FOUND         # class/method does not exist
JADX_ERROR        # internal JADX exception
TIMEOUT           # operation timed out
RATE_LIMITED      # rate limit exceeded
```

### Don't do "smart" degradation at the tool layer

The AI should see the real situation — don't silently return an empty list
that looks like a successful zero-result search.

```python
# ❌ Silent degradation — the AI thinks it found 0 results
if not class_exists:
    return {"classes": []}

# ✅ Tell the AI explicitly
if not class_exists:
    return {"error": "NOT_FOUND", "message": f"Class '{class_name}' not found"}
```

---

## 3. Pagination

Every tool that returns a list must support pagination, to avoid a single
call blowing up the token budget.

```python
# Standard pagination parameters
offset: int = 0    # starting position
count: int = 100   # page size (with a sane cap, e.g. 500)

# Standard pagination response
{
    "items": [...],
    "total": 237931,
    "offset": 0,
    "count": 100,
    "has_more": True
}
```

**Per-call caps**:
- class lists: 500 items
- source code: unlimited (single class)
- search results: 200 items
- xrefs: 500 items

---

## 4. Long-running operations: async ticket pattern

Anything that can take longer than **10 seconds** must use a
submit → poll pattern, or the MCP client will time out.

```python
# ✅ Correct: return a ticket immediately
@mcp.tool()
async def submit_security_scan(scan_type: str = "full") -> dict:
    """Submit security scan. Returns ticket immediately; poll get_task_result(ticket)."""
    ticket = async_tasks.submit(_run_security_scan(scan_type))
    return {"ticket": ticket, "status": "submitted", "retry_after_seconds": 5}

@mcp.tool()
async def get_task_result(ticket: str) -> dict:
    """Poll result of a submitted task. status: running|done|error|not_found"""
    return async_tasks.poll(ticket)
```

**Ticket TTL**: 300s on the Python side, 600s on the Java side (the longer
Java-side TTL accounts for search requests queued during warmup).

**Triggers**: security scans, callgraph export, code search over a large APK.

---

## 5. Tiered timeouts

Different operations have different latency profiles — timeouts must match
actual behavior, not use a single blanket value.

```python
# in the HTTP call layer

TIMEOUT_METADATA = 30     # class/method-name search, always <1s
TIMEOUT_CODE_READ = 120   # source decompilation, can take 10-60s on a large class
TIMEOUT_SEARCH_CODE = 120 # code search, can take 15-60s on a large APK
TIMEOUT_WARMUP = 600      # warmup-related operations

# Key rule: the same endpoint can need a different timeout depending on params
# search-classes-by-keyword with search_in=class → TIMEOUT_METADATA
# search-classes-by-keyword with search_in=code  → TIMEOUT_SEARCH_CODE
```

---

## 6. Batch operations

Provide a batch variant for any frequently-used single-item operation —
this meaningfully cuts the AI's per-call overhead.

```python
# Single version
get_class_source(class_name) → dict

# Batch version (an AI often needs to analyze several classes at once)
batch_get_class_source(class_names: list[str]) → dict
# Returns: {"classes": [{"name": "...", "content": "..."}], "found": N, "total": N}
```

**Recommended batch caps**:
- < 20 items: execute directly
- 20-50 items: allowed, but hint the AI to split it up
- > 50 items: return a `BATCH_TOO_LARGE` error suggesting the caller split the batch

---

## 7. `instance_id` is a vestigial parameter — do not add new routing logic on it

Tools still accept `instance_id: Optional[str] = None` for call-site
compatibility with the ~30 tool modules that pass it through, but the
gateway is single-machine/single-instance/single-user: `get_from_jadx`
always proxies to the one backend configured via `InstanceRegistry.configure()`
and ignores the value (see `gateway/src/routing/request_router.py`). Don't
write new code that assumes `instance_id` selects among multiple backends —
there is exactly one.

```python
async def any_tool(param: str, instance_id: Optional[str] = None) -> dict:
    return await get_from_jadx("endpoint",
                               params={"param": param},
                               instance_id=instance_id)
```

---

## 8. Handling load state

While a large APK is loading, any Java-layer endpoint may return HTTP 202
`{"status": "loading"}`.

```python
# The Python tool layer must handle the 202/loading state
result = await get_from_jadx("decompile-status", instance_id=instance_id)
if result.get("status") == "loading":
    result["retry_after_seconds"] = 3
    result["suggestion"] = "JADX is still loading the file. Retry in 3 seconds."
return result
```

Once the AI sees a `retry_after_seconds` field, it retries automatically
instead of treating it as a failure.

---

## 9. Handling large responses

When a single response exceeds 32KB (`DELAMAIN_INLINE_RESPONSE_MAX_BYTES`),
the Java layer returns a "too large" signal instead of the content. The
gateway does not expose a transfer/download endpoint for inline tool
responses, so the tool itself must steer the AI toward a smaller batch or
chunked/single-item reads.

```json
{
  "response_too_large": true,
  "size_bytes": 130000
}
```

The Python layer must detect this field and return a clear suggestion to
shrink the batch or read in chunks.

---

## 10. Tool naming conventions

```
verb_noun → get_class_source, search_classes_by_keyword
submit_verb → submit_security_scan, submit_code_search (async submission)
get_verb_result → get_task_result, get_code_search_result (polling)
list_noun → list_packages, list_jadx_instances
```

Verb choice:
- `get_` — retrieve a known object
- `search_` — search/look up
- `list_` — enumerate a collection
- `submit_` — submit an async task
- `generate_` — generate code (e.g. a Frida hook)
- `export_` — export data

---

## 11. Anti-patterns (lessons learned the hard way)

| Anti-pattern | Consequence | Correct approach |
|---|---|---|
| Tool description over 500 chars | Extra token overhead on every call | Keep it under 200 chars |
| Synchronous wait on a long operation | MCP client times out | Use the submit/poll ticket pattern |
| One blanket 30s timeout everywhere | Code search gets silently truncated | Tier timeouts by operation type |
| Iterating a live JADX list directly | `ConcurrentModificationException` → crash | Snapshot first (`new ArrayList<>()`) |
| Returning `null` instead of an error | AI has no idea what happened | Return `{"error": "...", "message": "..."}` |
| Hardcoding class names in tests | Breaks the moment the test APK changes | Use a fixture that picks the first class dynamically |
| Query params on a POST endpoint | Parameters get truncated | POST body uses `json_body`, GET uses `params` |
