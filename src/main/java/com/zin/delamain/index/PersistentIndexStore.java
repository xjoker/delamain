package com.zin.delamain.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Persists the trigram → BitSet mapping from {@link CodeContentIndex} to disk so that
 * a cold restart can skip the Phase-2 index-build step (160 s → &lt;5 s for large APKs).
 *
 * <h2>Index file format</h2>
 * <pre>
 *   MAGIC    (4 bytes) = 0x4A44584A  "JADJ"
 *   VERSION  (4 bytes) = 1
 *   count    (4 bytes) = number of trigram entries
 *   foreach:
 *     trigram_utf8_len  (2 bytes) — always 3 for ASCII trigrams
 *     trigram_bytes     (N bytes)
 *     word_count        (4 bytes) — number of longs in BitSet.toLongArray()
 *     words             (word_count × 8 bytes)
 * </pre>
 *
 * <h2>File naming</h2>
 * Each index file is named {@code {sha256_hash}.idx} inside {@code indexDir}.
 * The hash is derived from the input file sizes, paths, and the first 64 KB of content,
 * which is sufficient to detect file replacements without reading the entire APK.
 *
 * <h2>Thread safety</h2>
 * This class is stateless (except for {@code indexDir} which is immutable); concurrent
 * callers may use separate instances pointing to the same directory safely because the
 * write is done to a temp file and then atomically moved.
 */
public class PersistentIndexStore {

    private static final Logger logger = LoggerFactory.getLogger(PersistentIndexStore.class);

    /** "JADJ" — distinguishes our index from other binary files. */
    private static final int MAGIC = 0x4A41444A;
    private static final int VERSION = 1;

    private final Path indexDir;

    public PersistentIndexStore(Path indexDir) throws IOException {
        this.indexDir = indexDir;
        Files.createDirectories(indexDir);
    }

    // -------------------------------------------------------------------------
    // Input-hash computation
    // -------------------------------------------------------------------------

    /**
     * Computes a stable hash that identifies a specific set of input files.
     *
     * <p>For efficiency, only the first 64 KB of each file's content is hashed,
     * combined with the file's absolute path and length.  This is sufficient to
     * detect file replacements (different APK loaded at the same path) while
     * avoiding a full multi-hundred-MB read on every start.</p>
     *
     * @param inputFiles list of APK/JAR/DEX files; must not be null or empty
     * @return lowercase hex SHA-256 string
     */
    public String computeInputHash(List<File> inputFiles) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        List<File> sorted = new ArrayList<>(inputFiles);
        Collections.sort(sorted, Comparator.comparing(File::getAbsolutePath));

