package com.zin.delamain.index.shard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the shard catalog ({@code {inputHash}.shardcat}) — the ordered list of shard
 * files that together make up one logical content index. Mirrors the
 * {@code PersistentIndexStore}/{@code UsageGraphStore} conventions: magic + version header and an
 * atomic temp-then-move write. Entry order is the class-id order (each shard covers a strictly
 * increasing, non-overlapping id range).
 *
 * <pre>
 *   MAGIC   (4 bytes) = 0x4A415243  "JARC" (JADx Roaring Catalog)
 *   VERSION (4 bytes) = 1
 *   count   (4 bytes) = number of shard entries
 *   foreach entry (id order):
 *     seq          (4 bytes)
 *     idLo         (4 bytes)
 *     idHi         (4 bytes)
 *     trigramCount (4 bytes)
 *     checksum     (8 bytes)  — CRC32 of the shard file
 *     nameLen      (2 bytes)
 *     nameBytes    (nameLen bytes, UTF-8)
 * </pre>
 */
public final class ShardCatalog {

    private static final Logger logger = LoggerFactory.getLogger(ShardCatalog.class);

    private static final int MAGIC = 0x4A415243; // "JARC"
    private static final int VERSION = 1;

    private ShardCatalog() {}

    /** Descriptor of one written shard file — returned by {@link ContentShardBuilder#flush()}. */
    public static final class ShardEntry {
        public final int seq;
        public final int idLo;
        public final int idHi;
        public final int trigramCount;
        public final long checksum;
        public final String fileName;

        public ShardEntry(int seq, int idLo, int idHi, int trigramCount, long checksum, String fileName) {
            this.seq = seq;
            this.idLo = idLo;
            this.idHi = idHi;
            this.trigramCount = trigramCount;
            this.checksum = checksum;
            this.fileName = fileName;
        }
    }

    public static Path catalogPath(Path indexDir, String inputHash) {
        return indexDir.resolve(inputHash + ".shardcat");
    }

    public static void write(Path indexDir, String inputHash, List<ShardEntry> entries) throws IOException {
        Files.createDirectories(indexDir);
        Path finalPath = catalogPath(indexDir, inputHash);
        Path tmpPath = finalPath.resolveSibling(inputHash + ".shardcat.tmp");
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmpPath)))) {
            dos.writeInt(MAGIC);
            dos.writeInt(VERSION);
            dos.writeInt(entries.size());
            for (ShardEntry e : entries) {
                dos.writeInt(e.seq);
                dos.writeInt(e.idLo);
                dos.writeInt(e.idHi);
                dos.writeInt(e.trigramCount);
                dos.writeLong(e.checksum);
                byte[] name = e.fileName.getBytes(StandardCharsets.UTF_8);
                if (name.length > 0xFFFF) throw new IOException("shard file name too long: " + e.fileName);
                dos.writeShort(name.length);
                dos.write(name);
            }
        }
        Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        logger.info("[ShardCatalog] Wrote catalog ({} shards) to {}", entries.size(), finalPath.getFileName());
    }

    /** @return the ordered shard entries, or {@code null} if no catalog exists / it is corrupt. */
    public static List<ShardEntry> read(Path indexDir, String inputHash) throws IOException {
        Path path = catalogPath(indexDir, inputHash);
        if (!Files.exists(path)) {
            return null;
        }
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            int magic = dis.readInt();
            if (magic != MAGIC) {
                logger.warn("[ShardCatalog] Bad magic in {}; ignoring", path.getFileName());
                return null;
            }
            int version = dis.readInt();
            if (version != VERSION) {
                logger.info("[ShardCatalog] Version mismatch ({} vs {}); ignoring", version, VERSION);
                return null;
            }
            int count = dis.readInt();
            if (count < 0 || count > 1_000_000) throw new IOException("bad shard count " + count);
            List<ShardEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int seq = dis.readInt();
                int idLo = dis.readInt();
                int idHi = dis.readInt();
                int trigramCount = dis.readInt();
                long checksum = dis.readLong();
                int nameLen = dis.readUnsignedShort();
                byte[] name = new byte[nameLen];
                dis.readFully(name);
                entries.add(new ShardEntry(seq, idLo, idHi, trigramCount, checksum,
                        new String(name, StandardCharsets.UTF_8)));
            }
            return entries;
        } catch (Exception e) {
            logger.warn("[ShardCatalog] Corrupt catalog {}; ignoring: {}", path.getFileName(), e.getMessage());
            return null;
        }
    }
}
