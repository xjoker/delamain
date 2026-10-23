package com.zin.delamain.server.routes;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.WarmupManager;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.server.AuthConfig;

import io.javalin.Javalin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SQLite workspace must follow WarmupManager's canonical input identity.  These are real
 * HTTP and SQLite integration tests: neither the JDBC layer nor the route's SQL is mocked.
 */
class AnnotationRoutesIdentityTest {
    private static final Gson GSON = new Gson();
    private Javalin app;
    private HeadlessJadxWrapper wrapper;
    private HttpClient http;

    @BeforeEach
    void setUp() {
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (http != null) http.close();
        if (app != null) app.stop();
        waitForWarmup();
        if (wrapper != null) wrapper.close();
        WarmupManager.resetIdentityForReload();
        WarmupManager.setIndexDir(new File(System.getProperty("java.io.tmpdir"), "delamain-index").toPath());
        ContentShardIndex.clear();
        CodeContentIndex.clear();
    }

    @Test
    void readyIdentityScopesEveryDefaultWriteAndRejectsConflictingBodyHash(@TempDir Path tempDir)
            throws Exception {
        String identity = publishIdentity(apk("UnCrackable-Level1.apk"), tempDir.resolve("index-a"));
        Path db = tempDir.resolve("annotations.db");
        startApp(db);

        assertCreatedWithIdentity(post("/annotations", annotationBody()), identity);
        assertCreatedWithIdentity(post("/bookmarks", bookmarkBody()), identity);
        assertCreatedWithIdentity(post("/tags", tagBody()), identity);
        assertRowsUseOnly(db, identity, 3);

        assertCreatedWithIdentity(post("/annotations",
                annotationBody().replace("}", ",\"apk_hash\":\"" + identity + "\"}")), identity);
        String conflicting = "other-apk";
        assertEquals(409, post("/annotations", annotationBody().replace("}", ",\"apk_hash\":\"" + conflicting + "\"}")).statusCode());
        assertEquals(409, post("/bookmarks", bookmarkBody().replace("}", ",\"apk_hash\":\"" + conflicting + "\"}")).statusCode());
        assertEquals(409, post("/tags", tagBody().replace("}", ",\"apk_hash\":\"" + conflicting + "\"}")).statusCode());
        assertRowsUseOnly(db, identity, 4);
    }

    @Test
    void pendingIdentityRejectsImplicitWritesAndKeepsLegacyRowsAvailableByExplicitHash(@TempDir Path tempDir)
            throws Exception {
        WarmupManager.resetIdentityForReload();
        Path db = tempDir.resolve("annotations.db");
        startApp(db);

        assertEquals(409, post("/annotations", annotationBody()).statusCode());
        assertEquals(409, post("/bookmarks", bookmarkBody()).statusCode());
        assertEquals(409, post("/tags", tagBody()).statusCode());
        assertEquals(0, totalRows(db), "pending writes must not create SQLite rows");
        assertEquals(409, get("/annotations").statusCode(), "implicit reads must not fall back to unknown");
        assertEquals(409, get("/analysis-notes").statusCode(), "summary must also require a ready identity");

        String legacy = "legacy-path-hash";
        seedLegacyRows(db, legacy);
        assertEquals(1, json(get("/annotations?apk_hash=" + legacy)).get("count").getAsInt());
        assertEquals(1, json(get("/bookmarks?apk_hash=" + legacy)).get("count").getAsInt());
        assertEquals(1, json(get("/tags?apk_hash=" + legacy)).get("count").getAsInt());
        JsonObject notes = json(get("/analysis-notes?apk_hash=" + legacy));
        assertEquals(legacy, notes.get("apk_hash").getAsString());
        assertEquals(1, notes.get("annotations_count").getAsInt());
        assertEquals(1, notes.get("bookmarks_count").getAsInt());
        assertEquals(1, notes.get("tags_count").getAsInt());
    }