        for (File f : sorted) {
            // Mix in path and size — cheap and catches most renames/replacements
            md.update(f.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            md.update(Long.toString(f.length()).getBytes(StandardCharsets.UTF_8));
            // Hash first 64 KB of content to detect content changes
            try (InputStream is = new BufferedInputStream(new FileInputStream(f))) {
                byte[] buf = new byte[65536];
                int n = is.read(buf);
                if (n > 0) {
                    md.update(buf, 0, n);
                }
            }
        }

        // Mix in the deobfuscation config: index keys / BitSet sort-basis / CodeStore source
        // text all differ between deobf states, so a deobf change MUST invalidate prior
        // artifacts. Namespacing the hash (and thus all index filenames + CodeStore dir) by
        // deobf state turns a would-be silent dirty-read into a clean cache-miss + rebuild.
        md.update(com.zin.delamain.core.HeadlessJadxWrapper.deobfConfigTag()
                .getBytes(StandardCharsets.UTF_8));

        byte[] hash = md.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    /** Returns the index file path for a given input hash. */
    public Path getIndexPath(String inputHash) {
        return indexDir.resolve(inputHash + ".idx");
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    /**
     * Saves the index map to disk.
     *
     * <p>The write goes to a temp file first, then is atomically moved to the final
     * path, so a crash mid-write leaves no partial/corrupt file behind.</p>
     *
     * @param index     trigram (3-char String) → BitSet mapping from CodeContentIndex
     * @param inputHash hash previously returned by {@link #computeInputHash}
     */
    public void save(Map<String, BitSet> index, String inputHash) throws IOException {
        Path finalPath = getIndexPath(inputHash);
        Path tmpPath = finalPath.resolveSibling(inputHash + ".idx.tmp");

        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmpPath), 1 << 20))) {
            dos.writeInt(MAGIC);
            dos.writeInt(VERSION);
            dos.writeInt(index.size());

            for (Map.Entry<String, BitSet> entry : index.entrySet()) {
                byte[] trigramBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                dos.writeShort(trigramBytes.length);
                dos.write(trigramBytes);

                long[] words = entry.getValue().toLongArray();
                dos.writeInt(words.length);
                for (long w : words) {
                    dos.writeLong(w);
                }
            }
        }

        // Atomic rename: on most OS/FS this is crash-safe
        Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        logger.info("[PersistentIndexStore] Saved {} trigrams to {}", index.size(), finalPath.getFileName());
    }

    /**
     * Convenience overload: saves the index from a {@link CodeContentIndex} instance.
     *
     * <p>Wave 2A will replace the stub {@code CodeContentIndex} with the full
     * implementation that returns a proper map; until then {@link CodeContentIndex#getIndexMap()}
     * returns an empty map and this is effectively a no-op.</p>
     */
    public void save(CodeContentIndex codeContentIndex, String inputHash) throws IOException {
        save(codeContentIndex.getIndexMap(), inputHash);
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    /**
     * Attempts to load the persisted index for the given input hash.
     *
     * @return the trigram → BitSet map, or {@code null} if the index file does not
     *         exist, has an unrecognised header/version, or is corrupt
     */
    public Map<String, BitSet> tryLoad(String inputHash) throws IOException {
        Path path = getIndexPath(inputHash);
        if (!Files.exists(path)) {
            logger.debug("[PersistentIndexStore] No index file found for hash {}", inputHash.substring(0, 8));
            return null;
        }

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path), 1 << 20))) {

            int magic = dis.readInt();
            if (magic != MAGIC) {
                logger.warn("[PersistentIndexStore] Bad magic 0x{} in {}; will rebuild", Integer.toHexString(magic), path.getFileName());
                Files.deleteIfExists(path);
                return null;
            }

            int version = dis.readInt();
            if (version != VERSION) {
                logger.info("[PersistentIndexStore] Version mismatch ({} vs {}); will rebuild", version, VERSION);
                Files.deleteIfExists(path);
                return null;
            }

            int count = dis.readInt();
            Map<String, BitSet> index = new HashMap<>(count * 2);

            for (int i = 0; i < count; i++) {
                int trigramLen = dis.readShort() & 0xFFFF;
                byte[] trigramBytes = new byte[trigramLen];
                dis.readFully(trigramBytes);
                String trigram = new String(trigramBytes, StandardCharsets.UTF_8);

                int wordCount = dis.readInt();
                long[] words = new long[wordCount];
                for (int j = 0; j < wordCount; j++) {
                    words[j] = dis.readLong();
                }
                index.put(trigram, BitSet.valueOf(words));
            }

            logger.info("[PersistentIndexStore] Loaded {} trigrams from {} (fast-path)", count, path.getFileName());
            return index;

        } catch (Exception e) {
            logger.warn("[PersistentIndexStore] Corrupt index file {}; deleting and will rebuild: {}", path.getFileName(), e.getMessage());
            Files.deleteIfExists(path);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Invalidation / cleanup
    // -------------------------------------------------------------------------

    /** Deletes the index file for the given hash (forces a full rebuild on next load). */
    public void invalidate(String inputHash) throws IOException {
        Path path = getIndexPath(inputHash);
        boolean deleted = Files.deleteIfExists(path);
        if (deleted) {
            logger.info("[PersistentIndexStore] Invalidated index for hash {}", inputHash.substring(0, 8));
        }
    }

    /**
     * Removes index files that are not in {@code activeHashes}.
     * Call this on startup to clean up stale entries from previous file loads.
     *
     * @param activeHashes hashes that should be retained; others are deleted
     */
    public void pruneStale(Set<String> activeHashes) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(indexDir, "*.idx")) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                String hash = name.substring(0, name.length() - 4); // strip ".idx"
                if (!activeHashes.contains(hash)) {
                    Files.deleteIfExists(entry);
                    logger.debug("[PersistentIndexStore] Pruned stale index {}", name);
                }
            }
        }
    }
}
