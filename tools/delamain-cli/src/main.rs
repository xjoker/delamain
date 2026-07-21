mod chunker;
mod client;
mod hasher;
mod retry;

use anyhow::{bail, Context, Result};
use clap::Parser;
use indicatif::{ProgressBar, ProgressStyle};
use reqwest::blocking::Client;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::PathBuf;
use std::time::Duration;

/// Resumable chunked uploader for delamain's transfer endpoints
/// (`GET /transfer/status`, `PUT /transfer/upload`). Obtain a token first
/// via the `create_transfer_token` MCP tool.
#[derive(Parser, Debug)]
#[command(name = "delamain-cli", version, about)]
struct Cli {
    /// Base server URL, e.g. http://host:8651. Combined with the standard
    /// /transfer/status and /transfer/upload paths. Mutually exclusive
    /// with --upload-url (one of the two is required).
    #[arg(long)]
    server: Option<String>,

    /// Full upload URL, e.g. http://host:8651/transfer/upload. Must end in
    /// /transfer/upload so the matching /transfer/status URL can be derived
    /// for the resume check. Prefer --server when possible.
    #[arg(long)]
    upload_url: Option<String>,

    /// Transfer token returned by create_transfer_token.
    #[arg(long)]
    token: String,

    /// Chunk size in bytes for each PUT request.
    #[arg(long, default_value_t = 8 * 1024 * 1024)]
    chunk_size: u64,

    /// Max attempts per chunk before giving up (includes the first try).
    #[arg(long, default_value_t = 3)]
    retries: u32,

    /// File to upload.
    file: PathBuf,
}

struct Endpoints {
    status_url: String,
    upload_url: String,
}

fn resolve_endpoints(cli: &Cli) -> Result<Endpoints> {
    if let Some(server) = &cli.server {
        let base = server.trim_end_matches('/');
        return Ok(Endpoints {
            status_url: format!("{base}/transfer/status"),
            upload_url: format!("{base}/transfer/upload"),
        });
    }
    if let Some(upload_url) = &cli.upload_url {
        let Some(base) = upload_url.strip_suffix("/transfer/upload") else {
            bail!(
                "--upload-url must end with /transfer/upload so the matching \
                 /transfer/status URL can be derived; pass --server instead if unsure"
            );
        };
        return Ok(Endpoints {
            status_url: format!("{base}/transfer/status"),
            upload_url: upload_url.clone(),
        });
    }
    bail!("one of --server or --upload-url is required");
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    let endpoints = resolve_endpoints(&cli)?;

    if cli.chunk_size == 0 {
        bail!("--chunk-size must be > 0");
    }
    if cli.retries == 0 {
        bail!("--retries must be >= 1");
    }

    let metadata = std::fs::metadata(&cli.file)
        .with_context(|| format!("cannot stat file {}", cli.file.display()))?;
    let total_size = metadata.len();

    let http = Client::builder()
        .build()
        .context("failed to build HTTP client")?;

    eprintln!("checking existing transfer status...");
    let status = client::fetch_status(&http, &endpoints.status_url, &cli.token)?;
    if status.consumed {
        bail!(
            "transfer token already consumed (file already uploaded server-side); \
             request a new token if you need to upload again"
        );
    }
    let local_filename = cli.file.file_name().and_then(|n| n.to_str()).unwrap_or_default();
    if status.filename != local_filename {
        eprintln!(
            "warning: token was created for filename '{}' but uploading '{}'; \
             server will save under the token's filename",
            status.filename, local_filename
        );
    }
    if let Some(expires_at) = status.expires_at_epoch_ms {
        eprintln!("token expires at epoch_ms={expires_at}");
    }

    let start_offset = status.bytes_received;
    if start_offset > total_size {
        bail!(
            "server reports {} bytes received but local file {} is only {} bytes; \
             refusing to upload a mismatched/shorter file, request a new token",
            start_offset,
            cli.file.display(),
            total_size
        );
    }
    if start_offset > 0 {
        eprintln!("resuming upload from byte offset {start_offset}");
    }

    eprintln!("computing SHA-256 of {}...", cli.file.display());
    let hash_file = File::open(&cli.file)
        .with_context(|| format!("cannot open file {} for hashing", cli.file.display()))?;
    let whole_file_sha256 = hasher::sha256_stream(hash_file)?;

    let plan = chunker::plan_chunks(total_size, start_offset, cli.chunk_size);

    let progress = ProgressBar::new(total_size);
    progress.set_style(
        ProgressStyle::with_template(
            "{bar:40.cyan/blue} {bytes}/{total_bytes} ({eta}) {msg}",
        )
        .unwrap_or_else(|_| ProgressStyle::default_bar()),
    );
    progress.set_position(start_offset);

    let mut file = File::open(&cli.file)
        .with_context(|| format!("cannot open file {} for reading", cli.file.display()))?;

    let mut last_response: Option<client::UploadResponse> = None;
    for chunk in plan {
        file.seek(SeekFrom::Start(chunk.offset))
            .with_context(|| format!("seeking to offset {}", chunk.offset))?;
        let mut buf = vec![0u8; chunk.len as usize];
        file.read_exact(&mut buf)
            .with_context(|| format!("reading {} bytes at offset {}", chunk.len, chunk.offset))?;

        let sha_for_this_chunk = if chunk.is_final { Some(whole_file_sha256.as_str()) } else { None };

        let response = retry::retry_with_backoff(
            cli.retries,
            Duration::from_millis(500),
            |attempt| {
                if attempt > 1 {
                    eprintln!(
                        "retrying chunk at offset {} (attempt {}/{})",
                        chunk.offset, attempt, cli.retries
                    );
                }
                client::put_chunk(
                    &http,
                    &endpoints.upload_url,
                    &cli.token,
                    chunk.offset,
                    chunk.is_final,
                    sha_for_this_chunk,
                    buf.clone(),
                )
            },
            std::thread::sleep,
        )
        .with_context(|| {
            format!(
                "chunk at offset {} failed after {} attempt(s)",
                chunk.offset, cli.retries
            )
        })?;

        let confirmed_offset = response.bytes_received.unwrap_or(chunk.offset + chunk.len);
        progress.set_position(confirmed_offset);
        last_response = Some(response);
    }
    progress.finish_and_clear();

    match last_response {
        Some(resp) if resp.status == "complete" => {
            println!(
                "upload complete: path={} bytes={} sha256={}",
                resp.path.unwrap_or_default(),
                resp.bytes.unwrap_or(total_size),
                resp.sha256.unwrap_or(whole_file_sha256)
            );
            Ok(())
        }
        Some(resp) => {
            bail!("server did not report completion, last status was {:?}", resp.status);
        }
        None => bail!("no chunks were sent (empty upload plan) -- this should not happen"),
    }
}
