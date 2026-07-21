package com.zin.delamain.index.shard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary / corruption / concurrency coverage for the mmap shard subsystem (W10). Complements
 * {@link ContentShardBuilderTest} and {@link ContentShardIndexTest}, which cover the happy-path
 * build/query soundness contract. This class only exercises failure and edge inputs — malformed
 * files, missing files, empty windows, id-range boundaries and concurrent readers/writers — and
 * must never touch covered/candidates soundness semantics.
 */
class ContentShardRobustnessTest {

    private static final String HASH = "aabbccddeeff0011";

    @BeforeEach
    void reset() {
        ContentShardIndex.clear();
    }

    @AfterEach
    void tearDown() {
        ContentShardIndex.clear();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static long crc32(Path file) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(Files.readAllBytes(file));
        return crc.getValue();
    }

    /** Flips one byte at {@code offset} (bitwise NOT), invalidating whatever checksum covered it. */
    private static void flipByte(Path file, int offset) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        bytes[offset] = (byte) ~bytes[offset];
        Files.write(file, bytes);
    }

    /** Overwrites the big-endian int header field at {@code offset} with {@code value}. */
    private static void patchHeaderInt(Path file, int offset, int value) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(offset, value);
        Files.write(file, bytes);
    }

    private static void patchHeaderLong(Path file, int offset, long value) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putLong(offset, value);
        Files.write(file, bytes);
    }

    /** Builds two flushed shards over disjoint id ranges [0,idSplit] and [idSplit+2, idSplit+2+span]. */
    private static List<ShardCatalog.ShardEntry> buildTwoShards(Path dir, int gap) throws IOException {
        List<ShardCatalog.ShardEntry> entries = new ArrayList<>();
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            b.addClass(0, "class a { void render() {} }");
            b.addClass(1, "class b { void render() {} }");
            ShardCatalog.ShardEntry m1 = b.flush();
            assertNotNull(m1);
            entries.add(m1);

            int base = 1 + gap + 1;
            b.addClass(base, "class c { void update() {} }");
            b.addClass(base + 1, "class d { void update() {} }");
            ShardCatalog.ShardEntry m2 = b.flush();
            assertNotNull(m2);
            entries.add(m2);
        }
        return entries;
    }

    // ---- 1. corrupted shard (CRC mismatch) skipped, other shards still usable -----------------

    @Test
    void corruptedShardIsSkippedOtherShardsRemainUsable(@TempDir Path dir) throws IOException {
        List<ShardCatalog.ShardEntry> entries = buildTwoShards(dir, 0);
        ShardCatalog.ShardEntry shard0 = entries.get(0);
        ShardCatalog.ShardEntry shard1 = entries.get(1);
        ShardCatalog.write(dir, HASH, entries);

        // Flip a byte well past the header (inside the covered-bitmap/dict/blob region) in shard0.
        Path f0 = dir.resolve(shard0.fileName);
        flipByte(f0, ContentShard.HEADER_LEN);

        ContentShardIndex.loadCatalog(dir, HASH);

        assertTrue(ContentShardIndex.isBuilt(), "the other, uncorrupted shard must still load");
        Map<String, Object> stats = ContentShardIndex.getStats();
        assertEquals(1, ((Number) stats.get("shard_count")).intValue(),
                "exactly one of the two shards should have loaded");

        assertFalse(ContentShardIndex.isCovered(shard0.idLo),
                "ids owned by the corrupted shard must not be covered");
        assertTrue(ContentShardIndex.isCovered(shard1.idLo),
                "ids owned by the healthy shard must remain covered");

        TermLookupResult r = ContentShardIndex.candidatesForTerm("update");
        assertTrue(r.covered.contains(shard1.idLo), "healthy shard must still answer queries");
        assertFalse(r.covered.contains(shard0.idLo));
    }

    // ---- 2. catalog lists a shard whose file is missing from disk -----------------------------

    @Test
    void missingShardFileIsSkippedOtherShardsRemainUsable(@TempDir Path dir) throws IOException {
        List<ShardCatalog.ShardEntry> entries = buildTwoShards(dir, 0);
        ShardCatalog.ShardEntry shard0 = entries.get(0);
        ShardCatalog.ShardEntry shard1 = entries.get(1);
        ShardCatalog.write(dir, HASH, entries);

        Files.delete(dir.resolve(shard0.fileName));

        ContentShardIndex.loadCatalog(dir, HASH);

        assertTrue(ContentShardIndex.isBuilt());
        assertEquals(1, ((Number) ContentShardIndex.getStats().get("shard_count")).intValue());
        assertFalse(ContentShardIndex.isCovered(shard0.idLo));
        assertTrue(ContentShardIndex.isCovered(shard1.idLo));
    }

    // ---- 3. truncated / undersized file -> ContentShard.open throws IOException, no mis-slice --

    @Test
    void fileSmallerThanHeaderFailsToOpen(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("tiny.shard");
        Files.write(f, new byte[ContentShard.HEADER_LEN - 1]);
        assertThrows(IOException.class, () -> ContentShard.open(f),
                "a file shorter than the fixed header must never be mis-sliced");
    }

    @Test
    void headerDeclaringOutOfBoundsRegionFailsToOpen(@TempDir Path dir) throws IOException {
        List<ShardCatalog.ShardEntry> entries = new ArrayList<>();
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            b.addClass(0, "class a { void render() {} }");
            entries.add(b.flush());
        }
        Path f = dir.resolve(entries.get(0).fileName);
        // dictOffset field lives at header byte offset 48 (see ContentShard header layout);
        // push it far past the actual file size.
        long size = Files.size(f);
        patchHeaderLong(f, 48, size + 1_000_000L);
        assertThrows(IOException.class, () -> ContentShard.open(f),
                "an out-of-bounds region declared in the header must fail loudly, not silently mis-slice");
    }

    // ---- 4. bad magic / unsupported version -> IOException -------------------------------------

    @Test
    void badMagicFailsToOpen(@TempDir Path dir) throws IOException {
        List<ShardCatalog.ShardEntry> entries = new ArrayList<>();
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            b.addClass(0, "class a { void render() {} }");
            entries.add(b.flush());
        }
        Path f = dir.resolve(entries.get(0).fileName);
        patchHeaderInt(f, 0, 0xDEADBEEF);
        assertThrows(IOException.class, () -> ContentShard.open(f));
    }

    @Test
    void unsupportedVersionFailsToOpen(@TempDir Path dir) throws IOException {
        List<ShardCatalog.ShardEntry> entries = new ArrayList<>();
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            b.addClass(0, "class a { void render() {} }");
            entries.add(b.flush());
        }
        Path f = dir.resolve(entries.get(0).fileName);
        patchHeaderInt(f, 4, 99);
        assertThrows(IOException.class, () -> ContentShard.open(f));
    }

    /**
     * A shard file can be structurally invalid (bad magic/version/out-of-bounds header) while its
     * own checksum is internally consistent — e.g. a stale shard produced by a schema migration,
     * or a hand-off between two incompatible builds sharing an index dir. loadCatalog must skip
     * such a shard and keep loading the rest instead of letting the whole catalog load fail.
     */
    @Test
    void structurallyInvalidShardWithMatchingChecksumIsSkippedNotFatal(@TempDir Path dir) throws IOException {
        List<ShardCatalog.ShardEntry> entries = buildTwoShards(dir, 0);
        ShardCatalog.ShardEntry shard0 = entries.get(0);
        ShardCatalog.ShardEntry shard1 = entries.get(1);

        Path f0 = dir.resolve(shard0.fileName);
        patchHeaderInt(f0, 4, 99); // unsupported version, but re-checksummed below so CRC still matches
        long fixedChecksum = crc32(f0);
        ShardCatalog.ShardEntry patched = new ShardCatalog.ShardEntry(
                shard0.seq, shard0.idLo, shard0.idHi, shard0.trigramCount, fixedChecksum, shard0.fileName);

        List<ShardCatalog.ShardEntry> patchedEntries = new ArrayList<>();
        patchedEntries.add(patched);
        patchedEntries.add(shard1);
        ShardCatalog.write(dir, HASH, patchedEntries);

        // Must not throw, and must still load the healthy second shard.
        ContentShardIndex.loadCatalog(dir, HASH);

        assertTrue(ContentShardIndex.isBuilt(), "the healthy shard must still load");
        assertEquals(1, ((Number) ContentShardIndex.getStats().get("shard_count")).intValue());
        assertFalse(ContentShardIndex.isCovered(shard0.idLo),
                "the structurally invalid shard must not be covered");
        assertTrue(ContentShardIndex.isCovered(shard1.idLo));
    }

    // ---- 5. empty shard (only markExcluded, no covered classes) --------------------------------

    @Test
    void shardWithOnlyExcludedIdsHasEmptyCoverageAndDoesNotCrash(@TempDir Path dir) throws IOException {
        ShardCatalog.ShardEntry meta;
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            b.markExcluded(0);
            b.markExcluded(1);
            meta = b.flush();
        }
        assertNotNull(meta, "a window with only excluded ids still has a non-empty id range and must flush");
        assertEquals(0, meta.trigramCount);

        ShardCatalog.write(dir, HASH, List.of(meta));
        ContentShardIndex.loadCatalog(dir, HASH);

        assertTrue(ContentShardIndex.isBuilt());
        assertFalse(ContentShardIndex.isCovered(0));
        assertFalse(ContentShardIndex.isCovered(1));

        TermLookupResult r = ContentShardIndex.candidatesForTerm("render");
        assertTrue(r.covered.isEmpty(), "no covered ids exist in an all-excluded shard");
        assertTrue(r.candidates.isEmpty());
    }

    // ---- 6. concurrent candidatesForTerm + tombstone -------------------------------------------

    @Test
    void concurrentQueriesAndTombstonesDoNotThrowAndConverge(@TempDir Path dir) throws Exception {
        List<ShardCatalog.ShardEntry> entries = new ArrayList<>();
        int n = 200;
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            for (int id = 0; id < n; id++) {
                b.addClass(id, "class c" + id + " { void render" + id + "() {} }");
            }
            ShardCatalog.ShardEntry m = b.flush();
            assertNotNull(m);
            entries.add(m);
        }
        ShardCatalog.write(dir, HASH, entries);
        ContentShardIndex.loadCatalog(dir, HASH);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        ContentShardIndex.candidatesForTerm("render" + (i % n));
                        ContentShardIndex.isCovered(i % n);
                        if (i % 50 == 0) {
                            ContentShardIndex.tombstone((tid * 37 + i) % n);
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "worker threads must finish promptly");
        if (failure.get() != null) {
            throw new AssertionError("concurrent read/write must not throw", failure.get());
        }

        // Every id tombstoned by any thread must now be uncovered and never a candidate.
        for (int id = 0; id < n; id++) {
            TermLookupResult r = ContentShardIndex.candidatesForTerm("render" + id);
            if (!ContentShardIndex.isCovered(id)) {
                assertFalse(r.covered.contains(id), "tombstoned id must not reappear as covered");
                assertFalse(r.candidates.contains(id), "tombstoned id must never be a candidate");
            }
        }
    }

    // ---- 7. id boundaries: idLo/idHi exact, and the gap between two shards --------------------

    @Test
    void idExactlyAtShardBoundaryIsCoveredGapIdIsNot(@TempDir Path dir) throws IOException {
        // shard 0 covers ids 0..1, shard 1 covers ids 5..6 -> ids 2,3,4 are a gap never handed
        // to any builder call, so no shard has authority over them.
        int gap = 3;
        List<ShardCatalog.ShardEntry> entries = buildTwoShards(dir, gap);
        ShardCatalog.ShardEntry shard0 = entries.get(0);
        ShardCatalog.ShardEntry shard1 = entries.get(1);
        ShardCatalog.write(dir, HASH, entries);
        ContentShardIndex.loadCatalog(dir, HASH);

        assertTrue(ContentShardIndex.isCovered(shard0.idLo), "idLo boundary of shard 0 must be covered");
        assertTrue(ContentShardIndex.isCovered(shard0.idHi), "idHi boundary of shard 0 must be covered");
        assertTrue(ContentShardIndex.isCovered(shard1.idLo), "idLo boundary of shard 1 must be covered");
        assertTrue(ContentShardIndex.isCovered(shard1.idHi), "idHi boundary of shard 1 must be covered");

        for (int gapId = shard0.idHi + 1; gapId < shard1.idLo; gapId++) {
            assertFalse(ContentShardIndex.isCovered(gapId),
                    "id " + gapId + " falls in the gap between shards and must not be covered");
        }

        TermLookupResult r = ContentShardIndex.candidatesForTerm("render");
        for (int gapId = shard0.idHi + 1; gapId < shard1.idLo; gapId++) {
            assertFalse(r.covered.contains(gapId));
            assertFalse(r.definitivelyAbsent(gapId), "an uncovered gap id must never be a definitive negative");
        }
    }

    // ---- 8. per-shard definitive negative when a term's trigram is absent from that shard's dict ----

    @Test
    void termAbsentFromOneShardsDictIsDefinitiveNegativeThereButNotElsewhere(@TempDir Path dir) throws IOException {
        List<ShardCatalog.ShardEntry> entries = new ArrayList<>();
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            b.addClass(0, "class a { void render() {} }"); // shard 0: has "render"
            entries.add(b.flush());
            b.addClass(1, "class b { void update() {} }"); // shard 1: no "render" at all
            entries.add(b.flush());
        }
        ShardCatalog.write(dir, HASH, entries);
        ContentShardIndex.loadCatalog(dir, HASH);

        TermLookupResult r = ContentShardIndex.candidatesForTerm("render");
        assertTrue(r.covered.contains(0));
        assertTrue(r.covered.contains(1));
        assertTrue(r.candidates.contains(0), "shard 0 truly contains the term");
        assertFalse(r.candidates.contains(1), "shard 1's dictionary lacks the trigram: definitive negative");
        assertTrue(r.definitivelyAbsent(1));
        assertFalse(r.definitivelyAbsent(0));
    }

    // ---- 9. clear() resets to a safe, non-crashing empty state ---------------------------------

    @Test
    void clearResetsToEmptyStateWithoutCrashing(@TempDir Path dir) throws IOException {
        List<ShardCatalog.ShardEntry> entries = new ArrayList<>();
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            b.addClass(0, "class a { void render() {} }");
            entries.add(b.flush());
        }
        ShardCatalog.write(dir, HASH, entries);
        ContentShardIndex.loadCatalog(dir, HASH);
        assertTrue(ContentShardIndex.isBuilt());

        ContentShardIndex.clear();

        assertFalse(ContentShardIndex.isBuilt());
        assertFalse(ContentShardIndex.isCovered(0));
        TermLookupResult r = ContentShardIndex.candidatesForTerm("render");
        assertTrue(r.covered.isEmpty());
        assertTrue(r.candidates.isEmpty());
        assertTrue(RoaringBitmap.andNot(r.candidates, r.covered).isEmpty());
    }
}
