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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H1: {@code startBackgroundShardBuild}'s inline thread lambda was extracted into a package-private
 * static method ({@code runShardBuildLoop}) precisely so the epoch gate
 * ({@link WarmupManager#backgroundBuildStillValid}) can be exercised on the ACTUAL call path, not
 * only as a standalone pure-function check (that check lives in
 * {@link WarmupManagerBackgroundBuildEpochGateTest} and remains valid — it verifies the gate logic
 * itself; this class verifies the gate is actually wired into the build loop).
 *
 * <p>Runs one real cold warmup against the small {@code UnCrackable-Level1.apk} fixture to obtain a
 * genuine, non-null {@code codeStore} and a real {@code currentInputHash}, then calls the extracted
 * loop method DIRECTLY with a {@code hash} that does NOT match the live {@code currentInputHash} —
 * simulating a background build thread that is still running after a newer APK has already loaded
 * and started its own warmup — and asserts nothing gets published on the stale build's behalf.</p>
 */
class WarmupManagerBackgroundBuildWiringTest {

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
        while ((WarmupManager.isShardBuildRunning() || WarmupManager.isUsePlacesHarvestRunning())
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
