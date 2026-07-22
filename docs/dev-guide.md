# Developer guide

## Project layout

```
delamain/
├── src/main/java/com/zin/delamain/   ← Java backend (Javalin + JadxDecompiler)
├── src/test/java/com/zin/delamain/   ← JUnit tests
├── gateway/                          ← Python MCP gateway (FastMCP)
│   ├── main.py
│   ├── src/
│   │   ├── tools/                   ← one MCP tool module per feature area
│   │   ├── config/                  ← TOML config loading
│   │   ├── auth/                    ← MCP client bearer-token auth
│   │   └── registry/                ← JADX backend instance registry
│   └── tests/
│       ├── *.py                     ← unit tests (mocked HTTP)
│       └── integration/             ← integration tests (needs a running service)
├── tests/                           ← Python integration tests against a live
│                                       Java backend + gateway pair
└── docker/
    └── Dockerfile                   ← fused image: Java + Python, no Xvfb/noVNC
```

---

## Requirements

| Component | Version | Notes |
|---|---|---|
| Java | 21 | Maven build, Javalin runtime |
| Maven | 3.9+ | builds the JAR |
| Python | 3.10+ | gateway |
| uv | latest | Python dependency management |

---

## Java layer

### Build

```bash
# First time: install jadx-all into the local Maven repo (not on Maven Central)
wget https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip -O /tmp/jadx.zip
unzip /tmp/jadx.zip -d /tmp/jadx
mvn install:install-file \
  -Dfile=$(find /tmp/jadx/lib -name 'jadx-*-all.jar' | head -1) \
  -DgroupId=io.github.skylot -DartifactId=jadx-all \
  -Dversion=1.5.6 -Dpackaging=jar

# Build the JAR
mvn -DskipTests clean package

# Artifact
ls target/delamain-*.jar
```

### Upgrading the jadx version

`scripts/bump-jadx.sh` automates "download the release zip → compute its
SHA-256 → install into the local m2 repo → update the version references in
`pom.xml`/Dockerfile/docs":

```bash
scripts/bump-jadx.sh 1.5.7
```

The script does not commit anything and does not run `mvn test` —
verify compatibility and commit manually after upgrading.

### Running locally

```bash
java -jar target/delamain-*.jar \
  --port 8650 \
  --auth-token "$DELAMAIN_AUTH_TOKEN"
```

### Key environment variables

| Variable | Default | Notes |
|---|---|---|
| `DELAMAIN_AUTH_TOKEN` | — | Backend bearer token; falls back to a persisted `~/.delamain/auth.properties` token, then a randomly generated one, if unset |
| `DELAMAIN_PORT` | 8650 | Java server listen port |
| `JADX_FILE_ROOT` | — | Sandbox root for `load_file`/uploads; the fused Compose setup sets this to `/apks` |
| `DELAMAIN_WARMUP_ON_START` | true | Auto-starts background warmup once the server is up |
| `DELAMAIN_WARMUP_DECOMPILE_WORKERS` | 8 | Phase 1 parallel decompile worker count |
| `DELAMAIN_WARMUP_INDEX_WORKERS` | — | Phase 2 (trigram/shard build) worker count |
| `DELAMAIN_CODE_INDEX_ENABLED` | true | Trigram index on/off |
| `DELAMAIN_CODE_INDEX_MAX_TRIGRAMS` | 200000 | Trigram cap (OOM guard) |
| `DELAMAIN_CODE_INDEX_EVICTION_ENABLED` | — | Heap-pressure eviction for the trigram index |
| `DELAMAIN_INLINE_RESPONSE_MAX_BYTES` | — | Inline response size cap before a tool must fall back to batching/chunking |
| `DELAMAIN_RELOAD_LOCK_TIMEOUT_SECONDS` | 60 | How long `/load-file` reload waits to acquire the analysis write lock before failing and rolling back to the previously loaded file |
| `DELAMAIN_XREF_DEADLINE_SECONDS` | 25 | Server-side deadline for the live-decompile path of `/xrefs-to-class`, `/xrefs-to-method`, `/xrefs-to-field`, and `/batch-xrefs`; on timeout the response returns partial results (`partial_results: true`) instead of blocking indefinitely — use `/submit-xref` for an unbounded async alternative |

---

## Python layer

### Setup

```bash
cd gateway
uv sync
```

### Run

```bash
DELAMAIN_AUTH_TOKEN="$DELAMAIN_AUTH_TOKEN" \
DELAMAIN_AUTH_TOKENS="$DELAMAIN_AUTH_TOKENS" \
uv run python main.py --jadx localhost:8650
```

