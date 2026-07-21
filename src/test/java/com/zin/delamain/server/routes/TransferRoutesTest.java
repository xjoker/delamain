package com.zin.delamain.server.routes;

import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.server.TransferTokenStore;
import com.zin.delamain.utils.FilePathSandbox;

import io.javalin.Javalin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the upload-transfer contract's endpoints A/B/C
 * (POST /create-transfer-token, PUT /transfer/upload, GET /transfer/status), exercised over a
 * real Javalin instance + {@link HttpClient} (no mocking framework is a project dependency).
 *
 * <p>Each test gets its own sandbox root ({@code @TempDir}) and its own {@link TransferRoutes}
 * instance with the package-private test-seam constructor, so cap/TTL can be overridden without
 * touching real environment variables or waiting out wall-clock time.
 */
class TransferRoutesTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Path root;
    private FilePathSandbox sandbox;
    private TransferTokenStore tokenStore;
    private TransferRoutes routes;
    private Javalin app;
    private String base;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        root = tempDir.toRealPath();
        sandbox = new FilePathSandbox(root);
        tokenStore = new TransferTokenStore();
    }

    private void startApp(long maxBytes, int ttlMinutes) {
        routes = new TransferRoutes(sandbox, tokenStore, maxBytes, ttlMinutes);
        app = Javalin.create();
        routes.register(app, new AuthConfig(null, false));
        app.start(0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, byte[] body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String json, String key) {
        // Minimal ad-hoc extraction (no Jackson dependency wired into this test) — good enough
        // for the flat, single-level JSON shapes these endpoints return.
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (json.charAt(i) == '"') {
            int end = json.indexOf('"', i + 1);
            return json.substring(i + 1, end);
        }
        // Numeric or bare literal (true/false/null): read until the next structural delimiter.
        int end = i;
        while (end < json.length() && ",}]".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(i, end).trim();
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(data)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void singleShotUpload_succeedsAndFileLandsInRoot() throws Exception {
        startApp(1024L * 1024, 30);
        byte[] content = "PK fake apk bytes".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> created = post("/create-transfer-token",
                "{\"filename\":\"app.apk\",\"size_bytes\":" + content.length + "}");
        assertEquals(200, created.statusCode(), created.body());
        String token = field(created.body(), "token");
        assertEquals(64, token.length(), "token must be 256-bit (64 hex chars): " + token);

        HttpResponse<String> uploaded = put("/transfer/upload", content, Map.of(
                "X-Transfer-Token", token));
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        assertEquals("complete", field(uploaded.body(), "status"));
        assertEquals("app.apk", field(uploaded.body(), "path"));
        assertEquals(sha256Hex(content), field(uploaded.body(), "sha256"));

        Path landed = root.resolve("app.apk");
        assertTrue(Files.isRegularFile(landed), "uploaded file must land at sandbox root");
        assertEquals(new String(content, StandardCharsets.UTF_8), Files.readString(landed));
        assertFalse(Files.exists(root.resolve(".transfer").resolve(token + ".part")),
                "staging .part file must be removed after a successful move");
    }

    /**
     * Regression for a real-machine E2E finding: {@code ctx.bodyAsBytes()} routes through
     * Javalin's cached-body path, which enforces {@code http.maxRequestSize} (default
     * 1,000,000 bytes) and rejects anything larger with 413 "Content Too Large" — before the
     * handler's own {@code JADX_TRANSFER_MAX_MB} cap logic ever runs. Any real APK chunk (the
     * CLI's default is 8 MiB) tripped this. The fix streams via {@code ctx.bodyInputStream()},
     * which is not subject to that cap. A 2 MiB chunk (comfortably over the old 1MB ceiling,
     * comfortably under this test's cap) must complete, not 413.
     */
    @Test
    void chunkOverOneMegabyte_succeedsInsteadOfHittingJavalinDefaultCap() throws Exception {
        startApp(16L * 1024 * 1024, 30); // 16 MiB cap — plenty of headroom over the 2 MiB payload
        byte[] content = new byte[2 * 1024 * 1024]; // 2 MiB, comfortably over Javalin's default 1,000,000-byte cap
        new java.util.Random(42).nextBytes(content);

        HttpResponse<String> created = post("/create-transfer-token",
                "{\"filename\":\"big.apk\",\"size_bytes\":" + content.length + "}");
        assertEquals(200, created.statusCode(), created.body());
        String token = field(created.body(), "token");

        HttpResponse<String> uploaded = put("/transfer/upload", content, Map.of("X-Transfer-Token", token));
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        assertEquals("complete", field(uploaded.body(), "status"));
        assertEquals(sha256Hex(content), field(uploaded.body(), "sha256"));

        Path landed = root.resolve("big.apk");
        assertTrue(Files.isRegularFile(landed));
        assertEquals(content.length, Files.size(landed));
    }

    // -------------------------------------------------------------------------
    // Red-light security boundaries (MUST, per contract)
    // -------------------------------------------------------------------------

    @Test
    void expiredToken_rejectedWithUnauthorized() throws Exception {
        startApp(1024L * 1024, 30);
        TransferTokenStore.TokenEntry expired =
                tokenStore.createWithTtlMillis("app.apk", null, -1000L);

        HttpResponse<String> resp = put("/transfer/upload", "data".getBytes(StandardCharsets.UTF_8),
                Map.of("X-Transfer-Token", expired.token));

        assertEquals(401, resp.statusCode(), resp.body());
    }

    @Test
    void consumedToken_rejectedOnReuse() throws Exception {
        startApp(1024L * 1024, 30);
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> created = post("/create-transfer-token", "{\"filename\":\"app.apk\"}");
        String token = field(created.body(), "token");

        HttpResponse<String> first = put("/transfer/upload", content, Map.of("X-Transfer-Token", token));
        assertEquals(200, first.statusCode(), first.body());

        HttpResponse<String> second = put("/transfer/upload", content, Map.of("X-Transfer-Token", token));
        assertEquals(401, second.statusCode(), second.body());
    }

    @Test
    void pathTraversalFilename_rejectedAtCreate() throws Exception {
        startApp(1024L * 1024, 30);
        HttpResponse<String> resp = post("/create-transfer-token",
                "{\"filename\":\"../evil.apk\"}");
        assertEquals(400, resp.statusCode(), resp.body());
    }

    @Test
    void disallowedExtension_rejectedAtCreate() throws Exception {
        startApp(1024L * 1024, 30);
        HttpResponse<String> resp = post("/create-transfer-token",
                "{\"filename\":\"payload.exe\"}");
        assertEquals(400, resp.statusCode(), resp.body());
    }

    @Test
    void sizeBytesExceedsCap_rejectedAtCreateWith413() throws Exception {
        startApp(100, 30); // 100-byte cap
        HttpResponse<String> resp = post("/create-transfer-token",
                "{\"filename\":\"app.apk\",\"size_bytes\":1000}");
        assertEquals(413, resp.statusCode(), resp.body());
    }

    @Test
    void cumulativeUploadExceedsCap_rejectedWith413() throws Exception {
        startApp(10, 30); // 10-byte cap, no size_bytes declared up front
        HttpResponse<String> created = post("/create-transfer-token", "{\"filename\":\"app.apk\"}");
        String token = field(created.body(), "token");

        byte[] tooLarge = "this is way more than ten bytes".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> resp = put("/transfer/upload", tooLarge, Map.of("X-Transfer-Token", token));
        assertEquals(413, resp.statusCode(), resp.body());
    }

    @Test
    void chunkOffsetMismatch_rejectedWith409() throws Exception {
        startApp(1024L * 1024, 30);
        HttpResponse<String> created = post("/create-transfer-token", "{\"filename\":\"app.apk\"}");
        String token = field(created.body(), "token");

        byte[] chunk1 = "first-chunk".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> firstResp = put("/transfer/upload", chunk1, Map.of(
                "X-Transfer-Token", token, "X-Chunk-Offset", "0", "X-Chunk-Final", "false"));
        assertEquals(200, firstResp.statusCode(), firstResp.body());
        assertEquals("partial", field(firstResp.body(), "status"));

        byte[] chunk2 = "second-chunk".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> mismatched = put("/transfer/upload", chunk2, Map.of(
                "X-Transfer-Token", token, "X-Chunk-Offset", "999", "X-Chunk-Final", "true"));
        assertEquals(409, mismatched.statusCode(), mismatched.body());
        assertEquals("offset_mismatch", field(mismatched.body(), "error"));
        assertEquals(String.valueOf(chunk1.length), field(mismatched.body(), "bytes_received"));
    }

    @Test
    void finalChunkShaMismatch_rejectedWith422() throws Exception {
        startApp(1024L * 1024, 30);
        HttpResponse<String> created = post("/create-transfer-token", "{\"filename\":\"app.apk\"}");
        String token = field(created.body(), "token");

        byte[] content = "actual content".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> resp = put("/transfer/upload", content, Map.of(
                "X-Transfer-Token", token,
                "X-Content-Sha256", "0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals(422, resp.statusCode(), resp.body());
        assertFalse(Files.exists(root.resolve("app.apk")), "file must not land on sha256 mismatch");
    }

    // -------------------------------------------------------------------------
    // Status endpoint
    // -------------------------------------------------------------------------

    @Test
    void status_reflectsBytesReceivedAndUnknownTokenIs404() throws Exception {
        startApp(1024L * 1024, 30);
        HttpResponse<String> created = post("/create-transfer-token", "{\"filename\":\"app.apk\"}");
        String token = field(created.body(), "token");

        byte[] chunk = "partial-data".getBytes(StandardCharsets.UTF_8);
        put("/transfer/upload", chunk, Map.of(
                "X-Transfer-Token", token, "X-Chunk-Offset", "0", "X-Chunk-Final", "false"));

        HttpResponse<String> status = get("/transfer/status?token=" + token);
        assertEquals(200, status.statusCode(), status.body());
        assertEquals(String.valueOf(chunk.length), field(status.body(), "bytes_received"));
        assertEquals("false", field(status.body(), "consumed"));

        HttpResponse<String> unknown = get("/transfer/status?token=deadbeef");
        assertEquals(404, unknown.statusCode(), unknown.body());
    }

    // -------------------------------------------------------------------------
    // #6: sha256 mismatch / finalize failure must not strand the token (Fable review finding)
    // -------------------------------------------------------------------------

    @Test
    void shaMismatch_resetsUploadAndAllowsRetryFromZeroWithSameToken() throws Exception {
        startApp(1024L * 1024, 30);
        HttpResponse<String> created = post("/create-transfer-token", "{\"filename\":\"app.apk\"}");
        String token = field(created.body(), "token");

        byte[] content = "actual content".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> mismatch = put("/transfer/upload", content, Map.of(
                "X-Transfer-Token", token,
                "X-Content-Sha256", "0000000000000000000000000000000000000000000000000000000000000000"));
        assertEquals(422, mismatch.statusCode(), mismatch.body());

        // The staging .part must be gone and bytesReceived reset to 0 — otherwise the client is
        // stuck: offset 0 would 409 (bytes_received still N), and there's no way to address the
        // bytes past N since the whole file was already (wrongly) sent.
        assertFalse(Files.exists(root.resolve(".transfer").resolve(token + ".part")),
                ".part must be deleted after a sha256 mismatch, not left as a full-size orphan");
        HttpResponse<String> status = get("/transfer/status?token=" + token);
        assertEquals("0", field(status.body(), "bytes_received"), "bytesReceived must reset to 0");
        assertEquals("false", field(status.body(), "consumed"), "token must remain usable, not consumed");

        // Same token, retried from offset 0, now with a correct (omitted) hash — must complete.
        HttpResponse<String> retried = put("/transfer/upload", content, Map.of("X-Transfer-Token", token));
        assertEquals(200, retried.statusCode(), retried.body());
        assertEquals("complete", field(retried.body(), "status"));
    }

    // -------------------------------------------------------------------------
    // #5: bytesReceived (memory) vs .part (disk) reconciliation (Fable review finding)
    // -------------------------------------------------------------------------

    @Test
    void diskSizeIsSourceOfTruth_whenMemoryCounterDriftsFromActualPartFile() throws Exception {
        startApp(1024L * 1024, 30);
        HttpResponse<String> created = post("/create-transfer-token", "{\"filename\":\"app.apk\"}");
        String token = field(created.body(), "token");

        // First chunk lands normally: memory and disk agree at this point.
        byte[] chunk1 = "first-chunk".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> firstResp = put("/transfer/upload", chunk1, Map.of(
                "X-Transfer-Token", token, "X-Chunk-Offset", "0", "X-Chunk-Final", "false"));
        assertEquals(200, firstResp.statusCode(), firstResp.body());

        // Simulate the drift scenario from the review finding: something appended extra bytes to
        // the .part file directly (standing in for "a previous request's write landed on disk
        // but the in-memory counter update didn't run"), so disk and memory now disagree.
        Path partPath = root.resolve(".transfer").resolve(token + ".part");
        Files.write(partPath, "EXTRA-ON-DISK".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        long actualOnDisk = Files.size(partPath);

        // A client that only trusts the (stale, lower) in-memory bytesReceived would offset from
        // chunk1.length and corrupt the file. The client must instead be told the disk truth.
        HttpResponse<String> wrongOffset = put("/transfer/upload", "next".getBytes(StandardCharsets.UTF_8), Map.of(
                "X-Transfer-Token", token, "X-Chunk-Offset", String.valueOf(chunk1.length), "X-Chunk-Final", "false"));
        assertEquals(409, wrongOffset.statusCode(), wrongOffset.body());
        assertEquals(String.valueOf(actualOnDisk), field(wrongOffset.body(), "bytes_received"),
                "409 must report the disk-reconciled size, not the stale in-memory one");

        // Uploading with the disk-truth offset must be accepted (proves reconciliation actually
        // updated the entry, not just the error body).
        HttpResponse<String> correctOffset = put("/transfer/upload", "next".getBytes(StandardCharsets.UTF_8), Map.of(
                "X-Transfer-Token", token, "X-Chunk-Offset", String.valueOf(actualOnDisk), "X-Chunk-Final", "false"));
        assertEquals(200, correctOffset.statusCode(), correctOffset.body());
    }

    // -------------------------------------------------------------------------
    // #1: token/.part lifecycle — sweep + startup cleanup (Fable review finding)
    // -------------------------------------------------------------------------

    @Test
    void sweep_removesExpiredAndConsumedTokensAndTheirPartFiles() throws Exception {
        startApp(1024L * 1024, 30);

        // An expired token with an orphaned .part file (transfer abandoned mid-way).
        TransferTokenStore.TokenEntry expired = tokenStore.createWithTtlMillis("orphan.apk", null, -1000L);
        Path expiredPart = root.resolve(".transfer").resolve(expired.token + ".part");
        Files.createDirectories(expiredPart.getParent());
        Files.writeString(expiredPart, "orphaned bytes");

        // A live (non-expired, non-consumed) token that must survive the sweep untouched.
        HttpResponse<String> created = post("/create-transfer-token", "{\"filename\":\"keep.apk\"}");
        String liveToken = field(created.body(), "token");

        int removed = routes.sweepExpiredAndConsumedTokens();

        assertEquals(1, removed, "only the expired entry should be swept");
        assertEquals(null, tokenStore.get(expired.token), "expired token must be removed from the store");
        assertFalse(Files.exists(expiredPart), "orphaned .part for the expired token must be deleted");
        assertTrue(tokenStore.get(liveToken) != null, "a live token must not be touched by the sweep");
    }

    @Test
    void startupCleanup_clearsPreExistingTransferStagingFiles(@TempDir Path freshRoot) throws Exception {
        Path realRoot = freshRoot.toRealPath();
        Path transferDir = realRoot.resolve(".transfer");
        Files.createDirectories(transferDir);
        Files.writeString(transferDir.resolve("leftover-from-previous-run.part"), "stale bytes");
        Files.writeString(realRoot.resolve("visible.apk"), "must survive");

        FilePathSandbox freshSandbox = new FilePathSandbox(realRoot);
        // Constructing TransferRoutes must clear .transfer/ contents (a restart invalidates every
        // in-memory token, so anything left there is an unambiguous orphan) without touching
        // anything else in the sandbox root.
        new TransferRoutes(freshSandbox, new TransferTokenStore(), 1024L * 1024, 30);

        assertEquals(0, countEntries(transferDir), ".transfer must be emptied on startup");
        assertTrue(Files.exists(realRoot.resolve("visible.apk")), "startup cleanup must not touch files outside .transfer");
    }

    private static long countEntries(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.count();
        }
    }

    // -------------------------------------------------------------------------
    // #8: .apks (split APK) support (Fable review finding)
    // -------------------------------------------------------------------------

    @Test
    void apksExtension_isAcceptedAtCreateAndUpload() throws Exception {
        startApp(1024L * 1024, 30);
        HttpResponse<String> created = post("/create-transfer-token", "{\"filename\":\"split.apks\"}");
        assertEquals(200, created.statusCode(), created.body());
        String token = field(created.body(), "token");

        byte[] content = "split apk set bytes".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> uploaded = put("/transfer/upload", content, Map.of("X-Transfer-Token", token));
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        assertTrue(Files.isRegularFile(root.resolve("split.apks")));
    }

    @Test
    void emptyStemFilename_rejectedAtCreate() throws Exception {
        startApp(1024L * 1024, 30);
        HttpResponse<String> resp = post("/create-transfer-token", "{\"filename\":\".apk\"}");
        assertEquals(400, resp.statusCode(), resp.body());
    }
}
