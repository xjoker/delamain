package com.zin.delamain.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Disk-backed store of decompiled Java source, keyed by class full name.
 *
 * <p><b>Why.</b> An APK is immutable, so a class's decompiled source never changes. Re-running
 * jadx's {@code getCode()} on a cold restart (Phase-1 warmup) costs minutes for a 237k-class APK,
 * and heap-pressure eviction silently throws warm code away. Persisting the source lets a restart
 * (or an evicted cache-miss) read it back in milliseconds instead of re-decompiling.</p>
 *
 * <h2>Layout</h2>
 * <pre>{@code {indexDir}/code/{inputHash8}/{xx}/{sha256(className)}.gz}</pre>
 * Sharded into 256 buckets by the first SHA-256 byte so no directory holds all 237k files, and
 * the sha256 filename sidesteps class-name charset/length limits. Files are gzipped (~3-4× on
 * decompiled Java). Writes are per-file, so the parallel Phase-1 workers write lock-free.
 * A {@code .complete} marker is written once a full warmup finishes, so a restart can tell the
 * store is fully populated and safely skip re-decompilation.
 */
public class CodeStore {

    private static final Logger logger = LoggerFactory.getLogger(CodeStore.class);

    private final Path baseDir;          // {indexDir}/code/{inputHash8}
    private final Path completeMarker;

    public CodeStore(Path indexDir, String inputHash) throws IOException {
        String tag = inputHash.length() >= 8 ? inputHash.substring(0, 8) : inputHash;
        this.baseDir = indexDir.resolve("code").resolve(tag);
        this.completeMarker = baseDir.resolve(".complete");
        Files.createDirectories(baseDir);
    }

    private Path pathFor(String className) {
        String h = sha256Hex(className);
        return baseDir.resolve(h.substring(0, 2)).resolve(h + ".gz");
    }

    /** Writes (gzipped) decompiled source for a class. Safe to call from parallel workers. */
    public void put(String className, String code) {
        if (className == null || code == null) return;
        Path p = pathFor(className);
        try {
            Files.createDirectories(p.getParent());
            // Write to a temp sibling then atomic-move so a reader never sees a partial file.
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp" + Thread.currentThread().getId());
            try (Writer w = new OutputStreamWriter(
                    new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 15)),
                    StandardCharsets.UTF_8)) {
                w.write(code);
            }
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.debug("[CodeStore] put failed for {}: {}", className, e.getMessage());
        }
    }

    /** @return the stored source for a class, or {@code null} if not present / unreadable. */
    public String get(String className) {
        if (className == null) return null;
        Path p = pathFor(className);
        if (!Files.exists(p)) return null;
        try (Reader r = new InputStreamReader(
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(p), 1 << 15)),
                StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder(8192);
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) >= 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (Exception e) {
            logger.debug("[CodeStore] get failed for {}: {}", className, e.getMessage());
            return null;
        }
    }

    public boolean contains(String className) {
        return className != null && Files.exists(pathFor(className));
    }

    /**
     * Deletes the persisted source for a class (post-rename invalidation), so a later
     * {@link #get} correctly misses instead of serving stale pre-rename source. No-op if the
     * class was never persisted.
     */
    public void remove(String className) {
        if (className == null) return;
        Path p = pathFor(className);
        try {
            Files.deleteIfExists(p);
        } catch (Exception e) {
            logger.debug("[CodeStore] remove failed for {}: {}", className, e.getMessage());
        }
    }

    /** Marks the store fully populated (call after a complete warmup). */
    public void markComplete(int classCount) {
        try {
            Files.writeString(completeMarker, Integer.toString(classCount), StandardCharsets.UTF_8);
            logger.info("[CodeStore] marked complete ({} classes) at {}", classCount, baseDir);
        } catch (IOException e) {
            logger.warn("[CodeStore] failed to write complete marker: {}", e.getMessage());
        }
    }

    /** @return true if a previous warmup fully populated this store (so Phase-1 can be skipped). */
    public boolean isComplete() {
        return Files.exists(completeMarker);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // Fallback: hashCode hex (collision-tolerant for our keyed use)
            return Integer.toHexString(s.hashCode());
        }
    }
}
