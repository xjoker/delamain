package com.zin.delamain.server;

import com.zin.delamain.core.HeadlessJadxWrapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DelamainServer#NO_FILE_REQUIRED_PATHS} and {@link DelamainServer#TRANSFER_TOKEN_AUTH_PATHS}
 * are hand-maintained sets of path strings, checked separately from wherever the real routes get
 * registered. Two failure modes are possible and both have happened: a path gets removed from this
 * round's audit ({@code /memory-config} was a dead entry deleted this pass) or a typo/rename in
 * either list quietly leaves a route un-exempted or grants an exemption that reaches nothing.
 *
 * <p>This test starts a real {@link DelamainServer} (no APK loaded, matching
 * {@link DelamainServerHealthLockDiagnosticsTest}'s pattern of using the real Javalin instance
 * rather than mocking it) and reads Javalin's own route table via
 * {@link DelamainServer#registeredHttpPaths()} — not a second hand-copied list — so a stale or
 * misspelled entry in either exemption set shows up as a real assertion failure.</p>
 */
class DelamainServerRouteAuthCoverageTest {

    private DelamainServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void everyNoFileRequiredPathIsARealRegisteredRoute(@TempDir Path outputDir) throws Exception {
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(Collections.emptyList(), outputDir.toFile(), 1);
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig(null, false));
        server.start();

        Set<String> realPaths = server.registeredHttpPaths();

        for (String path : DelamainServer.NO_FILE_REQUIRED_PATHS) {
            assertTrue(realPaths.contains(path),
                "NO_FILE_REQUIRED_PATHS contains '" + path + "' but no such route is registered "
                    + "with Javalin -- this is a dead exemption entry. Registered paths: " + realPaths);
        }
    }

    @Test
    void everyTransferTokenAuthPathIsARealRegisteredRoute(@TempDir Path outputDir) throws Exception {
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(Collections.emptyList(), outputDir.toFile(), 1);
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig(null, false));
        server.start();

        Set<String> realPaths = server.registeredHttpPaths();

        for (String path : DelamainServer.TRANSFER_TOKEN_AUTH_PATHS) {
            assertTrue(realPaths.contains(path),
                "TRANSFER_TOKEN_AUTH_PATHS contains '" + path + "' but no such route is registered "
                    + "with Javalin -- this is a dead exemption entry. Registered paths: " + realPaths);
        }
    }
}
