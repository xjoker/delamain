package com.zin.delamain.index;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.utils.ClassCacheManager;

import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F7: the {@code ClassIdRegistry} id space (class &lt;-&gt; id, the contract the mmap shard layer's
 * classId depends on — see {@code ClassIdRegistry.java}'s class doc) has three independent paths
 * that can clear it out from under an already-served/being-built shard. This class covers path (A)
 * and gives (B) a focused unit-level check of the restore mechanism both paths share.
 *
 * <p>Style mirrors {@code WarmupManagerNoPhase2Test} / {@code ClassCacheManagerInvalidateCodeStoreTest}:
 * a real cold warmup against the small {@code UnCrackable-Level1.apk} test fixture, run in-process.
 */
class WarmupManagerIdSpaceOwnershipTest {

    @BeforeEach
    void setUp() {
        ClassCacheManager.resetForTests();
        ContentShardIndex.clear();
        ClassIdRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        ClassCacheManager.resetForTests();
        ContentShardIndex.clear();
        ClassIdRegistry.clear();
        WarmupManager.setIndexDir(new File(System.getProperty("java.io.tmpdir"), "delamain-index").toPath());
    }

    /**
     * Path (A): an owner-rotate clear firing DURING a real warmup run must not lose the id space.
     *
     * <p>Reproduces the exact production timeline: a first wrapper (w1) establishes a project
     * owner (no clear yet — {@code cacheOwnerKey} starts empty). {@code WarmupManager.start(w2,...)}
     * then runs a real warmup for a SECOND wrapper instance (w2) over the same APK; near the end of
     * that run it calls {@code ClassCacheManager.initCache(w2)}, which sees a DIFFERENT owner
     * (buildCacheOwnerKey includes {@code System.identityHashCode(wrapper)}, so w1 != w2 even over
     * the same file) and fires the "project owner changed" clear — wiping the ids w2's own warmup
     * JUST preAssigned for w2's classes and used to build w2's shard, all within the same run.
     *
     * <p>Before the F7 fix: {@code resolveClass(0)} returns null after this (or, worse, some other
     * class) instead of {@code sorted.get(0)} — a real search would silently drop or misresolve
     * every shard candidate for the rest of the session.
     */
    @Test
    void ownerRotateDuringWarmupPreservesIdSpace(@TempDir Path indexDir) throws Exception {
        WarmupManager.setIndexDir(indexDir);

        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());

        HeadlessJadxWrapper w1 = new HeadlessJadxWrapper(List.of(apk), new File(indexDir.toFile(), "out1"), 2);
        w1.load();
        ClassCacheManager.initCache(w1); // establishes the FIRST owner; previousOwner was empty, no clear

        HeadlessJadxWrapper w2 = new HeadlessJadxWrapper(List.of(apk), new File(indexDir.toFile(), "out2"), 2);
        w2.load();

        // w2's own warmup run calls ClassCacheManager.initCache(w2) internally near the end of
        // runWarmup — a DIFFERENT wrapper instance over the same file is a different owner key, so
        // this triggers the real "project owner changed" clear mid-run.
        Map<String, Object> result = WarmupManager.start(w2, false);
        assertTrue((Boolean) result.get("started"), "cold warmup must start: " + result);

        long deadline = System.currentTimeMillis() + 120_000;
        while (isWarmupRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertTrue(!isWarmupRunning(),
                "warmup did not reach DONE within timeout; status=" + WarmupManager.getStatus());

        List<JavaClass> sorted = new ArrayList<>(w2.getClassesWithInners());
        sorted.sort(Comparator.comparing(JavaClass::getRawName));
        assertTrue(sorted.size() >= 4, "test APK must yield at least 4 classes");

        for (int i = 0; i < sorted.size(); i++) {
            assertSame(sorted.get(i), ClassIdRegistry.resolveClass(i),
                    "id " + i + ": after the mid-warmup owner-rotate clear, the id space must still "
                            + "resolve to w2's own class — not null (dropped results) and not a "
                            + "different class (misresolved results)");
        }

        // Wait out the background shard build so the @TempDir can be deleted afterward.
        while (WarmupManager.isShardBuildRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        ContentShardIndex.clear();
    }

    /**
     * Path (B), unit-level: the restore mechanism ({@code WarmupManager.reassignIdsForCurrentApk},
     * called immediately after WarmupManager's own Phase-4 heap-pressure {@code ClassIdRegistry.clear()})
     * reproduces the EXACT same id&lt;-&gt;class mapping a real warmup preAssigned, given a real
     * warmup ran first so the restore snapshot is genuinely populated (not test-seeded).
     *
     * <p>This does not drive the real {@code harvestAndPersistUsePlaces} background thread — forcing
     * its internal heap-pressure branch deterministically in a test is impractical (see the F7 task
     * report's "端到端未验证" note) — but it exercises the actual production restore call the Phase-4
     * fix added, fed by ids a REAL warmup captured, which is the exact invariant that fix depends on.
     */
    @Test
    void restoreAfterClearReproducesExactWarmupAssignment(@TempDir Path indexDir) throws Exception {
        WarmupManager.setIndexDir(indexDir);

        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(apk), new File(indexDir.toFile(), "out"), 2);
        wrapper.load();

        Map<String, Object> result = WarmupManager.start(wrapper, false);
        assertTrue((Boolean) result.get("started"), "cold warmup must start: " + result);

        long deadline = System.currentTimeMillis() + 120_000;
        while (isWarmupRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertTrue(!isWarmupRunning(), "warmup did not reach DONE within timeout");

        List<JavaClass> sorted = new ArrayList<>(wrapper.getClassesWithInners());
        sorted.sort(Comparator.comparing(JavaClass::getRawName));
        assertTrue(sorted.size() >= 4, "test APK must yield at least 4 classes");

        // Precondition: the real warmup's own preAssignIds already gives us this mapping.
        for (int i = 0; i < sorted.size(); i++) {
            assertSame(sorted.get(i), ClassIdRegistry.resolveClass(i), "precondition id " + i);
        }

        // Simulate WarmupManager.java's Phase-4 heap-pressure clear (line ~814-820): an external
        // ClassIdRegistry.clear() firing mid-session. Immediately after, the fixed production code
        // calls WarmupManager.reassignIdsForCurrentApk() (public API) — call the SAME method here.
        ClassIdRegistry.clear();
        for (int i = 0; i < sorted.size(); i++) {
            assertFalse(ClassIdRegistry.resolveClass(i) == sorted.get(i),
                    "sanity: clear() must actually wipe the mapping before we test the restore");
        }
        WarmupManager.reassignIdsForCurrentApk();

        for (int i = 0; i < sorted.size(); i++) {
            assertSame(sorted.get(i), ClassIdRegistry.resolveClass(i),
                    "id " + i + ": reassignIdsForCurrentApk() must reproduce EXACTLY the same "
                            + "id<->class mapping the warmup originally built, so a background "
                            + "trigram rebuild's getOrAssignId sees every class already seeded "
                            + "(ALREADY_INDEXED) instead of drifting to gap-compacted ids");
        }

        while (WarmupManager.isShardBuildRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        ContentShardIndex.clear();
    }

    private static boolean isWarmupRunning() {
        Object running = WarmupManager.getStatus().get("running");
        return Boolean.TRUE.equals(running);
    }
}
