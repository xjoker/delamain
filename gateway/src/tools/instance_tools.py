"""
delamain Gateway - JADX Workflow Guide Tool

Single-instance gateway: the instance-management tools this module used to
expose (list/remove/set-default/health-check across multiple JADX instances)
were removed along with the multi-instance registry. Only get_jadx_guide
remains — it is the product's AI-onboarding guidance, not instance plumbing.
"""

from typing import Optional

_JADX_SHORT_GUIDE = """\
Delamain - Quick Start

PRE-FLIGHT (call before heavy ops):
1. get_decompile_status()  → check memory (<85%) and search_lock (false)
2. get_index_stats()       → check shard_index.built/covered_classes before code search
                              (trigram_index is now just a residual/self-heal layer)

SEARCH RULES:
- search_classes_by_keyword(search_in='class,method,field') — always fast
- NEVER use search_in='code' for hex, UUID, base64, or long URLs
- For code search on large APKs: submit_code_search + get_code_search_result

ORIENTATION:
- list_packages()  → find main package (ignore lib packages)
- get_android_manifest()  → entry points and permissions
- get_attack_surface()  → exported components
"""

_JADX_VERBOSE_GUIDE = """\
# Delamain - Reverse Engineering Workflow Guide

## Pre-Flight Checks (ALWAYS run before heavy operations)

1. **get_decompile_status()** — check runtime health:
   - memory.usage_percentage > 85% → reduce batch_size to ≤5; skip get_smali_of_class
   - search_lock.locked = true → another op holds the write lock; wait 5s and retry

2. **get_index_stats()** — check shard_index before code search:
   - shard_index.built == true and shard_index.covered_classes > 0 → code search viable
     (coverage is decoupled from heap size; works on low-memory instances too)
   - shard_index not built yet → metadata-only search (class/method/field names)
   - broad_term=true in a code-search result → narrow the term or use search_in='class'/'method'
     instead of retrying (see search_info.candidate_count/hint)
   - trigram_index is now a residual/self-heal layer, not the coverage signal

## Search Decision Tree

| What you're looking for | Tool | search_in |
|------------------------|------|-----------|
| Class by name | search_classes_by_keyword | class |
| Method by name | search_classes_by_keyword | method |
| String literal in code | submit_code_search | code |
| JNI/native methods | search_native_methods | — |

### CODE SEARCH HARD LIMITS — NEVER use search_in='code' for:
- Hex strings ≥ 8 chars (AES keys, hashes, device IDs)
- UUIDs, random alphanumeric tokens
- Base64 blobs
- Long URL paths (/api/v1/some/deep/path)
→ These live in native libs or are excluded from the shard index due to class size.
→ Instead: search_classes_by_keyword(search_in='class') to find the owning class, then get_class_source()

## Standard Reconnaissance Workflow

```
1. get_file_info()                    → APK name, package, version
2. get_decompile_status()             → memory health check
3. list_packages()                    → identify main vs library packages
4. get_android_manifest()             → entry points, permissions, services
5. get_attack_surface()               → exported components (attack surface)
6. search_classes_by_keyword(         → find key classes by name
       search_term="Network",
       package="com.example",
       search_in="class,method,field")
7. get_class_info(class_name)         → inheritance, method/field list
8. get_class_source(class_name)       → full decompiled source
```

## Async Code Search (Large APKs)

```python
# 1. Submit
result = submit_code_search("OkHttp", package="com.example")
ticket = result["ticket"]

# 2. Poll until done
while True:
    r = get_code_search_result(ticket)
    if r["status"] == "done":
        break
    sleep(r.get("retry_after_seconds", 5))

# 3. Paginate results
classes = r["classes"]  # up to count= items, use offset= for more
```

## Xrefs (Cross-References)

- get_xrefs(class_name) → all classes that reference this class
- get_xrefs(class_name, method_name) → all callers of a specific method
- **NOTE**: Xrefs on heavily-referenced classes (main Activity) can take 60s+ on large APKs (O(N) scan)

## File Upload (create_transfer_token)

- Alternative to `delamain-cli` CLI: `curl -T <local-file> -H "X-Transfer-Token: <token>" <upload_url>`
- If the upload stalls or the connection drops, the token can be reused to resume:
  GET <upload_url's host>/transfer/status?token=<token> reports bytes_received,
  then re-PUT from that offset with header X-Chunk-Offset.

## Rename / Refactor

- rename(class_name, new_name) → rename a class (persisted to disk)
- Revert: always use the ORIGINAL DEX name, not the alias
- All renames trigger a 30s global cache cooldown

## Performance Reference

| Operation              | Expected Time | Notes                     |
|------------------------|--------------|---------------------------|
| search_in=class/method | <100ms       | Always fast               |
| search_in=code         | 10ms–60s     | Fast once shard index warm; broad terms return partial results fast; cold first search on large APKs can still take up to ~60s |
| get_class_source       | <1s          | Fast when cached          |
| xrefs (simple class)   | 14ms         | Most classes              |
| xrefs (main activity)  | 60s+         | O(N) scan on large APKs   |
| batch_get_* (20 items) | <3s          | —                         |
"""

