package com.zin.delamain.server.routes;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.utils.JadxSearchLock;
import com.zin.delamain.utils.PaginationUtils;
import com.zin.delamain.utils.TicketRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XrefsRoutesAsyncCancellationTest {

    private HeadlessJadxWrapper wrapper;
    private ExecutorService executor;

    @BeforeEach
    void setUp(@TempDir Path workDir) throws Exception {
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        wrapper = new HeadlessJadxWrapper(List.of(apk), new File(workDir.toFile(), "out"), 2);
        wrapper.load();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) executor.shutdownNow();
        if (wrapper != null) wrapper.close();
    }

    @Test
    void cancelRouteInterruptsRunningTaskAndReleasesBothReadLocks() throws Exception {
        XrefsRoutes routes = new XrefsRoutes(wrapper, new PaginationUtils(), executor);

        CountDownLatch started = new CountDownLatch(1);
        String ticket = routes.submitAsyncTask(() -> {
            if (!JadxSearchLock.tryAcquireRead()) {
                throw new IllegalStateException("test could not acquire JadxSearchLock read lock");
            }
            started.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
                return Map.of("unexpected", true);
            } finally {
                JadxSearchLock.releaseRead();
            }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(1, wrapper.getAnalysisReadLockCount());
        assertEquals(1, JadxSearchLock.getReadLockCount());

        assertTrue(routes.cancelAsyncTask(ticket));

        CountDownLatch drained = new CountDownLatch(1);
        executor.execute(drained::countDown);
        assertTrue(drained.await(2, TimeUnit.SECONDS),
            "executor sentinel proves the cancelled task returned through both finally blocks");
        assertEquals(0, wrapper.getAnalysisReadLockCount());
        assertEquals(0, JadxSearchLock.getReadLockCount());
        assertEquals(
            TicketRegistry.Status.NOT_FOUND,
            routes.pollAsyncTask(ticket).getStatus(),
            "cancelled tickets must remain absent from the one-shot poll contract");
    }

    @Test
    void rejectedExecutionCancelsRegisteredFutureBeforeReturningFailure() {
        AtomicReference<Runnable> rejectedTask = new AtomicReference<>();
        ExecutorService rejectingExecutor = new AbstractExecutorService() {
            @Override
            public void shutdown() {}

            @Override
            public List<Runnable> shutdownNow() {
                return Collections.emptyList();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return false;
            }

            @Override
            public void execute(Runnable command) {
                rejectedTask.set(command);
                throw new RejectedExecutionException("synthetic rejection");
            }
        };
        AtomicBoolean ran = new AtomicBoolean();
        XrefsRoutes routes = new XrefsRoutes(wrapper, new PaginationUtils(), rejectingExecutor);

        assertThrows(RejectedExecutionException.class,
            () -> routes.submitAsyncTask(() -> {
                ran.set(true);
                return Map.of();
            }));

        assertFalse(ran.get());
        assertTrue(rejectedTask.get() instanceof Future<?>);
        assertTrue(((Future<?>) rejectedTask.get()).isCancelled(),
            "a registered task rejected by the executor must be cancelled and removed");
        assertEquals(0, wrapper.getAnalysisReadLockCount());
    }
}
