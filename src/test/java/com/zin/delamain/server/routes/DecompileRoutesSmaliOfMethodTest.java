package com.zin.delamain.server.routes;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 2 ("按方法名取 smali 片段"): {@link DecompileRoutes#parseMethodBlocks} must extract exactly
 * one class's {@code .method ... .end method} block per method, robust against method-level
 * {@code .annotation ... .end annotation} nesting, and must not bleed into neighbouring methods.
 *
 * <p>Uses real {@code getSmali()} output decompiled from the project's real test APK — no
 * synthetic/hand-crafted smali fixtures, mirroring {@link DecompileRoutesQualityVerdictTest}.</p>
 */
class DecompileRoutesSmaliOfMethodTest {

    private static JadxDecompiler jadx;

    @BeforeAll
    static void setUp() {
        File apk = new File("test-harness/real/UnCrackable-Level2.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        JadxArgs args = new JadxArgs();
        args.setInputFile(apk);
        args.setSkipResources(true);
        jadx = new JadxDecompiler(args);
        jadx.load();
    }

    @AfterAll
    static void tearDown() {
        if (jadx != null) jadx.close();
    }

    private static JavaClass findClass(String fullName) {
        for (JavaClass cls : jadx.getClasses()) {
            if (fullName.equals(cls.getFullName())) return cls;
        }
        return null;
    }

    /**
     * android.arch.lifecycle.LiveData#a()Ljava/lang/Object; carries a method-level
     * {@code .annotation system Ldalvik/annotation/Signature;} block nested INSIDE the method
     * body, before {@code .end method}. The naive "next .end X" scan must not stop at
     * {@code .end annotation} — it must find the real {@code .end method} terminator.
     */
    @Test
    void extractsSingleMethodBlockAcrossNestedAnnotation() throws Exception {
        JavaClass cls = findClass("android.arch.lifecycle.LiveData");
        assertNotNull(cls, "expected android.arch.lifecycle.LiveData in UnCrackable-Level2.apk");
        String smali = cls.getSmali();
        assertNotNull(smali);

        List<Object> blocks = parseMethodBlocks(smali);
        Object match = null;
        int matchCount = 0;
        for (Object b : blocks) {
            if ("a".equals(nameOf(b)) && "()Ljava/lang/Object;".equals(descriptorOf(b))) {
                match = b;
                matchCount++;
            }
        }
        assertEquals(1, matchCount, "exactly one block should match a()Ljava/lang/Object;");
        assertNotNull(match);

        String body = bodyOf(match);
        assertTrue(body.contains(".method public a()Ljava/lang/Object;"), "block must start with its own .method line: " + body);
        assertTrue(body.contains(".annotation"), "block must retain the nested annotation content: " + body);
        assertTrue(body.trim().endsWith(".end method"), "block must end at its own .end method: " + body);
        // Must not bleed into a neighbouring method's body.
        assertFalse(body.contains(".method private a(Landroid/arch/lifecycle/LiveData$a;)V"),
                "block must not include a different method's declaration: " + body);
    }

    /**
     * android.arch.lifecycle.a declares 6 real overloads/distinct methods all named "a" with
     * different descriptors — filtering parseMethodBlocks() by name alone (no signature) must
     * return all of them as separate, non-overlapping blocks.
     */
    @Test
    void returnsAllOverloadsWhenFilteringByNameOnly() throws Exception {
        JavaClass cls = findClass("android.arch.lifecycle.a");
        assertNotNull(cls, "expected android.arch.lifecycle.a in UnCrackable-Level2.apk");
        String smali = cls.getSmali();
        assertNotNull(smali);

        List<Object> blocks = parseMethodBlocks(smali);
        long countNamedA = blocks.stream().filter(b -> "a".equals(nameOf(b))).count();
        assertTrue(countNamedA >= 6, "expected at least 6 overloads/methods named 'a', got " + countNamedA);

        // Distinct descriptors among the "a" overloads (confirms real overload disambiguation is possible).
        long distinctDescriptors = blocks.stream()
                .filter(b -> "a".equals(nameOf(b)))
                .map(DecompileRoutesSmaliOfMethodTest::descriptorOf)
                .distinct()
                .count();
        assertTrue(distinctDescriptors >= 2, "expected genuinely different descriptors among 'a' overloads");
    }

    // -------------------------------------------------------------------------
    // Reflective access: parseMethodBlocks/MethodBlock are package-private/private to
    // DecompileRoutes.
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Object> parseMethodBlocks(String smali) throws Exception {
        Method m = DecompileRoutes.class.getDeclaredMethod("parseMethodBlocks", String.class);
        m.setAccessible(true);
        return (List<Object>) m.invoke(null, smali);
    }

    private static String nameOf(Object block) {
        return (String) field(block, "name");
    }

    private static String descriptorOf(Object block) {
        return (String) field(block, "descriptor");
    }

    private static String bodyOf(Object block) {
        return (String) field(block, "body");
    }

    private static Object field(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
