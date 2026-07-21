package com.zin.delamain.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JadxSearchLock is a process-wide static lock, so these tests exercise the primitive's
 * correctness directly rather than the background watchdog's timing (which enforces
 * LOCK_TIMEOUT_SECONDS = 60s — waiting that out in a unit test would make the suite slow and
 * flaky). {@link #forceRelease_unlocksAHeldWriteLock} covers the actual mechanism the watchdog
 * calls; the 60s-elapsed trigger condition itself is verified by code review, not exercised here.
 */
class JadxSearchLockTest {

    @AfterEach
    void ensureUnlocked() {
        // Best-effort cleanup in case an assertion fails mid-test and leaves the static lock held,
        // which would otherwise cascade into failures in every other test in the JVM.
        if (JadxSearchLock.isBusy()) {
            JadxSearchLock.forceRelease("test cleanup");
        }
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
        // This is the exact call the background watchdog makes once a write lock has been held
        // longer than LOCK_TIMEOUT_SECONDS (JadxSearchLock.java: checkAndForceReleaseIfStale()).
        assertTrue(JadxSearchLock.tryAcquire());
        assertTrue(JadxSearchLock.isBusy());

        JadxSearchLock.forceRelease("test: simulated stale holder");

        assertFalse(JadxSearchLock.isBusy(), "forceRelease() must free the lock for new acquirers");
        assertTrue(JadxSearchLock.tryAcquire(), "lock must be acquirable again after forceRelease()");
        JadxSearchLock.release();
    }
}
