package com.zin.delamain.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessJadxWrapperLoadFailureTest {

    private HeadlessJadxWrapper wrapper;

    @AfterEach
    void tearDown() {
        if (wrapper != null) {
            wrapper.close();
        }
    }

    @Test
    void missingInputFailsBeforeJadxLoadWithItsPath(@TempDir Path workDir) {
        File missing = workDir.resolve("missing.apk").toFile();
        wrapper = new HeadlessJadxWrapper(List.of(missing), workDir.resolve("out").toFile(), 1);

        assertThrows(RuntimeException.class, wrapper::load);

        assertNotLoadedWithErrorContaining(missing.getPath());
    }

    @Test
    void archiveWithNoClassesFailsInsteadOfReportingLoaded(@TempDir Path workDir) throws Exception {
        File emptyJar = workDir.resolve("resources-only.jar").toFile();
        try (ZipOutputStream ignored = new ZipOutputStream(new FileOutputStream(emptyJar))) {
            // A valid but empty ZIP lets JADX complete with zero classes.
        }
        wrapper = new HeadlessJadxWrapper(List.of(emptyJar), workDir.resolve("out").toFile(), 1);

        assertThrows(RuntimeException.class, wrapper::load);

        assertNotLoadedWithErrorContaining("zero classes");
        assertTrue(wrapper.getLoadError().contains(emptyJar.getPath()),
                () -> "zero-class error must identify the input path: " + wrapper.getLoadError());
    }

    @Test
    void reloadMissingInputRestoresPreviouslyLoadedApk(@TempDir Path workDir) {
        loadRealApk(workDir);
        LoadedApkSnapshot oldApk = snapshotLoadedApk();
        File missing = workDir.resolve("missing-reload.apk").toFile();
        AtomicBoolean preCloseHookCalled = new AtomicBoolean();
        AtomicBoolean postLoadActionCalled = new AtomicBoolean();
        wrapper.addPreCloseQuiesceHook(() -> preCloseHookCalled.set(true));

        assertTrue(wrapper.beginReload());
        assertThrows(RuntimeException.class, () -> wrapper.reloadReserved(
                List.of(missing), workDir.resolve("new-out").toFile(), 1,
                () -> postLoadActionCalled.set(true)));

        assertFalse(preCloseHookCalled.get(),
                "path preflight must fail before quiescing or closing the old JADX instance");
        assertFalse(postLoadActionCalled.get(),
                "a failed new load and its rollback must not rebuild external caches");
        assertReloadFailurePreservesOldApk(oldApk, missing.getPath());
    }

    @Test
    void reloadArchiveWithNoClassesRestoresPreviouslyLoadedApk(@TempDir Path workDir)
            throws Exception {
        loadRealApk(workDir);
        LoadedApkSnapshot oldApk = snapshotLoadedApk();
        File emptyJar = workDir.resolve("reload-resources-only.jar").toFile();
        try (ZipOutputStream ignored = new ZipOutputStream(new FileOutputStream(emptyJar))) {
            // Valid resource-only input: preflight passes, but the new JADX instance has no classes.
        }
        AtomicBoolean postLoadActionCalled = new AtomicBoolean();

        assertTrue(wrapper.beginReload());
        assertThrows(RuntimeException.class, () -> wrapper.reloadReserved(
                List.of(emptyJar), workDir.resolve("new-out").toFile(), 1,
                () -> postLoadActionCalled.set(true)));

        assertFalse(postLoadActionCalled.get(),
                "a failed new load and its rollback must not rebuild external caches");
        assertReloadFailurePreservesOldApk(oldApk, "zero classes");
    }

    private void loadRealApk(Path workDir) {
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.isFile(), "test APK must exist: " + apk.getAbsolutePath());
        wrapper = new HeadlessJadxWrapper(List.of(apk), workDir.resolve("old-out").toFile(), 2);
        wrapper.load();
        assertEquals(HeadlessJadxWrapper.LoadState.LOADED, wrapper.getLoadState());
    }

    private LoadedApkSnapshot snapshotLoadedApk() {
        assertTrue(wrapper.getTotalClassCount() > 0, "real APK must expose classes");
        return new LoadedApkSnapshot(
                new ArrayList<>(wrapper.getInputFiles()),
                wrapper.getOutputDir(),
                wrapper.getThreads(),
                wrapper.getTotalClassCount(),
                wrapper.getClasses().get(0).getFullName());
    }

    private void assertReloadFailurePreservesOldApk(LoadedApkSnapshot oldApk,
                                                     String expectedErrorText) {
        assertEquals(HeadlessJadxWrapper.LoadState.LOADED, wrapper.getLoadState(),
                "failed reload must restore the old APK to LOADED");
        assertEquals(oldApk.inputFiles(), wrapper.getInputFiles(),
                "failed reload must restore the old input list");
        assertEquals(oldApk.outputDir(), wrapper.getOutputDir(),
                "failed reload must restore the old output directory");
        assertEquals(oldApk.threads(), wrapper.getThreads(),
                "failed reload must restore the old thread count");
        assertEquals(oldApk.classCount(), wrapper.getTotalClassCount(),
                "failed reload must restore all old classes");
        assertTrue(wrapper.getClasses().stream()
                        .anyMatch(cls -> cls.getFullName().equals(oldApk.representativeClass())),
                "a representative class from the old APK must still be analyzable");
        assertNotNull(wrapper.getLoadError(), "new input failure must remain visible");
        assertTrue(wrapper.getLoadError().toLowerCase().contains(expectedErrorText.toLowerCase()),
                () -> "loadError must describe the new input failure: " + wrapper.getLoadError());
        assertNotNull(wrapper.getLastReloadError(), "last reload failure must remain visible");
        assertTrue(wrapper.getLastReloadError().toLowerCase()
                        .contains(expectedErrorText.toLowerCase()),
                () -> "lastReloadError must describe the new input failure: "
                        + wrapper.getLastReloadError());
        assertTrue(wrapper.tryAcquireAnalysisAccess(),
                "analysis access must be available after rollback");
        wrapper.releaseAnalysisAccess();
    }

    private record LoadedApkSnapshot(List<File> inputFiles, File outputDir, int threads,
                                     int classCount, String representativeClass) {
    }

    private void assertNotLoadedWithErrorContaining(String expected) {
        assertFalse(wrapper.isLoaded(), "a failed input must never be advertised as loaded");
        assertEquals(HeadlessJadxWrapper.LoadState.FAILED, wrapper.getLoadState());
        assertNotNull(wrapper.getLoadError());
        assertTrue(wrapper.getLoadError().toLowerCase().contains(expected.toLowerCase()),
                () -> "load error must contain '" + expected + "': " + wrapper.getLoadError());
    }
}
