package com.zin.delamain.server;

import com.google.gson.Gson;
import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.PersistentIndexStore;
import com.zin.delamain.index.WarmupManager;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.server.routes.FileManagementRoutes;
import com.zin.delamain.utils.FilePathSandbox;

import io.javalin.Javalin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * req6 (end-to-end replace regression): every endpoint that echoes "which APK is currently
 * loaded" — /file-info, /apk-info, /decompile-status, /index-stats and the search responses —
 * must report the SAME file after a real {@code /load-file} replace (A -> B), and none of them
 * may keep reporting the old APK (the gateway split-brain that Wave1/Wave2 fixed via
 * {@link com.zin.delamain.core.ApkIdentity}). This drives an actual replace through
 * {@link FileManagementRoutes#handleLoadFile}, then re-reads all five endpoints from a real
 * {@link DelamainServer} sharing the same {@link HeadlessJadxWrapper} instance.
 */
class ApkIdentityReplaceConsistencyTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    private DelamainServer server;
    private Javalin loaderApp;
    private HeadlessJadxWrapper wrapper;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (loaderApp != null) {
            loaderApp.stop();
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

    private static boolean isWarmupRunning() {
        return Boolean.TRUE.equals(WarmupManager.getStatus().get("running"));
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> get(int port, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "unexpected status for " + path + ": " + resp.body());
        return GSON.fromJson(resp.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> identityOf(Map<String, Object> body) {
        Object identity = body.get("apk_identity");
        assertNotNull(identity, "expected apk_identity to be present");
        assertTrue(identity instanceof Map);
        return (Map<String, Object>) identity;
    }

    /** Snapshot of the effective identity as seen from all five endpoints at a point in time. */
    private static final class IdentitySnapshot {
        final String fileInfoTopFileName;
        final String fileInfoIdentityFileName;
        final String apkInfoTopFileName;
        final String apkInfoTopApkPackage;
        final String apkInfoIdentityFileName;
        final String apkInfoIdentityApkPackage;
        final String decompileStatusIdentityFileName;
        final String indexStatsIdentityFileName;
        final String searchIdentityFileName;

        IdentitySnapshot(int serverPort) throws Exception {
            Map<String, Object> fileInfo = get(serverPort, "/file-info");
            Map<String, Object> apkInfo = get(serverPort, "/apk-info");
            Map<String, Object> decompileStatus = get(serverPort, "/decompile-status");
            Map<String, Object> indexStats = get(serverPort, "/index-stats");
            Map<String, Object> search = get(serverPort, "/search-classes-by-keyword?search_term=a");

            fileInfoTopFileName = (String) fileInfo.get("file_name");
            fileInfoIdentityFileName = (String) identityOf(fileInfo).get("file_name");

            apkInfoTopFileName = (String) apkInfo.get("file_name");
            apkInfoTopApkPackage = (String) apkInfo.get("apk_package");
            Map<String, Object> apkInfoIdentity = identityOf(apkInfo);
            apkInfoIdentityFileName = (String) apkInfoIdentity.get("file_name");
            apkInfoIdentityApkPackage = (String) apkInfoIdentity.get("apk_package");

            decompileStatusIdentityFileName = (String) identityOf(decompileStatus).get("file_name");
            indexStatsIdentityFileName = (String) identityOf(indexStats).get("file_name");
            searchIdentityFileName = (String) identityOf(search).get("file_name");
        }
    }

    @Test
    void replaceMakesAllFiveIdentityEndpointsConsistentlyReportTheNewApk(
            @TempDir Path outputDir, @TempDir Path indexDir, @TempDir Path sandboxDir) throws Exception {
        WarmupManager.setIndexDir(indexDir);

        File apkA = new File("test-harness/real/UnCrackable-Level1.apk");
        File apkB = new File("test-harness/real/UnCrackable-Level2.apk");
        assertTrue(apkA.exists(), "test APK A must exist: " + apkA.getAbsolutePath());
        assertTrue(apkB.exists(), "test APK B must exist: " + apkB.getAbsolutePath());

        Path root = sandboxDir.toRealPath();
        Path aInSandbox = root.resolve(apkA.getName());
        Path bInSandbox = root.resolve(apkB.getName());
        Files.copy(apkA.toPath(), aInSandbox);
        Files.copy(apkB.toPath(), bInSandbox);

        // Precondition: A and B are genuinely different APKs (distinct package/version), so a
        // stale-A-after-loading-B would be an observably wrong identity — the split-brain this
        // guards against.
        PersistentIndexStore store = new PersistentIndexStore(indexDir);
        String hashA = store.computeInputHash(List.of(aInSandbox.toFile()));
        String hashB = store.computeInputHash(List.of(bInSandbox.toFile()));
        assertTrue(!hashA.equals(hashB), "A and B must have distinct input hashes");

        // 1) Load A directly (no HTTP round trip needed for the initial load) and start a real
        //    DelamainServer over the SAME wrapper instance, so /load-file's later reload (dispatched
        //    through a second, sandbox-scoped app below) is observed by every endpoint here.
        wrapper = new HeadlessJadxWrapper(List.of(apkA), outputDir.toFile(), 2);
        wrapper.load();
        assertTrue(wrapper.isLoaded(), "test setup: wrapper must report A loaded");

        int serverPort = findFreePort();
        server = new DelamainServer(wrapper, serverPort, "127.0.0.1", new AuthConfig(null, false));
        server.start();

        // A second, minimal app exposes /load-file with a real sandbox root (DelamainServer's own
        // FileManagementRoutes uses FilePathSandbox.fromEnvironment(), unusable here without
        // mutating process env). It shares the identical `wrapper` object, so its reload is a
        // reload of the very wrapper DelamainServer reads from — not a second, disconnected copy.
        FilePathSandbox sandbox = new FilePathSandbox(root);
        loaderApp = Javalin.create();
        new FileManagementRoutes(wrapper, sandbox).register(loaderApp, new AuthConfig(null, false));
        loaderApp.start(0);

        // 2) Before the replace: every endpoint must point at A.
        IdentitySnapshot beforeReplace = new IdentitySnapshot(serverPort);
        assertEquals("UnCrackable-Level1.apk", beforeReplace.fileInfoTopFileName);
        assertEquals("UnCrackable-Level1.apk", beforeReplace.fileInfoIdentityFileName);
        assertEquals("UnCrackable-Level1.apk", beforeReplace.apkInfoTopFileName);
        assertEquals("UnCrackable-Level1.apk", beforeReplace.apkInfoIdentityFileName);
        assertEquals("UnCrackable-Level1.apk", beforeReplace.decompileStatusIdentityFileName);
        assertEquals("UnCrackable-Level1.apk", beforeReplace.indexStatsIdentityFileName);
        assertEquals("UnCrackable-Level1.apk", beforeReplace.searchIdentityFileName);

        // 3) Replace with B via a real HTTP /load-file call, mode=replace.
        HttpRequest loadB = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + loaderApp.port() + "/load-file"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"path\":\"" + apkB.getName() + "\",\"mode\":\"replace\"}"))
                .build();
        HttpResponse<String> loadBResponse = HTTP.send(loadB, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, loadBResponse.statusCode(),
                "replace dispatch must succeed: " + loadBResponse.body());

        // Deterministic wait: reload runs on a background thread; wait until the wrapper reports
        // LOADED again with B's file name as the primary input (not a fixed sleep).
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            if (wrapper.isLoaded()
                    && !wrapper.getInputFiles().isEmpty()
                    && apkB.getName().equals(wrapper.getInputFiles().get(0).getName())) {
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(wrapper.isLoaded(), "wrapper must report LOADED again after the replace completes");
        assertEquals(apkB.getName(), wrapper.getInputFiles().get(0).getName(),
                "wrapper's primary input file must be B after the replace");

        // 4) Core assertion: all five endpoints now report B, consistently, with no residue of A.
        IdentitySnapshot afterReplace = new IdentitySnapshot(serverPort);

        assertEquals("UnCrackable-Level2.apk", afterReplace.fileInfoTopFileName,
                "/file-info top-level file_name must be B, not A");
        assertEquals("UnCrackable-Level2.apk", afterReplace.fileInfoIdentityFileName,
                "/file-info apk_identity.file_name must be B, not A");
        assertEquals("UnCrackable-Level2.apk", afterReplace.apkInfoTopFileName,
                "/apk-info top-level file_name must be B, not A");
        assertEquals("UnCrackable-Level2.apk", afterReplace.apkInfoIdentityFileName,
                "/apk-info apk_identity.file_name must be B, not A");
        assertEquals("UnCrackable-Level2.apk", afterReplace.decompileStatusIdentityFileName,
                "/decompile-status apk_identity.file_name must be B, not A");
        assertEquals("UnCrackable-Level2.apk", afterReplace.indexStatsIdentityFileName,
                "/index-stats apk_identity.file_name must be B, not A");
        assertEquals("UnCrackable-Level2.apk", afterReplace.searchIdentityFileName,
                "search response apk_identity.file_name must be B, not A");

        // 5) Cross-endpoint consistency: /apk-info's top-level fields, /file-info's top-level
        //    fields, and each endpoint's own nested apk_identity must all agree with one another —
        //    proving the four places no longer disagree about which APK is loaded (the split-brain
        //    this fix eliminates).
        assertEquals(afterReplace.apkInfoTopFileName, afterReplace.fileInfoTopFileName,
                "/apk-info and /file-info top-level file_name must agree");
        assertEquals(afterReplace.apkInfoTopFileName, afterReplace.apkInfoIdentityFileName,
                "/apk-info top-level file_name must match its own apk_identity.file_name");
        assertEquals(afterReplace.fileInfoTopFileName, afterReplace.fileInfoIdentityFileName,
                "/file-info top-level file_name must match its own apk_identity.file_name");
        assertNotNull(afterReplace.apkInfoTopApkPackage, "/apk-info apk_package must be populated for B");
        assertEquals(afterReplace.apkInfoTopApkPackage, afterReplace.apkInfoIdentityApkPackage,
                "/apk-info top-level apk_package must match its own apk_identity.apk_package");

        // Package must actually differ between A and B (real distinct test APKs, not a same-file
        // false positive that would make the file_name-only assertions above too weak).
        assertNotNull(beforeReplace.apkInfoTopApkPackage, "/apk-info apk_package must be populated for A");
        assertTrue(!beforeReplace.apkInfoTopApkPackage.equals(afterReplace.apkInfoTopApkPackage),
                "A and B must have distinct apk_package, otherwise this test cannot detect a split-brain");
    }
}
