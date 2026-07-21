package com.zin.delamain.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red-then-green coverage for {@link IndexCacheManager}'s disk-cache LRU eviction.
 *
 * <p>Each test builds fake per-inputHash artifact groups under a {@code @TempDir} index root
 * (code/{hash8}/ dir + sidecar files of a controlled size) with a controlled {@code .lastused}
 * mtime, then calls {@link IndexCacheManager#enforceQuota(Path, String, long)} and asserts on
 * what survives.</p>
 */
class IndexCacheManagerTest {

    private static final long MB = 1024L * 1024L;

    /** Creates a fake artifact group of approximately {@code sizeBytes} total, {@code ageMinutesAgo} old. */
    private void makeGroup(Path indexDir, String hash, long sizeBytes, long ageMinutesAgo) throws IOException {
        Path codeDir = indexDir.resolve("code").resolve(hash.substring(0, 8));
        Files.createDirectories(codeDir);
        Path blob = codeDir.resolve("blob.gz");
        try (RandomAccessFile raf = new RandomAccessFile(blob.toFile(), "rw")) {
            raf.setLength(sizeBytes);
        }
        Files.write(indexDir.resolve(hash + ".idx"), new byte[16]);
        Files.write(indexDir.resolve(hash + ".graph"), new byte[16]);
        Files.write(indexDir.resolve(hash + ".useplaces"), new byte[16]);
        Files.write(indexDir.resolve(hash + ".shard.0"), new byte[16]);
        Files.write(indexDir.resolve(hash + ".shardcat"), new byte[16]);
        Files.write(indexDir.resolve(hash + ".manifest.json"), new byte[16]);

        Path lastUsed = indexDir.resolve(hash + ".lastused");
        Files.write(lastUsed, new byte[0]);
        FileTime ft = FileTime.from(Instant.now().minus(ageMinutesAgo, ChronoUnit.MINUTES));
        Files.setLastModifiedTime(lastUsed, ft);
    }

    private boolean groupExists(Path indexDir, String hash) {
        return Files.exists(indexDir.resolve("code").resolve(hash.substring(0, 8)))
                || Files.exists(indexDir.resolve(hash + ".idx"))
                || Files.exists(indexDir.resolve(hash + ".lastused"));
    }

    @Test
    void evictsLeastRecentlyUsedGroupFirst(@TempDir Path indexDir) throws IOException {
        // Two groups of 30MB each -> total 60MB, quota 50MB (low water 40MB).
        makeGroup(indexDir, "aaaaaaaa1111111111111111111111111111111111111111111111111111", 30 * MB, 100);
        makeGroup(indexDir, "bbbbbbbb2222222222222222222222222222222222222222222222222222", 30 * MB, 5);

        IndexCacheManager.enforceQuota(indexDir, null, 50 * MB);

        assertFalse(groupExists(indexDir, "aaaaaaaa1111111111111111111111111111111111111111111111111111"),
                "oldest (least recently used) group must be evicted");
        assertTrue(groupExists(indexDir, "bbbbbbbb2222222222222222222222222222222222222222222222222222"),
                "recently used group must be retained");
    }

    @Test
    void neverEvictsCurrentlyLoadedHash(@TempDir Path indexDir) throws IOException {
        String current = "cccccccc3333333333333333333333333333333333333333333333333333";
        String other = "dddddddd4444444444444444444444444444444444444444444444444444";
        // current is the OLDEST by lastused, other is newer -- naive LRU would pick current first.
        makeGroup(indexDir, current, 30 * MB, 1000);
        makeGroup(indexDir, other, 30 * MB, 500);

        IndexCacheManager.enforceQuota(indexDir, current, 50 * MB);

        assertTrue(groupExists(indexDir, current), "the currently loaded inputHash must never be deleted");
        assertFalse(groupExists(indexDir, other), "the non-current oldest-remaining group should be evicted instead");
    }

    @Test
    void stopsEvictingOnceLowWaterMarkReached(@TempDir Path indexDir) throws IOException {
        // Three 20MB groups (distinct 8-char prefixes so their code/ dirs don't collide) = ~60MB
        // total. Quota 60MB -> low water 48MB. Evicting just the single oldest (~20MB) brings
        // total to ~40MB <= low water, so only one is removed.
        makeGroup(indexDir, "e1111111111111111111111111111111111111111111111111111111111e1", 20 * MB, 300);
        makeGroup(indexDir, "e2222222222222222222222222222222222222222222222222222222222e2", 20 * MB, 200);
        makeGroup(indexDir, "e3333333333333333333333333333333333333333333333333333333333e3", 20 * MB, 100);

        IndexCacheManager.enforceQuota(indexDir, null, 60 * MB);

        int remaining = 0;
        for (String h : List.of(
                "e1111111111111111111111111111111111111111111111111111111111e1",
                "e2222222222222222222222222222222222222222222222222222222222e2",
                "e3333333333333333333333333333333333333333333333333333333333e3")) {
            if (groupExists(indexDir, h)) remaining++;
        }
        assertEquals(2, remaining, "eviction should stop as soon as the low-water mark is reached");
        assertFalse(groupExists(indexDir, "e1111111111111111111111111111111111111111111111111111111111e1"),
                "the oldest group must be the one evicted");
    }

    @Test
    void disabledWhenMaxBytesIsZero(@TempDir Path indexDir) throws IOException {
        makeGroup(indexDir, "f0000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 100 * MB, 100);

        IndexCacheManager.enforceQuota(indexDir, null, 0L);

        assertTrue(groupExists(indexDir, "f0000000aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                "maxBytes=0 must disable eviction entirely");
    }

    @Test
    void doesNotEvictWhenUnderQuota(@TempDir Path indexDir) throws IOException {
        makeGroup(indexDir, "1a1a1a1a2222222222222222222222222222222222222222222222222222", 10 * MB, 1000);

        IndexCacheManager.enforceQuota(indexDir, null, 50 * MB);

        assertTrue(groupExists(indexDir, "1a1a1a1a2222222222222222222222222222222222222222222222222222"),
                "total size within quota must not trigger any eviction");
    }

    @Test
    void touchLastUsedCreatesFreshFile(@TempDir Path indexDir) throws IOException {
        String hash = "abc123abc1230000000000000000000000000000000000000000000000000000";
        IndexCacheManager.touchLastUsed(indexDir, hash);
        Path p = indexDir.resolve(hash + ".lastused");
        assertTrue(Files.exists(p), "touchLastUsed must create the .lastused marker");
    }

    @Test
    void resolveMaxBytesDefaultsTo50Gb() {
        // No env var set in the test environment for JADX_CACHE_MAX_GB -> default 50GB.
        String env = System.getenv("JADX_CACHE_MAX_GB");
        if (env == null || env.isEmpty()) {
            assertEquals(50L * 1024 * 1024 * 1024, IndexCacheManager.resolveMaxBytes());
        }
    }
}
