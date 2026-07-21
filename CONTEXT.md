# delamain — project background and purpose

## What this is

**delamain** is an MCP (Model Context Protocol) server that exposes the
full capability set of **JADX** — the Android APK/DEX decompiler — to AI
agents doing Android reverse engineering. It is built for **headless,
low-memory, high-performance** operation: no display server, no GUI
process, deployable on a CI runner, a bare VM, or an edge box with a
constrained heap.

## Why it exists

Reverse-engineering an Android app with an AI agent means the agent needs
more than "decompile this class" — it needs code search, cross-references,
call graphs, data-flow tracing, resource/manifest inspection, Frida hook
generation, and security scanning, all returned in a shape an AI can
actually consume (bounded output sizes, decompile-quality signals, node
budgets on graph queries) rather than a shape designed for a human clicking
through a GUI. delamain wraps jadx's headless `JadxDecompiler` API behind an
HTTP backend and fronts it with an MCP gateway so any MCP-capable AI client
can drive the whole workflow directly.

## Architecture in one paragraph

A Java backend (Javalin, port 8650, package `com.zin.delamain`) wraps jadx's
headless decompiler and does all the heavy lifting: decompilation, a
persisted code index (mmap'd shards + trigram search), usage-graph/xref
storage, security scanning, and out-of-band file transfer. A Python gateway
(FastMCP, port 8651) is a thin MCP-protocol proxy in front of it. Both ship
in a single fused Docker image with a single exposed port (8651) — the Java
backend only ever binds loopback inside the container. See
`docs/architecture-reference.md` for the full breakdown.

## Design priorities

- **AI-first tool design** — every MCP tool is shaped for how an AI agent
  actually calls tools (short descriptions, structured errors, submit/poll
  tickets for slow operations, bounded batch sizes). See
  `docs/mcp-tool-design-guide.md`.
- **Low memory, high scale** — mmap'd content shards keep code search
  available even on hosts where an in-memory-only index would have to be
  disabled under memory pressure; a prebaked index volume can be built once
  on a large-heap machine and shipped to a low-spec one for
  near-instant `FAST_RESTORE`. See `docs/prebaked-index.md`.
- **Context-window discipline** — large file uploads (APK/JAR/AAR/DEX) never
  pass through the AI's context; they move out-of-band via one-time
  transfer tokens. See `docs/file-upload.md`.