_JADX_SKILL_CLAUDE = """\
---
description: JADX APK reverse engineering workflow guide and decision rules
---

# JADX Reverse Engineering Workflow

When working with the `jadx` MCP server for Android APK analysis:

## Pre-Flight (ALWAYS run before heavy operations)

1. Call `get_decompile_status()` — check memory (<85%) and search lock (false)
2. Call `get_index_stats()` — verify shard_index.built/covered_classes before code search

## Search Rules

- `search_classes_by_keyword(search_in='class,method,field')` — always fast (<100ms)
- For code search: use `submit_code_search` + `get_code_search_result` (async, no timeout)
- **NEVER** use `search_in='code'` for: hex strings, UUIDs, base64, long URL paths

## Standard Recon Workflow

```
get_file_info() → list_packages() → get_android_manifest()
→ search_classes_by_keyword(search_in='class') → get_class_source()
```

## Key Rules

- Xrefs on main Activity class can take 60s+ on large APKs (O(N) scan) — this is expected
- Rename revert: always use the ORIGINAL class name, not the alias
- Batch ops max 20 items; call `get_jadx_guide(verbose=True)` for the full guide
"""

_JADX_SKILL_CODEX = """\
# JADX Reverse Engineering Workflow

Pre-flight: get_decompile_status() + get_index_stats() before heavy ops
  (check shard_index.built/covered_classes, not trigram_index — that's residual only).
Search: search_in='class,method,field' is fast; use submit_code_search for code.
On broad_term=true in results: narrow the term or use search_in='class'/'method', don't retry.
Never: search_in='code' for hex, UUID, base64, long URLs.
Full guide: get_jadx_guide(verbose=True)
"""


def register_instance_tools(mcp):
    """Register the JADX workflow guide tool with the MCP server."""

    @mcp.tool()
    async def get_jadx_guide(
        verbose: bool = False,
        install_skills: bool = False,
        target: Optional[str] = None,
    ) -> dict:
        """Bootstrap guide for JADX reverse-engineering workflows.

        Default call returns a short summary and key rules (~100 tokens).
        verbose=True returns the full workflow guide with all sections (~800 tokens).
        install_skills=True, target='claude' returns the skill file for the AI to write
        to ~/.claude/commands/jadx-workflow.md — the AI MUST get user authorization first.

        Call this at the start of a new reverse-engineering session to orient yourself.

        Args:
            verbose: Return full guide (default False = short summary only).
            install_skills: Return skill file payload to install locally.
            target: 'claude' | 'codex' — required when install_skills=True.
        Returns:
            dict: {guide} or {files_to_write, instruction} when install_skills=True.
        """
        if install_skills:
            if target not in ("claude", "codex"):
                return {
                    "error": "INVALID_TARGET",
                    "message": "install_skills=True requires target='claude' or target='codex'",
                }
            skill_content = _JADX_SKILL_CLAUDE if target == "claude" else _JADX_SKILL_CODEX
            skill_path = "~/.claude/commands/jadx-workflow.md" if target == "claude" else "~/.codex/jadx-workflow.md"
            return {
                "files_to_write": [{"path": skill_path, "content": skill_content}],
                "instruction": (
                    "Write this file using your file tools. "
                    "You MUST confirm with the user before writing — this modifies their local AI config. "
                    "After install, the skill loads automatically on every new conversation."
                ),
                "expected_after_install": (
                    "The jadx-workflow skill will be available as /jadx-workflow in future sessions. "
                    "It contains DECISION GUIDE, CODE SEARCH LIMITS, and step-by-step analysis SOPs."
                ),
            }

        if verbose:
            return {"guide": _JADX_VERBOSE_GUIDE}

        return {
            "guide": _JADX_SHORT_GUIDE,
            "hint": "Call get_jadx_guide(verbose=True) for the full workflow guide. "
                    "Call get_jadx_guide(install_skills=True, target='claude') to install the local skill.",
        }
