package com.zin.delamain.server.routes;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.JadxError;
import jadx.core.dex.nodes.ClassNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 1 ("空源分类"): {@code /class-source} must classify a {@code code != null && code.isEmpty()}
 * result into an authoritative root cause using existing structured JADX signals only — never a
 * string-length guess — via {@link DecompileRoutes#classifySourceStatus}.
 *
 * <p>Mirrors {@link DecompileRoutesQualityVerdictTest}'s pattern: unit-test the package-private
 * pure classification function directly against real {@link ClassNode} instances decompiled from
 * the project's real test APKs, no HTTP layer involved.</p>
 */
class DecompileRoutesEmptySourceClassificationTest {

    private static JadxDecompiler jadx2;

    @BeforeAll
    static void setUp() {
        jadx2 = loadDecompiler("test-harness/real/UnCrackable-Level2.apk");
    }

    @AfterAll
    static void tearDown() {
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
        cls.getCode(); // force decompile so process state / attributes / getCode() are populated
        return cls.getClassNode();
    }

    /**
     * An inner class's source is inlined into its outer class's decompiled output — JADX
     * deliberately returns "" for {@code JavaClass#getCode()} on the inner class itself. This
     * must be classified as "inner_class_inlined" (not confused with a real failure), with a
     * next-action hint pointing at the outer/top-level class.
     */
    @Test
    void innerClassIsClassifiedAsInlined() {
        // android.arch.a.a.a.AnonymousClass1 — a real anonymous inner class present in
        // UnCrackable-Level2.apk (a bundled AndroidX/support-lib dependency class).
        JavaClass inner = findClass(jadx2, "android.arch.a.a.a.AnonymousClass1");
        assertNotNull(inner, "expected AnonymousClass1 inner class in UnCrackable-Level2.apk");
        assertTrue(inner.isInner(), "precondition: class must actually be an inner class");

        String code = inner.getCode();
        assertEquals("", code, "precondition: JavaClass#getCode() must return empty string for an inlined inner class");

        Object status = DecompileRoutes.classifySourceStatus(inner, nodeOf(inner), true);
        assertEquals("inner_class_inlined", statusOf(status));
        assertNotNull(nextActionOf(status), "inner_class_inlined must carry a next-action hint");
        assertTrue(nextActionOf(status).toLowerCase().contains("outer")
                || nextActionOf(status).contains(inner.getTopParentClass().getFullName()),
                "hint should point at the outer/top-level class: " + nextActionOf(status));
    }

    /**
     * A class carrying a JadxError (the hard-failure structured signal, same one
     * {@link DecompileRoutes#computeVerdict} treats as authoritative) must be classified as
     * "decompile_failed" — never "unknown_empty" — with a next-action hint pointing at
     * get_smali_of_class.
     */
    @Test
    void classWithJadxErrorIsClassifiedAsDecompileFailed() {
        JadxDecompiler local = loadDecompiler("test-harness/real/UnCrackable-Level1.apk");
        try {
            JavaClass cls = findClass(local, "sg.vantagepoint.uncrackable1.MainActivity");
            assertNotNull(cls, "expected MainActivity in UnCrackable-Level1.apk");
            ClassNode node = nodeOf(cls);
            node.addAttr(AType.JADX_ERROR, new JadxError("synthetic decompile failure for test", null));

            Object status = DecompileRoutes.classifySourceStatus(cls, node, true);
            assertEquals("decompile_failed", statusOf(status));
            assertNotNull(nextActionOf(status));
            assertTrue(nextActionOf(status).contains("get_smali_of_class"),
                    "decompile_failed hint should point at get_smali_of_class: " + nextActionOf(status));
        } finally {
            local.close();
        }
    }

    /** A normal class isn't expected to hit this classifier at all (its code is non-empty), but
     * a null target must never crash — degrade to unknown_empty. */
    @Test
    void nullTargetClassIsUnknownEmpty() {
        Object status = DecompileRoutes.classifySourceStatus(null, null, true);
        assertEquals("unknown_empty", statusOf(status));
    }

    private static JavaClass findClass(JadxDecompiler d, String fullName) {
        for (JavaClass cls : d.getClasses()) {
            if (fullName.equals(cls.getFullName())) {
                return cls;
            }
        }
        // also search nested/inner classes recursively
        for (JavaClass cls : d.getClasses()) {
            JavaClass hit = findInner(cls, fullName);
            if (hit != null) return hit;
        }
        return null;
    }

    private static JavaClass findInner(JavaClass cls, String fullName) {
        for (JavaClass inner : cls.getInnerClasses()) {
            if (fullName.equals(inner.getFullName())) return inner;
            JavaClass hit = findInner(inner, fullName);
            if (hit != null) return hit;
        }
        return null;
    }

    // Reflective accessors: SourceStatus is a private nested type of DecompileRoutes.
    private static String statusOf(Object status) {
        return (String) field(status, "status");
    }

    private static String nextActionOf(Object status) {
        return (String) field(status, "nextAction");
    }

    private static Object field(Object status, String name) {
        try {
            java.lang.reflect.Field f = status.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(status);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
