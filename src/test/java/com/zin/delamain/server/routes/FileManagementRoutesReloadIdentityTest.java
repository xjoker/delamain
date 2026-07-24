package com.zin.delamain.server.routes;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.PersistentIndexStore;
import com.zin.delamain.index.WarmupManager;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.FilePathSandbox;

import io.javalin.Javalin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * req5 (replace-time identity atomicity): after a file replace (A → B) is dispatched, the published
 * APK identity ({@link WarmupManager#getCurrentInputHash()}, read by {@code apk_identity.input_hash})
 * must NEVER still report APK A's hash. Before this fix, {@code doReload} flipped the load state to
 * LOADED while {@code WarmupManager.currentInputHash} was lazily updated only by the follow-up
 * warmup, leaving a window where {@code apk_identity} reported the OLD APK's hash for the NEW APK.
 *
 * <p>The fix invalidates the identity ({@code currentInputHash} → null = pending) the moment the
 * reload is reserved, so it is impossible to observe the stale hash after the replace is dispatched.</p>
 */
class FileManagementRoutesReloadIdentityTest {

    private Javalin app;
    private HeadlessJadxWrapper wrapper;

    @AfterEach
    void tearDown() throws Exception {
        if (app != null) {
            app.stop();
        }
        long deadline = System.currentTimeMillis() + 120_000;
        while (isWarmupRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        while ((WarmupManager.isShardBuildRunning() || WarmupManager.isUsePlacesHarvestRunning())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        ContentShardIndex.clear();
        CodeContentIndex.clear();
        if (wrapper != null) {
            wrapper.close();
        }
        WarmupManager.setIndexDir(new File(System.getProperty("java.io.tmpdir"), "delamain-index").toPath());
    }

    @Test
    void replaceNeverReportsThePreviousApksIdentityHash(@TempDir Path tempDir, @TempDir Path indexDir)
            throws Exception {
        WarmupManager.setIndexDir(indexDir);

        Path root = tempDir.toRealPath();
        File apkA = new File("test-harness/real/UnCrackable-Level1.apk");
        File apkB = new File("test-harness/real/UnCrackable-Level2.apk");
        assertTrue(apkA.exists(), "test APK A must exist: " + apkA.getAbsolutePath());
        assertTrue(apkB.exists(), "test APK B must exist: " + apkB.getAbsolutePath());
        Files.copy(apkA.toPath(), root.resolve("a.apk"));
        Files.copy(apkB.toPath(), root.resolve("b.apk"));

        // Precondition: A and B are genuinely different inputs (distinct input hashes), so a stale
        // A-hash after loading B would be an observably wrong identity — the bug this guards.
        PersistentIndexStore store = new PersistentIndexStore(indexDir);
        String hashAExpected = store.computeInputHash(List.of(root.resolve("a.apk").toFile()));
        String hashBExpected = store.computeInputHash(List.of(root.resolve("b.apk").toFile()));
        assertNotEquals(hashAExpected, hashBExpected, "A and B must have distinct input hashes");

        FilePathSandbox sandbox = new FilePathSandbox(root);
        File outDir = new File(indexDir.toFile(), "out");
        wrapper = new HeadlessJadxWrapper(Collections.emptyList(), outDir, 2);

        app = Javalin.create();
        new FileManagementRoutes(wrapper, sandbox).register(app, new AuthConfig(null, false));
        app.start(0);

        HttpClient client = HttpClient.newHttpClient();

        // 1) Load + warm A. Reload and warmup are async, and warmup's "running" flag window can be
        //    shorter than a poll tick, so wait on deterministic signals instead: first until A's
        //    warmup has published its identity (getCurrentInputHash != null), then until every
        //    warmup thread has drained — so the subsequent replace cannot race A's warmup touching a
        //    closing decompiler (this test has no DelamainServer quiesce hook to serialize them).
        post(client, "a.apk");
        long deadline = System.currentTimeMillis() + 120_000;
        while (WarmupManager.getCurrentInputHash() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        while ((isWarmupRunning() || WarmupManager.isShardBuildRunning()
                || WarmupManager.isUsePlacesHarvestRunning())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        String hashA = WarmupManager.getCurrentInputHash();
        assertNotNull(hashA, "A's warmup must have published an input hash");
        assertNotEquals(hashBExpected, hashA, "sanity: A's published hash must be A's, not B's");

        // 2) Replace with B. The identity must be invalidated synchronously as part of dispatching
        //    the reload, so the instant the 202 returns getCurrentInputHash() can never be A's hash.
        HttpResponse<String> respB = post(client, "b.apk");
        assertTrue(respB.statusCode() == 202, "replace must dispatch: " + respB.statusCode() + " " + respB.body());
        assertNotEquals(hashA, WarmupManager.getCurrentInputHash(),
                "after dispatching a replace to B, the identity must not still report A's hash");
    }

    private HttpResponse<String> post(HttpClient client, String name) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + app.port() + "/load-file"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"path\":\"" + name + "\",\"mode\":\"replace\"}"))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static boolean isWarmupRunning() {
        return Boolean.TRUE.equals(WarmupManager.getStatus().get("running"));
    }
}
