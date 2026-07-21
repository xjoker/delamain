# delamain-cli

Cross-platform CLI that uploads a local APK/JAR to a running delamain
backend over the token-based transfer endpoints (`GET /transfer/status`,
`PUT /transfer/upload`), so the file bytes never pass through the AI's
context window.

## Relationship to `create_transfer_token`

1. In your MCP client, call the `create_transfer_token` tool. It returns a
   JSON body containing `token`, `upload_url`, `status_path`, and
   `expires_at_epoch_ms`.
2. Run `delamain-cli` (below) with that token to push the file's bytes to
   the server.
3. Once it prints `upload complete: path=...`, call the `load_file` MCP
   tool with that `path` to have JADX load it.

The token is single-use and time-limited (`JADX_TRANSFER_TTL_MIN` on the
server, default 30 minutes) — if it expires mid-transfer, request a new one
and re-run `delamain-cli`; it will resume from wherever the previous attempt
left off as long as the token is still valid.

## Usage

```sh
delamain-cli --server http://host:8651 --token <token> /path/to/app.apk
```

Or, if you already have the full upload URL from `create_transfer_token`:

```sh
delamain-cli --upload-url http://host:8651/transfer/upload --token <token> /path/to/app.apk
```

(`--upload-url` must end in `/transfer/upload` — the matching
`/transfer/status` URL is derived from it for the resume check. Prefer
`--server` when you have it.)

### Options

| Flag | Default | Meaning |
|---|---|---|
| `--server <url>` | — | Base server URL, e.g. `http://host:8651`. |
| `--upload-url <url>` | — | Full `/transfer/upload` URL, alternative to `--server`. |
| `--token <token>` | required | Transfer token from `create_transfer_token`. |
| `--chunk-size <bytes>` | `8388608` (8 MiB) | Size of each PUT chunk. |
| `--retries <n>` | `3` | Max attempts per chunk (exponential backoff, base 500ms). |

### Behavior

- Queries `/transfer/status` first; if the server already has some bytes
  for this token (e.g. a previous run was interrupted), resumes from that
  offset instead of re-uploading from scratch.
- Streams the whole file once to compute its SHA-256, then sends chunks of
  `--chunk-size` bytes via `PUT`, each carrying `X-Transfer-Token`,
  `X-Chunk-Offset`, and `X-Chunk-Final`; the final chunk also carries
  `X-Content-Sha256` so the server can verify integrity.
- On a network error, retries the current chunk with exponential backoff
  before giving up. If the process is killed or the network drops
  entirely, just re-run the same command — the token and status check
  make it pick up where it left off.
- Shows a progress bar on stderr; on success prints the server-confirmed
  `path`, `bytes`, and `sha256` to stdout and exits 0. Any failure exits
  non-zero with the HTTP status/reason in the error message.

## Building

Requires a Rust toolchain (`rustup.rs`). Uses `reqwest` with `rustls-tls`
(no system OpenSSL dependency), so the same source builds natively or
cross-compiles for macOS, Linux, and Windows without extra native
dependencies.

```sh
cd tools/delamain-cli
cargo build --release
cargo test
```

The binary is produced at `target/release/delamain-cli`
(`target/release/delamain-cli.exe` on Windows).

### Cross-compiling

Install the target once, then build with `--target`:

```sh
# Linux x86_64 (from macOS/Linux)
rustup target add x86_64-unknown-linux-gnu
cargo build --release --target x86_64-unknown-linux-gnu

# Linux aarch64
rustup target add aarch64-unknown-linux-gnu
cargo build --release --target aarch64-unknown-linux-gnu

# macOS (Apple Silicon)
rustup target add aarch64-apple-darwin
cargo build --release --target aarch64-apple-darwin

# macOS (Intel)
rustup target add x86_64-apple-darwin
cargo build --release --target x86_64-apple-darwin

# Windows x86_64
rustup target add x86_64-pc-windows-gnu
cargo build --release --target x86_64-pc-windows-gnu
```

Linux/Windows GNU targets typically need a matching linker installed (e.g.
`gcc` for Linux targets, `mingw-w64` for `x86_64-pc-windows-gnu`) — install
it via your OS package manager if `cargo build --target ...` fails at the
link step. `rustls-tls` avoids needing OpenSSL cross-compiled, which is the
usual pain point for `reqwest` cross builds.

## Manual equivalent with curl

For a single-shot, non-resumable upload of a small file, `curl` alone is
enough and needs no extra tooling:

```sh
curl -T /path/to/app.apk -H "X-Transfer-Token: <token>" http://host:8651/transfer/upload
```

Use `delamain-cli` instead whenever you want resumability, chunking for
large files, or SHA-256 verification.
