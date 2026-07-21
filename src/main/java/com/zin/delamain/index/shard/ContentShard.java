package com.zin.delamain.index.shard;

import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Immutable, mmap-backed read view of one shard file (see {@link ContentShardBuilder} for the
 * binary layout). The covered/excluded id sets are small and deserialized into heap on open; the
 * (potentially large) trigram postings stay in the mapped file and are constructed zero-copy per
 * lookup via {@link ImmutableRoaringBitmap}.
 *
 * <p>Per the design, no explicit {@code unmap} is performed — the mapping lives as long as this
 * object is referenced and is released by GC when the owning {@link ContentShardIndex} drops it
 * (APK switch).
 */
public final class ContentShard implements Closeable {

    static final int MAGIC = 0x4A415253;   // "JARS" (JADx Roaring Shard)
    static final int VERSION = 1;
    static final int HEADER_LEN = 80;
    static final int DICT_ENTRY_BYTES = 18; // 3 chars (6B) + postingOffset (8B) + postingLength (4B)

    private final int shardSeq;
    private final int idLo;
    private final int idHi;
    private final int trigramCount;
    private final RoaringBitmap coveredIds;
    private final RoaringBitmap excludedIds;
    private final ByteBuffer dictBuf; // slice over the dictionary region, BIG_ENDIAN, position 0
    private final ByteBuffer blobBuf; // slice over the blob region, BIG_ENDIAN, position 0

    @SuppressWarnings("unused") // retained to keep the backing mapping reachable
    private final MappedByteBuffer mapping;

    private ContentShard(int shardSeq, int idLo, int idHi, int trigramCount,
                         RoaringBitmap coveredIds, RoaringBitmap excludedIds,
                         ByteBuffer dictBuf, ByteBuffer blobBuf, MappedByteBuffer mapping) {
        this.shardSeq = shardSeq;
        this.idLo = idLo;
        this.idHi = idHi;
        this.trigramCount = trigramCount;
        this.coveredIds = coveredIds;
        this.excludedIds = excludedIds;
        this.dictBuf = dictBuf;
        this.blobBuf = blobBuf;
        this.mapping = mapping;
    }

    public static ContentShard open(Path file) throws IOException {
        MappedByteBuffer full;
        long size;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            size = ch.size();
            if (size < HEADER_LEN) {
                throw new IOException("shard file too small: " + file + " (" + size + " bytes)");
            }
            // The mapping stays valid after the channel is closed; close it here to not leak fds.
            full = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
        }
        full.order(ByteOrder.BIG_ENDIAN);

        int magic = full.getInt(0);
        if (magic != MAGIC) {
            throw new IOException("bad shard magic 0x" + Integer.toHexString(magic) + " in " + file);
        }
        int version = full.getInt(4);
        if (version != VERSION) {
            throw new IOException("unsupported shard version " + version + " in " + file);
        }
        int shardSeq = full.getInt(8);
        int idLo = full.getInt(12);
        int idHi = full.getInt(16);
        int trigramCount = full.getInt(20);
        long coveredOffset = full.getLong(24);
        int coveredLen = full.getInt(32);
        long excludedOffset = full.getLong(36);
        int excludedLen = full.getInt(44);
        long dictOffset = full.getLong(48);
        long dictLen = full.getLong(56);
        long blobOffset = full.getLong(64);
        long blobLen = full.getLong(72);

        // Bounds validation — a corrupt header must fail loudly, never silently mis-slice.
        checkRegion(coveredOffset, coveredLen, size, "covered");
        checkRegion(excludedOffset, excludedLen, size, "excluded");
        checkRegion(dictOffset, dictLen, size, "dict");
        checkRegion(blobOffset, blobLen, size, "blob");
        if (dictLen != (long) trigramCount * DICT_ENTRY_BYTES) {
            throw new IOException("dict length " + dictLen + " inconsistent with trigramCount "
                    + trigramCount + " in " + file);
        }

        RoaringBitmap covered = deserialize(full, coveredOffset, coveredLen);
        RoaringBitmap excluded = deserialize(full, excludedOffset, excludedLen);
        ByteBuffer dictBuf = region(full, dictOffset, dictLen);
        ByteBuffer blobBuf = region(full, blobOffset, blobLen);

        return new ContentShard(shardSeq, idLo, idHi, trigramCount, covered, excluded, dictBuf, blobBuf, full);
    }

    private static void checkRegion(long off, long len, long size, String name) throws IOException {
        if (off < 0 || len < 0 || off + len > size) {
            throw new IOException("shard " + name + " region out of bounds: off=" + off
                    + " len=" + len + " size=" + size);
        }
    }

    /** Slices {@code [off, off+len)} of the mapping into an independent BIG_ENDIAN view at position 0. */
    private static ByteBuffer region(ByteBuffer full, long off, long len) {
        ByteBuffer d = full.duplicate();
        d.position((int) off);
        d.limit((int) (off + len));
        ByteBuffer s = d.slice();
        s.order(ByteOrder.BIG_ENDIAN);
        return s;
    }

    private static RoaringBitmap deserialize(ByteBuffer full, long off, int len) throws IOException {
        RoaringBitmap rb = new RoaringBitmap();
        if (len == 0) {
            return rb;
        }
        rb.deserialize(region(full, off, len));
        return rb;
    }

    public int shardSeq() { return shardSeq; }
    public int idLo() { return idLo; }
    public int idHi() { return idHi; }
    public int trigramCount() { return trigramCount; }

    /** The authoritative (fully-indexed) class ids in this shard. Read-only — do not mutate. */
    public RoaringBitmap coveredIds() { return coveredIds; }

    /** The class ids deliberately skipped in this shard's range. Read-only — do not mutate. */
    public RoaringBitmap excludedIds() { return excludedIds; }

    /**
     * @return the ids (within this shard's covered set) containing the trigram, as a zero-copy
     *         mmap-backed bitmap; or {@code null} if the trigram is absent from the dictionary —
     *         a definitive negative for every covered id, because this shard fully indexed them.
     */
    public ImmutableRoaringBitmap postingsFor(char c0, char c1, char c2) {
        int lo = 0;
        int hi = trigramCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int base = mid * DICT_ENTRY_BYTES;
            char e0 = dictBuf.getChar(base);
            char e1 = dictBuf.getChar(base + 2);
            char e2 = dictBuf.getChar(base + 4);
            int cmp = compare(c0, c1, c2, e0, e1, e2);
            if (cmp == 0) {
                long postingOffset = dictBuf.getLong(base + 6);
                int postingLength = dictBuf.getInt(base + 14);
                return new ImmutableRoaringBitmap(region(blobBuf, postingOffset, postingLength));
            } else if (cmp < 0) {
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return null;
    }

    /** Unsigned lexicographic comparison of two char triples (chars are already 0..65535). */
    private static int compare(char a0, char a1, char a2, char b0, char b1, char b2) {
        if (a0 != b0) return Integer.compare(a0, b0);
        if (a1 != b1) return Integer.compare(a1, b1);
        return Integer.compare(a2, b2);
    }

    @Override
    public void close() {
        // No portable force-unmap; GC releases the mapping once this shard is unreferenced.
    }
}
