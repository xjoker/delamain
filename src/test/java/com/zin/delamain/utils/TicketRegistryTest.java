package com.zin.delamain.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit coverage for the generic submit-now/poll-later ticket store, which previously was
 * only exercised indirectly through the xref async endpoints' integration tests. Exercises the
 * primitive's own contract: ticket issuance, RUNNING/DONE/ERROR/NOT_FOUND polling, one-shot
 * consume-on-terminal-poll semantics, TTL-based pruning, and concurrent register/poll safety.
 */
class TicketRegistryTest {

    @Test
    void register_returnsUsableTicket() {
        TicketRegistry<String> registry = new TicketRegistry<>(60);
        CompletableFuture<String> future = CompletableFuture.completedFuture("value");

        String ticket = registry.register(future);

        assertNotNull(ticket, "register() must return a non-null ticket");
        assertTrue(!ticket.isEmpty(), "ticket must be non-empty");
    }

    @Test
    void poll_beforeCompletion_returnsRunning() {
        TicketRegistry<String> registry = new TicketRegistry<>(60);
        CompletableFuture<String> future = new CompletableFuture<>();
        String ticket = registry.register(future);

        TicketRegistry.PollResult<String> result = registry.poll(ticket);

        assertEquals(TicketRegistry.Status.RUNNING, result.getStatus());
        assertNull(result.getResult());
    }

    @Test
    void poll_afterCompletion_returnsDoneWithResult() {
        TicketRegistry<String> registry = new TicketRegistry<>(60);
        CompletableFuture<String> future = CompletableFuture.completedFuture("hello");
        String ticket = registry.register(future);

        TicketRegistry.PollResult<String> result = registry.poll(ticket);

        assertEquals(TicketRegistry.Status.DONE, result.getStatus());
        assertEquals("hello", result.getResult());
    }

    @Test
    void poll_unknownTicket_returnsNotFound() {
        TicketRegistry<String> registry = new TicketRegistry<>(60);

        TicketRegistry.PollResult<String> result = registry.poll("does-not-exist");

        assertEquals(TicketRegistry.Status.NOT_FOUND, result.getStatus());
    }

    @Test
    void poll_futureCompletedExceptionally_returnsError() {
        TicketRegistry<String> registry = new TicketRegistry<>(60);
        CompletableFuture<String> future = new CompletableFuture<>();
        future.completeExceptionally(new IllegalStateException("boom"));
        String ticket = registry.register(future);

        TicketRegistry.PollResult<String> result = registry.poll(ticket);

        assertEquals(TicketRegistry.Status.ERROR, result.getStatus());
        assertNotNull(result.getError());
        assertEquals("boom", result.getError().getMessage());
    }

    @Test
    void poll_futureCompletedWithNull_returnsError() {
        TicketRegistry<String> registry = new TicketRegistry<>(60);
        CompletableFuture<String> future = CompletableFuture.completedFuture(null);
        String ticket = registry.register(future);

        TicketRegistry.PollResult<String> result = registry.poll(ticket);

        assertEquals(TicketRegistry.Status.ERROR, result.getStatus(),
            "a future that completes with a null result has no valid DONE payload");
    }

    @Test
    void poll_afterDone_isOneShot_secondPollReturnsNotFound() {
        // TicketRegistry.poll() removes the entry from the registry once it observes a terminal
        // (done) future, per its javadoc: "Terminal states (DONE/ERROR) remove the ticket from the
        // registry". Verify the second poll for the same ticket comes back NOT_FOUND.
        TicketRegistry<String> registry = new TicketRegistry<>(60);
        CompletableFuture<String> future = CompletableFuture.completedFuture("once");
        String ticket = registry.register(future);

        TicketRegistry.PollResult<String> first = registry.poll(ticket);
        TicketRegistry.PollResult<String> second = registry.poll(ticket);

        assertEquals(TicketRegistry.Status.DONE, first.getStatus());
        assertEquals(TicketRegistry.Status.NOT_FOUND, second.getStatus(),
            "polling a terminal ticket a second time must not resurface the consumed result");
    }

    @Test
    void poll_afterError_isOneShot_secondPollReturnsNotFound() {
        TicketRegistry<String> registry = new TicketRegistry<>(60);
        CompletableFuture<String> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("fail"));
        String ticket = registry.register(future);

        registry.poll(ticket);
        TicketRegistry.PollResult<String> second = registry.poll(ticket);

