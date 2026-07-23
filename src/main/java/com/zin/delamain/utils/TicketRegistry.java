package com.zin.delamain.utils;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Generic ticket-based async result store shared by long-running JADX operations that need a
 * submit-now / poll-later shape (a caller registers a {@link Future}, gets back an
 * opaque short ticket, and polls it later without blocking the request thread).
 *
 * <p>Extracted so new async endpoints (e.g. xref resolution) can reuse the same TTL/UUID/expiry
 * mechanics that {@code CodeSearchCoordinator}'s original code-search-only ticket registry
 * implemented inline, instead of duplicating them. {@code CodeSearchCoordinator} keeps its own
 * ticket storage as-is (it layers extra semantics — TIMED_OUT/CANCELLED distinctions derived
 * from the future's failure cause, plus a separate result cache — that don't generalise cleanly
 * here); this class is for new callers that just need "submit a future, poll it by ticket".</p>
 *
 * @param <T> the result type produced by the registered future
 */
public final class TicketRegistry<T> {

    public enum Status { RUNNING, DONE, ERROR, NOT_FOUND }

    public static final class PollResult<T> {
        private final Status status;
        private final T result;
        private final Throwable error;

        private PollResult(Status status, T result, Throwable error) {
            this.status = status;
            this.result = result;
            this.error = error;
        }

        public static <T> PollResult<T> running()        { return new PollResult<>(Status.RUNNING, null, null); }
        public static <T> PollResult<T> done(T result)    { return new PollResult<>(Status.DONE, result, null); }
        public static <T> PollResult<T> error(Throwable t) { return new PollResult<>(Status.ERROR, null, t); }
        public static <T> PollResult<T> notFound()        { return new PollResult<>(Status.NOT_FOUND, null, null); }

        public Status getStatus()   { return status; }
        public T getResult()        { return result; }
        public Throwable getError() { return error; }
    }

    private static final class Entry<T> {
        final Future<T> future;
        volatile ScheduledFuture<?> expiryTask;

        Entry(Future<T> future) {
            this.future = future;
        }
    }

    private static final ScheduledExecutorService EXPIRER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ticket-registry-expirer");
            thread.setDaemon(true);
            return thread;
        });

    private final ConcurrentHashMap<String, Entry<T>> registry = new ConcurrentHashMap<>();
    private final int ttlSeconds;

    public TicketRegistry(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    /** Registers a future under a new opaque ticket and returns the ticket. */
    public String register(Future<T> future) {
        String ticket = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Entry<T> entry = new Entry<>(future);
        registry.put(ticket, entry);
        ScheduledFuture<?> expiryTask = EXPIRER.schedule(
            () -> expire(ticket, entry), ttlSeconds, TimeUnit.SECONDS);
        entry.expiryTask = expiryTask;
        if (registry.get(ticket) != entry) {
            expiryTask.cancel(false);
        }
        return ticket;
    }

    /**
     * Polls a ticket. Terminal states (DONE/ERROR) remove the ticket from the registry — poll
     * again after that returns NOT_FOUND, matching the one-shot consume semantics callers expect.
     */
    public PollResult<T> poll(String ticket) {
        Entry<T> entry = registry.get(ticket);
        if (entry == null) {
            return PollResult.notFound();
        }
        synchronized (entry) {
            Future<T> future = entry.future;
            if (registry.get(ticket) != entry) {
                return PollResult.notFound();
            }
            if (!future.isDone()) {
                return PollResult.running();
            }
            registry.remove(ticket, entry);
            cancelExpiry(entry);
            try {
                T result = future.get();
                if (result != null) {
                    return PollResult.done(result);
                }
                return PollResult.error(new IllegalStateException("Task completed with null result"));
            } catch (ExecutionException e) {
                return PollResult.error(e.getCause() != null ? e.getCause() : e);
            } catch (CancellationException e) {
                return PollResult.error(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return PollResult.error(e);
            } catch (Exception e) {
                return PollResult.error(e);
            }
        }
    }

    /**
     * Cancels live work and removes its ticket. Completed, expired, unknown, and already-cancelled
     * tickets return false so callers can consistently expose them as not found.
     */
    public boolean cancel(String ticket) {
        Entry<T> entry = registry.get(ticket);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (registry.get(ticket) != entry || entry.future.isDone() || !entry.future.cancel(true)) {
                return false;
            }
            registry.remove(ticket, entry);
            cancelExpiry(entry);
            return true;
        }
    }

    private void expire(String ticket, Entry<T> entry) {
        synchronized (entry) {
            if (registry.get(ticket) != entry) {
                return;
            }
            if (!entry.future.isDone()) {
                entry.future.cancel(true);
            }
            registry.remove(ticket, entry);
        }
    }

    private static void cancelExpiry(Entry<?> entry) {
        ScheduledFuture<?> expiryTask = entry.expiryTask;
        if (expiryTask != null) {
            expiryTask.cancel(false);
        }
    }
}
