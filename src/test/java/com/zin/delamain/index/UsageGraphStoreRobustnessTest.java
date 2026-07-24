package com.zin.delamain.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Corruption coverage for {@link UsageGraphStore}'s CRC-guarded v2 format, mirroring
 * {@code ContentShardRobustnessTest}'s style: silent corruption must never surface a wrong
 * adjacency array, and pre-existing v1 files (no CRC trailer) must still load (grandfather).
 */
class UsageGraphStoreRobustnessTest {

    private static final String HASH = "aabbccddeeff0011";

    private static int[][] sampleAdjacency() {
        return new int[][] {
                {1, 2},
                {0},
                {0, 1}
        };
    }

    /** Flips one byte at {@code offset} (bitwise NOT). */
    private static void flipByte(Path file, int offset) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        bytes[offset] = (byte) ~bytes[offset];
        Files.write(file, bytes);
    }

    @Test
    void v2RoundTripsAdjacencyExactly(@TempDir Path dir) throws IOException {
        UsageGraphStore store = new UsageGraphStore(dir);
        int[][] adj = sampleAdjacency();
        store.save(adj, HASH);

        int[][] loaded = store.tryLoad(HASH, adj.length);
        assertNotNull(loaded, "freshly saved v2 graph must load back");
        for (int i = 0; i < adj.length; i++) {
            assertArrayEquals(adj[i], loaded[i], "row " + i + " mismatch");
        }
    }

    @Test
    void corruptedBodyIsRejectedByCrc(@TempDir Path dir) throws IOException {
        UsageGraphStore store = new UsageGraphStore(dir);
        int[][] adj = sampleAdjacency();
        store.save(adj, HASH);

        Path path = store.getGraphPath(HASH);
        // Offset 18 sits inside row0's first adjacency value (header is magic[0-3]+version[4-7]+N[8-11],
        // row0.length is [12-15], row0[0] is [16-19]) -- deliberately NOT touching N or any row-length
        // field, so a pre-CRC reader's existing sanity checks (class-count / row-length bounds) would
        // stay silent and hand back a corrupted adjacency array.
        flipByte(path, 18);

        int[][] loaded = store.tryLoad(HASH, adj.length);
        assertNull(loaded, "CRC mismatch must be treated as corruption, not surfaced as data");
    }

    @Test
    void legacyV1FileWithoutCrcTrailerStillLoads(@TempDir Path dir) throws IOException {
        UsageGraphStore store = new UsageGraphStore(dir);
        int[][] adj = sampleAdjacency();
        writeLegacyV1(store.getGraphPath(HASH), adj);

        int[][] loaded = store.tryLoad(HASH, adj.length);
        assertNotNull(loaded, "pre-existing v1 graphs (no CRC trailer) must be grandfathered in");
        for (int i = 0; i < adj.length; i++) {
            assertArrayEquals(adj[i], loaded[i], "row " + i + " mismatch");
        }
    }

    @Test
    void truncatedFileReturnsNullNotException(@TempDir Path dir) throws IOException {
        UsageGraphStore store = new UsageGraphStore(dir);
        int[][] adj = sampleAdjacency();
        store.save(adj, HASH);

        Path path = store.getGraphPath(HASH);
        byte[] bytes = Files.readAllBytes(path);
        Files.write(path, java.util.Arrays.copyOf(bytes, bytes.length / 2));

        int[][] loaded = store.tryLoad(HASH, adj.length);
        assertNull(loaded, "truncated file must not throw and must be treated as corrupt");
    }

    @Test
    void badMagicReturnsNullNotException(@TempDir Path dir) throws IOException {
        UsageGraphStore store = new UsageGraphStore(dir);
        int[][] adj = sampleAdjacency();
        store.save(adj, HASH);

        Path path = store.getGraphPath(HASH);
        byte[] bytes = Files.readAllBytes(path);
        bytes[0] = (byte) 0xFF;
        Files.write(path, bytes);

        int[][] loaded = store.tryLoad(HASH, adj.length);
        assertNull(loaded, "bad magic must not throw");
    }

    /** Hand-writes the pre-CRC v1 format: MAGIC + VERSION(1) + N + rows, no trailer. */
    private static void writeLegacyV1(Path path, int[][] adjacency) throws IOException {
        Files.createDirectories(path.getParent());
        try (DataOutputStream dos = new DataOutputStream(
                Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            dos.writeInt(0x4A414447); // "JADG"
            dos.writeInt(1); // legacy VERSION
            dos.writeInt(adjacency.length);
            for (int[] row : adjacency) {
                dos.writeInt(row.length);
                for (int v : row) dos.writeInt(v);
            }
        }
    }
}
