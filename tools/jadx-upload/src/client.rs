//! Thin HTTP layer over the `/transfer/status` and `/transfer/upload`
//! endpoints defined in the line protocol contract. Kept separate from
//! `main.rs` so the pure planning/hashing/retry logic in the sibling
//! modules can be unit tested without any of this.

use anyhow::{anyhow, bail, Context, Result};
use reqwest::blocking::Client;
use reqwest::StatusCode;
use serde::Deserialize;

pub const HEADER_TOKEN: &str = "X-Transfer-Token";
pub const HEADER_CHUNK_OFFSET: &str = "X-Chunk-Offset";
pub const HEADER_CHUNK_FINAL: &str = "X-Chunk-Final";
pub const HEADER_CONTENT_SHA256: &str = "X-Content-Sha256";

#[derive(Debug, Deserialize)]
pub struct StatusResponse {
    pub filename: String,
    pub bytes_received: u64,
    #[serde(default)]
    pub expires_at_epoch_ms: Option<u64>,
    #[serde(default)]
    pub consumed: bool,
}

#[derive(Debug, Deserialize)]
pub struct UploadResponse {
    pub status: String,
    #[serde(default)]
    pub bytes_received: Option<u64>,
    #[serde(default)]
    pub path: Option<String>,
    #[serde(default)]
    pub bytes: Option<u64>,
    #[serde(default)]
    pub sha256: Option<String>,
}

/// Query `GET /transfer/status` for how many bytes the server already has
/// for this token, to support resuming an interrupted upload.
pub fn fetch_status(client: &Client, status_url: &str, token: &str) -> Result<StatusResponse> {
    let resp = client
        .get(status_url)
        .query(&[("token", token)])
        .header(HEADER_TOKEN, token)
        .send()
        .with_context(|| format!("GET {status_url} (status check) failed to send"))?;

    let status = resp.status();
    if status == StatusCode::NOT_FOUND {
        bail!("transfer token rejected by server (404 Not Found): invalid, expired, or unknown token");
    }
    if !status.is_success() {
        let body = resp.text().unwrap_or_default();
        bail!("GET {status_url} returned HTTP {status}: {body}");
    }
    resp.json::<StatusResponse>()
        .with_context(|| format!("GET {status_url} returned a body that isn't valid StatusResponse JSON"))
}

/// PUT one chunk of the file to `/transfer/upload`.
///
/// `offset` is this chunk's starting byte in the whole file, `is_final`
/// sets `X-Chunk-Final`, and `whole_file_sha256` (only meaningful when
/// `is_final`) is sent as `X-Content-Sha256` for server-side verification.
pub fn put_chunk(
    client: &Client,
    upload_url: &str,
    token: &str,
    offset: u64,
    is_final: bool,
    whole_file_sha256: Option<&str>,
    body: Vec<u8>,
) -> Result<UploadResponse> {
    let mut req = client
        .put(upload_url)
        .header(HEADER_TOKEN, token)
        .header(HEADER_CHUNK_OFFSET, offset.to_string())
        .header(HEADER_CHUNK_FINAL, is_final.to_string())
        .header(reqwest::header::CONTENT_TYPE, "application/octet-stream");

    if is_final {
        if let Some(sha) = whole_file_sha256 {
            req = req.header(HEADER_CONTENT_SHA256, sha);
        }
    }

    let resp = req
        .body(body)
        .send()
        .with_context(|| format!("PUT {upload_url} (offset={offset}, final={is_final}) failed to send"))?;

    let status = resp.status();
    if status.is_success() {
        return resp.json::<UploadResponse>().with_context(|| {
            format!("PUT {upload_url} (offset={offset}) returned a body that isn't valid UploadResponse JSON")
        });
    }

    let body_text = resp.text().unwrap_or_default();
    let reason = match status {
        StatusCode::UNAUTHORIZED => "401 Unauthorized: transfer token invalid, expired, or already consumed",
        StatusCode::CONFLICT => "409 Conflict: offset mismatch, server has a different byte count than expected",
        StatusCode::PAYLOAD_TOO_LARGE => "413 Payload Too Large: upload exceeds server's configured size cap",
        StatusCode::UNPROCESSABLE_ENTITY => "422 Unprocessable Entity: whole-file SHA-256 did not match server's",
        StatusCode::BAD_REQUEST => "400 Bad Request: filename/path/extension rejected by server",
        StatusCode::SERVICE_UNAVAILABLE => "503 Service Unavailable: server sandbox is not enabled",
        _ => "unexpected HTTP status",
    };
    Err(anyhow!(
        "PUT {upload_url} (offset={offset}, final={is_final}) failed: {reason} -- body: {body_text}"
    ))
}
