package com.zin.delamain.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FilePathSandbox#resolveForCreate} backs the file-transfer upload finalize step, where
 * the destination file does not exist yet — unlike {@link FilePathSandbox#resolveWithinRoot},
 * which requires the target to already exist and therefore cannot be reused here.
 */
class FilePathSandboxResolveForCreateTest {

    @Test
    void resolvesPlainBasenameUnderRoot_evenWhenFileDoesNotExist(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.toRealPath();
        FilePathSandbox sandbox = new FilePathSandbox(root);

        Path resolved = sandbox.resolveForCreate("app.apk");

        assertEquals(root.resolve("app.apk"), resolved);
    }

    @Test
    void rejectsDotDotTraversal(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.toRealPath();
        FilePathSandbox sandbox = new FilePathSandbox(root);

        assertThrows(FilePathSandbox.SandboxViolation.class,
                () -> sandbox.resolveForCreate("../evil.apk"));
    }

    @Test
    void rejectsAbsolutePath(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.toRealPath();
        FilePathSandbox sandbox = new FilePathSandbox(root);

        String absolute = root.getRoot().resolve("etc").resolve("evil.apk").toString();
        assertThrows(FilePathSandbox.SandboxViolation.class,
                () -> sandbox.resolveForCreate(absolute));
    }

    @Test
    void disabledSandbox_throws() {
        FilePathSandbox sandbox = new FilePathSandbox(null);
        assertThrows(FilePathSandbox.SandboxViolation.class,
                () -> sandbox.resolveForCreate("app.apk"));
    }
}
