package com.zin.delamain.server.routes;

import com.google.gson.Gson;
import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.core.RenameStorage;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.PaginationUtils;

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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红线守卫：Frida/Xposed hook 只认原始混淆名（见项目规范）。发现类端点（搜索/方法源码/批量取源/重命名/
 * 调用图/交叉引用）必须在返回显示名（去混淆后，供 AI 阅读）的同时返回对应的原始名（APK 内真实名，唯一可供
 * Frida 使用）。本测试锁定这一契约：漏掉任何一处 raw_name 系字段都会让这个测试变红。
 *
 * <p>对真实 {@code test-harness/real/UnCrackable-Level1.apk} 起真实 {@link HeadlessJadxWrapper} +
 * 真实 Javalin 路由，不 mock —— 与 {@code RefactoringRoutesBatchWiringTest} 同一模式。</p>
 */
class DiscoveryRawNameCoverageTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static final File TEST_APK = new File("test-harness/real/UnCrackable-Level1.apk");
    private static final String MAIN_ACTIVITY = "sg.vantagepoint.uncrackable1.MainActivity";

    private HeadlessJadxWrapper wrapper;
    private Javalin app;
    private String base;

    @BeforeEach
    void setUp(@TempDir Path workDir) throws Exception {
        assertTrue(TEST_APK.exists(), "test APK must exist: " + TEST_APK.getAbsolutePath());

        wrapper = new HeadlessJadxWrapper(List.of(TEST_APK), new File(workDir.toFile(), "out"), 2);
        wrapper.load();

        // ClassCacheManager is a process-wide singleton (see e.g. RefactoringRoutesBatchWiringTest):
        // a route handler only calls initCache() lazily when status==NOT_INITIALIZED, so a prior
        // test class in the same JVM fork can leave it READY for a *different* wrapper's classes.
        // Force + wait for it to (re)build against *this* wrapper before any HTTP request below.
        ClassCacheManager.initCache(wrapper);
        long cacheDeadline = System.currentTimeMillis() + 60_000;
        while (ClassCacheManager.getCache().isEmpty() && System.currentTimeMillis() < cacheDeadline) {
            Thread.sleep(50);
        }
        assertTrue(!ClassCacheManager.getCache().isEmpty(), "class index must be built for this test");

        PaginationUtils paginationUtils = new PaginationUtils();
        RenameStorage renameStorage = new RenameStorage(Path.of(workDir.toString(), "renames"));
        SearchRoutes searchRoutes = new SearchRoutes(wrapper, paginationUtils);

        app = Javalin.create();
        AuthConfig auth = new AuthConfig(null, false);
        searchRoutes.register(app, auth);
        new MethodRoutes(wrapper).register(app, auth);
        new BatchRoutes(wrapper, searchRoutes).register(app, auth);
        new RefactoringRoutes(wrapper, renameStorage).register(app, auth);
        new AnalysisRoutes(wrapper).register(app, auth);
        new XrefsRoutes(wrapper, paginationUtils).register(app, auth);
        app.start(0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        if (wrapper != null) wrapper.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) throws Exception {
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "unexpected status for " + path + ": " + resp.body());
        return GSON.fromJson(resp.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, String jsonBody) throws Exception {
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "unexpected status for " + path + ": " + resp.body());
        return GSON.fromJson(resp.body(), Map.class);
    }

    // ------------------------------- SearchRoutes -------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void searchClassesByKeyword_returnsRawNameForEveryMatch() throws Exception {
        Map<String, Object> body = get(
                "/search-classes-by-keyword?search_term=MainActivity&search_in=class&count=20");
        List<Object> classes = (List<Object>) body.get("classes");
        assertFalse(classes.isEmpty(), "expected at least one class match: " + body);
        for (Object entry : classes) {
            Map<String, Object> m = (Map<String, Object>) entry;
            assertNotNull(m.get("name"), "class entry must keep display 'name': " + m);
            assertNotNull(m.get("raw_name"),
                    "class entry must carry 'raw_name' (the only name Frida/Xposed can use): " + m);
        }
    }

    // ------------------------------- MethodRoutes -------------------------------

    @Test
    void methodSource_includesRawClassName() throws Exception {
        Map<String, Object> body = get(
                "/method-source?class_name=" + MAIN_ACTIVITY + "&method_name=onCreate");
        assertNotNull(body.get("class_name"), "response: " + body);
        assertNotNull(body.get("raw_class_name"),
                "get_method_source must carry raw_class_name so a caller can Frida-hook the "
                        + "returned method without a second lookup: " + body);
        assertNotNull(body.get("raw_method_name"), "response: " + body);
    }

    // ------------------------------- BatchRoutes --------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void batchClassSource_includesRawNameForResolvedClass() throws Exception {
        Map<String, Object> body = get("/batch-class-source?class_names=" + MAIN_ACTIVITY);
        List<Object> classes = (List<Object>) body.get("classes");
        assertFalse(classes.isEmpty(), "response: " + body);
        Map<String, Object> entry = (Map<String, Object>) classes.get(0);
        assertEquals(Boolean.TRUE, entry.get("found"), "entry: " + entry);
        assertNotNull(entry.get("raw_name"),
                "batch-class-source must carry raw_name for a resolved class: " + entry);
    }

    // ----------------------------- RefactoringRoutes -----------------------------

    @Test
    void renameClass_responseIncludesRawName() throws Exception {
        Map<String, Object> body = post("/rename-class",
                "{\"class_name\":\"" + MAIN_ACTIVITY + "\",\"new_name\":\"MainActivityRenamedForTest\"}");
        assertNotNull(body.get("result"), "response: " + body);
        assertNotNull(body.get("raw_name"),
                "rename-class must echo raw_name so the caller keeps the Frida-safe identifier "
                        + "through the rename workflow: " + body);
    }

    // ------------------------------- AnalysisRoutes ------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void exportCallgraph_rootNodeIncludesRawClassAndRawMethod() throws Exception {
        Map<String, Object> body = get(
                "/export-callgraph?class=" + MAIN_ACTIVITY + "&method=onCreate&depth=1&format=json");
        List<Object> nodes = (List<Object>) body.get("nodes");
        assertFalse(nodes.isEmpty(), "response: " + body);
        Map<String, Object> root = (Map<String, Object>) nodes.get(0);
        assertNotNull(root.get("class"), "node: " + root);
        assertNotNull(root.get("raw_class"),
                "export-callgraph node must carry raw_class: " + root);
        assertNotNull(root.get("raw_method"),
                "export-callgraph node must carry raw_method: " + root);
    }

    // -------------------------- XrefsRoutes (regression lock) --------------------

    @Test
    @SuppressWarnings("unchecked")
    void xrefsToClass_alreadyCarriesRawClassOnEveryReference() throws Exception {
        // XrefsRoutes was already fully covered before this task (raw_class/raw_method/
        // raw_from_method on every code path) -- this pins that finding so it cannot silently
        // regress, it is not a fix.
        Map<String, Object> body = get("/xrefs-to-class?class_name=" + MAIN_ACTIVITY);
        List<Object> references = (List<Object>) body.get("references");
        for (Object entry : references) {
            Map<String, Object> m = (Map<String, Object>) entry;
            assertTrue(m.containsKey("raw_class"), "reference entry must carry raw_class: " + m);
        }
    }
}
