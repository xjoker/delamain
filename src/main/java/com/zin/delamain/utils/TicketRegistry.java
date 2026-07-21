package com.zin.delamain.utils;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic ticket-based async result store shared by long-running JADX operations that need a
 * submit-now / poll-later shape (a caller registers a {@link CompletableFuture}, gets back an
 * opaque short ticket, and polls it later without blocking the request thread).
 *
 * <p>Extracted so new async endpoints (e.g. xref resolution) can reuse the same TTL/UUID/prune
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
        final CompletableFuture<T> future;
        final long createdAtMs;

        Entry(CompletableFuture<T> future, long createdAtMs) {
            this.future = future;
            this.createdAtMs = createdAtMs;
        }
    }

    private final ConcurrentHashMap<String, Entry<T>> registry = new ConcurrentHashMap<>();
    private final int ttlSeconds;

    public TicketRegistry(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    /** Registers a future under a new opaque ticket and returns the ticket. */
    public String register(CompletableFuture<T> future) {
        pruneExpired();
        String ticket = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        registry.put(ticket, new Entry<>(future, System.currentTimeMillis()));
        return ticket;
    }

    /**
     * Polls a ticket. Terminal states (DONE/ERROR) remove the ticket from the registry — poll
     * again after that returns NOT_FOUND, matching the one-shot consume semantics callers expect.
     */
    public PollResult<T> poll(String ticket) {
        pruneExpired();
        Entry<T> entry = registry.get(ticket);
        if (entry == null) {
            return PollResult.notFound();
        }
        CompletableFuture<T> future = entry.future;
        if (!future.isDone()) {
            return PollResult.running();
        }
        registry.remove(ticket);
        try {
            T result = future.getNow(null);
            if (result != null) {
                return PollResult.done(result);
            }
            return PollResult.error(new IllegalStateException("Task completed with null result"));
        } catch (CompletionException e) {
            return PollResult.error(e.getCause() != null ? e.getCause() : e);
        } catch (CancellationException e) {
            return PollResult.error(e);
        } catch (Exception e) {
            return PollResult.error(e);
        }
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        registry.entrySet().removeIf(e -> (now - e.getValue().createdAtMs) > (ttlSeconds * 1000L));
    }
}
