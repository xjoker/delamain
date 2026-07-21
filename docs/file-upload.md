# Out-of-band file upload

How to get a large APK/JAR/AAR/DEX file onto a running delamain instance
without ever passing its bytes through the AI's context window.

## Why out-of-band

MCP tool calls and their results flow through the AI client's context —
tolerable for small JSON payloads, not for a 200MB APK. `create_transfer_token`
instead hands the AI a one-time token and an `upload_url`; the *human* (or a
small CLI) then PUTs the raw file bytes directly to the gateway over plain
HTTP, completely outside the MCP/AI transport. The AI only ever sees small
JSON status responses, never the file content.

## End-to-end flow

1. **AI calls `create_transfer_token`** (an MCP tool, see
   `gateway/src/tools/transfer_tools.py`) with the destination `filename`
   (and optionally `size_bytes` for an upfront cap check). It returns:
   ```json
   {
     "token": "...",
     "filename": "app.apk",
     "upload_path": "/transfer/upload",
     "status_path": "/transfer/status",
     "expires_at_epoch_ms": 1234567890000,
     "max_bytes": 1073741824,
     "chunk_size_hint": 8388608,
     "upload_url": "http://<gateway-host>:8651/transfer/upload"
   }
   ```
   If the gateway has no externally-reachable address configured (see
   `transfer_public_url` below), `upload_url` is replaced with an
   `upload_url_hint` string explaining what to configure.

2. **The AI relays `token` and `upload_url` to the human**, who uploads the
   file using either curl or the `jadx-upload` CLI (see below).

3. **The client polls `GET /transfer/status`** (or just re-runs `jadx-upload`,
   which does this automatically) until it reports `"consumed": true`.

4. **The AI calls `load_file`** with the destination filename to have JADX
   load the uploaded file.

## Uploading: curl (one-shot, no extra tooling)

For a single-shot, non-resumable upload of a small-to-medium file:

```sh
curl -T /path/to/app.apk -H "X-Transfer-Token: <token>" <upload_url>
```

A successful final chunk returns:
```json
{"status": "complete", "path": "app.apk", "bytes": 12345678, "sha256": "..."}
```

## Uploading: `jadx-upload` CLI (resumable, chunked, checksummed)

For large files, flaky connections, or when you want SHA-256 verification,
use the `jadx-upload` CLI in `tools/jadx-upload/` (Rust, cross-platform):

```sh
jadx-upload --server http://<gateway-host>:8651 --token <token> /path/to/app.apk
```

or, using the full `upload_url` returned by `create_transfer_token` directly:

```sh
jadx-upload --upload-url <upload_url> --token <token> /path/to/app.apk
```

Options (`tools/jadx-upload/README.md`):

| Flag | Default | Meaning |
|---|---|---|
| `--server <url>` | — | Base server URL. |
| `--upload-url <url>` | — | Full `/transfer/upload` URL, alternative to `--server`. |
| `--token <token>` | required | Transfer token from `create_transfer_token`. |
| `--chunk-size <bytes>` | `8388608` (8 MiB) | Size of each PUT chunk. |
| `--retries <n>` | `3` | Max attempts per chunk (exponential backoff, base 500ms). |

It queries `/transfer/status` first and resumes from the reported
`bytes_received` offset if a previous attempt was interrupted, streams the
file once to compute its SHA-256, then sends chunked `PUT` requests carrying
`X-Transfer-Token`, `X-Chunk-Offset`, and `X-Chunk-Final` headers (the final
chunk also carries `X-Content-Sha256`). On success it prints the
server-confirmed `path`, `bytes`, and `sha256`.

## Checking status manually

```sh
curl "<gateway-host>:8651/transfer/status?token=<token>"
```
or with the header instead of a query param: `-H "X-Transfer-Token: <token>"`.
Response:
```json
{"filename": "app.apk", "bytes_received": 8388608, "expires_at_epoch_ms": ..., "consumed": false}
```

## Key facts (verified against source)

- **Endpoints and headers** — `PUT /transfer/upload` and `GET
  /transfer/status`, both implemented in
  `src/main/java/com/zin/delamain/server/routes/TransferRoutes.java` and
  reverse-proxied by the gateway (single external port 8651; the Java backend
  itself only binds these to `127.0.0.1:8650`) in
  `gateway/src/tools/transfer_tools.py`. Recognized headers, forwarded
  verbatim by the gateway proxy: `X-Transfer-Token`, `X-Chunk-Offset`,
  `X-Chunk-Final`, `X-Content-Sha256`.
- **One-time token** — each `create_transfer_token` call mints a fresh token;
  once the upload completes (`entry.markConsumed()` in `TransferRoutes.java`)
  it cannot be reused for a new upload, only for a final `GET
  /transfer/status` check.
- **TTL** — token expires after `JADX_TRANSFER_TTL_MIN` minutes (default 30,
  `TransferRoutes.java`: `ENV_TTL_MIN` / `DEFAULT_TTL_MIN`).
- **Size cap** — `JADX_TRANSFER_MAX_MB` (default 1024 MB / 1 GiB,
  `TransferRoutes.java`: `ENV_MAX_MB` / `DEFAULT_MAX_MB`); an upload exceeding
  the cap is rejected mid-stream with HTTP 413 `size_exceeds_cap`, and an
  upfront `size_bytes` over the cap is rejected at token-creation time.
- **Landing directory** — files are sandboxed under the directory named by
  `JADX_FILE_ROOT` (default `/apks` inside the fused Docker image); with
  neither set, `load_file` and the transfer endpoints are disabled entirely
  (`src/main/java/com/zin/delamain/utils/FilePathSandbox.java`). Bytes
  stage in `<root>/.transfer/<token>.part` while in flight and are atomically
  moved to `<root>/<filename>` on the final chunk.
  Stale `.part` files are cleared on backend startup and swept every 60s
  alongside expired/consumed tokens.
- **Allowed extensions** — `.apk`, `.apks`, `.xapk`, `.apkm`, `.aab`, `.jar`,
  `.dex`, `.aar`, `.class`, `.zip`
  (`src/main/java/com/zin/delamain/core/MultiFileLoader.java`:
  `VALID_EXTENSIONS`, shared by `create-transfer-token`'s extension check and
  by the ordinary `load_file` upload route).
- **Single-port deployment** — end users only ever talk to gateway port
  8651; it proxies both the MCP protocol and the two transfer endpoints
  through to the Java backend on `127.0.0.1:8650`, which is not reachable
  from outside the container. Set `transfer_public_url` (TOML) or
  `JADX_TRANSFER_PUBLIC_URL` (env, higher priority) to the gateway's own
  externally-reachable address if its bind host (e.g. `0.0.0.0`) isn't
  itself usable as a client-facing hostname — see
  `gateway/src/tools/transfer_tools.py:resolve_upload_base()`.
