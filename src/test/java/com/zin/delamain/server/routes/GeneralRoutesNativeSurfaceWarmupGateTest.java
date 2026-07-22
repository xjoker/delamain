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
 * Bug 2 (production dogfood report): on a cold, un-warmed large APK (XHS, 138k classes),
 * {@code get_native_surface} ran {@code GeneralRoutes.collectLoadedLibraries()}'s unbounded
 * {@code cls.getCode()} scan over every class looking for {@code loadLibrary}/{@code .so} calls —
 * blowing the >120s request timeout and spiking memory.
 *
 * <p>Fix: {@link GeneralRoutes#buildNativeSurface} takes warmup readiness as an explicit
 * parameter (mirroring {@link GeneralRoutes#buildXrefReadiness()}'s existing pattern) so it is
 * deterministically testable without driving real {@code WarmupManager} state. When not ready,
 * the loadLibrary scan is skipped entirely (never an unbounded cold scan); native method
 * enumeration is pure metadata and must always return immediately regardless.</p>
 */
class GeneralRoutesNativeSurfaceWarmupGateTest {

    private static JadxDecompiler jadx;
    private static List<JavaClass> classes;
    private final GeneralRoutes routes = new GeneralRoutes(null);

    @BeforeAll
    static void setUp() {
        File apk = new File("test-harness/real/UnCrackable-Level2.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        JadxArgs args = new JadxArgs();
        args.setInputFile(apk);
        args.setSkipResources(true);
        jadx = new JadxDecompiler(args);
        jadx.load();
        classes = jadx.getClasses();
    }

    @AfterAll
    static void tearDown() {
        if (jadx != null) jadx.close();
    }

    @Test
    void nativeMethodsReturnImmediatelyEvenWhenWarmupNotReady() {
        long start = System.currentTimeMillis();
        Map<String, Object> response = routes.buildNativeSurface(classes, false);
        long elapsedMs = System.currentTimeMillis() - start;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> natives = (List<Map<String, Object>>) response.get("native_methods");
        assertFalse(natives.isEmpty(), "native methods must still be enumerated when not warmed up");
        Map<String, Object> initMethod = natives.stream()
                .filter(m -> "sg.vantagepoint.uncrackable2.MainActivity".equals(m.get("class_name"))
                        && "init".equals(m.get("method_name")))
                .findFirst()
                .orElse(null);
        assertTrue(initMethod != null, "expected native method 'init' in MainActivity: " + natives);

        assertTrue(elapsedMs < 5000,
            "metadata-only native method enumeration must stay fast (took " + elapsedMs + "ms)");
    }

    @Test
    void loadLibraryScanIsSkippedWithRequiresWarmupWhenNotReady() {
        Map<String, Object> response = routes.buildNativeSurface(classes, false);

        assertEquals("requires_warmup", response.get("libraries_status"),
            "unwarmed APK must gate the loadLibrary scan instead of running it: " + response);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> libs = (List<Map<String, Object>>) response.get("loaded_libraries");
        assertTrue(libs.isEmpty(), "loaded_libraries must be empty (never scanned) when gated: " + libs);
        assertTrue(response.containsKey("libraries_hint"),
            "a hint explaining the gate must be present: " + response);
    }

    @Test
    void loadLibraryScanRunsAndFindsRealCallWhenWarmupReady() {
        Map<String, Object> response = routes.buildNativeSurface(classes, true);

        assertEquals("ready", response.get("libraries_status"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> libs = (List<Map<String, Object>>) response.get("loaded_libraries");
        assertTrue(libs.stream().anyMatch(l -> "foo".equals(l.get("name"))),
            "expected System.loadLibrary(\"foo\") to be found once warmup is ready: " + libs);
        assertFalse(response.containsKey("libraries_hint"),
            "no gate hint expected once the scan actually ran: " + response);
    }
}
