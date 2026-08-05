package com.zin.delamain.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;

import com.zin.delamain.core.HeadlessJadxWrapper;

import org.junit.jupiter.api.AfterEach;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * auth.properties holds a bearer token in plaintext. {@link AuthConfig#restrictFilePermissions}
 * must lock it down to owner-only read/write (0600) after every write, so other local users on a
 * shared host can't read the token off disk.
 *
 * <p>Exercises the extracted static helper against a throwaway {@code @TempDir} file rather than
 * the real {@code ~/.delamain/auth.properties} (which AuthConfig's instance methods hardcode
 * via a {@code private static final} field) — this test must never touch the developer's actual
 * home directory / real auth token file.
 */
class AuthConfigTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

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

    // -------------------------------------------------------------------------
    // validateToken: previously the "no test ever sends a wrong token" gap --
    // AuthConfigTest had zero coverage of validateToken/constantTimeEquals before
    // this block. See completion report for the red/green verification against a
    // deliberately broken constantTimeEquals.
    // -------------------------------------------------------------------------

    @Test
    void validateToken_correctToken_isAccepted() {
        AuthConfig auth = new AuthConfig("correct-horse-battery-staple", true);
        assertTrue(auth.validateToken("correct-horse-battery-staple"));
    }

    @Test
    void validateToken_wrongToken_isRejected() {
        AuthConfig auth = new AuthConfig("correct-horse-battery-staple", true);
        assertFalse(auth.validateToken("totally-different-token"));
    }

    @Test
    void validateToken_longerTokenWithMatchingPrefix_isRejected() {
        // Guards against a `startsWith`-style comparison: a supplied token that merely
        // has the real token as a prefix (plus extra suffix bytes) must still fail.
        AuthConfig auth = new AuthConfig("short-token", true);
        assertFalse(auth.validateToken("short-token-plus-extra-suffix"));
    }

    @Test
    void validateToken_shorterTokenWithMatchingPrefix_isRejected() {
        // Guards against an early-return-on-length-mismatch bug that could accidentally
        // compare only the overlapping prefix and call it a match.
        AuthConfig auth = new AuthConfig("a-fairly-long-token-value", true);
        assertFalse(auth.validateToken("a-fairly-long"));
    }

    @Test
    void validateToken_authDisabled_acceptsAnyToken() {
        // AuthConfig(String, Boolean) two-arg ctor: envEnabled=false forces authEnabled=false
        // regardless of the supplied token, per AuthConfig#applyEnvironmentOverrides.
        AuthConfig auth = new AuthConfig("some-token", false);
        assertTrue(auth.validateToken("anything-at-all"));
        assertTrue(auth.validateToken(null));
        assertTrue(auth.validateToken(""));
    }

    @Test
    void validateToken_authEnabled_emptyToken_isRejected() {
        AuthConfig auth = new AuthConfig("real-token", true);
        assertFalse(auth.validateToken(""));
    }

    @Test
    void validateToken_authEnabled_nullToken_isRejected() {
        AuthConfig auth = new AuthConfig("real-token", true);
        assertFalse(auth.validateToken(null));
    }

    // -------------------------------------------------------------------------
    // End-to-end: a request bearing a *wrong* bearer token must get 401, not 200.
    // The only pre-existing 401 test (DelamainServerDecompileStatusDiagnosticsTest
    // .decompile_status_requires_bearer_when_auth_enabled) sends no Authorization
    // header at all -- it never exercises the token-comparison path itself.
    // -------------------------------------------------------------------------

    @Test
    void decompileStatus_wrongBearerToken_isRejectedWith401(@TempDir Path outputDir) throws Exception {
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(Collections.emptyList(), outputDir.toFile(), 1);
        int port = findFreePort();
        server = new DelamainServer(wrapper, port, "127.0.0.1", new AuthConfig("the-real-token", true));
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/decompile-status"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer wrong-token")
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode(), "a wrong bearer token must not be accepted");
    }

    @Test
    void restrictFilePermissions_setsOwnerOnlyReadWrite(@TempDir Path tempDir) throws IOException {
        assumeTrue(isPosixFileSystem(tempDir), "POSIX permissions not supported on this filesystem");

        Path file = tempDir.resolve("auth.properties");
        Files.writeString(file, "auth.token=test\n");
        // Start from permissive perms so the test actually proves restriction happened.
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        AuthConfig.restrictFilePermissions(file);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertEquals(PosixFilePermissions.fromString("rw-------"), perms,
            "auth.properties must be owner-only read/write (0600) after saving");
    }

    @Test
    void restrictFilePermissions_missingFile_doesNotThrow(@TempDir Path tempDir) {
        // saveToken()'s FileOutputStream write always happens first in practice, but the
        // permission step itself must fail closed (log + return), never propagate.
        Path missing = tempDir.resolve("does-not-exist.properties");
        AuthConfig.restrictFilePermissions(missing);
    }

    private static boolean isPosixFileSystem(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }
}
