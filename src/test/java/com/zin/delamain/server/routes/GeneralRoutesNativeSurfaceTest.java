package com.zin.delamain.server.routes;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 4 ("get_native_surface"): the Ghidra/unidbg handoff worklist — real {@code native}
 * methods (with JNI mangled-name candidates) plus {@code System.loadLibrary}/{@code load}
 * targets — built purely from real UnCrackable-Level2.apk data (which genuinely declares a
 * native method and calls {@code System.loadLibrary("foo")}), no synthetic fixtures.
 */
class GeneralRoutesNativeSurfaceTest {

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

    @Test
    void collectsRealNativeMethodWithJniNameCandidate() {
        List<Map<String, Object>> natives = GeneralRoutes.collectNativeMethods(jadx.getClasses());
        assertFalse(natives.isEmpty(), "expected at least one native method in UnCrackable-Level2.apk");

        Map<String, Object> initMethod = natives.stream()
                .filter(m -> "sg.vantagepoint.uncrackable2.MainActivity".equals(m.get("class_name"))
                        && "init".equals(m.get("method_name")))
                .findFirst()
                .orElse(null);
        assertTrue(initMethod != null, "expected native method 'init' in MainActivity: " + natives);
        assertEquals("Java_sg_vantagepoint_uncrackable2_MainActivity_init", initMethod.get("jni_name_candidate"));
    }

    @Test
    void collectsRealLoadLibraryCall() {
        List<Map<String, Object>> libs = GeneralRoutes.collectLoadedLibraries(jadx.getClasses());
        assertTrue(libs.stream().anyMatch(l -> "foo".equals(l.get("name"))),
                "expected System.loadLibrary(\"foo\") to be found: " + libs);
    }

    @Test
    void jniNameEscapesUnderscoresBeforeDots() {
        // Escaping order matters: an original '_' becomes '_1' BEFORE '.' becomes '_', otherwise
        // a freshly-created '_' (from a dot) would be indistinguishable from an original one.
        String candidate = GeneralRoutes.jniNameCandidate("com.example.my_pkg.My_Class", "do_thing");
        assertEquals("Java_com_example_my_1pkg_My_1Class_do_1thing", candidate);
    }
}
