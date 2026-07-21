package com.zin.delamain.server;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-in-memory store for one-time file-transfer tokens (see the upload-transfer contract:
 * POST /create-transfer-token, PUT /transfer/upload, GET /transfer/status).
 *
 * <p>Tokens are 256-bit {@link SecureRandom} values (64 hex chars), single-use (consumed on a
 * successful final chunk), and expire after a TTL. Not persisted across restarts — a restart
 * simply invalidates all in-flight transfers, which is acceptable for this short-lived,
 * operator-initiated flow.
 */
public final class TransferTokenStore {

    private static final int TOKEN_BYTES = 32; // 256 bits -> 64 hex chars

    private final ConcurrentHashMap<String, TokenEntry> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** Creates a new token with the given TTL (in minutes). */
    public TokenEntry create(String filename, Long expectedSize, int ttlMinutes) {
        return createWithTtlMillis(filename, expectedSize, ttlMinutes * 60_000L);
    }

    /**
     * Creates a new token with an explicit TTL in milliseconds. Package-visible test seam: a
     * negative value produces an already-expired entry without needing to wait out a real TTL.
     */
    public TokenEntry createWithTtlMillis(String filename, Long expectedSize, long ttlMillis) {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = toHex(raw);
        long expiresAt = System.currentTimeMillis() + ttlMillis;
        TokenEntry entry = new TokenEntry(token, filename, expectedSize, expiresAt);
        tokens.put(token, entry);
        return entry;
    }

    public TokenEntry get(String token) {
        return token == null || token.isEmpty() ? null : tokens.get(token);
    }

    public void remove(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    /** Weakly-consistent snapshot view, safe to iterate while other threads mutate the map
     *  (used by the periodic sweep in {@link com.zin.delamain.server.routes.TransferRoutes}). */
    public Collection<TokenEntry> allEntries() {
        return tokens.values();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * A single transfer token's state. Mutable fields ({@code bytesReceived}, {@code consumed})
     * are guarded by synchronizing on the entry instance itself — callers performing a
     * check-then-act sequence (e.g. "offset matches bytesReceived, then append and advance")
     * must hold that lock for the whole sequence; see {@link com.zin.delamain.server.routes.TransferRoutes}.
     */
    public static final class TokenEntry {
        public final String token;
        public final String filename;
        public final Long expectedSize; // nullable
        public final long expiresAtEpochMs;

        private long bytesReceived = 0;
        private boolean consumed = false;

        TokenEntry(String token, String filename, Long expectedSize, long expiresAtEpochMs) {
            this.token = token;
            this.filename = filename;
            this.expectedSize = expectedSize;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }

        public synchronized long getBytesReceived() {
            return bytesReceived;
        }

        public synchronized boolean isConsumed() {
            return consumed;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAtEpochMs;
        }

        /** Sets the received-byte count directly. The on-disk {@code .part} file's actual size
         *  is the source of truth (see the upload flow in {@code TransferRoutes}) — this exists
         *  so callers can reconcile the in-memory counter to it rather than trusting an
         *  incremental add that could drift if a previous write partially failed. */
        public synchronized void setBytesReceived(long n) {
            bytesReceived = n;
        }

        public synchronized void markConsumed() {
            consumed = true;
        }
    }
}
