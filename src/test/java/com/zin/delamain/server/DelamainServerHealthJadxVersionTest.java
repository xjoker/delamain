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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code jadx_init} MCP tool's dependency: GET /health must report the real running
 * jadx version (via {@code jadx.core.Jadx.getVersion()}) so gateway clients can do a
 * version-compatibility check, even before any APK/JAR has been loaded.
 */
class DelamainServerHealthJadxVersionTest {

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
    void health_reportsNonEmptyJadxVersion_withoutAnyFileLoaded(@TempDir Path outputDir) throws Exception {
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
        Object jadxVersion = body.get("jadx_version");
        assertNotNull(jadxVersion, "expected /health to include jadx_version");
        assertTrue(jadxVersion instanceof String);
        assertFalse(((String) jadxVersion).isEmpty());
    }
}
