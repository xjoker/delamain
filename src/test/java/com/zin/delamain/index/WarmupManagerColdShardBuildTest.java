package com.zin.delamain.index;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.server.routes.GeneralRoutes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W2: the cold warmup path (first-ever load, no persisted catalog/graph/CodeStore) must also
 * trigger the mmap shard build, not just FAST_RESTORE. Runs a real cold {@link WarmupManager#start}
 * against a small test APK in a brand-new {@code @TempDir} index dir (guaranteeing no prior
 * catalog exists, so the run necessarily takes the COLD branch of {@code runWarmup}), then polls
 * until warmup DONE and the background shard build (kicked off right after
 * {@code codeStore.markComplete}) has completed.
 *
 * <p>W8 extends the same run to also assert the prebaked-index manifest (written right after the
 * shard build — see {@code WarmupManager#writePrebakedManifest}) and the {@code /index-stats}
 * {@code index_prebaked} section it feeds (see {@code GeneralRoutes#readPrebakedManifest}),
 * rather than running a second full cold decompile of the same fixture APK in its own test class:
 * the fixture is tiny (15 classes) and JADX's parallel Phase-1 decompile is occasionally flaky at
 * that scale (observed transient {@code NullPointerException}s in JADX's own passes under
 * concurrency unrelated to this repo's code) — doubling the number of real cold-warmup runs in the
 * suite measurably increased the odds of tripping that pre-existing flakiness in an unrelated test.</p>
 */
class WarmupManagerColdShardBuildTest {

    @AfterEach
    void tearDown() {
        // WarmupManager holds process-wide static state; leave it pointed at a valid (default)
        // location and the shard index cleared so no other test in this JVM can observe the
        // now-deleted @TempDir.
        ContentShardIndex.clear();
        WarmupManager.setIndexDir(new File(System.getProperty("java.io.tmpdir"), "delamain-index").toPath());
    }

    @Test
    void coldWarmupBuildsShardIndexWithoutWaitingForRestart(@TempDir Path indexDir) throws Exception {
        WarmupManager.setIndexDir(indexDir);

        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        File outDir = new File(indexDir.toFile(), "out");

        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(apk), outDir, 2);
        wrapper.load();

        Map<String, Object> result = WarmupManager.start(wrapper, false);
        assertTrue((Boolean) result.get("started"), "cold warmup must start: " + result);

        // Poll for warmup DONE (Phase-1/2/3 complete), then poll for the background shard build
        // (kicked off right after codeStore.markComplete inside the COLD branch) to finish.
        long deadline = System.currentTimeMillis() + 120_000;
        while (isWarmupRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertTrue(!isWarmupRunning(), "warmup did not reach DONE within timeout; status=" + WarmupManager.getStatus());

        while (WarmupManager.isShardBuildRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertTrue(!WarmupManager.isShardBuildRunning(),
                "background shard build did not finish within timeout");

        assertTrue(ContentShardIndex.isBuilt(),
                "cold path must have built the mmap shard index without requiring a restart");
        Map<String, Object> stats = ContentShardIndex.getStats();
        Object coveredClasses = stats.get("covered_classes");
        assertTrue(coveredClasses instanceof Number && ((Number) coveredClasses).intValue() > 0,
                "shard index must cover at least one class after the cold build: " + stats);

        // W14: the fixture APK (UnCrackable-Level1.apk) has inner classes whose CodeStore source is
        // an empty string (source inlined into their top-level class) — these must be recorded as
        // shard-excluded, not left as scan-requiring holes, without reducing covered coverage.
        Object excludedClasses = stats.get("excluded_classes");
        assertTrue(excludedClasses instanceof Number && ((Number) excludedClasses).intValue() > 0,
                "shard index must record empty inner classes as excluded after the cold build: " + stats);

        // --- W8: prebaked-index manifest, written right after the shard build above ---
        String hash = WarmupManager.getCurrentInputHash();
        assertTrue(hash != null && !hash.isEmpty(), "input hash must be computed after warmup");

        Path manifestPath = indexDir.resolve(hash + ".manifest.json");
        assertTrue(Files.isRegularFile(manifestPath), "manifest file must be written: " + manifestPath);
        String json = Files.readString(manifestPath);
        assertTrue(json.contains("\"input_hash\""), "manifest must contain input_hash: " + json);
        assertTrue(json.contains(hash), "manifest input_hash must match current APK's hash: " + json);
        assertTrue(json.contains("\"tool_version\""), "manifest must contain tool_version: " + json);
        assertTrue(json.contains("\"shard_count\""), "manifest must contain shard_count: " + json);
        assertTrue(json.contains("\"built_at_epoch_ms\""), "manifest must contain built_at_epoch_ms: " + json);

        // --- W8: /index-stats index_prebaked section reads the manifest above ---
        GeneralRoutes routes = new GeneralRoutes(wrapper);
        Map<String, Object> prebaked = routes.readPrebakedManifest();
        assertEquals(Boolean.TRUE, prebaked.get("complete"),
                "index_prebaked.complete must be true once manifest exists and matches loaded APK: " + prebaked);
        assertEquals(hash, prebaked.get("input_hash"));
        Object shardCount = prebaked.get("shard_count");
        assertTrue(shardCount instanceof Number && ((Number) shardCount).intValue() > 0,
                "manifest shard_count must be > 0: " + prebaked);
    }

    private static boolean isWarmupRunning() {
        Object running = WarmupManager.getStatus().get("running");
        return Boolean.TRUE.equals(running);
    }
}
