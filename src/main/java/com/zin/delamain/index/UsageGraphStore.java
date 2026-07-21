package com.zin.delamain.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;

/**
 * Persists the {@link UsageGraphIndex} class-level reverse graph to disk so a cold restart
 * skips re-harvesting (and, more importantly, lets xref answer instantly without re-decompiling
 * callers). Mirrors {@link PersistentIndexStore}'s format conventions (magic + version + atomic
 * move) and reuses its {@code {input_hash}.graph} naming so the file is invalidated when the
 * loaded APK changes.
 *
 * <h2>File format</h2>
 * <pre>
 *   MAGIC   (4 bytes) = 0x4A414447  "JADG"
 *   VERSION (4 bytes) = 1
 *   N       (4 bytes) = number of classes (adjacency rows, id-ordered 0..N-1)
 *   foreach row:
 *     refCount (4 bytes)
 *     refIds   (refCount × 4 bytes, ascending source-class ids)
 * </pre>
 * Row order IS the class-id order, so the array is stored densely without keys; ids resolve
 * against {@link UsageGraphIndex#assignIds} rebuilt from the same sorted class list.
 */
public class UsageGraphStore {

    private static final Logger logger = LoggerFactory.getLogger(UsageGraphStore.class);

    private static final int MAGIC = 0x4A414447; // "JADG"
    private static final int VERSION = 1;

    private final Path indexDir;

    public UsageGraphStore(Path indexDir) throws IOException {
        this.indexDir = indexDir;
        Files.createDirectories(indexDir);
    }

    public Path getGraphPath(String inputHash) {
        return indexDir.resolve(inputHash + ".graph");
    }

    public void save(int[][] adjacency, String inputHash) throws IOException {
        if (adjacency == null) return;
        Path finalPath = getGraphPath(inputHash);
        Path tmpPath = finalPath.resolveSibling(inputHash + ".graph.tmp");

        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmpPath), 1 << 20))) {
            dos.writeInt(MAGIC);
            dos.writeInt(VERSION);
            dos.writeInt(adjacency.length);
            for (int[] row : adjacency) {
                if (row == null) {
                    dos.writeInt(0);
                    continue;
                }
                dos.writeInt(row.length);
                for (int v : row) dos.writeInt(v);
            }
        }
        Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        logger.info("[UsageGraphStore] Saved usage graph ({} classes) to {}", adjacency.length, finalPath.getFileName());
    }

    /**
     * @return the adjacency array, or {@code null} if missing / wrong header / corrupt /
     *         class-count mismatch with {@code expectedClasses}.
     */
    public int[][] tryLoad(String inputHash, int expectedClasses) throws IOException {
        Path path = getGraphPath(inputHash);
        if (!Files.exists(path)) {
            logger.debug("[UsageGraphStore] No graph file for hash {}", inputHash.substring(0, 8));
            return null;
        }
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path), 1 << 20))) {
            int magic = dis.readInt();
            if (magic != MAGIC) {
                logger.warn("[UsageGraphStore] Bad magic in {}; will rebuild", path.getFileName());
                Files.deleteIfExists(path);
                return null;
            }
            int version = dis.readInt();
            if (version != VERSION) {
                logger.info("[UsageGraphStore] Version mismatch ({} vs {}); will rebuild", version, VERSION);
                Files.deleteIfExists(path);
                return null;
            }
            int n = dis.readInt();
            if (n != expectedClasses) {
                logger.info("[UsageGraphStore] Class-count mismatch ({} vs {}); will rebuild", n, expectedClasses);
                Files.deleteIfExists(path);
                return null;
            }
            int[][] adj = new int[n][];
            for (int i = 0; i < n; i++) {
                int len = dis.readInt();
                if (len < 0 || len > n) throw new IOException("bad row length " + len);
                int[] row = new int[len];
                for (int j = 0; j < len; j++) row[j] = dis.readInt();
                adj[i] = row;
            }
            logger.info("[UsageGraphStore] Loaded usage graph ({} classes) from {} (fast-path)", n, path.getFileName());
            return adj;
        } catch (Exception e) {
            logger.warn("[UsageGraphStore] Corrupt graph {}; deleting: {}", path.getFileName(), e.getMessage());
            Files.deleteIfExists(path);
            return null;
        }
    }
}
