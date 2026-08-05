package com.zin.delamain.index;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.index.shard.ShardCatalog;
import com.zin.delamain.utils.ClassCacheManager;

import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H1: {@code startBackgroundTrigramBuild}/{@code startBackgroundShardBuild}'s inline thread lambdas
 * were extracted into package-private static methods ({@code runTrigramBuildLoop}/
 * {@code runShardBuildLoop}) precisely so the epoch gate ({@link WarmupManager#backgroundBuildStillValid})
 * can be exercised on the ACTUAL call path, not only as a standalone pure-function check (that check
 * lives in {@link WarmupManagerBackgroundBuildEpochGateTest} and remains valid — it verifies the gate
 * logic itself; this class verifies the gate is actually wired into the two build loops).
 *
 * <p>Runs one real cold warmup against the small {@code UnCrackable-Level1.apk} fixture (mirrors
 * {@code WarmupManagerNoPhase2Test}/{@code WarmupManagerIdSpaceOwnershipTest}) to obtain a genuine,
 * non-null {@code codeStore} and a real {@code currentInputHash}, then calls the extracted loop
 * methods DIRECTLY with a {@code builtForHash}/{@code hash} that does NOT match the live
 * {@code currentInputHash} — simulating a background build thread that is still running after a
 * newer APK has already loaded and started its own warmup — and asserts nothing gets published on
 * the stale build's behalf.</p>
 */
class WarmupManagerBackgroundBuildWiringTest {

    private boolean originalTrigramHeap;

    @BeforeEach
    void setUp() {
        ClassCacheManager.resetForTests();
        ContentShardIndex.clear();
        CodeContentIndex.clear();
        originalTrigramHeap = CodeContentIndex.TRIGRAM_HEAP;
    }

    @AfterEach
    void tearDown() {
        CodeContentIndex.TRIGRAM_HEAP = originalTrigramHeap;
        ClassCacheManager.resetForTests();
        ContentShardIndex.clear();
        CodeContentIndex.clear();
        WarmupManager.setIndexDir(new File(System.getProperty("java.io.tmpdir"), "delamain-index").toPath());
    }

    @Test
    void runTrigramBuildLoopDoesNotIndexForAStaleHash(@TempDir Path indexDir) throws Exception {
        // Force the heap trigram path on so tryIndexFromCache actually mutates state — otherwise
        // (default off, A1) this negative assertion would hold trivially regardless of the gate.
        CodeContentIndex.TRIGRAM_HEAP = true;

        HeadlessJadxWrapper wrapper = coldWarmup(indexDir);
        List<JavaClass> sorted = sortedClasses(wrapper);
        String realHash = WarmupManager.getCurrentInputHash();
        assertTrue(realHash != null && !realHash.isEmpty(), "real cold warmup must compute an input hash");

        // Clean slate: the real warmup's own Phase-2 already indexed everything (TRIGRAM_HEAP was
        // on for this run too). Reset the heap index before exercising the extracted loop directly,
        // restoring the id space via reassignIdsForCurrentApk() so resolveClass() stays intact.
        CodeContentIndex.clear();
        WarmupManager.reassignIdsForCurrentApk();
        assertEquals(0, CodeContentIndex.trigramCount(), "precondition: heap trigram index must start empty");

        // The stale build: captured a hash different from what is now current — must not index a
        // single class into the shared heap trigram state on the superseded APK's behalf.
        WarmupManager.runTrigramBuildLoop(wrapper, sorted, "stale-hash-does-not-match-" + realHash);

        assertEquals(0, CodeContentIndex.trigramCount(),
                "a trigram build loop invoked for a hash that is no longer current must not index "
                        + "ANY class into the shared heap trigram state — the epoch gate must stop it "
                        + "at the very first loop iteration");
        assertEquals(0, CodeContentIndex.indexedClassCount(),
                "no class may be marked as trigram-indexed by a build for a superseded hash");

        waitForBackgroundBuilds();
    }

    @Test
    void runTrigramBuildLoopIndexesWhenHashIsStillCurrent(@TempDir Path indexDir) throws Exception {
        CodeContentIndex.TRIGRAM_HEAP = true;

        HeadlessJadxWrapper wrapper = coldWarmup(indexDir);
        List<JavaClass> sorted = sortedClasses(wrapper);
        String realHash = WarmupManager.getCurrentInputHash();

        CodeContentIndex.clear();
        WarmupManager.reassignIdsForCurrentApk();

        // Positive control: same call, but builtForHash MATCHES the live currentInputHash — proves
        // the extracted method still does real work, so the negative test above is isolating the
        // gate rather than merely observing a method that never indexes anything.
        //
        // Asserting on trigramCount() rather than indexedClassCount(): the test surefire profile
        // caps MAX_TRIGRAMS at 5 (pom.xml DELAMAIN_CODE_INDEX_MAX_TRIGRAMS) to bound test memory,
        // so even a single real class's postings blow that budget and the class is un-marked as
        // "indexed" (see CodeContentIndex#index's cap-reached undo) — indexedClassCount() would
        // stay 0 here regardless of the gate. trigramCount() still moves off zero the moment ANY
        // trigram gets written, which is enough to prove the loop actually reached CodeContentIndex.
        WarmupManager.runTrigramBuildLoop(wrapper, sorted, realHash);

        assertTrue(CodeContentIndex.trigramCount() > 0,
                "a trigram build loop invoked for the STILL-current hash must actually write trigrams "
                        + "into the heap index — otherwise the negative test above would prove nothing");

        waitForBackgroundBuilds();
    }

    @Test
    void runShardBuildLoopDoesNotPublishCatalogForAStaleHash(@TempDir Path indexDir) throws Exception {
        HeadlessJadxWrapper wrapper = coldWarmup(indexDir);
        List<JavaClass> sorted = sortedClasses(wrapper);
        String realHash = WarmupManager.getCurrentInputHash();
        assertTrue(realHash != null && !realHash.isEmpty(), "real cold warmup must compute an input hash");
        waitForBackgroundBuilds(); // let the warmup's own (valid) shard build finish first

        String staleHash = "stale-hash-does-not-match-" + realHash;
        Path staleCatalog = ShardCatalog.catalogPath(indexDir, staleHash);
        assertFalse(Files.exists(staleCatalog), "precondition: no catalog for the made-up stale hash yet");

        // The stale build: captured a hash different from the live currentInputHash — must not
        // publish (write catalog / load into ContentShardIndex / write manifest) on its behalf, even
        // though the build loop itself runs to completion against the tiny fixture.
        WarmupManager.runShardBuildLoop(wrapper, sorted, staleHash);

        assertFalse(Files.exists(staleCatalog),
                "a shard build loop invoked for a hash that is no longer current must never write "
                        + "its catalog to disk — the epoch gate must discard the completed build "
                        + "instead of publishing it");
        assertFalse(Files.exists(indexDir.resolve(staleHash + ".manifest.json")),
                "must not write a prebaked-index manifest for a superseded/made-up hash either");
    }

    private HeadlessJadxWrapper coldWarmup(Path indexDir) throws Exception {
        WarmupManager.setIndexDir(indexDir);
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(apk), new File(indexDir.toFile(), "out"), 2);
        wrapper.load();

        Map<String, Object> result = WarmupManager.start(wrapper, false);
        assertTrue((Boolean) result.get("started"), "cold warmup must start: " + result);

        long deadline = System.currentTimeMillis() + 120_000;
        while (isWarmupRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertTrue(!isWarmupRunning(),
                "warmup did not reach DONE within timeout; status=" + WarmupManager.getStatus());
        return wrapper;
    }

    private static List<JavaClass> sortedClasses(HeadlessJadxWrapper wrapper) {
        List<JavaClass> sorted = new ArrayList<>(wrapper.getClassesWithInners());
        sorted.sort(Comparator.comparing(JavaClass::getRawName));
        return sorted;
    }

    private static void waitForBackgroundBuilds() {
        long deadline = System.currentTimeMillis() + 120_000;
        while ((WarmupManager.isShardBuildRunning() || WarmupManager.isTrigramBuildRunning()
                || WarmupManager.isUsePlacesHarvestRunning())
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        ContentShardIndex.clear();
    }

    private static boolean isWarmupRunning() {
        Object running = WarmupManager.getStatus().get("running");
        return Boolean.TRUE.equals(running);
    }
}
