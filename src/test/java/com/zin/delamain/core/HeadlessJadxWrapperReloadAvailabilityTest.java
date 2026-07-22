package com.zin.delamain.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production incident 2026-07-22: a single runaway xref request held the analysis READ lock (a
 * client-side timeout does not interrupt the server thread), then a {@code /load-file} flipped the
 * wrapper to LOADING and parked forever on {@code writeLock().lock()}. Every request — including
 * the ones the still-perfectly-usable old APK could have served — got {@code 503 apk_not_ready}
 * until the container was restarted by hand.
 *
 * <p>Contract pinned here: a reload that cannot get the write lock must be a failed reload, not a
 * dead service. Availability of the currently-loaded APK is never surrendered before the reload can
 * actually proceed, and a reload that cannot proceed gives up with a diagnosable error.</p>
 */
class HeadlessJadxWrapperReloadAvailabilityTest {

    private HeadlessJadxWrapper wrapper;

    @AfterEach
    void tearDown() {
        if (wrapper != null) {
            wrapper.close();
        }
    }

    private void loadApk(Path workDir) {
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        wrapper = new HeadlessJadxWrapper(List.of(apk), new File(workDir.toFile(), "out"), 2);
        wrapper.load();
        assertEquals(HeadlessJadxWrapper.LoadState.LOADED, wrapper.getLoadState());
    }

    @Test
    void aPendingReloadDoesNotTakeTheOldApkOffline(@TempDir Path workDir) throws Exception {
        loadApk(workDir);
        wrapper.setReloadLockTimeoutMillis(30_000);

        // An in-flight analysis request — exactly what the runaway xref was.
        assertTrue(wrapper.tryAcquireAnalysisAccess(), "precondition: analysis access is available");

        assertTrue(wrapper.beginReload(), "the reload must be accepted");
        assertEquals(HeadlessJadxWrapper.LoadState.LOADED, wrapper.getLoadState(),
            "reserving a reload must not take the loaded APK offline before the reload can start");

        AtomicReference<Throwable> reloadFailure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread reload = new Thread(() -> {
            try {
                wrapper.reloadReserved(new ArrayList<>(wrapper.getInputFiles()),
                    wrapper.getOutputDir(), wrapper.getThreads());
            } catch (Throwable t) {
                reloadFailure.set(t);
            } finally {
                done.countDown();
            }
        }, "test-reload");
        reload.setDaemon(true);
        reload.start();

        // While the reload waits for the write lock, new requests must still be served by the APK
        // that is loaded right now. A fair lock plus a *timed* tryLock would fail here: a queued
        // writer blocks every new reader, so the service goes dark without any state change.
        long until = System.currentTimeMillis() + 1_500;
        while (System.currentTimeMillis() < until) {
            assertEquals(HeadlessJadxWrapper.LoadState.LOADED, wrapper.getLoadState(),
                "the old APK must stay LOADED while the reload is only pending");
            assertTrue(wrapper.tryAcquireAnalysisAccess(),
                "a new analysis request must still be admitted while a reload is merely pending");
            wrapper.releaseAnalysisAccess();
            Thread.sleep(50);
        }

        // Release the "runaway" request; the reload must then complete normally.
        wrapper.releaseAnalysisAccess();
        assertTrue(done.await(120, TimeUnit.SECONDS), "the reload must proceed once the lock frees up");
        assertNull(reloadFailure.get(), "reload must succeed: " + reloadFailure.get());
        assertEquals(HeadlessJadxWrapper.LoadState.LOADED, wrapper.getLoadState());
    }

    @Test
    void aReloadBlockedByAStuckReaderFailsInsteadOfParkingForever(@TempDir Path workDir) throws Exception {
        loadApk(workDir);
        wrapper.setReloadLockTimeoutMillis(400);

        // A reader that never comes back — the runaway xref that caused the incident.
        assertTrue(wrapper.tryAcquireAnalysisAccess());

        assertTrue(wrapper.beginReload());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread reload = new Thread(() -> {
            try {
                wrapper.reloadReserved(new ArrayList<>(wrapper.getInputFiles()),
                    wrapper.getOutputDir(), wrapper.getThreads());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        }, "test-reload-timeout");
        reload.setDaemon(true);
        reload.start();

        assertTrue(done.await(30, TimeUnit.SECONDS),
            "the reload must give up on its own — parking forever is what required a manual restart");

        Throwable t = failure.get();
        assertNotNull(t, "a reload that could not acquire the write lock must report a failure");
        assertTrue(t instanceof HeadlessJadxWrapper.ReloadLockTimeoutException,
            "expected ReloadLockTimeoutException, got " + t);
        assertNotNull(t.getMessage());
        assertTrue(t.getMessage().toLowerCase().contains("read lock"),
            "the error must name what is blocking the reload so it can be diagnosed without a heap "
                + "dump, got: " + t.getMessage());

        assertEquals(HeadlessJadxWrapper.LoadState.LOADED, wrapper.getLoadState(),
            "a failed reload must roll the state back so the old APK keeps serving");
        assertTrue(wrapper.tryAcquireAnalysisAccess(),
            "requests must still be admitted after a failed reload");
        wrapper.releaseAnalysisAccess();
        assertNotNull(wrapper.getLastReloadError(),
            "the failed reload must stay visible for /health and /decompile-status");

        // The reservation must be released too, otherwise the APK could never be replaced again.
        assertTrue(wrapper.beginReload(), "a failed reload must not leave the reservation stuck");
        assertFalse(wrapper.beginReload(), "concurrent reloads must still be mutually exclusive");

        wrapper.releaseAnalysisAccess();
    }
}
