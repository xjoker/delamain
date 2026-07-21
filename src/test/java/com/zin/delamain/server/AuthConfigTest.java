package com.zin.delamain.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
