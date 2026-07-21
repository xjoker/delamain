package com.zin.delamain.index.shard;

import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Accumulates trigram postings for one id-range window, flushes to a shard file when the heap
 * budget is reached, then resets for the next window. Not thread-safe: callers must serialize
 * appends per builder instance (parallel warmup uses one builder per disjoint id segment).
 *
 * <h2>Soundness</h2>
 * Ids handed to {@link #addClass} become <em>covered</em> (authoritative — the shard can give a
 * trustworthy definitive-negative for them). Ids handed to {@link #markExcluded}, or added with
 * source shorter than a trigram, are recorded as <em>excluded</em> and are deliberately NOT
 * covered — the shard makes no claim about them. This is the "under-mark covered, never over-mark" rule that
 * keeps the query side from producing false negatives.
 */
public final class ContentShardBuilder implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ContentShardBuilder.class);

    private final Path indexDir;
    private final String inputHash;
    private final long budgetBytes;

    /** trigram key (three chars packed big-first into a long) -> class ids containing it. */
    private TreeMap<Long, RoaringBitmap> postings = new TreeMap<>();
    /** ids fully indexed in the current window (authoritative), including trigram-empty sources. */
    private RoaringBitmap coveredIds = new RoaringBitmap();
    /** ids deliberately skipped in the current window (oversized / too short). */
    private RoaringBitmap excludedIds = new RoaringBitmap();

    private int windowLo = -1;
    private int windowHi = -1;
    /** last id accepted by addClass/markExcluded — ids must be strictly increasing across both. */
    private int lastId = Integer.MIN_VALUE;

    /** Distinct (trigram, classId) posting pairs in the current window; drives the O(1) budget estimate. */
    private long postingElements = 0;

    private int nextSeq;

    /**
     * Every shard entry this builder has written, in flush order. Auto-flushes triggered inside
     * {@link #addClass} (and the trailing flush in {@link #close}) discard their return value at
     * the call site, so this accumulator is the caller's only way to recover the full catalog —
     * without it, budget-triggered shards would have files on disk but no catalog entry.
     */
    private final List<ShardCatalog.ShardEntry> written = new ArrayList<>();

    public ContentShardBuilder(Path indexDir, String inputHash, long budgetBytes) throws IOException {
        this(indexDir, inputHash, budgetBytes, 0);
    }

    /**
     * @param seqBase the first shard sequence number this builder emits (parallel segments pass
     *                disjoint bases so shard file names never collide).
     */
    public ContentShardBuilder(Path indexDir, String inputHash, long budgetBytes, int seqBase) throws IOException {
        this.indexDir = indexDir;
        this.inputHash = inputHash;
        this.budgetBytes = budgetBytes;
        this.nextSeq = seqBase;
        Files.createDirectories(indexDir);
    }

    /** Packs three chars big-first so natural long ordering equals UTF-16BE byte ordering. */
    static long packTrigram(char c0, char c1, char c2) {
        return ((long) c0 << 32) | ((long) c1 << 16) | (long) c2;
    }

    private void advanceId(int classId) {
        if (classId <= lastId) {
            throw new IllegalArgumentException(
                    "class ids must be strictly increasing; got " + classId + " after " + lastId);
        }
        lastId = classId;
        if (windowLo == -1) windowLo = classId;
        windowHi = classId;
    }

    /**
     * Adds one class's trigrams using the exact same sliding window and whitespace rule as
     * {@code CodeContentIndex.index()}. {@code lowerCaseCode} must already be lower-cased. A source
     * shorter than 3 chars cannot be indexed and is routed to {@link #markExcluded}.
     *
     * @return true if this call pushed the window over budget and triggered a flush.
     */
    public boolean addClass(int classId, String lowerCaseCode) throws IOException {
        if (lowerCaseCode == null || lowerCaseCode.length() < 3) {
            markExcluded(classId);
            return false;
        }
        advanceId(classId);
        // Authoritative regardless of whether any trigram survives the whitespace filter: a
        // class whose every window is whitespace is still a definitive negative for real terms.
        coveredIds.add(classId);

        int len = lowerCaseCode.length();
        for (int i = 0; i <= len - 3; i++) {
            char c0 = lowerCaseCode.charAt(i);
            char c1 = lowerCaseCode.charAt(i + 1);
            char c2 = lowerCaseCode.charAt(i + 2);
            if (c0 <= ' ' && c1 <= ' ' && c2 <= ' ') {
                continue;
            }
            if (postings.computeIfAbsent(packTrigram(c0, c1, c2), k -> new RoaringBitmap()).checkedAdd(classId)) {
                postingElements++;
            }
        }

        if (estimatedBytes() >= budgetBytes) {
            flush();
            return true;
        }
        return false;
    }

    /** Records {@code classId} as skipped in the current window without indexing it. */
    public void markExcluded(int classId) {
        advanceId(classId);
        excludedIds.add(classId);
    }

    /**
     * O(1) estimate of the in-progress window's footprint. Iterating every posting's
     * getSizeInBytes() (the obvious implementation) is O(distinct trigrams) and, called once per
     * class, made the whole build O(classes × trigrams) — a 346k-class APK never finished one
     * window. Instead we track distinct posting elements incrementally and bound each element at
     * 2 bytes (RoaringBitmap array-container worst case; dense runs compress smaller), plus per-
     * trigram map overhead. This over-estimates, so windows flush at or below budget — never above,
     * keeping shards well clear of the 2GB file cap.
     */
    public long estimatedBytes() {
        return coveredIds.getSizeInBytes()
                + excludedIds.getSizeInBytes()
                + (long) postings.size() * 16L
                + postingElements * 2L;
    }

    /**
     * Force-writes the current window to a new shard file even if under budget. Resets the window
     * for the next one; the id cursor keeps advancing so subsequent shards stay ordered.
     *
     * @return the written shard descriptor, or {@code null} if the window was empty.
     */
    public ShardCatalog.ShardEntry flush() throws IOException {
        if (windowLo == -1) {
            return null;
        }
        int seq = nextSeq++;

        byte[] coveredBytes = coveredIds.isEmpty() ? new byte[0] : serialize(coveredIds);
        byte[] excludedBytes = excludedIds.isEmpty() ? new byte[0] : serialize(excludedIds);

        int trigramCount = postings.size();
        int dictLen = trigramCount * ContentShard.DICT_ENTRY_BYTES;

        // Serialize every posting to build the blob, remembering each entry's blob offset/length.
        long[] postingOffsets = new long[trigramCount];
        int[] postingLengths = new int[trigramCount];
        byte[][] postingBytes = new byte[trigramCount][];
        long blobPos = 0;
        int idx = 0;
        for (Map.Entry<Long, RoaringBitmap> e : postings.entrySet()) {
            RoaringBitmap rb = e.getValue();
            rb.runOptimize();
            byte[] bytes = serialize(rb);
            postingBytes[idx] = bytes;
            postingOffsets[idx] = blobPos;
            postingLengths[idx] = bytes.length;
            blobPos += bytes.length;
            idx++;
        }
        long blobLen = blobPos;

        long coveredOffset = ContentShard.HEADER_LEN;
        long excludedOffset = coveredOffset + coveredBytes.length;
        long dictOffset = excludedOffset + excludedBytes.length;
        long blobOffset = dictOffset + dictLen;
        long totalLong = blobOffset + blobLen;
        if (totalLong > Integer.MAX_VALUE) {
            throw new IOException("shard exceeds 2GB (" + totalLong + " bytes); lower the budget");
        }
        int total = (int) totalLong;

        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(ContentShard.MAGIC);
        buf.putInt(ContentShard.VERSION);
        buf.putInt(seq);
        buf.putInt(windowLo);
        buf.putInt(windowHi);
        buf.putInt(trigramCount);
        buf.putLong(coveredOffset);
        buf.putInt(coveredBytes.length);
        buf.putLong(excludedOffset);
        buf.putInt(excludedBytes.length);
        buf.putLong(dictOffset);
        buf.putLong(dictLen);
        buf.putLong(blobOffset);
        buf.putLong(blobLen);
        // header is exactly HEADER_LEN bytes
        buf.put(coveredBytes);
        buf.put(excludedBytes);
        // dictionary — TreeMap iteration is ascending trigram-key order (== UTF-16BE byte order)
        idx = 0;
        for (Long key : postings.keySet()) {
            long k = key;
            buf.putChar((char) ((k >>> 32) & 0xFFFF));
            buf.putChar((char) ((k >>> 16) & 0xFFFF));
            buf.putChar((char) (k & 0xFFFF));
            buf.putLong(postingOffsets[idx]);
            buf.putInt(postingLengths[idx]);
            idx++;
        }
        for (byte[] pb : postingBytes) {
            buf.put(pb);
        }

        byte[] fileBytes = buf.array();
        CRC32 crc = new CRC32();
        crc.update(fileBytes);
        long checksum = crc.getValue();

        String fileName = inputHash + ".shard." + seq;
        Path finalPath = indexDir.resolve(fileName);
        Path tmpPath = indexDir.resolve(fileName + ".tmp");
        Files.write(tmpPath, fileBytes);
        Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        ShardCatalog.ShardEntry meta =
                new ShardCatalog.ShardEntry(seq, windowLo, windowHi, trigramCount, checksum, fileName);
        written.add(meta);
        logger.info("[ContentShardBuilder] Flushed shard {} ids [{},{}] trigrams={} bytes={}",
                seq, windowLo, windowHi, trigramCount, total);

        // Reset window; the id cursor (lastId) persists so ranges stay ordered.
        postings = new TreeMap<>();
        coveredIds = new RoaringBitmap();
        excludedIds = new RoaringBitmap();
        windowLo = -1;
        windowHi = -1;
        postingElements = 0;
        return meta;
    }

    /**
     * All shard entries written so far, in flush (== id) order — including budget-triggered
     * auto-flushes and the trailing {@link #close} flush. The caller feeds this straight into
     * {@link ShardCatalog#write}. The returned list is the live accumulator; treat it as read-only.
     */
    public List<ShardCatalog.ShardEntry> writtenShards() {
        return written;
    }

    private static byte[] serialize(RoaringBitmap rb) {
        ByteBuffer bb = ByteBuffer.allocate(rb.serializedSizeInBytes());
        rb.serialize(bb);
        return bb.array();
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}
