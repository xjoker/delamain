package com.zin.delamain.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 fix: {@link CodeStore#put} failures used to be swallowed at debug level with no aggregate
 * signal, and {@link CodeStore#markComplete} would still write the {@code .complete} marker even
 * if every single put had failed — a future restart would then FAST_RESTORE onto a store that
 * serves almost nothing from {@link CodeStore#get}, with no diagnostic trail explaining why.
 *
 * <p>Forces a real put failure deterministically by pre-occupying the exact on-disk shard directory
 * a class name's path hashes into with a REGULAR FILE, so {@code Files.createDirectories} inside
 * {@code put()} throws — no filesystem permission tricks needed.
 */
class CodeStorePutFailureTest {

    private static final String HASH = "ffeeddccbbaa0011";

    @Test
    void putReturnsFalseAndIsCountedWhenWriteFails(@TempDir Path dir) throws IOException {
        CodeStore store = new CodeStore(dir, HASH);

        // One healthy put succeeds normally.
        assertTrue(store.put("a.Good", "class Good { void x() {} }"));
        assertEquals(1, store.successfulPutCount());
        assertEquals(0, store.putFailureCount());

        // Sabotage: pre-create a REGULAR FILE at the exact shard bucket directory "a.Sabotaged"
        // hashes into, so put()'s Files.createDirectories(p.getParent()) throws.
        String className = "a.Sabotaged";
        Path shardBucketDir = shardBucketDirFor(dir, className);
        Files.createDirectories(shardBucketDir.getParent());
        Files.write(shardBucketDir, new byte[]{0}); // regular file occupying the directory's path

        boolean putSucceeded = store.put(className, "class Sabotaged { }");

        assertFalse(putSucceeded, "put() must return false when the on-disk write fails");
        assertEquals(1, store.successfulPutCount(), "the earlier healthy put must still be counted");
        assertEquals(1, store.putFailureCount(), "the sabotaged put must be counted as a failure");
    }

    @Test
    void markCompleteRefusesWhenTooManyPutsFailed(@TempDir Path dir) throws IOException {
        CodeStore store = new CodeStore(dir, HASH);

        // 1 successful put out of an attempted count of 10 -> a 10% success ratio, far below the
        // 99% threshold. Simulates a systemic write failure (disk full/permissions) where the
        // caller (WarmupManager) attempted 10 puts but almost all of them failed.
        assertTrue(store.put("a.OnlyOne", "class OnlyOne { }"));

        boolean marked = store.markComplete(10);

        assertFalse(marked, "markComplete must refuse when far fewer puts succeeded than attempted");
        assertFalse(store.isComplete(),
                "no .complete marker may exist after a refused markComplete — a restart must re-run "
                        + "Phase-1 rather than FAST_RESTORE onto a near-empty store");
    }

    @Test
    void markCompleteSucceedsWhenAllAttemptedPutsSucceeded(@TempDir Path dir) throws IOException {
        CodeStore store = new CodeStore(dir, HASH);
        store.put("a.B", "class B { void x() {} }");
        store.put("a.C", "class C { void y() {} }");

        boolean marked = store.markComplete(2);

        assertTrue(marked, "a fully successful batch must still be able to mark complete");
        assertTrue(store.isComplete());
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Mirrors {@code CodeStore#pathFor}'s shard bucket directory ({@code {base}/code/{hash8}/{xx}}). */
    private static Path shardBucketDirFor(Path indexDir, String className) {
        String h = sha256Hex(className);
        return indexDir.resolve("code").resolve(HASH.substring(0, 8)).resolve(h.substring(0, 2));
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
