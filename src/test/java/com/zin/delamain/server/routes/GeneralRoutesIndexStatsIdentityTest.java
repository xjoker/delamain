package com.zin.delamain.server.routes;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.server.AuthConfig;

import io.javalin.Javalin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * index-stats echo: {@code GET /index-stats} must include a nested {@code apk_identity} object
 * (built from {@link com.zin.delamain.core.ApkIdentity}) so an operator/AI can, in one call,
 * confirm which APK the reported index numbers actually belong to. Only a load is required — the
 * identity is available before any warmup (input_hash is legitimately null until warmup computes it).
 */
class GeneralRoutesIndexStatsIdentityTest {

    private static final Gson GSON = new Gson();

    private Javalin app;
    private HeadlessJadxWrapper wrapper;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
        if (wrapper != null) {
            wrapper.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void indexStatsIncludesApkIdentityForLoadedApk(@TempDir Path indexDir) throws Exception {
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        File outDir = new File(indexDir.toFile(), "out");

        wrapper = new HeadlessJadxWrapper(List.of(apk), outDir, 2);
        wrapper.load();

        app = Javalin.create();
        new GeneralRoutes(wrapper).register(app, new AuthConfig(null, false));
        app.start(0);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + app.port() + "/index-stats"))
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "index-stats must return 200: " + resp.body());

        Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> body = GSON.fromJson(resp.body(), mapType);

        Object identityObj = body.get("apk_identity");
        assertNotNull(identityObj, "index-stats must include apk_identity: " + resp.body());
        Map<String, Object> identity = (Map<String, Object>) identityObj;
        assertEquals(Boolean.TRUE, identity.get("loaded"), "apk_identity.loaded must be true: " + identity);
        assertNotNull(identity.get("apk_package"),
                "apk_identity.apk_package must be present for a loaded APK: " + identity);
    }
}
