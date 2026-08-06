package com.zin.delamain.server.routes;

import com.google.gson.Gson;
import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.UsageGraphIndex;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.PaginationUtils;

import io.javalin.Javalin;

import jadx.api.JavaClass;

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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 红线守卫（{@link DiscoveryRawNameCoverageTest} 的同一条红线，单独成类）：{@code /callers-chain}
 * 的每一层类名都必须同时给出显示名和原始混淆名。
 *
 * <p>这个端点单独成类是因为它需要 {@link UsageGraphIndex} 就绪 —— 而那是一份进程级静态图，
 * 建图前还得把每个类解出来 {@code getUseIn()} 才有内容。把这份 setup 摊到发现类端点的每个用例上
 * 不值得，所以只在这里付一次。</p>
 *
 * <p>callers-chain 是导航入口：追到"谁能到达这个加密类"之后，下一步动作就是 hook。只给显示名
 * 等于把调用方交给一个 Frida 找不到的名字。</p>
 */
class CallersChainRawNameTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static final File TEST_APK = new File("test-harness/real/UnCrackable-Level1.apk");

    private HeadlessJadxWrapper wrapper;
    private Javalin app;
    private String base;
    /** A class that at least one other class references — otherwise every layer is empty. */
    private JavaClass targetWithCallers;

    @BeforeEach
    void setUp(@TempDir Path workDir) throws Exception {
        org.junit.jupiter.api.Assertions.assertTrue(
                TEST_APK.exists(), "test APK must exist: " + TEST_APK.getAbsolutePath());

        wrapper = new HeadlessJadxWrapper(List.of(TEST_APK), new File(workDir.toFile(), "out"), 2);
        wrapper.load();

        // ClassCacheManager is a process-wide singleton — a prior test class in the same fork can
        // leave it READY for a *different* wrapper (see DiscoveryRawNameCoverageTest).
        ClassCacheManager.initCache(wrapper);
        long cacheDeadline = System.currentTimeMillis() + 60_000;
        while (ClassCacheManager.getCache().isEmpty() && System.currentTimeMillis() < cacheDeadline) {
            Thread.sleep(50);
        }
        assertFalse(ClassCacheManager.getCache().isEmpty(), "class index must be built for this test");

        // getUseIn() is only populated once a class has been processed, so decompile the whole
        // (tiny) APK before harvesting the graph. UsageGraphIndex.build() has the same precondition
        // in production, where warmup Phase-1 satisfies it.
        List<JavaClass> sorted = UsageGraphIndex.sortByFullName(wrapper.getClasses());
        for (JavaClass cls : sorted) {
            cls.getCode();
        }
        UsageGraphIndex.build(sorted);

        targetWithCallers = sorted.stream()
                .filter(c -> {
                    List<JavaClass> refs = UsageGraphIndex.referrersOf(c);
                    return refs != null && !refs.isEmpty();
                })
                .findFirst()
                .orElse(null);
        assertNotNull(targetWithCallers,
                "test APK must contain at least one class with referrers, else this guard proves nothing");

        app = Javalin.create();
        new XrefsRoutes(wrapper, new PaginationUtils())
                .register(app, new com.zin.delamain.server.AuthConfig(null, false));
        app.start(0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        // Process-wide static graph: leaving it built would make an unrelated later test see a
        // ready index pointing at this test's (closed) wrapper.
        UsageGraphIndex.clear();
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

    @Test
    @SuppressWarnings("unchecked")
    void callersChain_everyLayerEntryCarriesRawName() throws Exception {
        Map<String, Object> body = get(
                "/callers-chain?class_name=" + targetWithCallers.getFullName() + "&depth=3");

        assertNotNull(body.get("target"), "response: " + body);
        assertNotNull(body.get("raw_target"),
                "callers-chain must echo raw_target — the caller reached this class by display "
                        + "name and still needs the name Frida/Xposed can target: " + body);

        List<Object> layers = (List<Object>) body.get("layers");
        assertFalse(layers == null || layers.isEmpty(),
                "expected at least one caller layer for " + targetWithCallers.getFullName() + ": " + body);

        int checked = 0;
        for (Object layerObj : layers) {
            Map<String, Object> layer = (Map<String, Object>) layerObj;
            List<Object> classes = (List<Object>) layer.get("classes");
            assertNotNull(classes, "layer must carry 'classes': " + layer);
            for (Object entry : classes) {
                assertFalse(entry instanceof String,
                        "layer entries must be {name, raw_name} objects, not bare display-name "
                                + "strings: " + entry);
                Map<String, Object> m = (Map<String, Object>) entry;
                assertNotNull(m.get("name"), "layer entry must keep display 'name': " + m);
                assertNotNull(m.get("raw_name"),
                        "layer entry must carry 'raw_name' (the only name Frida/Xposed can use): " + m);
                checked++;
            }
        }
        assertFalse(checked == 0, "no layer entries were checked — guard proves nothing: " + body);
    }

    @Test
    void callersChain_unknownClassStillReturns404() throws Exception {
        // The raw-name change must not alter the not-found contract.
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(base + "/callers-chain?class_name=no.such.Class"))
                        .timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode(), "response: " + resp.body());
        assertNull(GSON.fromJson(resp.body(), Map.class).get("layers"));
    }
}
