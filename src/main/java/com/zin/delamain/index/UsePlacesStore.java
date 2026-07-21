package com.zin.delamain.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;

/**
 * Persists the {@link UsePlacesIndex} per-target precise use-places to disk so a {@code FAST_RESTORE}
 * serves precise xref ({@code /xrefs-to-class?include_snippet=true}) instantly, without waiting for
 * the background deep-warm to rebuild jadx's processed state (the "cold window").
 *
 * <p>Mirrors {@link UsageGraphStore}'s conventions (magic + version + atomic move) and reuses the
 * {@code {input_hash}.useplaces} naming so the file is invalidated when the loaded APK changes.</p>
 *
 * <h2>File format</h2>
 * <pre>
 *   MAGIC   (4 bytes) = 0x4A415550  "JAUP"
 *   VERSION (4 bytes) = 1
 *   N       (4 bytes) = number of target classes (rows, id-ordered 0..N-1)
 *   foreach target row:
 *     tripleCount (4 bytes)                       // number of (refId,decompLine,srcLine) triples
 *     triples     (tripleCount × 3 × 4 bytes)     // flat: refId, decompiledLine, sourceLine
 * </pre>
 * Row order IS the class-id order ({@link UsePlacesIndex#assignIds}, sorted by RAW name), so rows
 * are stored densely without keys; ids resolve against the same sorted class list rebuilt for the
 * current session.
 */
public class UsePlacesStore {

    private static final Logger logger = LoggerFactory.getLogger(UsePlacesStore.class);

    private static final int MAGIC = 0x4A415550; // "JAUP"
    private static final int VERSION = 1;
    // Sanity cap on per-target use-place triples (corruption / OOM guard, not a real invariant).
    private static final int MAX_PLACES_PER_TARGET = 50_000_000;

    private final Path indexDir;

    public UsePlacesStore(Path indexDir) throws IOException {
        this.indexDir = indexDir;
        Files.createDirectories(indexDir);
    }

    public Path getPath(String inputHash) {
        return indexDir.resolve(inputHash + ".useplaces");
    }

    public boolean exists(String inputHash) {
        return Files.exists(getPath(inputHash));
    }

    public void save(int[][] perTarget, String inputHash) throws IOException {
        if (perTarget == null) return;
        Path finalPath = getPath(inputHash);
        Path tmpPath = finalPath.resolveSibling(inputHash + ".useplaces.tmp");

        long triples = 0;
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmpPath), 1 << 20))) {
            dos.writeInt(MAGIC);
            dos.writeInt(VERSION);
            dos.writeInt(perTarget.length);
            for (int[] row : perTarget) {
                if (row == null || row.length == 0) {
                    dos.writeInt(0);
                    continue;
                }
                int count = row.length / 3; // triples
                dos.writeInt(count);
                for (int i = 0; i < count * 3; i++) dos.writeInt(row[i]);
                triples += count;
            }
        }
        Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        logger.info("[UsePlacesStore] Saved precise use-places ({} targets, {} places) to {}",
            perTarget.length, triples, finalPath.getFileName());
    }

    /**
     * @return the per-target triples array, or {@code null} if missing / wrong header / corrupt /
     *         class-count mismatch with {@code expectedClasses}.
     */
    public int[][] tryLoad(String inputHash, int expectedClasses) throws IOException {
        Path path = getPath(inputHash);
        if (!Files.exists(path)) {
            logger.debug("[UsePlacesStore] No use-places file for hash {}", safeTag(inputHash));
            return null;
        }
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path), 1 << 20))) {
            int magic = dis.readInt();
            if (magic != MAGIC) {
                logger.warn("[UsePlacesStore] Bad magic in {}; will rebuild", path.getFileName());
                Files.deleteIfExists(path);
                return null;
            }
            int version = dis.readInt();
            if (version != VERSION) {
                logger.info("[UsePlacesStore] Version mismatch ({} vs {}); will rebuild", version, VERSION);
                Files.deleteIfExists(path);
                return null;
            }
            int n = dis.readInt();
            if (n != expectedClasses) {
                logger.info("[UsePlacesStore] Class-count mismatch ({} vs {}); will rebuild", n, expectedClasses);
                Files.deleteIfExists(path);
                return null;
            }
            int[][] data = new int[n][];
            for (int i = 0; i < n; i++) {
                int count = dis.readInt();
                // count = number of use-place triples for this target. Unlike the usage graph
                // (where a row holds distinct referrer CLASSES, bounded by n), a single hot class
                // can be referenced at FAR more positions than there are classes, so count is NOT
                // bounded by n. Only guard against negatives and absurd values (corruption / OOM).
                if (count < 0 || count > MAX_PLACES_PER_TARGET) throw new IOException("bad triple count " + count);
                int len = count * 3;
                int[] row = new int[len];
                for (int j = 0; j < len; j++) row[j] = dis.readInt();
                data[i] = row;
            }
            logger.info("[UsePlacesStore] Loaded precise use-places ({} targets) from {} (fast-path)",
                n, path.getFileName());
            return data;
        } catch (Exception e) {
            logger.warn("[UsePlacesStore] Corrupt use-places {}; deleting: {}", path.getFileName(), e.getMessage());
            Files.deleteIfExists(path);
            return null;
        }
    }

    private static String safeTag(String hash) {
        return hash != null && hash.length() >= 8 ? hash.substring(0, 8) : String.valueOf(hash);
    }
}
