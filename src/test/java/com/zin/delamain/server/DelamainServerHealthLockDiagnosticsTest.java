package com.zin.delamain.server;

import com.google.gson.Gson;
import com.zin.delamain.core.HeadlessJadxWrapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-07-22 production incident: a runaway xref request held the analysisLock read lock
 * indefinitely, which permanently blocked /load-file's write lock and took the whole service
 * down with 503 apk_not_ready across every endpoint. During triage there was no way to tell
 * from the outside whether the service was "still loading" or "wedged behind a stuck read
 * lock" — both looked identical from the client's point of view, so the only recovery path was
 * a manual restart. GET /health must surface enough lock/queue state that a stuck reader is
 * visible without attaching a debugger or taking a heap dump.
 */
class DelamainServerHealthLockDiagnosticsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

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

    @Test
    void health_exposes_lock_diagnostics_so_a_stuck_read_lock_is_visible_without_a_heap_dump(
            @TempDir Path outputDir) throws Exception {
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(Collections.emptyList(), outputDir.toFile(), 1);
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig(null, false));
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = GSON.fromJson(resp.body(), Map.class);

        Object searchLockObj = body.get("search_lock");
        assertNotNull(searchLockObj, "expected /health to include search_lock");
        assertTrue(searchLockObj instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> searchLock = (Map<String, Object>) searchLockObj;
        assertTrue(searchLock.containsKey("read_lock_count"), "search_lock must expose read_lock_count");
        assertTrue(searchLock.containsKey("write_locked"), "search_lock must expose write_locked");

        Object analysisLockObj = body.get("analysis_lock");
        assertNotNull(analysisLockObj, "expected /health to include analysis_lock");
        assertTrue(analysisLockObj instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> analysisLock = (Map<String, Object>) analysisLockObj;
        assertTrue(analysisLock.containsKey("read_lock_count"), "analysis_lock must expose read_lock_count");
        assertTrue(analysisLock.containsKey("reload_pending"), "analysis_lock must expose reload_pending");

        Object requestsObj = body.get("requests");
        assertNotNull(requestsObj, "expected /health to include requests");
        assertTrue(requestsObj instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> requests = (Map<String, Object>) requestsObj;
        assertTrue(requests.containsKey("in_flight"), "requests must expose in_flight");
        assertTrue(requests.containsKey("oldest_in_flight_seconds"), "requests must expose oldest_in_flight_seconds");
    }
}
