package com.zin.delamain.index.shard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 1 — single-shard build/flush/mmap round-trip and exclusion semantics for
 * {@link ContentShardBuilder} + {@link ContentShard}. Pure temp-dir I/O, no real APK.
 */
class ContentShardBuilderTest {

    private static final String HASH = "deadbeefcafef00d";

    /** Mirrors CodeContentIndex.index()'s exact trigram extraction (skip all-whitespace windows). */
    private static Set<Long> trigramsOf(String code) {
        Set<Long> out = new HashSet<>();
        for (int i = 0; i + 3 <= code.length(); i++) {
            char a = code.charAt(i), b = code.charAt(i + 1), c = code.charAt(i + 2);
            if (a <= ' ' && b <= ' ' && c <= ' ') continue;
            out.add(((long) a << 32) | ((long) b << 16) | (long) c);
        }
        return out;
    }

    private static Set<Integer> idsOf(ImmutableRoaringBitmap b) {
        Set<Integer> s = new HashSet<>();
        if (b != null) b.forEach((org.roaringbitmap.IntConsumer) s::add);
        return s;
    }

    @Test
    void singleShardRoundTripsTrigramPostingsThroughMmap(@TempDir Path dir) throws IOException {
        // Three classes with known lower-cased source.
        String c0 = "class alpha { void run() { } }";
        String c1 = "class beta { int run; }";
        String c2 = "class alpha gamma { }";

        ShardCatalog.ShardEntry meta;
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            assertFalse(b.addClass(10, c0));
            assertFalse(b.addClass(11, c1));
            assertFalse(b.addClass(12, c2));
            meta = b.flush();
        }
        assertNotNull(meta);
        assertEquals(10, meta.idLo);
        assertEquals(12, meta.idHi);

