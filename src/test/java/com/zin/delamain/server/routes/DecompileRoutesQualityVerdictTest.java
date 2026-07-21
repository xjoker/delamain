package com.zin.delamain.server.routes;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.JadxError;
import jadx.core.dex.nodes.ClassNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "smali degradation signal" — structured verdict computation, unit-tested directly against
 * {@link DecompileRoutes#computeVerdict(ClassNode)} (package-private) using real {@link ClassNode}
 * instances decompiled from the project's real test APKs (mirrors {@code SearchRoutesShardPruneTest}).
 *
 * <p>Deliberately does NOT assert on comment-marker text scanning (that is a separate, best-effort
 * diagnostic in {@code /decompile-diag} and must never be conflated with this authoritative
 * verdict) — only on the three structured JADX signals: JadxError list, processComplete state,
 * and the FALLBACK decompile-mode override.</p>
 */
class DecompileRoutesQualityVerdictTest {

    private static JadxDecompiler jadx1;
    private static JadxDecompiler jadx2;

    @BeforeAll
    static void setUp() {
        jadx1 = loadDecompiler("test-harness/real/UnCrackable-Level1.apk");
        jadx2 = loadDecompiler("test-harness/real/UnCrackable-Level2.apk");
    }

    @AfterAll
    static void tearDown() {
        if (jadx1 != null) jadx1.close();
        if (jadx2 != null) jadx2.close();
    }

    private static JadxDecompiler loadDecompiler(String path) {
        File apk = new File(path);
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        JadxArgs args = new JadxArgs();
        args.setInputFile(apk);
        args.setSkipResources(true);
        JadxDecompiler d = new JadxDecompiler(args);
        d.load();
        return d;
    }

    @SuppressWarnings("JadxInternalApiUsage")
    private static ClassNode nodeOf(JavaClass cls) {
        cls.getCode(); // force decompile so process state / attributes are populated
        return cls.getClassNode();
    }

    /**
     * Guard against false positives: a normal, cleanly-decompiled business class must report
     * "ok" with no hint, even though it commonly contains ordinary comments that a naive
     * text-marker regex could mistake for a warning.
     */
    @Test
    void normalBusinessClassIsOk() {
        JavaClass cls = findClass(jadx1, "sg.vantagepoint.uncrackable1.MainActivity");
        assertNotNull(cls, "expected MainActivity in UnCrackable-Level1.apk");

        Object verdict = DecompileRoutes.computeVerdict(nodeOf(cls));
        assertEquals("ok", quality(verdict), "a normally-decompiled class must never be misreported as degraded/failed");
        assertNull(reason(verdict));
    }

    /**
     * Guard against false positives: a class containing native methods (no code body — JADX
     * cannot and does not need to decompile the native method's body) must not be misreported
     * as failed/degraded merely because it has no-code methods.
     */
    @Test
    void classWithNativeMethodsIsOk() {
        JavaClass cls = findClass(jadx2, "sg.vantagepoint.uncrackable2.MainActivity");
        assertNotNull(cls, "expected MainActivity (declares native init()) in UnCrackable-Level2.apk");

        boolean hasNative = false;
        for (JavaMethod m : cls.getMethods()) {
            if (m.getAccessFlags() != null && m.getAccessFlags().isNative()) {
                hasNative = true;
                break;
            }
        }
        assertTrue(hasNative, "precondition: class must actually declare a native method");

        Object verdict = DecompileRoutes.computeVerdict(nodeOf(cls));
        assertEquals("ok", quality(verdict), "a native-method class must not be misreported as degraded/failed");
        assertNull(reason(verdict));
    }

    /**
     * A class carrying a JadxError attribute (the hard-failure structured signal) must be
     * reported as "failed" with a reason and (via the endpoint-level hint) point at
     * get_smali_of_class. Injects a real JadxError the same way JADX's own error-recording path
     * (ErrorsCounter) would, onto a real decompiled ClassNode, rather than trying to force a
     * genuine decompile failure out of a fixture APK.
     *
     * <p>Uses its own dedicated, locally-scoped {@link JadxDecompiler} instance (not the shared
     * {@code jadx1}/{@code jadx2} fixtures) — {@code JavaClass}/{@code ClassNode} instances are
     * cached per-decompiler, so mutating a shared instance's attributes here would leak into
     * {@link #normalBusinessClassIsOk()} depending on JUnit's (unspecified) method execution
     * order.</p>
     */
    @Test
    void classWithJadxErrorIsFailed() {
        JadxDecompiler local = loadDecompiler("test-harness/real/UnCrackable-Level1.apk");
        try {
            JavaClass cls = findClass(local, "sg.vantagepoint.uncrackable1.MainActivity");
            assertNotNull(cls, "expected MainActivity in UnCrackable-Level1.apk");
            ClassNode node = nodeOf(cls);

            node.addAttr(AType.JADX_ERROR, new JadxError("synthetic decompile failure for test", null));

            Object verdict = DecompileRoutes.computeVerdict(node);
            assertEquals("failed", quality(verdict));
            assertNotNull(reason(verdict));
            assertTrue(reason(verdict).contains("jadx error"));
        } finally {
            local.close();
        }
    }

    @Test
    void nullClassNodeIsFailed() {
        Object verdict = DecompileRoutes.computeVerdict(null);
        assertEquals("failed", quality(verdict));
    }

    private static JavaClass findClass(JadxDecompiler d, String fullName) {
        for (JavaClass cls : d.getClasses()) {
            if (fullName.equals(cls.getFullName())) {
                return cls;
            }
        }
        return null;
    }

    // Reflective accessors: DecompileVerdict is a private nested type of DecompileRoutes.
    private static String quality(Object verdict) {
        return (String) field(verdict, "quality");
    }

    private static String reason(Object verdict) {
        return (String) field(verdict, "reason");
    }

    private static Object field(Object verdict, String name) {
        try {
            java.lang.reflect.Field f = verdict.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(verdict);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
