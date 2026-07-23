package com.zin.delamain.index;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.utils.ClassCacheManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1: with the heap trigram index OFF ({@code TRIGRAM_HEAP == false}) a full cold warmup must skip
 * Phase-2 (trigram fill) entirely — no BitSet is ever pushed to the heap — while still running
 * {@code preAssignIds}, so the id machine the shard coverage judgment depends on stays populated.
 *
 * <p>Runs a real cold {@link WarmupManager#start} against a small test APK in a brand-new
 * {@code @TempDir} index dir (guaranteeing the COLD branch of {@code runWarmup}), mirroring
 * {@code WarmupManagerColdShardBuildTest}. After warmup DONE it asserts the two halves of the
 * split: {@code trigramCount() == 0} (Phase-2 skipped, heap trigram empty) AND
 * {@code resolveClass(0) != null} (preAssignIds ran, id machine intact).</p>
 */
class WarmupManagerNoPhase2Test {

    private boolean originalTrigramHeap;

    @BeforeEach
    void disableTrigramHeap() {
        // ClassCacheManager.cacheOwnerKey is process-global static and leaks between tests in the
        // shared JVM. If a prior test left a non-empty owner, this test's warmup-triggered initCache
        // sees a foreign owner and fires a "project owner changed" clear that wipes the id machine
        // AFTER preAssignIds — nulling resolveClass(0) mid-test. Reset to the clean baseline so the
        // owner starts empty and no project-switch clear fires (makes the assertion order-independent).
        ClassCacheManager.resetForTests();
        originalTrigramHeap = CodeContentIndex.TRIGRAM_HEAP;
        CodeContentIndex.TRIGRAM_HEAP = false;
        CodeContentIndex.clear();
    }

    @AfterEach
    void tearDown() {
        ContentShardIndex.clear();
        CodeContentIndex.clear();
        ClassCacheManager.resetForTests(); // don't leak this test's owner to the next test
        CodeContentIndex.TRIGRAM_HEAP = originalTrigramHeap;
        WarmupManager.setIndexDir(new File(System.getProperty("java.io.tmpdir"), "delamain-index").toPath());
    }

    @Test
    void coldWarmupSkipsPhase2ButKeepsIdMachine(@TempDir Path indexDir) throws Exception {
        WarmupManager.setIndexDir(indexDir);

        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        File outDir = new File(indexDir.toFile(), "out");

        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(apk), outDir, 2);
        wrapper.load();

        Map<String, Object> result = WarmupManager.start(wrapper, false);
        assertTrue((Boolean) result.get("started"), "cold warmup must start: " + result);

        long deadline = System.currentTimeMillis() + 120_000;
        while (isWarmupRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertTrue(!isWarmupRunning(),
                "warmup did not reach DONE within timeout; status=" + WarmupManager.getStatus());

        // Wait out the background shard build + use-places harvest (they keep running after warmup
        // reaches DONE and hold mmap handles inside the @TempDir); otherwise JUnit cannot delete it.
        while ((WarmupManager.isShardBuildRunning() || WarmupManager.isUsePlacesHarvestRunning())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        ContentShardIndex.clear(); // unmap shard files so the @TempDir can be deleted after the test

        assertEquals(0, CodeContentIndex.trigramCount(),
                "Phase-2 must be skipped with TRIGRAM_HEAP off — no heap trigram BitSet may be built");
        assertEquals(0, CodeContentIndex.indexedClassCount(),
                "no class may be marked trigram-built when Phase-2 is skipped");
        assertNotNull(CodeContentIndex.resolveClass(0),
                "preAssignIds must still run with TRIGRAM_HEAP off — resolveClass(0) must be non-null "
                        + "(the shard coverage judgment depends on the id machine)");
    }

    private static boolean isWarmupRunning() {
        Object running = WarmupManager.getStatus().get("running");
        return Boolean.TRUE.equals(running);
    }
}
