//! Streaming SHA-256 helper used to compute the whole-file digest without
//! loading the entire file into memory (sent as `X-Content-Sha256` on the
//! final chunk).

use anyhow::{Context, Result};
use sha2::{Digest, Sha256};
use std::io::Read;

/// Size of the read buffer used while streaming a file through the hasher.
pub const STREAM_BUFFER_SIZE: usize = 64 * 1024;

/// Stream `reader` to completion, returning the lowercase hex-encoded
/// SHA-256 digest. Does not load the whole input into memory at once.
pub fn sha256_stream<R: Read>(mut reader: R) -> Result<String> {
    let mut hasher = Sha256::new();
    let mut buf = [0u8; STREAM_BUFFER_SIZE];
    loop {
        let n = reader.read(&mut buf).context("reading input while hashing")?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode_lower(hasher.finalize()))
}

/// Minimal hex-lowercase encoder so we don't need an extra `hex` crate
/// dependency just for this.
mod hex {
    pub fn encode_lower(bytes: impl AsRef<[u8]>) -> String {
        let mut s = String::with_capacity(bytes.as_ref().len() * 2);
        for b in bytes.as_ref() {
            s.push_str(&format!("{:02x}", b));
        }
        s
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn one_shot_hex(data: &[u8]) -> String {
        let digest = Sha256::digest(data);
        hex::encode_lower(digest)
    }

    #[test]
    fn empty_input_matches_one_shot_digest() {
        let data: &[u8] = b"";
        let streamed = sha256_stream(Cursor::new(data)).unwrap();
        assert_eq!(streamed, one_shot_hex(data));
        // Known SHA-256 of empty string.
        assert_eq!(
            streamed,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }

    #[test]
    fn small_input_matches_one_shot_digest() {
        let data = b"delamain transfer contract";
        let streamed = sha256_stream(Cursor::new(data)).unwrap();
        assert_eq!(streamed, one_shot_hex(data));
    }

    #[test]
    fn input_larger_than_stream_buffer_matches_one_shot_digest() {
        // Force multiple read() calls through the streaming hasher.
        let data = vec![0xABu8; STREAM_BUFFER_SIZE * 3 + 1234];
        let streamed = sha256_stream(Cursor::new(&data)).unwrap();
        assert_eq!(streamed, one_shot_hex(&data));
    }

    #[test]
    fn output_is_lowercase_hex_of_correct_length() {
        let streamed = sha256_stream(Cursor::new(b"x")).unwrap();
        assert_eq!(streamed.len(), 64);
        assert!(streamed.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()));
    }
}
