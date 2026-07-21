package com.zin.delamain.utils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.StampedLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global lock for JADX search and decompilation operations.
 */
public final class JadxSearchLock {

    private static final Logger logger = LoggerFactory.getLogger(JadxSearchLock.class);

    private static final StampedLock STAMPED_LOCK = new StampedLock();
    private static final ThreadLocal<Long> writeStampTL = new ThreadLocal<>();
    private static final ThreadLocal<Long> readStampTL = new ThreadLocal<>();
    private static final AtomicLong lockAcquireTime = new AtomicLong(0);
    private static final AtomicReference<Thread> holdingThread = new AtomicReference<>(null);

    public static final int RETRY_AFTER_SECONDS = 10;
    public static final int LOCK_TIMEOUT_SECONDS = 60;

    private static final int WATCHDOG_POLL_SECONDS = 5;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jadx-searchlock-watchdog");
        t.setDaemon(true);
        return t;
    });

    static {
        // maybeSoftInterrupt() only fires when a NEW request calls tryAcquire() — a holder that
        // hangs with nobody else contending the lock would never get reclaimed. This background
        // poll enforces LOCK_TIMEOUT_SECONDS unconditionally by calling forceRelease(), which was
        // previously defined but never invoked from anywhere (dead code).
        WATCHDOG.scheduleWithFixedDelay(
            JadxSearchLock::checkAndForceReleaseIfStale, WATCHDOG_POLL_SECONDS, WATCHDOG_POLL_SECONDS,
            TimeUnit.SECONDS);
    }

    private static void checkAndForceReleaseIfStale() {
        try {
            long acquireTime = lockAcquireTime.get();
            if (acquireTime == 0 || !STAMPED_LOCK.isWriteLocked()) {
                return;
            }
            long heldSeconds = (System.currentTimeMillis() - acquireTime) / 1000;
            if (heldSeconds > LOCK_TIMEOUT_SECONDS) {
                Thread staleThread = holdingThread.get();
                String holder = staleThread != null ? staleThread.getName() : "unknown";
                forceRelease("watchdog: write lock held " + heldSeconds + "s (timeout "
                    + LOCK_TIMEOUT_SECONDS + "s), holder=[" + holder + "]");
            }
        } catch (Exception e) {
            logger.error("[JAI] JadxSearchLock watchdog check failed", e);
        }
    }

    private JadxSearchLock() {}

    public static boolean tryAcquire() {
        maybeSoftInterrupt();

        long stamp = STAMPED_LOCK.tryWriteLock();
        if (stamp != 0L) {
            writeStampTL.set(stamp);
            lockAcquireTime.set(System.currentTimeMillis());
            holdingThread.set(Thread.currentThread());
            return true;
        }
        return false;
    }

    public static boolean tryAcquire(int timeoutSeconds) {
        try {
            long stamp = STAMPED_LOCK.tryWriteLock(timeoutSeconds, TimeUnit.SECONDS);
            if (stamp != 0L) {
                writeStampTL.set(stamp);
                lockAcquireTime.set(System.currentTimeMillis());
                holdingThread.set(Thread.currentThread());
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void release() {
        Long stamp = writeStampTL.get();
        if (stamp != null && stamp != 0L) {
            writeStampTL.remove();
            holdingThread.set(null);
            lockAcquireTime.set(0);
            try {
                STAMPED_LOCK.unlockWrite(stamp);
            } catch (IllegalMonitorStateException e) {
                logger.debug("release(): stamp no longer valid (already force-released)");
            }
        }
    }

    public static void forceRelease(String reason) {
        long heldSeconds = getLockHeldSeconds();
        logger.warn("[JAI] Force-releasing write lock (held {}s): {}", heldSeconds, reason);
        holdingThread.set(null);
        lockAcquireTime.set(0);
        boolean released = STAMPED_LOCK.tryUnlockWrite();
        logger.warn("[JAI] Force-release result: lock_was_held={}", released);
    }

    public static boolean tryAcquireRead() {
        long stamp = STAMPED_LOCK.tryReadLock();
        if (stamp != 0L) {
            readStampTL.set(stamp);
            return true;
        }
        return false;
    }

    public static boolean tryAcquireRead(int timeoutSeconds) {
        try {
            long stamp = STAMPED_LOCK.tryReadLock(timeoutSeconds, TimeUnit.SECONDS);
            if (stamp != 0L) {
                readStampTL.set(stamp);
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void releaseRead() {
        Long stamp = readStampTL.get();
        if (stamp != null && stamp != 0L) {
            readStampTL.remove();
            try {
                STAMPED_LOCK.unlockRead(stamp);
            } catch (IllegalMonitorStateException e) {
                logger.debug("releaseRead(): stamp no longer valid");
            }
        }
    }

    public static boolean isBusy() {
        return STAMPED_LOCK.isWriteLocked();
    }

    public static boolean isHeldByCurrentThread() {
        return holdingThread.get() == Thread.currentThread();
    }

    public static long getLockHeldSeconds() {
        long acquireTime = lockAcquireTime.get();
        if (acquireTime == 0) {
            return 0;
        }
        return (System.currentTimeMillis() - acquireTime) / 1000;
    }

    public static int getReadLockCount() {
        return STAMPED_LOCK.getReadLockCount();
    }

    public static java.util.Map<String, Object> getStatus() {
        java.util.Map<String, Object> status = new java.util.HashMap<>();
        status.put("write_locked", STAMPED_LOCK.isWriteLocked());
        status.put("read_lock_count", STAMPED_LOCK.getReadLockCount());
        status.put("write_held_seconds", getLockHeldSeconds());
        status.put("timeout_seconds", LOCK_TIMEOUT_SECONDS);
        status.put("locked", STAMPED_LOCK.isWriteLocked());
        status.put("held_seconds", getLockHeldSeconds());
        return status;
    }

    private static void maybeSoftInterrupt() {
        long acquireTime = lockAcquireTime.get();
        if (acquireTime > 0 && STAMPED_LOCK.isWriteLocked()) {
            long heldSeconds = (System.currentTimeMillis() - acquireTime) / 1000;
            if (heldSeconds > LOCK_TIMEOUT_SECONDS) {
                Thread staleThread = holdingThread.get();
                if (staleThread != null) {
                    logger.warn("[JAI] Write lock held for {}s (timeout: {}s), interrupting holding thread [{}]",
                        heldSeconds, LOCK_TIMEOUT_SECONDS, staleThread.getName());
                    staleThread.interrupt();
                }
            }
        }
    }
}
