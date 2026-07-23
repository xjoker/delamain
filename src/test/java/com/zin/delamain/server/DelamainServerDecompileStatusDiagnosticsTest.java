package com.zin.delamain.server;

import com.google.gson.Gson;
import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.utils.JadxSearchLock;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3a: /decompile-status is documented (gateway mcp_server.py MCP_INSTRUCTIONS and
 * class_tools.py docstring) as exposing search_lock so callers can back off on
 * search_lock.locked=true, but the endpoint never actually returned it. This mirrors
 * DelamainServerHealthLockDiagnosticsTest but asserts the same diagnostics — sourced from the
 * same shared method as /health — are also reachable through the authenticated
 * /decompile-status endpoint, in both the loaded and not-yet-loaded branches.
 */
class DelamainServerDecompileStatusDiagnosticsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    private DelamainServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        // Global static lock: never leak a held write lock into the next test.
        if (JadxSearchLock.isHeldByCurrentThread()) {
            JadxSearchLock.release();
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> get(int port, String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> resp = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "unexpected status for " + path + ": " + resp.body());
        return GSON.fromJson(resp.body(), Map.class);
    }

    @Test
    void decompile_status_requires_bearer_when_auth_enabled(@TempDir Path outputDir) throws Exception {
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(Collections.emptyList(), outputDir.toFile(), 1);
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig("secret-token", true));
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/decompile-status"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode());
    }

    @Test
    void decompile_status_not_loaded_still_carries_runtime_diagnostics_and_load_error(
            @TempDir Path outputDir) throws Exception {
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(Collections.emptyList(), outputDir.toFile(), 1);
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig(null, false));
        server.start();

        Map<String, Object> body = get(port, "/decompile-status", null);

        // Existing not-loaded contract must survive.
        assertNotNull(body.get("status"));
        assertEquals(0.0, body.get("total_classes"));
        assertNotNull(body.get("message"));

        // New: search_lock at top level (matches the documented flat path search_lock.locked).
        Object searchLockObj = body.get("search_lock");
        assertNotNull(searchLockObj, "expected /decompile-status (not loaded) to include search_lock");
        assertTrue(searchLockObj instanceof Map);

        // New: runtime_diagnostics namespace with analysis_lock + requests.
        Object diagObj = body.get("runtime_diagnostics");
        assertNotNull(diagObj, "expected /decompile-status (not loaded) to include runtime_diagnostics");
        assertTrue(diagObj instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> diag = (Map<String, Object>) diagObj;
        assertTrue(diag.get("analysis_lock") instanceof Map);
        assertTrue(diag.get("requests") instanceof Map);
        assertFalse(diag.containsKey("memory"), "runtime_diagnostics must not duplicate memory");
    }

    @Test
    void decompile_status_loaded_matches_health_diagnostics_and_has_single_memory_block(
            @TempDir Path outputDir) throws Exception {
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(apk), outputDir.toFile(), 1);
        wrapper.load();
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig(null, false));
        server.start();

        Map<String, Object> health = get(port, "/health", null);
        Map<String, Object> status = get(port, "/decompile-status", null);

        // search_lock parity, top level in both.
        assertEquals(health.get("search_lock"), status.get("search_lock"));

        @SuppressWarnings("unchecked")
        Map<String, Object> diag = (Map<String, Object>) status.get("runtime_diagnostics");
        assertNotNull(diag, "expected /decompile-status (loaded) to include runtime_diagnostics");
        assertEquals(health.get("analysis_lock"), diag.get("analysis_lock"));
        assertEquals(health.get("requests"), diag.get("requests"));
        assertFalse(diag.containsKey("memory"), "runtime_diagnostics must not duplicate memory");

        // memory stays a single top-level block on /decompile-status.
        assertNotNull(status.get("memory"));
        assertTrue(((Map<?, ?>) status.get("memory")).containsKey("max_mb"));
    }

    @Test
    void decompile_status_search_lock_reflects_a_held_write_lock(@TempDir Path outputDir) throws Exception {
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(Collections.emptyList(), outputDir.toFile(), 1);
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig(null, false));
        server.start();

        assertTrue(JadxSearchLock.tryAcquire(), "test setup: failed to acquire the global search lock");
        try {
            Map<String, Object> body = get(port, "/decompile-status", null);
            @SuppressWarnings("unchecked")
            Map<String, Object> searchLock = (Map<String, Object>) body.get("search_lock");
            assertEquals(Boolean.TRUE, searchLock.get("locked"));
        } finally {
            JadxSearchLock.release();
        }
    }

    @Test
    void health_response_shape_is_unchanged(@TempDir Path outputDir) throws Exception {
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(Collections.emptyList(), outputDir.toFile(), 1);
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig(null, false));
        server.start();

        Map<String, Object> body = get(port, "/health", null);

        assertNotNull(body.get("search_lock"));
        assertNotNull(body.get("analysis_lock"));
        assertNotNull(body.get("requests"));
        assertNotNull(body.get("memory"));
        // /health must not gain the /decompile-status-specific nesting.
        assertFalse(body.containsKey("runtime_diagnostics"));
    }
}
