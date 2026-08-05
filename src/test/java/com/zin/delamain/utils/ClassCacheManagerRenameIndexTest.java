package com.zin.delamain.utils;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.shard.ContentShardIndex;

import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rename-perf fix (P1): {@code ClassCacheManager.reindex()}/{@code removeFromIndex()} used to scan
 * every bucket of the class/method/field name indices (O(N buckets)) to remove a single renamed
 * class, and {@code reindexRenamedClass} copied the ENTIRE {@code classCache} map on every call. A
 * batch rename of M classes therefore cost O(M×N). This adds a per-class reverse index (bucket keys
 * a class currently occupies) so removal is O(k), and a {@link ClassCacheManager#reindexRenamedClassesBatch}
 * that publishes exactly one {@code classCache} snapshot per batch.
 *
 * <p>This is a CORRECTNESS test of the refactor (via {@code ClassCacheManager}'s public API only —
 * the reverse-index fields are private implementation detail) rather than a timing/perf assertion,
 * which would be flaky. Uses the small {@code UnCrackable-Level1.apk} test fixture, mirroring
 * {@code ClassCacheManagerInvalidateCodeStoreTest}.
 */
class ClassCacheManagerRenameIndexTest {

    @BeforeEach
    void setUp() {
        ClassCacheManager.resetForTests();
        ContentShardIndex.clear();
        CodeContentIndex.clear();
    }

    @AfterEach
    void tearDown() {
        ClassCacheManager.resetForTests();
        ContentShardIndex.clear();
        CodeContentIndex.clear();
    }

    @Test
    void singleRenameMovesClassBetweenBucketsAndUpdatesClassCache(@TempDir File tempDir) throws Exception {
        HeadlessJadxWrapper wrapper = loadTestApk(tempDir);
        Map<String, JavaClass> cache = ClassCacheManager.getCache();
        assertFalse(cache.isEmpty());

        JavaClass target = cache.values().iterator().next();

        // Rename TWICE: ClassCacheManager deliberately re-adds a class's permanent RAW (pre-
        // deobfuscation) simple-name bucket on every reindex (see the "Bug 1 fix" comment in
        // ClassCacheManager#reindex), so a class's very first rename leaves its old bucket key —
        // which equals its raw name — still present BY DESIGN. Renaming twice isolates the actual
        // removeFromIndex correctness question (does a STALE intermediate alias bucket get cleaned
        // up?) from that intentional raw-name preservation.
        renameAndReindex(target, "F7Intermediate");
        renameAndReindex(target, "F7RenamedTargetClass");

        assertFalse(ClassCacheManager.findByExactName("class", "f7intermediate").contains(target),
                "the STALE intermediate-alias bucket must no longer contain the class after a "
                        + "second rename moved it elsewhere");
        assertTrue(ClassCacheManager.findByExactName("class", "f7renamedtargetclass").contains(target),
                "the CURRENT simple-name bucket must contain the renamed class");

        Map<String, JavaClass> refreshed = ClassCacheManager.getCache();
        assertSame(target, ClassCacheManager.findClass(refreshed, target.getFullName()),
                "classCache must resolve the class by its NEW full name after reindexRenamedClass");
    }

    @Test
    void batchRenameUpdatesAllClassesWithOnePublish(@TempDir File tempDir) throws Exception {
        HeadlessJadxWrapper wrapper = loadTestApk(tempDir);
        Map<String, JavaClass> cache = ClassCacheManager.getCache();
        assertTrue(cache.size() >= 2, "test APK must yield at least 2 classes for a batch rename");

        List<JavaClass> targets = new ArrayList<>(cache.values());
        if (targets.size() > 3) targets = targets.subList(0, 3); // keep the batch small and deterministic

        // Give each target an INTERMEDIATE alias first (single-rename path), for the same reason as
        // singleRenameMovesClassBetweenBucketsAndUpdatesClassCache: a class's very first rename
        // deliberately leaves its raw-name bucket in place, so the upcoming BATCH rename must move
        // it away from an intermediate (non-raw) bucket to exercise genuine stale-bucket removal.
        List<String> intermediateSimpleNamesLower = new ArrayList<>();
        int i = 0;
        for (JavaClass cls : targets) {
            String intermediateName = "F7BatchPre" + (i++);
            renameAndReindex(cls, intermediateName);
            intermediateSimpleNamesLower.add(intermediateName.toLowerCase());
        }

        List<ClassCacheManager.RenamedClass> batch = new ArrayList<>();
        List<String> newSimpleNamesLower = new ArrayList<>();
        i = 0;
        for (JavaClass cls : targets) {
            String previousFullName = cls.getFullName();
            String newSimpleName = "F7BatchFinal" + (i++);
            cls.getClassNode().rename(newSimpleName);
            newSimpleNamesLower.add(newSimpleName.toLowerCase());
            batch.add(new ClassCacheManager.RenamedClass(cls, previousFullName));
        }

        ClassCacheManager.reindexRenamedClassesBatch(batch);

        for (int idx = 0; idx < targets.size(); idx++) {
            JavaClass cls = targets.get(idx);
            assertFalse(ClassCacheManager.findByExactName("class", intermediateSimpleNamesLower.get(idx)).contains(cls),
                    "class " + idx + ": stale intermediate-alias bucket must no longer contain it "
                            + "after the batch rename moved it to a new bucket");
            assertTrue(ClassCacheManager.findByExactName("class", newSimpleNamesLower.get(idx)).contains(cls),
                    "class " + idx + ": new bucket must contain it after the batch rename");
        }

        Map<String, JavaClass> refreshed = ClassCacheManager.getCache();
        for (JavaClass cls : targets) {
            assertSame(cls, ClassCacheManager.findClass(refreshed, cls.getFullName()),
                    "classCache must resolve every batch-renamed class by its new full name after a "
                            + "single reindexRenamedClassesBatch call");
        }
    }

    /** Renames {@code cls}'s ClassNode and refreshes its indices via the single-rename production path. */
    private static void renameAndReindex(JavaClass cls, String newSimpleName) {
        String previousFullName = cls.getFullName();
        cls.getClassNode().rename(newSimpleName);
        ClassCacheManager.reindexRenamedClass(cls, previousFullName);
    }

    private static HeadlessJadxWrapper loadTestApk(File tempDir) throws Exception {
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(apk), new File(tempDir, "out"), 2);
        wrapper.load();
        ClassCacheManager.initCache(wrapper);
        return wrapper;
    }
}