    @Test
    void changingCurrentIdentitySeparatesDefaultNamespaces(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("annotations.db");
        String identityA = publishIdentity(apk("UnCrackable-Level1.apk"), tempDir.resolve("index-a"));
        startApp(db);
        assertCreatedWithIdentity(post("/annotations", annotationBody()), identityA);

        waitForWarmup();
        wrapper.close();
        wrapper = null;
        WarmupManager.resetIdentityForReload();
        String identityB = publishIdentity(apk("UnCrackable-Level2.apk"), tempDir.resolve("index-b"));
        assertNotEquals(identityA, identityB, "distinct APKs must not share a workspace identity");

        assertEquals(0, json(get("/annotations")).get("count").getAsInt());
        assertEquals(1, json(get("/annotations?apk_hash=" + identityA)).get("count").getAsInt());
        assertCreatedWithIdentity(post("/annotations", annotationBody()), identityB);
        assertEquals(2, totalRows(db));
        assertEquals(1, countRowsForHash(db, identityA));
        assertEquals(1, countRowsForHash(db, identityB));
    }

    private void startApp(Path db) {
        app = Javalin.create();
        new AnnotationRoutes(null, db.toString()).register(app, new AuthConfig(null, false));
        app.start(0);
    }

    private String publishIdentity(File apk, Path indexDir) throws Exception {
        WarmupManager.setIndexDir(indexDir);
        Path outDir = indexDir.resolve("out");
        Files.createDirectories(outDir);
        wrapper = new HeadlessJadxWrapper(List.of(apk), outDir.toFile(), 2);
        wrapper.load();
        WarmupManager.start(wrapper, false);
        long deadline = System.currentTimeMillis() + 120_000;
        while (WarmupManager.getCurrentInputHash() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        String identity = WarmupManager.getCurrentInputHash();
        assertNotNull(identity, "warmup must publish the current APK identity");
        return identity;
    }

    private void waitForWarmup() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while ((Boolean.TRUE.equals(WarmupManager.getStatus().get("running"))
                || WarmupManager.isShardBuildRunning() || WarmupManager.isUsePlacesHarvestRunning())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(!Boolean.TRUE.equals(WarmupManager.getStatus().get("running")),
                "warmup did not finish: " + WarmupManager.getStatus());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://127.0.0.1:" + app.port();
    }

    private static JsonObject json(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return GSON.fromJson(response.body(), JsonObject.class);
    }

    private static void assertCreatedWithIdentity(HttpResponse<String> response, String identity) {
        assertEquals(201, response.statusCode(), response.body());
        assertEquals(identity, GSON.fromJson(response.body(), JsonObject.class).get("apk_hash").getAsString());
    }

    private static File apk(String name) {
        File apk = new File("test-harness/real/" + name);
        assertTrue(apk.isFile(), "test APK must exist: " + apk.getAbsolutePath());
        return apk;
    }

    private static String annotationBody() {
        return "{\"target_type\":\"class\",\"target_name\":\"Example\",\"content\":\"note\"}";
    }

    private static String bookmarkBody() {
        return "{\"target_type\":\"class\",\"target_name\":\"Example\",\"label\":\"bookmark\"}";
    }

    private static String tagBody() {
        return "{\"target_type\":\"class\",\"target_name\":\"Example\",\"tag\":\"review\"}";
    }

    private static int totalRows(Path db) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             ResultSet rs = conn.createStatement().executeQuery(
                     "SELECT (SELECT count(*) FROM annotations) + (SELECT count(*) FROM bookmarks) + (SELECT count(*) FROM tags)")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int countRowsForHash(Path db, String hash) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT (SELECT count(*) FROM annotations WHERE apk_hash = ?) + (SELECT count(*) FROM bookmarks WHERE apk_hash = ?) + (SELECT count(*) FROM tags WHERE apk_hash = ?)")) {
            ps.setString(1, hash);
            ps.setString(2, hash);
            ps.setString(3, hash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void assertRowsUseOnly(Path db, String hash, int expected) throws Exception {
        assertEquals(expected, totalRows(db));
        assertEquals(expected, countRowsForHash(db, hash));
    }

    private static void seedLegacyRows(Path db, String hash) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            insert(conn, "INSERT INTO annotations (apk_hash, target_type, target_name, content) VALUES (?,?,?,?)", hash, "legacy-note");
            insert(conn, "INSERT INTO bookmarks (apk_hash, target_type, target_name, label) VALUES (?,?,?,?)", hash, "legacy-bookmark");
            insert(conn, "INSERT INTO tags (apk_hash, target_type, target_name, tag) VALUES (?,?,?,?)", hash, "legacy-tag");
        }
    }

    private static void insert(Connection conn, String sql, String hash, String value) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setString(2, "class");
            ps.setString(3, "Legacy");
            ps.setString(4, value);
            ps.executeUpdate();
        }
    }
}
