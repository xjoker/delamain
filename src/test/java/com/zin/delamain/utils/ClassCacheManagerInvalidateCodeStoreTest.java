package com.zin.delamain.utils;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.CodeStore;
import com.zin.delamain.index.WarmupManager;
import com.zin.delamain.index.shard.ContentShardIndex;

import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W6: renaming (or otherwise invalidating) a class must not leave the persistent {@link CodeStore}
 * disk cache or the mmap {@link ContentShardIndex} coverage pointing at pre-rename source.
 *
 * <p>{@code SearchRoutes.classMatchesAnyContentLocation} falls back to
 * {@code codeFromStore}/the shard index whenever {@code ClassCacheManager.getCachedCodeDirect}
 * misses (which it always does right after a rename, since the in-memory jadx code cache was just
 * cleared). Before this fix, {@code ClassCacheManager.invalidateCode} cleared the in-memory jadx
 * cache and the trigram {@code CodeContentIndex} but left the on-disk {@code CodeStore} entry and
 * the shard coverage bitmap untouched, so a post-rename search kept serving/matching the stale
 * pre-rename source.</p>
 *
 * <p>Runs a real cold warmup (so the CodeStore + shard index are actually built on disk), then
 * calls {@code ClassCacheManager.invalidateCode} directly on a real class — the same call
 * {@code RefactoringRoutes} makes after a rename — and asserts the CodeStore entry is gone and the
 * shard id is tombstoned.</p>
 */
class ClassCacheManagerInvalidateCodeStoreTest {

    @AfterEach
    void tearDown() {
        ClassCacheManager.clearCacheIncludingDecompiled();
        ContentShardIndex.clear();
        WarmupManager.setIndexDir(new File(System.getProperty("java.io.tmpdir"), "delamain-index").toPath());
    }

    @Test
    void invalidateCodeRemovesStaleCodeStoreEntryAndTombstonesShardId(@TempDir Path indexDir) throws Exception {
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
        assertTrue(!isWarmupRunning(), "warmup did not reach DONE within timeout; status=" + WarmupManager.getStatus());

        while (WarmupManager.isShardBuildRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertTrue(!WarmupManager.isShardBuildRunning(), "background shard build did not finish within timeout");
        assertTrue(ContentShardIndex.isBuilt(), "shard index must be built before we can test tombstoning");

        CodeStore codeStore = WarmupManager.codeStore();
        assertNotNull(codeStore, "codeStore must be populated after cold warmup");

        // Build the plugin-owned class index (same call RefactoringRoutes relies on being ready).
        ClassCacheManager.initCache(wrapper);
        Map<String, JavaClass> cache = ClassCacheManager.getCache();
        assertFalse(cache.isEmpty(), "class cache must be populated");

        // Find a real class that is fully present in both the CodeStore and the shard index, so
        // the test exercises the exact path classMatchesAnyContentLocation falls back to.
        JavaClass target = null;
        String targetRawName = null;
        int targetId = -1;
        for (JavaClass cls : cache.values()) {
            String rawName = cls.getRawName();
            if (rawName == null || rawName.isEmpty()) continue;
            if (codeStore.get(rawName) == null) continue;
            int id = CodeContentIndex.idOf(cls);
            if (id < 0 || !ContentShardIndex.isCovered(id)) continue;
            target = cls;
            targetRawName = rawName;
            targetId = id;
            break;
        }
        assertNotNull(target, "expected at least one class covered by both the CodeStore and the shard index");

        // Preconditions: stale source and shard coverage exist before invalidation.
        assertNotNull(codeStore.get(targetRawName), "precondition: CodeStore must hold the class's source");
        assertTrue(ContentShardIndex.isCovered(targetId), "precondition: shard index must cover the class's id");

        // This is the exact call RefactoringRoutes makes right after a rename.
        ClassCacheManager.invalidateCode(target.getFullName());

        assertNull(codeStore.get(targetRawName),
                "CodeStore must no longer serve the pre-rename source after invalidateCode");
        assertFalse(ContentShardIndex.isCovered(targetId),
                "shard index must no longer report the class's id as covered after invalidateCode");
    }

    private static boolean isWarmupRunning() {
        Object running = WarmupManager.getStatus().get("running");
        return Boolean.TRUE.equals(running);
    }
}
