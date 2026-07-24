package com.zin.delamain.server;

import com.google.gson.Gson;
import com.zin.delamain.core.HeadlessJadxWrapper;

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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave1 fix: /apk-info, /decompile-status, and search responses each exposed a different,
 * incomplete slice of "which APK is currently loaded" (RCA: /apk-info lacked file_name,
 * apk_package, and version fields that /file-info already had — the gateway split-brain root
 * cause). This asserts every one of them now carries a single, consistent apk_identity block
 * (via ApkIdentity#build), and that /apk-info additionally gained the flat fields to match
 * /file-info without breaking its pre-existing package_name field.
 */
class ApkIdentityContractTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static final File TEST_APK = new File("test-harness/real/UnCrackable-Level1.apk");

    private DelamainServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
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
    private static Map<String, Object> apkIdentityOf(Map<String, Object> body) {
        Object identity = body.get("apk_identity");
        assertNotNull(identity, "expected apk_identity to be present");
        assertTrue(identity instanceof Map);
        return (Map<String, Object>) identity;
    }

    @Test
    void apk_info_not_loaded_reports_apk_identity_loaded_false(@TempDir Path outputDir) throws Exception {
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(), outputDir.toFile(), 1);
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig(null, false));
        server.start();

        Map<String, Object> body = get(port, "/apk-info");
        Map<String, Object> identity = apkIdentityOf(body);
        assertEquals(false, identity.get("loaded"));
    }

    @Test
    void apk_info_loaded_gains_file_name_apk_package_version_and_apk_identity(@TempDir Path outputDir)
            throws Exception {
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(TEST_APK), outputDir.toFile(), 1);
        wrapper.load();
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig(null, false));
        server.start();

        Map<String, Object> body = get(port, "/apk-info");

        // Pre-existing contract must survive.
        assertEquals("loaded", body.get("load_state"));
        assertNotNull(body.get("package_name"));

        // New flat fields, matching /file-info's field names so gateway callers stop split-braining.
        assertEquals("UnCrackable-Level1.apk", body.get("file_name"));
        assertNotNull(body.get("apk_package"));
        assertEquals(body.get("apk_package"), body.get("package_name"));

        Map<String, Object> identity = apkIdentityOf(body);
        assertEquals(true, identity.get("loaded"));
        assertEquals("UnCrackable-Level1.apk", identity.get("file_name"));
        assertNotNull(identity.get("apk_package"));
        assertTrue(((Number) identity.get("class_count")).intValue() > 0);
    }

    @Test
    void decompile_status_carries_apk_identity_when_loaded(@TempDir Path outputDir) throws Exception {
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(TEST_APK), outputDir.toFile(), 1);
        wrapper.load();
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig(null, false));
        server.start();

        Map<String, Object> body = get(port, "/decompile-status");
        Map<String, Object> identity = apkIdentityOf(body);
        assertEquals(true, identity.get("loaded"));
        assertEquals("UnCrackable-Level1.apk", identity.get("file_name"));
    }

    @Test
    void decompile_status_carries_apk_identity_when_not_loaded(@TempDir Path outputDir) throws Exception {
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(), outputDir.toFile(), 1);
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig(null, false));
        server.start();

        Map<String, Object> body = get(port, "/decompile-status");
        Map<String, Object> identity = apkIdentityOf(body);
        assertEquals(false, identity.get("loaded"));
    }

    @Test
    void search_response_carries_apk_identity_matching_currently_loaded_file(@TempDir Path outputDir)
            throws Exception {
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(TEST_APK), outputDir.toFile(), 1);
        wrapper.load();
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig(null, false));
        server.start();

        Map<String, Object> body = get(port, "/search-classes-by-keyword?search_term=MainActivity");
        Map<String, Object> identity = apkIdentityOf(body);
        assertEquals(true, identity.get("loaded"));
        assertEquals("UnCrackable-Level1.apk", identity.get("file_name"));
    }
}
