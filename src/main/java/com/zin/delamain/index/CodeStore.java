package com.zin.delamain.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
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
 *
 * <h2>Integrity</h2>
 * <p>Each {@link #put} computes a CRC32 of the class's decompiled source (bytes, pre-gzip) and
 * keeps it in an in-memory map; {@link #markComplete} dumps that map to a single sidecar manifest
 * file ({@code .crc}) alongside the {@code .complete} marker. A restart reads that one small file
 * back into memory (cheap — one file, not a per-class read), and {@link #get} checks a class's CRC
 * against it lazily, on the same read it already has to do to serve the class — never as a
 * separate pass and never by scanning the whole store. A store built before this feature existed
 * (no {@code .crc} sidecar) is grandfathered in: the map stays empty and reads proceed unchecked,
 * exactly as before.</p>
 */
public class CodeStore {

    private static final Logger logger = LoggerFactory.getLogger(CodeStore.class);

    private static final int CRC_MANIFEST_MAGIC = 0x4A434352; // "JCCR"
    private static final int CRC_MANIFEST_VERSION = 1;

    private final Path baseDir;          // {indexDir}/code/{inputHash8}
    private final Path completeMarker;
    private final Path crcManifestPath;
    /** className -> CRC32 of its decompiled source bytes. Empty for stores predating this feature. */
    private final Map<String, Integer> crcMap = new ConcurrentHashMap<>();

    public CodeStore(Path indexDir, String inputHash) throws IOException {
        String tag = inputHash.length() >= 8 ? inputHash.substring(0, 8) : inputHash;
        this.baseDir = indexDir.resolve("code").resolve(tag);
        this.completeMarker = baseDir.resolve(".complete");
        this.crcManifestPath = baseDir.resolve(".crc");
        Files.createDirectories(baseDir);
        loadCrcManifest();
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
            byte[] bytes = code.getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(p.getParent());
            // Write to a temp sibling then atomic-move so a reader never sees a partial file.
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp" + Thread.currentThread().getId());
            try (OutputStream out = new GZIPOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 15))) {
                out.write(bytes);
            }
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            CRC32 crc = new CRC32();
            crc.update(bytes);
            crcMap.put(className, (int) crc.getValue());
        } catch (Exception e) {
            logger.debug("[CodeStore] put failed for {}: {}", className, e.getMessage());
        }
    }

    /**
     * @return the stored source for a class, or {@code null} if not present / unreadable / (when
     *         a CRC was recorded for this class) corrupted.
     */
    public String get(String className) {
        if (className == null) return null;
        Path p = pathFor(className);
        if (!Files.exists(p)) return null;
        try (InputStream in = new GZIPInputStream(
                new BufferedInputStream(Files.newInputStream(p), 1 << 15))) {
            ByteArrayOutputStream bytesOut = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) bytesOut.write(buf, 0, n);
            byte[] bytes = bytesOut.toByteArray();

            Integer expectedCrc = crcMap.get(className);
            if (expectedCrc != null) {
                CRC32 crc = new CRC32();
                crc.update(bytes);
                if ((int) crc.getValue() != expectedCrc) {
                    logger.warn("[CodeStore] CRC mismatch for {}; treating as corrupt", className);
                    return null;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
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
        crcMap.remove(className);
        try {
            Files.deleteIfExists(p);
        } catch (Exception e) {
            logger.debug("[CodeStore] remove failed for {}: {}", className, e.getMessage());
        }
    }

    /** Marks the store fully populated (call after a complete warmup). */
    public void markComplete(int classCount) {
        writeCrcManifest();
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

    /**
     * Dumps the in-memory className -> CRC32 map accumulated by {@link #put} to a single sidecar
     * file, so a later restart can load per-class integrity checks with one small read instead of
     * touching every class file. Best-effort: a failure here only disables per-file CRC checking
     * (grandfather behavior), it never blocks {@link #markComplete}.
     */
    private void writeCrcManifest() {
        Path tmp = crcManifestPath.resolveSibling(crcManifestPath.getFileName() + ".tmp");
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
            dos.writeInt(CRC_MANIFEST_MAGIC);
            dos.writeInt(CRC_MANIFEST_VERSION);
            dos.writeInt(crcMap.size());
            for (Map.Entry<String, Integer> e : crcMap.entrySet()) {
                dos.writeUTF(e.getKey());
                dos.writeInt(e.getValue());
            }
            dos.flush();
            Files.move(tmp, crcManifestPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            logger.warn("[CodeStore] failed to write CRC manifest (per-file checks disabled): {}", e.getMessage());
        }
    }

    /**
     * Reads the CRC manifest sidecar (if present) into memory, once, at construction. Missing
     * sidecar (a store built before this feature existed) leaves {@link #crcMap} empty, which is
     * the grandfather path: reads proceed without a CRC check, exactly as before this change.
     */
    private void loadCrcManifest() {
        if (!Files.exists(crcManifestPath)) return;
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(crcManifestPath), 1 << 16))) {
            if (dis.readInt() != CRC_MANIFEST_MAGIC) return;
            if (dis.readInt() != CRC_MANIFEST_VERSION) return;
            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                String cls = dis.readUTF();
                int crc = dis.readInt();
                crcMap.put(cls, crc);
            }
        } catch (Exception e) {
            logger.warn("[CodeStore] failed to load CRC manifest, per-file checks disabled: {}", e.getMessage());
            crcMap.clear();
        }
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
