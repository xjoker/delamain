package com.zin.delamain.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Found in production while verifying the 20260722.2 deploy: {@code GET /memory-diagnostics}
 * returned {@code 503 apk_not_ready} on a freshly restarted container, because
 * {@code installLoadStateMiddleware} rejects every path outside
 * {@link DelamainServer#NO_FILE_REQUIRED_PATHS} until an APK is loaded.
 *
 * <p>Memory diagnostics answer "how much memory does this process hold right now" — a question
 * whose most useful answers are exactly the ones asked when nothing is loaded (baseline before a
 * load, capacity planning, checking whether the container-derived heap took effect). Gating it on
 * a loaded APK makes the endpoint unusable at the moment it matters most, and it needs no wrapper
 * state at all: {@code buildMemoryDiagnostics} reads only the JVM, /proc and the index statics.
 * Same reasoning already applied to its sibling {@code /memory-config}.</p>
 */
class DelamainServerNoFilePathsTest {

    @Test
    void memoryDiagnosticsIsReachableWithoutALoadedApk() {
        assertTrue(DelamainServer.NO_FILE_REQUIRED_PATHS.contains("/memory-diagnostics"),
            "/memory-diagnostics must not be gated behind a loaded APK: " + DelamainServer.NO_FILE_REQUIRED_PATHS);
    }

    @Test
    void theOtherApkFreeEndpointsStayApkFree() {
        for (String path : new String[]{"/health", "/apk-info", "/decompile-status", "/memory-config",
                "/list-available-files", "/load-file"}) {
            assertTrue(DelamainServer.NO_FILE_REQUIRED_PATHS.contains(path),
                path + " must stay reachable without a loaded APK");
        }
    }
}
