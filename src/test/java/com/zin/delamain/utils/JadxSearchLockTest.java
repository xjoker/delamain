package com.zin.delamain.utils;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JadxSearchLock is a process-wide static lock, so these tests exercise the primitive's
 * correctness directly rather than the background watchdog's real-time scheduling (waiting out
 * WATCHDOG_POLL_SECONDS in a unit test would make the suite slow and flaky). The stale-holder
 * trigger logic itself ({@link JadxSearchLock#checkAndInterruptStaleHolder()}) is invoked directly
 * after backdating the lock's acquire timestamp via reflection, rather than by waiting for the
 * real scheduler — see {@link #backdateLockAcquireTime}.
 */
class JadxSearchLockTest {

    @AfterEach
    void ensureUnlocked() {
        // Best-effort cleanup in case an assertion fails mid-test and leaves the static lock held,
        // which would otherwise cascade into failures in every other test in the JVM. forceRelease
        // is still a valid test-only escape hatch (see its updated javadoc) even though the
        // watchdog no longer calls it automatically.
        if (JadxSearchLock.isBusy()) {
            JadxSearchLock.forceRelease("test cleanup");
        }
        Thread.interrupted(); // clear this thread's interrupt flag between tests
    }

    /** Rewrites the private lockAcquireTime field so a test doesn't have to wait real seconds out. */
    private static void backdateLockAcquireTime(long secondsAgo) throws Exception {
        Field field = JadxSearchLock.class.getDeclaredField("lockAcquireTime");
        field.setAccessible(true);
        ((AtomicLong) field.get(null)).set(System.currentTimeMillis() - secondsAgo * 1000L);
    }

    @Test
    void tryAcquire_thenRelease_unlocksCleanly() {
        assertTrue(JadxSearchLock.tryAcquire(), "first acquire on an unlocked lock must succeed");
        assertTrue(JadxSearchLock.isBusy());
        assertTrue(JadxSearchLock.isHeldByCurrentThread());

        JadxSearchLock.release();

        assertFalse(JadxSearchLock.isBusy(), "lock must be free after release()");
    }

    @Test
    void tryAcquire_whileAlreadyHeld_fails() {
        assertTrue(JadxSearchLock.tryAcquire());
        try {
            assertFalse(JadxSearchLock.tryAcquire(), "a second tryAcquire() must not succeed while held");
        } finally {
            JadxSearchLock.release();
        }
    }

    @Test
    void forceRelease_unlocksAHeldWriteLock() {
        // forceRelease() itself is unchanged — it is still a real, unconditional unlock. What
        // changed (see the tests below) is that the watchdog no longer calls it automatically;
        // it is now a manual/test-only escape hatch.
        assertTrue(JadxSearchLock.tryAcquire());
        assertTrue(JadxSearchLock.isBusy());

        JadxSearchLock.forceRelease("test: simulated stale holder");

        assertFalse(JadxSearchLock.isBusy(), "forceRelease() must free the lock for new acquirers");
        assertTrue(JadxSearchLock.tryAcquire(), "lock must be acquirable again after forceRelease()");
        JadxSearchLock.release();
    }

    /**
     * F5 (3): the watchdog used to call forceRelease() unconditionally once a write lock had been
     * held past LOCK_TIMEOUT_SECONDS — STAMPED_LOCK.tryUnlockWrite() does not check which thread
     * holds the lock, so this stole the lock out from under a holder that could still be running
     * (e.g. RenameStorage.renameClass mutating a JADX ClassNode), letting a second thread acquire
     * the write lock and mutate the same shared state concurrently: a data race. Pinned here: while
     * the original holder is still in its critical section (has not called release()), the stale
     * check must NOT free the lock for anyone else — mutual exclusion holds until the holder
     * releases on its own.
     */
    @Test
    void staleWriteLockWatchdogDoesNotStealTheLockWhileHolderIsStillRunning() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        AtomicBoolean releaseNow = new AtomicBoolean(false);
        Thread holder = new Thread(() -> {
            assertTrue(JadxSearchLock.tryAcquire());
            acquired.countDown();
            // Simulates a holder still doing real work (e.g. mid-rename) when the watchdog fires:
            // the interrupt signal IS delivered (Thread.interrupted() below observes it), but —
            // like RenameStorage's JADX mutation calls, which have no interruption points of their
            // own — the "critical section" does not treat it as a reason to stop early. A plain
            // blocking wait (e.g. CountDownLatch.await) would instead throw InterruptedException
            // and unwind immediately, which is the opposite of what's being simulated here.
            while (!releaseNow.get()) {
                Thread.interrupted(); // observe and clear, but keep going regardless
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    // keep looping either way
                }
            }
            JadxSearchLock.release();
        }, "test-stale-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue(acquired.await(2, TimeUnit.SECONDS), "holder thread must acquire the write lock");

        backdateLockAcquireTime(JadxSearchLock.LOCK_TIMEOUT_SECONDS + 1);
        JadxSearchLock.checkAndInterruptStaleHolder();

        assertTrue(JadxSearchLock.isBusy(),
            "the watchdog must not release the lock while the original holder is still running");
        assertFalse(JadxSearchLock.tryAcquire(),
            "a second thread must not be able to acquire the write lock while the original holder "
                + "has not released it — that would be the exact concurrent-mutation race this fixes");

        releaseNow.set(true);
        holder.join(2000);
        assertFalse(holder.isAlive(), "holder thread must have finished and released");
        assertFalse(JadxSearchLock.isBusy(), "the lock must free itself once the real holder releases");
    }

    @Test
    void staleWriteLockWatchdogLogsAtErrorLevel() throws Exception {
        assertTrue(JadxSearchLock.tryAcquire());
        try {
            backdateLockAcquireTime(JadxSearchLock.LOCK_TIMEOUT_SECONDS + 1);

            String output = captureLoggerOutput(JadxSearchLock::checkAndInterruptStaleHolder);

            assertTrue(output.contains("ERROR"),
                "a write lock held past its timeout must be logged at ERROR, not swallowed at "
                    + "debug — this is the only visibility production has into a stuck holder: " + output);
        } finally {
            JadxSearchLock.release();
        }
    }

    /**
     * Covers the other half of F5 (3): if forceRelease() is ever invoked manually while the
     * original holder is still active (the escape hatch this class still exposes), that holder's
     * own release() call must surface the resulting broken invariant loudly, not at debug.
     */
    @Test
    void releaseAfterAForcedStealLogsAtErrorLevel() throws Exception {
        assertTrue(JadxSearchLock.tryAcquire());

        JadxSearchLock.forceRelease("test: simulate a manual forced steal while still held");

        String output = captureLoggerOutput(JadxSearchLock::release);

        assertTrue(output.contains("ERROR"),
            "release() discovering its stamp is no longer valid means mutual exclusion was already "
                + "broken elsewhere — that must be an ERROR, not a silently swallowed debug line: " + output);
    }

    /**
     * Attaches a temporary appender directly to JadxSearchLock's logger and captures formatted
     * log lines during {@code action}. Redirecting System.err does not work here: the actual
     * SLF4J provider bound at test runtime is the logback-classic instance bundled inside
     * jadx-all.jar, and its ConsoleAppender caches the PrintStream reference at start-up rather
     * than re-reading System.err per event, so a later System.setErr() swap is invisible to it.
     * Attaching at the logger/event level sidesteps that entirely.
     */
    private static String captureLoggerOutput(Runnable action) {
        Logger logbackLogger = (Logger) org.slf4j.LoggerFactory.getLogger(JadxSearchLock.class);
        StringBuilder captured = new StringBuilder();
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                captured.append(event.getLevel()).append(' ').append(event.getFormattedMessage()).append('\n');
            }
        };
        appender.setContext(logbackLogger.getLoggerContext());
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            action.run();
        } finally {
            logbackLogger.detachAppender(appender);
            appender.stop();
        }
        return captured.toString();
    }
}
