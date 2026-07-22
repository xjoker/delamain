package com.zin.delamain.server.routes;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.CodeContentIndex;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production dogfood bug: container deployments load APKs after process start via
 * {@code POST /load-file} (the entrypoint never passes {@code --apk}), so {@code Main}'s
 * startup-only auto-warmup trigger ({@code Main.java} — {@code if (warmupOnStart &&
 * wrapper.isLoaded())} right after {@code server.start()}) never fires for them. Without warmup,
 * the shard/UsageGraphIndex/UsePlacesIndex fast indexes are never built and xref/code-search
 * silently degrade to the slow live-decompile path (observed on real hardware as
 * {@code phase=IDLE} + xref timeouts).
 *
 * <p>Fix: {@link FileManagementRoutes#handleLoadFile} mirrors {@code Main}'s trigger after a
 * successful async reload. This test drives the real {@code /load-file} HTTP route against a
 * small real APK and asserts {@link WarmupManager} actually starts.</p>
 */
class FileManagementRoutesAutoWarmupTest {

    private Javalin app;
    private HeadlessJadxWrapper wrapper;

    @AfterEach
    void tearDown() throws Exception {
        if (app != null) {
            app.stop();
        }
        // Wait out any background warmup / shard build so the @TempDir index dir can be deleted.
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
    void loadFileAutoTriggersWarmupWhenEnabled(@TempDir Path tempDir, @TempDir Path indexDir) throws Exception {
        WarmupManager.setIndexDir(indexDir);

        Path root = tempDir.toRealPath();
        File apkSource = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apkSource.exists(), "test APK must exist: " + apkSource.getAbsolutePath());
        Path apkInSandbox = root.resolve("target.apk");
        Files.copy(apkSource.toPath(), apkInSandbox);

        FilePathSandbox sandbox = new FilePathSandbox(root);
        File outDir = new File(indexDir.toFile(), "out");
        wrapper = new HeadlessJadxWrapper(Collections.emptyList(), outDir, 2);

        app = Javalin.create();
        new FileManagementRoutes(wrapper, sandbox).register(app, new AuthConfig(null, false));
        app.start(0);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + app.port() + "/load-file"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"target.apk\",\"mode\":\"replace\"}"))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertTrue(resp.statusCode() == 202, "load-file must dispatch: " + resp.statusCode() + " " + resp.body());

        // Reload + warmup both run on background threads. On this tiny fixture (15 classes)
        // WarmupManager's "running" flag window can be shorter than a poll tick, so instead of
        // racing to observe running=true, wait for its deterministic, persisted end-of-run
        // side effect: the "<inputHash>.manifest.json" written into indexDir once the background
        // shard build (triggered only by a completed warmup) finishes. indexDir is a fresh
        // @TempDir scoped to this test, so any manifest file appearing there can only come from
        // this run.
        long deadline = System.currentTimeMillis() + 60_000;
        boolean sawManifest = false;
        while (System.currentTimeMillis() < deadline) {
            if (hasManifestFile(indexDir)) {
                sawManifest = true;
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(sawManifest,
                "load_file must auto-trigger WarmupManager once reload completes (no "
                        + "*.manifest.json appeared under indexDir); last status="
                        + WarmupManager.getStatus());
    }

    private static boolean hasManifestFile(Path indexDir) throws Exception {
        try (var stream = Files.list(indexDir)) {
            return stream.anyMatch(p -> p.getFileName().toString().endsWith(".manifest.json"));
        }
    }

    @Test
    void defaultEnvTriggersWarmup() {
        assertTrue(FileManagementRoutes.resolveWarmupOnStart(null));
    }

    @Test
    void falseEnvDisablesAutoWarmupTrigger() {
        assertFalse(FileManagementRoutes.resolveWarmupOnStart("false"));
    }

    @Test
    void falseEnvIsCaseInsensitive() {
        assertFalse(FileManagementRoutes.resolveWarmupOnStart("FALSE"));
    }

    @Test
    void arbitraryEnvValueKeepsWarmupEnabled() {
        assertTrue(FileManagementRoutes.resolveWarmupOnStart("yes"));
    }

    @Test
    void skipLibrariesDecisionMirrorsMain() {
        assertTrue(FileManagementRoutes.resolveSkipLibraries(null));
        assertFalse(FileManagementRoutes.resolveSkipLibraries("true"));
        assertFalse(FileManagementRoutes.resolveSkipLibraries("TRUE"));
        assertTrue(FileManagementRoutes.resolveSkipLibraries("false"));
        assertTrue(FileManagementRoutes.resolveSkipLibraries("yes"));
    }

    private static boolean isWarmupRunning() {
        Object running = WarmupManager.getStatus().get("running");
        return Boolean.TRUE.equals(running);
    }
}
