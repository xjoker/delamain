package com.zin.delamain.index.shard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P2 fix: {@link ContentShard#open} validated the four TOP-LEVEL regions (covered/excluded/dict/blob)
 * but never the individual dictionary entries' {@code postingOffset}/{@code postingLength} fields —
 * each of which points into the blob region. A corrupted or truncated dictionary entry let
 * {@link ContentShard#postingsFor} throw {@code IllegalArgumentException} deep inside a QUERY
 * thread (an out-of-range {@code ByteBuffer.slice}), which bypasses
 * {@code ContentShardIndex.loadCatalog}'s "skip this shard" degradation path entirely — that only
 * guards the {@code open()} call, not a later query. This must now fail loudly at {@code open()}
 * time instead, via {@link IOException}, so the catalog loader can skip the shard like it already
 * does for the other corruption cases in {@code ContentShardRobustnessTest}.
 */
class ContentShardDictBoundsTest {

    private static final String HASH = "00112233445566778899aabbccddeeff";

    @Test
    void dictEntryPostingOffsetPastBlobEndFailsToOpen(@TempDir Path dir) throws IOException {
        List<ShardCatalog.ShardEntry> entries = new ArrayList<>();
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            b.addClass(0, "class a { void render() {} }");
            entries.add(b.flush());
        }
        Path f = dir.resolve(entries.get(0).fileName);

        // Locate the single dictionary entry and push its postingOffset (bytes 6..13 of the entry,
        // per ContentShard.DICT_ENTRY_BYTES layout: 3 chars (6B) + postingOffset (8B) + postingLength
        // (4B)) far past the blob region, without touching postingLength — the exact shape of a
        // corrupted/truncated dictionary write.
        long dictOffset = readLongAt(f, 48);
        long entryBase = dictOffset; // first (only) entry starts at the dictionary's own offset
        patchLongAt(f, entryBase + 6, Files.size(f) + 1_000_000L);

        assertThrows(IOException.class, () -> ContentShard.open(f),
                "a dictionary entry whose posting region falls outside the blob must fail to open, "
                        + "not defer the failure to a later query thread's postingsFor() call");
    }

    @Test
    void dictEntryNegativePostingLengthFailsToOpen(@TempDir Path dir) throws IOException {
        List<ShardCatalog.ShardEntry> entries = new ArrayList<>();
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            b.addClass(0, "class a { void render() {} }");
            entries.add(b.flush());
        }
        Path f = dir.resolve(entries.get(0).fileName);

        long dictOffset = readLongAt(f, 48);
        // postingLength is the 4-byte int at entryBase+14.
        patchIntAt(f, dictOffset + 14, -1);

        assertThrows(IOException.class, () -> ContentShard.open(f),
                "a negative postingLength must fail to open, not silently underflow the bounds check");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static long readLongAt(Path file, int offset) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getLong(offset);
    }

    private static void patchLongAt(Path file, long offset, long value) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putLong((int) offset, value);
        Files.write(file, bytes);
    }

    private static void patchIntAt(Path file, long offset, int value) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt((int) offset, value);
        Files.write(file, bytes);
    }
}
