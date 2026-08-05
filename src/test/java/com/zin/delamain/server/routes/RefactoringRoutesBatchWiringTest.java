package com.zin.delamain.server.routes;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.core.RenameStorage;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;

import io.javalin.Javalin;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F8 (3): rename-package and import-rename-mappings used to acquire {@link
 * com.zin.delamain.utils.JadxSearchLock}'s write lock once PER item (M acquire/release cycles for
 * M classes/mappings) by calling the single-item {@link RenameStorage#renameClass}/{@code
 * renameMethod}/{@code renameField} in a loop -- see {@link RenameStorage.Batch}'s javadoc for why
 * that is a rename-perf problem (each acquire/release blocks every concurrent reader) and see
 * {@link com.zin.delamain.core.RenameStorageBatchInterruptTest} for the orderly-interrupt half of
 * this fix. This pins the wiring itself: the batch route must call {@link
 * RenameStorage#beginBatch()} exactly ONCE for the whole request, never the single-item API.
 *
 * <p>No mocking framework is a project dependency (see {@code TransferRoutesTest}), so a small
 * counting subclass of {@link RenameStorage} stands in for a mock, and requests go over a real
 * Javalin instance + {@link HttpClient}.</p>
 */
class RefactoringRoutesBatchWiringTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Javalin app;
    private HeadlessJadxWrapper wrapper;

    @BeforeEach
    void setUp(@TempDir Path workDir) throws Exception {
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());

        wrapper = new HeadlessJadxWrapper(List.of(apk), new File(workDir.toFile(), "out"), 2);
        wrapper.load();
        ClassCacheManager.initCache(wrapper);
        long deadline = System.currentTimeMillis() + 60_000;
        while (ClassCacheManager.getCache().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(!ClassCacheManager.getCache().isEmpty(), "class index must be built for this test");
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        if (wrapper != null) wrapper.close();
    }

    /** Counts calls instead of delegating to a mocking framework (none is a project dependency). */
    private static final class CountingRenameStorage extends RenameStorage {
        final AtomicInteger beginBatchCalls = new AtomicInteger();
        final AtomicInteger singleItemRenameClassCalls = new AtomicInteger();
        final AtomicInteger singleItemRenameMethodCalls = new AtomicInteger();
        final AtomicInteger singleItemRenameFieldCalls = new AtomicInteger();

        CountingRenameStorage(Path dataDir) throws IOException {
            super(dataDir);
        }

        @Override
        public synchronized Batch beginBatch() throws IOException {
            beginBatchCalls.incrementAndGet();
            return super.beginBatch();
        }

        @Override
        public synchronized void renameClass(JavaClass cls, String newName) throws IOException {
            singleItemRenameClassCalls.incrementAndGet();
            super.renameClass(cls, newName);
        }

        @Override
        public synchronized void renameMethod(JavaMethod method, String newName) throws IOException {
            singleItemRenameMethodCalls.incrementAndGet();
            super.renameMethod(method, newName);
        }

        @Override
        public synchronized void renameField(JavaField field, String newName) throws IOException {
            singleItemRenameFieldCalls.incrementAndGet();
            super.renameField(field, newName);
        }
    }

    private String startApp(CountingRenameStorage storage) {
        RefactoringRoutes routes = new RefactoringRoutes(wrapper, storage);
        app = Javalin.create();
        routes.register(app, new AuthConfig(null, false));
        app.start(0);
        return "http://127.0.0.1:" + app.port();
    }

    private HttpResponse<String> post(String base, String path, String jsonBody) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void renamePackage_acquiresBatchOnce_neverCallsSingleItemApi(@TempDir Path workDir) throws Exception {
        CountingRenameStorage storage = new CountingRenameStorage(Path.of(workDir.toString(), "renames"));
        String base = startApp(storage);

        HttpResponse<String> resp = post(base, "/rename-package",
                "{\"old_package_name\":\"owasp.mstg.uncrackable1\",\"new_package_name\":\"owasp.mstg.renamed1\"}");

        assertEquals(200, resp.statusCode(), "unexpected response: " + resp.body());
        assertTrue(resp.body().contains("\"renamed\""), "response must report a renamed count: " + resp.body());
        assertEquals(1, storage.beginBatchCalls.get(),
                "rename-package must acquire the batch write lock exactly ONCE for the whole package, "
                        + "not once per class: " + resp.body());
        assertEquals(0, storage.singleItemRenameClassCalls.get(),
                "rename-package must never fall back to the single-item per-class API: " + resp.body());
    }

    @Test
    void importRenameMappings_acquiresBatchOnce_neverCallsSingleItemApi(@TempDir Path workDir) throws Exception {
        CountingRenameStorage storage = new CountingRenameStorage(Path.of(workDir.toString(), "renames"));
        String base = startApp(storage);

        String mappings = "{\"mappings\":["
                + "{\"type\":\"class\",\"original_name\":\"owasp.mstg.uncrackable1.R\",\"new_name\":\"owasp.mstg.uncrackable1.RImported\",\"class_context\":\"\"},"
                + "{\"type\":\"class\",\"original_name\":\"owasp.mstg.uncrackable1.R.id\",\"new_name\":\"owasp.mstg.uncrackable1.RImported.idImported\",\"class_context\":\"\"}"
                + "]}";

        HttpResponse<String> resp = post(base, "/import-rename-mappings", mappings);

        assertEquals(200, resp.statusCode(), "unexpected response: " + resp.body());
        assertEquals(1, storage.beginBatchCalls.get(),
                "import-rename-mappings must acquire the batch write lock exactly ONCE for the whole "
                        + "array, not once per entry: " + resp.body());
        assertEquals(0, storage.singleItemRenameClassCalls.get(),
                "import-rename-mappings must never fall back to the single-item per-class API: " + resp.body());
        assertEquals(0, storage.singleItemRenameMethodCalls.get());
        assertEquals(0, storage.singleItemRenameFieldCalls.get());
    }

    /**
     * F8-b: {@code /apply-proguard-mapping} is the third instance of the exact same pattern as
     * {@code /import-rename-mappings} -- a per-entry loop over class renames only, with identical
     * per-entry failure isolation (a bad entry increments {@code failed} and {@code continue}s,
     * it never aborts the whole request). Same defect, same fix.
     */
    @Test
    void applyProguardMapping_acquiresBatchOnce_neverCallsSingleItemApi(@TempDir Path workDir) throws Exception {
        CountingRenameStorage storage = new CountingRenameStorage(Path.of(workDir.toString(), "renames"));
        String base = startApp(storage);

        // ProGuard mapping-file syntax: "<original human name> -> <obfuscated name>:" -- the
        // handler renames whatever the APK currently calls "R" back to the human-readable name.
        String mappingContent = "owasp.mstg.uncrackable1.RenamedHuman -> owasp.mstg.uncrackable1.R:\n";
        String body = new com.google.gson.Gson().toJson(java.util.Map.of("mapping_content", mappingContent));

        HttpResponse<String> resp = post(base, "/apply-proguard-mapping", body);

        assertEquals(200, resp.statusCode(), "unexpected response: " + resp.body());
        assertTrue(resp.body().contains("\"applied\":1"), "expected exactly one applied rename: " + resp.body());
        assertEquals(1, storage.beginBatchCalls.get(),
                "apply-proguard-mapping must acquire the batch write lock exactly ONCE for the whole "
                        + "mapping file, not once per entry: " + resp.body());
        assertEquals(0, storage.singleItemRenameClassCalls.get(),
                "apply-proguard-mapping must never fall back to the single-item per-class API: " + resp.body());
    }
}
