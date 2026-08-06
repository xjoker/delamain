package com.zin.delamain.server.routes;

import com.google.gson.Gson;
import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.core.RenameStorage;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;

import io.javalin.Javalin;

import jadx.api.ICodeInfo;
import jadx.api.JavaClass;
import jadx.api.JavaNode;
import jadx.api.JavaVariable;
import jadx.api.metadata.ICodeAnnotation;

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
 * 局部变量重命名（{@code /rename-variable}）在 headless 下的行为契约。
 *
 * <p>断言落在**反编译输出**上，而不是内部状态：变量重命名唯一的价值就是让下一次读到的源码变得
 * 可读，所以「改完之后 getCode() 里出现新名字、旧名字消失」才是这个功能是否work的判据。</p>
 *
 * <p>局部变量与类/方法/字段有一处本质不同：后三者的别名挂在长期存活的 node 上，
 * {@code unloadFromCache()} 不会丢；变量名活在每次反编译都会从字节码重建的 SSA 状态里，
 * 直接 {@code VarNode.setName()} 会在下一次反编译时被冲掉。因此实现必须走 jadx 的
 * {@code JadxCodeRename} + {@code reloadCodeData()} 规则表，让 {@code CodeRenameVisitor}
 * 在每次反编译时重新施加。{@link #renamedVariableSurvivesRepeatedDecompile()} 专门钉这一点——
 * 它是这个功能最容易被实现错的地方。</p>
 */
class RefactoringRoutesVariableRenameTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static final File TEST_APK = new File("test-harness/real/UnCrackable-Level1.apk");
    private static final String NEW_NAME = "decryptedCipherForTest";

    private HeadlessJadxWrapper wrapper;
    private Javalin app;
    private String base;

    /** A real local variable discovered from code metadata, so the fixture is not hardcoded. */
    private JavaClass fixtureClass;
    private String fixtureMethod;
    private String fixtureVariable;

    @BeforeEach
    void setUp(@TempDir Path workDir) throws Exception {
        assertTrue(TEST_APK.exists(), "test APK must exist: " + TEST_APK.getAbsolutePath());

        wrapper = new HeadlessJadxWrapper(List.of(TEST_APK), new File(workDir.toFile(), "out"), 2);
        wrapper.load();

        ClassCacheManager.initCache(wrapper);
        long deadline = System.currentTimeMillis() + 60_000;
        while (ClassCacheManager.getCache().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(ClassCacheManager.getCache().isEmpty(), "class index must be built for this test");

        // Local variables appear as DECLARATION annotations wrapping a VarNode. Metadata only
        // exists once the class has actually been decompiled, so force that first.
        outer:
        for (JavaClass cls : wrapper.getClasses()) {
            cls.getCode();
            ICodeInfo info = cls.getCodeInfo();
            if (info == null || !info.hasMetadata()) continue;
            for (ICodeAnnotation ann : info.getCodeMetadata().getAsMap().values()) {
                if (ann.getAnnType() != ICodeAnnotation.AnnType.DECLARATION) continue;
                JavaNode node = wrapper.getJadx().getJavaNodeByCodeAnnotation(info, ann);
                if (node instanceof JavaVariable jv) {
                    fixtureClass = cls;
                    fixtureMethod = jv.getMth().getName();
                    fixtureVariable = jv.getName();
                    break outer;
                }
            }
        }
        assertNotNull(fixtureVariable,
                "test APK must contain at least one named local variable, else this proves nothing");

        RenameStorage renameStorage = new RenameStorage(Path.of(workDir.toString(), "renames"));
        app = Javalin.create();
        new RefactoringRoutes(wrapper, renameStorage).register(app, new AuthConfig(null, false));
        app.start(0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        if (wrapper != null) wrapper.close();
    }

    private HttpResponse<String> postRaw(String path, String jsonBody) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String renameBody(String cls, String mth, String var, String newName) {
        return "{\"class_name\":\"" + cls + "\",\"method_name\":\"" + mth
                + "\",\"variable_name\":\"" + var + "\",\"new_name\":\"" + newName + "\"}";
    }

    @Test
    @SuppressWarnings("unchecked")
    void renameVariable_rewritesTheNameInDecompiledSource() throws Exception {
        String before = fixtureClass.getCode();
        assertTrue(before.contains(fixtureVariable),
                "fixture variable '" + fixtureVariable + "' must appear in the source to begin with");

        HttpResponse<String> resp = postRaw("/rename-variable",
                renameBody(fixtureClass.getFullName(), fixtureMethod, fixtureVariable, NEW_NAME));
        assertEquals(200, resp.statusCode(), "response: " + resp.body());

        Map<String, Object> body = GSON.fromJson(resp.body(), Map.class);
        assertNotNull(body.get("result"), "response: " + body);
        // Red-line: any response naming a class or method carries the APK's own name too.
        assertNotNull(body.get("raw_class_name"), "response: " + body);
        assertNotNull(body.get("raw_method_name"), "response: " + body);

        String after = fixtureClass.getCode();
        assertTrue(after.contains(NEW_NAME),
                "renamed variable must appear in the decompiled source: " + after);
    }

    @Test
    void renamedVariableSurvivesRepeatedDecompile() throws Exception {
        assertEquals(200, postRaw("/rename-variable",
                renameBody(fixtureClass.getFullName(), fixtureMethod, fixtureVariable, NEW_NAME))
                .statusCode());

        // Variable names are rebuilt from bytecode on every decompile. A rename applied by mutating
        // the live VarNode disappears here; only a rule re-applied by CodeRenameVisitor survives.
        fixtureClass.getClassNode().unloadFromCache();
        assertTrue(fixtureClass.getCode().contains(NEW_NAME),
                "rename must survive re-decompilation, not just the first getCode()");
    }

    @Test
    void renameVariable_unknownVariableIs404() throws Exception {
        HttpResponse<String> resp = postRaw("/rename-variable",
                renameBody(fixtureClass.getFullName(), fixtureMethod, "noSuchVariable", NEW_NAME));
        assertEquals(404, resp.statusCode(), "response: " + resp.body());
    }

    @Test
    void renameVariable_unknownClassIs404() throws Exception {
        HttpResponse<String> resp = postRaw("/rename-variable",
                renameBody("no.such.Class", "m", "v", NEW_NAME));
        assertEquals(404, resp.statusCode(), "response: " + resp.body());
    }
}