        try (ContentShard shard = ContentShard.open(dir.resolve(meta.fileName))) {
            assertEquals(10, shard.idLo());
            assertEquals(12, shard.idHi());

            // Every trigram of every class must round-trip to exactly the ids that contain it.
            String[] codes = {c0, c1, c2};
            int[] ids = {10, 11, 12};
            Set<Long> allTrigrams = new HashSet<>();
            for (String s : codes) allTrigrams.addAll(trigramsOf(s));

            for (long key : allTrigrams) {
                char a = (char) (key >>> 32), bb = (char) ((key >>> 16) & 0xFFFF), cc = (char) (key & 0xFFFF);
                Set<Integer> expected = new HashSet<>();
                for (int i = 0; i < codes.length; i++) {
                    if (trigramsOf(codes[i]).contains(key)) expected.add(ids[i]);
                }
                ImmutableRoaringBitmap posting = shard.postingsFor(a, bb, cc);
                assertNotNull(posting, "trigram present in some class must be in the dictionary");
                assertEquals(expected, idsOf(posting),
                        "posting ids for a trigram must exactly match the classes that contain it");
            }

            // A trigram absent from all classes must be a definitive negative (null).
            assertNull(shard.postingsFor('z', 'z', 'z'), "absent trigram must return null");

            // All three ids are authoritative (covered).
            assertTrue(shard.coveredIds().contains(10));
            assertTrue(shard.coveredIds().contains(11));
            assertTrue(shard.coveredIds().contains(12));
        }
    }

    @Test
    void excludedIdIsRecordedAndNotCovered(@TempDir Path dir) throws IOException {
        ShardCatalog.ShardEntry meta;
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            b.addClass(0, "class one { void alpha() {} }");
            b.markExcluded(1);                    // oversized/skipped class in range
            b.addClass(2, "class two { void beta() {} }");
            meta = b.flush();
        }
        try (ContentShard shard = ContentShard.open(dir.resolve(meta.fileName))) {
            assertEquals(0, shard.idLo());
            assertEquals(2, shard.idHi());
            assertTrue(shard.excludedIds().contains(1), "excluded id must be recorded");
            assertFalse(shard.coveredIds().contains(1),
                    "excluded id must NOT be covered — no definitive-negative may be given for it");
            assertTrue(shard.coveredIds().contains(0));
            assertTrue(shard.coveredIds().contains(2));
        }
    }

    @Test
    void shortSourceIsExcludedNotCovered(@TempDir Path dir) throws IOException {
        ShardCatalog.ShardEntry meta;
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            b.addClass(0, "class real { void alpha() {} }");
            b.addClass(1, "ab");                  // < 3 chars: cannot be indexed -> excluded
            meta = b.flush();
        }
        try (ContentShard shard = ContentShard.open(dir.resolve(meta.fileName))) {
            assertTrue(shard.excludedIds().contains(1));
            assertFalse(shard.coveredIds().contains(1),
                    "a class too short to index must not be treated as authoritative");
        }
    }

    @Test
    void allWhitespaceSourceIsCoveredWithNoPostings(@TempDir Path dir) throws IOException {
        // len >= 3 but every trigram window is all-whitespace -> no postings, yet the class
        // IS authoritative: it is a definitive negative for any real search term.
        ShardCatalog.ShardEntry meta;
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            b.addClass(0, "   \n\t  ");
            meta = b.flush();
        }
        assertNotNull(meta);
        try (ContentShard shard = ContentShard.open(dir.resolve(meta.fileName))) {
            assertTrue(shard.coveredIds().contains(0),
                    "a fully-indexed (even if trigram-empty) class must be covered");
            assertFalse(shard.excludedIds().contains(0));
        }
    }

    @Test
    void nonMonotonicIdIsRejected(@TempDir Path dir) throws IOException {
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            b.addClass(5, "class a { void alpha() {} }");
            assertThrows(IllegalArgumentException.class, () -> b.addClass(3, "class b {}"),
                    "ids must be strictly increasing to keep shard ranges ordered and non-overlapping");
            assertThrows(IllegalArgumentException.class, () -> b.addClass(5, "class c {}"),
                    "the same id twice is a bug and must be rejected");
        }
    }

    @Test
    void writtenShardsAccumulatesEveryAutoFlushedAndClosedShard(@TempDir Path dir) throws IOException {
        // Tiny budget so every non-empty addClass auto-flushes internally (discarding its meta),
        // plus a final trailing class flushed by close(). writtenShards() must still surface all of
        // them so the caller can build the catalog — this is the accumulator the query side depends
        // on (an auto-flush that vanished would leave a shard file with no catalog entry).
        java.util.List<ShardCatalog.ShardEntry> written;
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L)) {
            for (int id = 0; id < 5; id++) {
                b.addClass(id, "class c" + id + " { void method" + id + "() {} }");
            }
            // close() flushes any residual window; capture the accumulator afterwards.
            b.close();
            written = b.writtenShards();
        }

        // Every shard file on disk must have exactly one catalog entry, and vice versa.
        int onDisk = 0;
        for (int seq = 0; ; seq++) {
            if (!java.nio.file.Files.exists(dir.resolve(HASH + ".shard." + seq))) break;
            onDisk++;
        }
        assertEquals(onDisk, written.size(),
                "writtenShards() must return one entry per shard file actually written");
        assertEquals(5, written.size(), "each sub-budget class produced its own shard");

        // Entries must be in ascending, non-overlapping id order (== flush order).
        int prevHi = Integer.MIN_VALUE;
        int prevSeq = Integer.MIN_VALUE;
        for (ShardCatalog.ShardEntry e : written) {
            assertTrue(e.idLo > prevHi, "shard ranges must be strictly ordered and non-overlapping");
            assertTrue(e.seq > prevSeq, "shard seqs must be strictly increasing");
            assertTrue(java.nio.file.Files.exists(dir.resolve(e.fileName)),
                    "each returned entry must name a shard file that exists on disk");
            prevHi = e.idHi;
            prevSeq = e.seq;
        }
    }

    @Test
    void forceFlushOnEmptyWindowReturnsNull(@TempDir Path dir) throws IOException {
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            assertNull(b.flush(), "flushing an empty window must produce no shard");
        }
    }

    @Test
    void budgetTriggersFlushAndProducesOrderedNonOverlappingShards(@TempDir Path dir) throws IOException {
        // Tiny budget so almost every addClass overflows and flushes a 1-class shard.
        java.util.List<ShardCatalog.ShardEntry> metas = new java.util.ArrayList<>();
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L)) {
            for (int id = 0; id < 6; id++) {
                boolean flushed = b.addClass(id, "class c" + id + " { void method" + id + "() {} }");
                // With a 1-byte budget the very first non-empty class already exceeds it.
                assertTrue(flushed, "sub-budget class should have triggered a flush");
            }
            ShardCatalog.ShardEntry tail = b.flush();
            assertNull(tail, "all classes already flushed; final flush window is empty");
        }
        // Reconstruct the produced shards from disk via their deterministic names.
        int prevHi = Integer.MIN_VALUE;
        int shards = 0;
        for (int seq = 0; ; seq++) {
            Path f = dir.resolve(HASH + ".shard." + seq);
            if (!java.nio.file.Files.exists(f)) break;
            try (ContentShard shard = ContentShard.open(f)) {
                assertTrue(shard.idLo() > prevHi, "shard ranges must be strictly ordered and non-overlapping");
                prevHi = shard.idHi();
            }
            shards++;
        }
        assertEquals(6, shards, "each 1-class flush should have produced its own shard");
    }
}