        assertEquals(TicketRegistry.Status.NOT_FOUND, second.getStatus());
    }

    @Test
    void poll_afterTtlExpiry_returnsNotFound() throws InterruptedException {
        TicketRegistry<String> registry = new TicketRegistry<>(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        FutureTask<String> task = new FutureTask<>(() -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
                return "unexpected";
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
        });
        String ticket = registry.register(task);
        Thread worker = new Thread(task, "ticket-registry-ttl-test");
        worker.start();

        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.await(3, TimeUnit.SECONDS),
            "TTL expiry must actively interrupt running work without requiring poll/register");
        assertTrue(task.isCancelled());
        assertEquals(TicketRegistry.Status.NOT_FOUND, registry.poll(ticket).getStatus());
        worker.join(1000);
    }

    @Test
    void completedTicketExpiresWhenNobodyPollsIt() throws InterruptedException {
        TicketRegistry<String> registry = new TicketRegistry<>(1);
        String ticket = registry.register(CompletableFuture.completedFuture("done"));

        TimeUnit.MILLISECONDS.sleep(1100);

        assertEquals(TicketRegistry.Status.NOT_FOUND, registry.poll(ticket).getStatus(),
            "TTL must release completed tickets that were never consumed by poll");
    }

    @Test
    void cancelQueuedTaskPreventsBodyFromRunningAndRemovesTicket() {
        TicketRegistry<String> registry = new TicketRegistry<>(60);
        AtomicBoolean ran = new AtomicBoolean();
        FutureTask<String> task = new FutureTask<>(() -> {
            ran.set(true);
            return "unexpected";
        });
        String ticket = registry.register(task);

        assertTrue(registry.cancel(ticket));
        task.run();

        assertFalse(ran.get(), "register -> cancel -> execute must never enter the task body");
        assertTrue(task.isCancelled());
        assertEquals(TicketRegistry.Status.NOT_FOUND, registry.poll(ticket).getStatus());
    }

    @Test
    void cancelCompletedTaskReturnsFalseAndLeavesResultAvailableToPoll() {
        TicketRegistry<String> registry = new TicketRegistry<>(60);
        CompletableFuture<String> future = CompletableFuture.completedFuture("done");
        String ticket = registry.register(future);

        assertFalse(registry.cancel(ticket),
            "the cancel endpoint reports completed work as not found rather than cancelled");
        assertEquals(TicketRegistry.Status.DONE, registry.poll(ticket).getStatus());
    }

    @Test
    void cancelThatRacesWithCompletionKeepsCompletedResultAvailableToPoll() {
        TicketRegistry<String> registry = new TicketRegistry<>(60);
        Future<String> future = new CompletionDuringCancelFuture("done");
        String ticket = registry.register(future);

        assertFalse(registry.cancel(ticket),
            "a cancellation that loses the race to completion must report false");
        TicketRegistry.PollResult<String> pollResult = registry.poll(ticket);
        assertEquals(TicketRegistry.Status.DONE, pollResult.getStatus());
        assertEquals("done", pollResult.getResult());
    }

    @Test
    void cancelRunningTaskInterruptsItAndRemovesTicket() throws Exception {
        TicketRegistry<String> registry = new TicketRegistry<>(60);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        FutureTask<String> task = new FutureTask<>(() -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
                return "unexpected";
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
        });
        String ticket = registry.register(task);
        Thread worker = new Thread(task, "ticket-registry-cancel-test");
        worker.start();

        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTrue(registry.cancel(ticket));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertTrue(task.isCancelled());
        assertEquals(TicketRegistry.Status.NOT_FOUND, registry.poll(ticket).getStatus());
        worker.join(1000);
    }

    @Test
    void pollFutureTaskFailureUnwrapsExecutionExceptionCause() {
        TicketRegistry<String> registry = new TicketRegistry<>(60);
        FutureTask<String> task = new FutureTask<>(() -> {
            throw new IllegalStateException("future-task-boom");
        });
        String ticket = registry.register(task);
        task.run();

        TicketRegistry.PollResult<String> result = registry.poll(ticket);

        assertEquals(TicketRegistry.Status.ERROR, result.getStatus());
        assertEquals(IllegalStateException.class, result.getError().getClass());
        assertEquals("future-task-boom", result.getError().getMessage());
    }

    @Test
    void ticketsAreUniquePerRegisterCall() {
        TicketRegistry<String> registry = new TicketRegistry<>(60);

        String t1 = registry.register(CompletableFuture.completedFuture("a"));
        String t2 = registry.register(CompletableFuture.completedFuture("b"));

        assertTrue(!t1.equals(t2), "two independent register() calls must not collide on the same ticket");
    }

    @Test
    void concurrentRegisterAndPoll_isSafe() throws InterruptedException {
        // Registers and immediately polls N futures from multiple threads concurrently, verifying
        // every ticket resolves to exactly its own value with no cross-talk or lost updates —
        // the property that matters for a ConcurrentHashMap-backed registry shared by the xref
        // executor pool (2 worker threads) and arbitrary poller request threads.
        TicketRegistry<Integer> registry = new TicketRegistry<>(60);
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger mismatches = new AtomicInteger(0);
        List<Throwable> errors = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        int value = base + i;
                        String ticket = registry.register(CompletableFuture.completedFuture(value));
                        TicketRegistry.PollResult<Integer> result = registry.poll(ticket);
                        if (result.getStatus() != TicketRegistry.Status.DONE
                                || result.getResult() == null
                                || result.getResult() != value) {
                            mismatches.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "concurrent register/poll workload must finish within timeout");
        pool.shutdown();

        assertTrue(errors.isEmpty(), "no thread should throw: " + errors);
        assertEquals(0, mismatches.get(), "every ticket must resolve to exactly the value it was registered with");
    }

    private static final class CompletionDuringCancelFuture implements Future<String> {
        private final String result;
        private boolean done;

        CompletionDuringCancelFuture(String result) {
            this.result = result;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            done = true;
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public String get() {
            return result;
        }

        @Override
        public String get(long timeout, TimeUnit unit) {
            return result;
        }
    }
}
