package com.zin.delamain.server.routes;

import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.server.TransferTokenStore;
import com.zin.delamain.server.TransferTokenStore.TokenEntry;
import com.zin.delamain.utils.FilePathSandbox;
import com.zin.delamain.utils.FilePathSandbox.SandboxViolation;

import io.javalin.Javalin;
import io.javalin.http.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * File-transfer routes: hand a one-time upload token to an MCP client (see
 * {@code create_transfer_token}) and let bytes flow directly over HTTP, out of the AI context.
 *
 * Endpoints (see the upload-transfer contract, v1):
 *   POST /create-transfer-token              body: {filename, size_bytes?}
 *   PUT  /transfer/upload                    header X-Transfer-Token; optional chunk headers
 *   GET  /transfer/status?token=...          or header X-Transfer-Token
 *
 * Bytes land in {@code <root>/.transfer/<token>.part} while in flight and are atomically moved
 * to {@code <root>/<filename>} on the final chunk. All three endpoints reject requests when the
 * sandbox root is unavailable, matching {@link FileManagementRoutes}.
 */
public class TransferRoutes {
    private static final Logger logger = LoggerFactory.getLogger(TransferRoutes.class);

    private static final String ENV_TTL_MIN = "JADX_TRANSFER_TTL_MIN";
    private static final int DEFAULT_TTL_MIN = 30;
    private static final String ENV_MAX_MB = "JADX_TRANSFER_MAX_MB";
    private static final long DEFAULT_MAX_MB = 1024;
    private static final long CHUNK_SIZE_HINT = 8 * 1024 * 1024L; // 8 MiB, advisory only
    private static final int STREAM_BUFFER_SIZE = 64 * 1024; // 64 KiB read/write buffer
    private static final long SWEEP_INTERVAL_SECONDS = 60;

    private final FilePathSandbox sandbox;
    private final TransferTokenStore tokenStore;
    private final long maxBytes;
    private final int ttlMinutes;
    private final ScheduledExecutorService sweepScheduler; // null in the test-seam constructor

    public TransferRoutes(FilePathSandbox sandbox) {
        this(sandbox, new TransferTokenStore(), maxBytesFromEnv(), ttlMinutesFromEnv(), true);
    }

    /** Package-private test seam: inject the token store and override the size cap / TTL so
     *  tests don't depend on real environment variables or elapsed wall-clock time. Does not
     *  start the background sweep scheduler — tests call {@link #sweepExpiredAndConsumedTokens()}
     *  directly instead of waiting on a real 60s timer. */
    TransferRoutes(FilePathSandbox sandbox, TransferTokenStore tokenStore, long maxBytes, int ttlMinutes) {
        this(sandbox, tokenStore, maxBytes, ttlMinutes, false);
    }