### Adding a new MCP tool

1. Create or pick a module under `gateway/src/tools/`
2. Implement `async def tool_name(param: type, ...) -> dict`
3. Register it with `@mcp.tool()` inside that module's `register_*_tools(mcp)`
4. Wire the registration function into `gateway/src/tools/__init__.py`
5. Write a matching unit test (see testing section below)

---

## Testing

### Test layout

| Layer | Command | Speed | Dependencies |
|---|---|---|---|
| Gateway unit tests | `cd gateway && pytest tests --ignore=tests/integration` | seconds | none (all HTTP calls mocked) |
| Gateway integration tests | `cd gateway && pytest tests/integration` | varies | a running gateway/backend |
| Root integration tests | `pytest tests/` | varies | a running Java backend (`JADX_JAVA_URL`) + gateway (`GATEWAY_URL`) |
| Java unit tests | `mvn test` | seconds | none |

### Gateway unit test pattern

```python
# gateway/tests/test_xxx_tools.py
import pytest
from src.tools import xxx_tools

@pytest.mark.asyncio
async def test_some_tool_calls_correct_endpoint(monkeypatch):
    captured = {}

    async def fake_get_from_jadx(endpoint, params=None, instance_id=None,
                                  timeout=None, method="GET", json_body=None):
        captured.update(endpoint=endpoint, params=params, method=method)
        return {"result": "ok"}

    monkeypatch.setattr(xxx_tools, "get_from_jadx", fake_get_from_jadx)

    result = await xxx_tools.some_tool("input_value", instance_id="inst1")

    assert captured["endpoint"] == "expected-endpoint"
    assert captured["params"]["key"] == "input_value"
    assert captured["method"] == "GET"
```

Rules:
- Only assert on the HTTP call shape, not on business logic.
- The fake function's signature must match exactly (including `method` and
  `json_body`).
- POST tools assert on `json_body`; GET tools assert on `params`.

### Root integration test environment variables

```bash
export JADX_JAVA_URL=http://localhost:28650
export GATEWAY_URL=http://localhost:8651
export JADX_AUTH_TOKEN="$DELAMAIN_AUTH_TOKEN"
export MCP_AUTH_TOKEN="$MCP_AUTH_TOKEN"
```

### Recommended CI split

```yaml
# Every push: unit tests only (fast, no live service)
cd gateway && pytest tests --ignore=tests/integration -q
mvn test

# Before release: integration tests against a running instance
pytest tests/ -q
```

---

## Debugging tips

### Loading a file through Compose

Compose only exposes the gateway; the Java service only listens on the
internal network. With a host file directory and an MCP client token ready
(the internal gateway<->Java token is auto-generated inside the container):

```bash
export JADX_APK_DIR=/data/apks
export DELAMAIN_AUTH_TOKENS="$(openssl rand -hex 32)"
docker compose up -d
```

Call `list_available_files_tool` with an MCP client carrying
`Authorization: Bearer $DELAMAIN_AUTH_TOKENS`, then call
`load_file_tool(path="target.apk")`. The path must be relative to `/apks`
inside the container; that mount is read-only and the service never writes
into the APK directory.

### Checking local Java server status

This only applies to a local direct-launch setup, or a deployment that
explicitly publishes the Java port separately; the default Compose
deployment does not expose it.

```bash
curl -H "Authorization: Bearer $DELAMAIN_AUTH_TOKEN" http://localhost:8650/health
curl -H "Authorization: Bearer $DELAMAIN_AUTH_TOKEN" http://localhost:8650/decompile-status
curl -H "Authorization: Bearer $DELAMAIN_AUTH_TOKEN" http://localhost:8650/index-stats
```

### Loading an APK and polling progress

```bash
# Trigger the load (JADX_FILE_ROOT must point at the file's directory)
curl -X POST -H "Authorization: Bearer $DELAMAIN_AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"path": "target.apk"}' \
  http://localhost:8650/load-file

# Poll until cached_percentage stabilizes
watch -n 3 'curl -s -H "Authorization: Bearer $DELAMAIN_AUTH_TOKEN" \
  http://localhost:8650/decompile-status | python3 -m json.tool'
```

### Triggering warmup and watching progress

```bash
curl -X POST -H "Authorization: Bearer $DELAMAIN_AUTH_TOKEN" http://localhost:8650/cache/warmup
watch -n 5 'curl -s -H "Authorization: Bearer $DELAMAIN_AUTH_TOKEN" \
  http://localhost:8650/cache/warmup-status | python3 -m json.tool'
```
