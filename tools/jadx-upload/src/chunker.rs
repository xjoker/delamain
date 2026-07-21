//! Pure, network-free chunk planning for resumable uploads.
//!
//! Given a total file size, a starting offset (from `/transfer/status`'s
//! `bytes_received`) and a chunk size, computes the sequence of
//! `(offset, len, is_final)` chunks the CLI must PUT to the server.

/// One planned chunk: byte range `[offset, offset + len)` within the file,
/// and whether this is the last chunk to send (`X-Chunk-Final`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ChunkPlan {
    pub offset: u64,
    pub len: u64,
    pub is_final: bool,
}

/// Plan the chunks needed to upload `[start_offset, total_size)`.
///
/// - If `start_offset >= total_size` and the file is non-empty, the transfer
///   is already fully received but may not yet be finalized server-side; a
///   single zero-length final chunk is emitted so the client can still send
///   `X-Chunk-Final: true` (with the whole-file SHA) to trigger completion.
/// - If `total_size == 0`, a single zero-length final chunk is emitted so an
///   empty file can still be uploaded/finalized.
/// - Chunks are never smaller than `chunk_size` except the last one.
///
/// Panics if `chunk_size == 0`.
pub fn plan_chunks(total_size: u64, start_offset: u64, chunk_size: u64) -> Vec<ChunkPlan> {
    assert!(chunk_size > 0, "chunk_size must be > 0");

    if total_size == 0 {
        return vec![ChunkPlan { offset: 0, len: 0, is_final: true }];
    }

    if start_offset >= total_size {
        return vec![ChunkPlan { offset: total_size, len: 0, is_final: true }];
    }

    let mut chunks = Vec::new();
    let mut offset = start_offset;
    while offset < total_size {
        let remaining = total_size - offset;
        let len = remaining.min(chunk_size);
        let is_final = offset + len >= total_size;
        chunks.push(ChunkPlan { offset, len, is_final });
        offset += len;
    }
    chunks
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_smaller_than_one_chunk_yields_single_final_chunk() {
        let plan = plan_chunks(100, 0, 8 * 1024 * 1024);
        assert_eq!(plan, vec![ChunkPlan { offset: 0, len: 100, is_final: true }]);
    }

    #[test]
    fn file_exactly_divisible_by_chunk_size() {
        // 3 chunks of 10 bytes each, chunk_size = 10.
        let plan = plan_chunks(30, 0, 10);
        assert_eq!(
            plan,
            vec![
                ChunkPlan { offset: 0, len: 10, is_final: false },
                ChunkPlan { offset: 10, len: 10, is_final: false },
                ChunkPlan { offset: 20, len: 10, is_final: true },
            ]
        );
    }

    #[test]
    fn file_with_remainder_has_smaller_final_chunk() {
        // 25 bytes, chunk_size 10 -> 10, 10, 5(final)
        let plan = plan_chunks(25, 0, 10);
        assert_eq!(
            plan,
            vec![
                ChunkPlan { offset: 0, len: 10, is_final: false },
                ChunkPlan { offset: 10, len: 10, is_final: false },
                ChunkPlan { offset: 20, len: 5, is_final: true },
            ]
        );
    }

    #[test]
    fn resume_from_nonzero_offset_only_plans_remaining_bytes() {
        // Total 25, already have 12 bytes, chunk_size 10 -> 10, 3(final)
        let plan = plan_chunks(25, 12, 10);
        assert_eq!(
            plan,
            vec![
                ChunkPlan { offset: 12, len: 10, is_final: false },
                ChunkPlan { offset: 22, len: 3, is_final: true },
            ]
        );
    }

    #[test]
    fn resume_from_offset_equal_to_total_size_emits_zero_length_final_chunk() {
        let plan = plan_chunks(100, 100, 10);
        assert_eq!(plan, vec![ChunkPlan { offset: 100, len: 0, is_final: true }]);
    }

    #[test]
    fn resume_from_offset_past_total_size_still_emits_zero_length_final_chunk() {
        // Defensive: server reported more than the local file has (shouldn't
        // normally happen, but must not panic or underflow).
        let plan = plan_chunks(100, 150, 10);
        assert_eq!(plan, vec![ChunkPlan { offset: 100, len: 0, is_final: true }]);
    }

    #[test]
    fn empty_file_yields_single_zero_length_final_chunk() {
        let plan = plan_chunks(0, 0, 10);
        assert_eq!(plan, vec![ChunkPlan { offset: 0, len: 0, is_final: true }]);
    }

    #[test]
    fn chunk_size_larger_than_file_uses_whole_file_as_one_chunk() {
        let plan = plan_chunks(5, 0, 1_000_000);
        assert_eq!(plan, vec![ChunkPlan { offset: 0, len: 5, is_final: true }]);
    }

    #[test]
    fn single_byte_chunk_size_still_partitions_correctly() {
        let plan = plan_chunks(3, 0, 1);
        assert_eq!(
            plan,
            vec![
                ChunkPlan { offset: 0, len: 1, is_final: false },
                ChunkPlan { offset: 1, len: 1, is_final: false },
                ChunkPlan { offset: 2, len: 1, is_final: true },
            ]
        );
    }

    #[test]
    #[should_panic(expected = "chunk_size must be > 0")]
    fn zero_chunk_size_panics() {
        let _ = plan_chunks(10, 0, 0);
    }
}