    private TransferRoutes(FilePathSandbox sandbox, TransferTokenStore tokenStore, long maxBytes,
                            int ttlMinutes, boolean startScheduler) {
        this.sandbox = sandbox;
        this.tokenStore = tokenStore;
        this.maxBytes = maxBytes;
        this.ttlMinutes = ttlMinutes;

        // A restart invalidates every in-memory token, so any .part file still sitting in
        // .transfer/ at construction time is unambiguously an orphan from a transfer that never
        // finished before the previous process exited — safe to delete unconditionally.
        cleanupTransferDirOnStartup();

        if (startScheduler) {
            this.sweepScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jadx-transfer-sweep");
                t.setDaemon(true);
                return t;
            });
            this.sweepScheduler.scheduleAtFixedRate(this::sweepExpiredAndConsumedTokens,
                    SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
        } else {
            this.sweepScheduler = null;
        }
    }

    private static long maxBytesFromEnv() {
        String env = System.getenv(ENV_MAX_MB);
        long mb = DEFAULT_MAX_MB;
        if (env != null && !env.isEmpty()) {
            try {
                mb = Math.max(1, Long.parseLong(env.trim()));
            } catch (NumberFormatException ignored) {
                logger.warn("Invalid {}={}, falling back to default {}MB", ENV_MAX_MB, env, DEFAULT_MAX_MB);
            }
        }
        return mb * 1024L * 1024L;
    }

    private static int ttlMinutesFromEnv() {
        String env = System.getenv(ENV_TTL_MIN);
        if (env != null && !env.isEmpty()) {
            try {
                return Math.max(1, Integer.parseInt(env.trim()));
            } catch (NumberFormatException ignored) {
                logger.warn("Invalid {}={}, falling back to default {}min", ENV_TTL_MIN, env, DEFAULT_TTL_MIN);
            }
        }
        return DEFAULT_TTL_MIN;
    }

    public void register(Javalin app, AuthConfig auth) {
        app.post("/create-transfer-token", this::handleCreateTransferToken);
        app.put("/transfer/upload", this::handleUpload);
        app.get("/transfer/status", this::handleStatus);
    }

    // -------------------------------------------------------------------------
    // Token/staging-file lifecycle: startup cleanup + periodic sweep
    // -------------------------------------------------------------------------

    /** Deletes every leftover file directly inside {@code <root>/.transfer/} — never anything
     *  else in the sandbox. Called once at construction; safe no-op if the sandbox is disabled
     *  or the directory doesn't exist yet. */
    private void cleanupTransferDirOnStartup() {
        if (!sandbox.isEnabled()) {
            return;
        }
        Path transferDir = sandbox.getRoot().resolve(".transfer");
        if (!Files.isDirectory(transferDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(transferDir)) {
            for (Path stale : stream) {
                try {
                    Files.deleteIfExists(stale);
                } catch (IOException e) {
                    logger.warn("Failed to delete stale transfer staging file {}: {}", stale, e.getMessage());
                }
            }
            logger.info("Cleared stale .transfer staging directory on startup: {}", transferDir);
        } catch (IOException e) {
            logger.warn("Failed to list .transfer for startup cleanup: {}", e.getMessage());
        }
    }

    /**
     * Removes every expired or consumed token from the store and deletes its staging
     * {@code .part} file, if any. Run every {@value #SWEEP_INTERVAL_SECONDS}s by a daemon
     * scheduler in production (see the constructor); package-private and callable directly so
     * tests don't depend on real elapsed time.
     *
     * @return the number of tokens swept, for test assertions.
     */
    int sweepExpiredAndConsumedTokens() {
        if (!sandbox.isEnabled()) {
            return 0;
        }
        int removed = 0;
        for (TokenEntry entry : tokenStore.allEntries()) {
            if (entry.isExpired() || entry.isConsumed()) {
                tokenStore.remove(entry.token);
                deletePartFileQuietly(entry.token);
                removed++;
            }
        }
        return removed;
    }

    private void deletePartFileQuietly(String token) {
        Path partPath = sandbox.getRoot().resolve(".transfer").resolve(token + ".part");
        try {
            Files.deleteIfExists(partPath);
        } catch (IOException e) {
            logger.warn("Failed to delete .part for swept token {}: {}", tokenPrefix(token), e.getMessage());
        }
    }

    /** Sets {@code entry}'s bytesReceived to the .part file's actual on-disk size (0 if it
     *  doesn't exist), so the counter can never drift from what's really there. */
    private void reconcileBytesReceivedFromDisk(TokenEntry entry, Path partPath) {
        long actual;
        try {
            actual = Files.exists(partPath) ? Files.size(partPath) : 0;
        } catch (IOException e) {
            logger.warn("Failed to stat .part for token {} during reconcile: {}",
                    tokenPrefix(entry.token), e.getMessage());
            return;
        }
        entry.setBytesReceived(actual);
    }

    /** Deletes the staging .part file and zeroes bytesReceived so the same (still-unconsumed)
     *  token can restart the whole upload from offset 0, instead of being permanently stuck at
     *  bytesReceived==full-size with a full-size orphan .part until it expires. */
    private void resetForRetry(TokenEntry entry, Path partPath) {
        try {
            Files.deleteIfExists(partPath);
        } catch (IOException e) {
            logger.warn("Failed to delete .part during reset for token {}: {}",
                    tokenPrefix(entry.token), e.getMessage());
        }
        entry.setBytesReceived(0);
    }

    // -------------------------------------------------------------------------
    // POST /create-transfer-token
    // -------------------------------------------------------------------------

    public void handleCreateTransferToken(Context ctx) {
        if (!sandbox.isEnabled()) {
            ctx.status(503).json(Map.of("error", "Sandbox not configured. Set JADX_FILE_ROOT or mount a directory at /apks."));
            return;
        }
        try {
            Map<?, ?> body = ctx.bodyAsClass(Map.class);
            String filename = body == null ? null : String.valueOf(body.get("filename"));
            if (body == null || body.get("filename") == null || filename.isEmpty()) {
                ctx.status(400).json(Map.of("error", "filename is required"));
                return;
            }

            String basenameError = validateBasename(filename);
            if (basenameError != null) {
                ctx.status(400).json(Map.of("error", basenameError));
                return;
            }
            if (!hasAllowedExtension(filename)) {
                ctx.status(400).json(Map.of("error",
                        "extension not allowed (need one of " + FileManagementRoutes.ALLOWED_EXTENSIONS + "): " + filename));
                return;
            }
            if (hasEmptyStem(filename)) {
                ctx.status(400).json(Map.of("error",
                        "filename must have a non-empty name before the extension: " + filename));
                return;
            }

            Long sizeBytes = null;
            Object sizeObj = body.get("size_bytes");
            if (sizeObj instanceof Number) {
                sizeBytes = ((Number) sizeObj).longValue();
            }
            if (sizeBytes != null && (sizeBytes <= 0 || sizeBytes > maxBytes)) {
                ctx.status(413).json(Map.of(
                        "error", "size_bytes exceeds cap",
                        "max_bytes", maxBytes));
                return;
            }

            TokenEntry entry = tokenStore.create(filename, sizeBytes, ttlMinutes);

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("token", entry.token);
            result.put("filename", entry.filename);
            result.put("upload_path", "/transfer/upload");
            result.put("status_path", "/transfer/status");
            result.put("expires_at_epoch_ms", entry.expiresAtEpochMs);
            result.put("max_bytes", maxBytes);
            result.put("chunk_size_hint", CHUNK_SIZE_HINT);
            ctx.json(result);
        } catch (Exception e) {
            logger.error("create-transfer-token failed: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "create-transfer-token failed: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // PUT /transfer/upload
    // -------------------------------------------------------------------------

    public void handleUpload(Context ctx) {
        if (!sandbox.isEnabled()) {
            ctx.status(503).json(Map.of("error", "Sandbox not configured. Set JADX_FILE_ROOT or mount a directory at /apks."));
            return;
        }
        try {
            String token = ctx.header("X-Transfer-Token");
            TokenEntry entry = tokenStore.get(token);
            if (entry == null || entry.isExpired() || entry.isConsumed()) {
                ctx.status(401).json(Map.of("error", "invalid_or_expired_token"));
                return;
            }

            long offset = parseLongHeader(ctx.header("X-Chunk-Offset"), 0L);
            boolean isFinal = parseBoolHeader(ctx.header("X-Chunk-Final"), true);
            String expectedSha256 = ctx.header("X-Content-Sha256");

            synchronized (entry) {
                if (entry.isExpired() || entry.isConsumed()) {
                    ctx.status(401).json(Map.of("error", "invalid_or_expired_token"));
                    return;
                }

                Path partDir = sandbox.getRoot().resolve(".transfer");
                Path partPath = partDir.resolve(entry.token + ".part");

                // The .part file's actual size is the source of truth. If a previous request's
                // write partially failed after some bytes hit disk but before addBytesReceived
                // ran (or vice versa), the in-memory counter can drift from what's really there;
                // reconcile to disk before trusting it for the offset check below, so a client
                // resuming "from bytesReceived" never gets silently misaligned corruption.
                long onDisk = 0;
                try {
                    if (Files.exists(partPath)) {
                        onDisk = Files.size(partPath);
                    }
                } catch (IOException e) {
                    logger.error("Failed to stat .part for token {}: {}", tokenPrefix(entry.token), e.getMessage(), e);
                    ctx.status(500).json(Map.of("error", "failed to stat staging file: " + e.getMessage()));
                    return;
                }
                long bytesReceived = entry.getBytesReceived();
                if (onDisk != bytesReceived) {
                    logger.warn("bytesReceived/disk mismatch for token {}: memory={} disk={} — reconciling to disk",
                            tokenPrefix(entry.token), bytesReceived, onDisk);
                    entry.setBytesReceived(onDisk);
                    bytesReceived = onDisk;
                }

                if (offset != bytesReceived) {
                    ctx.status(409).json(Map.of(
                            "error", "offset_mismatch",
                            "bytes_received", bytesReceived));
                    return;
                }

                // Effective remaining allowance for this request: the operator-wide cap and the
                // (optional) declared expected size, whichever is stricter.
                long effectiveCap = entry.expectedSize != null ? Math.min(maxBytes, entry.expectedSize) : maxBytes;
                long capRemaining = Math.max(0, effectiveCap - bytesReceived);

                try {
                    Files.createDirectories(partDir);
                    // Stream the body straight to disk in bounded 64KiB chunks instead of
                    // ctx.bodyAsBytes(): that cached-body path enforces Javalin's
                    // http.maxRequestSize (default 1,000,000 bytes) and rejects any real APK
                    // chunk before our own JADX_TRANSFER_MAX_MB cap logic ever runs.
                    // ctx.bodyInputStream() is the raw servlet stream and isn't subject to that
                    // cap, and this loop keeps memory use bounded regardless of file size.
                    streamChunkToFile(ctx.bodyInputStream(), partPath, capRemaining);
                } catch (TransferCapExceededException e) {
                    ctx.status(413).json(Map.of(
                            "error", "size_exceeds_cap",
                            "max_bytes", maxBytes));
                    return;
                } catch (IOException e) {
                    logger.error("Failed to write transfer chunk for token {}: {}", tokenPrefix(entry.token), e.getMessage(), e);
                    ctx.status(500).json(Map.of("error", "failed to write chunk: " + e.getMessage()));
                    return;
                } finally {
                    // Re-derive from disk regardless of outcome above (including the cap-exceeded
                    // and I/O-failure paths) so bytesReceived always reflects what's really on
                    // disk for the *next* request, rather than trusting arithmetic that a partial
                    // write could have invalidated.
                    reconcileBytesReceivedFromDisk(entry, partPath);
                }

                if (!isFinal) {
                    ctx.status(200).json(Map.of(
                            "status", "partial",
                            "bytes_received", entry.getBytesReceived()));
                    return;
                }

                // Final chunk: optionally verify whole-file SHA-256, then move into place.
                String actualSha256;
                try {
                    actualSha256 = sha256Hex(partPath);
                } catch (IOException | NoSuchAlgorithmException e) {
                    logger.error("Failed to hash transfer file for token {}: {}", tokenPrefix(entry.token), e.getMessage(), e);
                    ctx.status(500).json(Map.of("error", "failed to hash file: " + e.getMessage()));
                    return;
                }
                if (expectedSha256 != null && !expectedSha256.isEmpty()
                        && !actualSha256.equalsIgnoreCase(expectedSha256.trim())) {
                    // Don't strand the token at bytesReceived==full-size with no way to make
                    // offset-based progress: reset so the same token can restart the whole
                    // upload from offset 0, instead of being pinned until TTL expiry with a
                    // full-size orphan .part occupying disk the whole time.
                    resetForRetry(entry, partPath);
                    ctx.status(422).json(Map.of(
                            "error", "sha256_mismatch",
                            "expected", expectedSha256,
                            "actual", actualSha256,
                            "message", "Upload reset — re-upload the whole file from offset 0 using the same token."));
                    return;
                }

                Path dest;
                try {
                    dest = sandbox.resolveForCreate(entry.filename);
                } catch (SandboxViolation e) {
                    ctx.status(400).json(Map.of("error", e.getMessage()));
                    return;
                }
                try {
                    Files.move(partPath, dest,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    logger.error("Failed to finalize transfer for token {}: {}", tokenPrefix(entry.token), e.getMessage(), e);
                    // Same reasoning as the sha256_mismatch branch: don't leave the token stuck.
                    resetForRetry(entry, partPath);
                    ctx.status(500).json(Map.of(
                            "error", "failed to finalize upload: " + e.getMessage(),
                            "message", "Upload reset — re-upload the whole file from offset 0 using the same token."));
                    return;
                }
                entry.markConsumed();

                ctx.status(200).json(Map.of(
                        "status", "complete",
                        "path", entry.filename,
                        "bytes", entry.getBytesReceived(),
                        "sha256", actualSha256));
            }
        } catch (Exception e) {
            logger.error("transfer/upload failed: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "transfer/upload failed: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // GET /transfer/status
    // -------------------------------------------------------------------------

    public void handleStatus(Context ctx) {
        if (!sandbox.isEnabled()) {
            ctx.status(503).json(Map.of("error", "Sandbox not configured. Set JADX_FILE_ROOT or mount a directory at /apks."));
            return;
        }
        try {
            String token = ctx.header("X-Transfer-Token");
            if (token == null || token.isEmpty()) {
                token = ctx.queryParam("token");
            }
            TokenEntry entry = tokenStore.get(token);
            if (entry == null || entry.isExpired()) {
                ctx.status(404).json(Map.of("error", "token_not_found"));
                return;
            }
            ctx.json(Map.of(
                    "filename", entry.filename,
                    "bytes_received", entry.getBytesReceived(),
                    "expires_at_epoch_ms", entry.expiresAtEpochMs,
                    "consumed", entry.isConsumed()));
        } catch (Exception e) {
            logger.error("transfer/status failed: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "transfer/status failed: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Rejects anything but a plain basename: no path separators, no "." / "..". */
    private static String validateBasename(String filename) {
        if (filename.contains("/") || filename.contains("\\")) {
            return "filename must not contain path separators: " + filename;
        }
        if (".".equals(filename) || "..".equals(filename)) {
            return "filename must not be '.' or '..'";
        }
        return null;
    }

    private static boolean hasAllowedExtension(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        List<String> allowed = FileManagementRoutes.ALLOWED_EXTENSIONS;
        return allowed.stream().anyMatch(lower::endsWith);
    }

    /** Rejects filenames that are nothing but the extension itself (e.g. {@code ".apk"}) —
     *  already known to have an allowed extension by the time this is called; this just checks
     *  the basename left after stripping it isn't empty. */
    private static boolean hasEmptyStem(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return FileManagementRoutes.ALLOWED_EXTENSIONS.stream().anyMatch(lower::equals);
    }

    /** Never log a full transfer token — it's a live, bearer-equivalent credential. */
    private static String tokenPrefix(String token) {
        if (token == null) {
            return "null";
        }
        return token.length() <= 8 ? token : token.substring(0, 8) + "...";
    }

    private static long parseLongHeader(String value, long defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean parseBoolHeader(String value, boolean defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * Streams {@code in} into {@code partPath} (append), aborting the moment the running total
     * for this request would exceed {@code capRemaining}. Bounded 64KiB buffer regardless of
     * total file size — no request body is ever fully materialized in memory.
     *
     * @throws TransferCapExceededException if the request would push the file over the cap;
     *         bytes read up to (and including) the buffer that tipped it over are not written.
     */
    private static long streamChunkToFile(InputStream in, Path partPath, long capRemaining) throws IOException {
        long written = 0;
        byte[] buf = new byte[STREAM_BUFFER_SIZE];
        try (OutputStream out = Files.newOutputStream(partPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (written + n > capRemaining) {
                    throw new TransferCapExceededException();
                }
                out.write(buf, 0, n);
                written += n;
            }
        }
        return written;
    }

    private static final class TransferCapExceededException extends IOException {
    }

    private static String sha256Hex(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
