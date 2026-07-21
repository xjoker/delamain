package com.zin.delamain.index.shard;

import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Top-level registry of the mmap-backed shard layer for one loaded APK. Holds the open shards plus
 * a small in-heap tombstone set; the trigram payload lives on disk. All methods are static because
 * there is exactly one loaded APK at a time (mirrors {@code CodeContentIndex}'s static model).
 *
 * <h2>Query soundness</h2>
 * {@link #candidatesForTerm} returns a {@link TermLookupResult} whose {@code covered} set is the
 * union of the shards' authoritative ids (minus tombstones), and whose {@code candidates} set is
 * the trigram-filtered subset that may contain the term. A term trigram absent from a shard's
 * dictionary is a definitive negative <em>for that shard only</em>, so a globally-rare trigram
 * never wipes out coverage elsewhere. Whitespace-only term trigrams are ignored for filtering
 * exactly as indexing skipped them — treating their absence as a negative would be a false
 * negative.
 */
public final class ContentShardIndex {

    private static final Logger logger = LoggerFactory.getLogger(ContentShardIndex.class);

    private static volatile List<ContentShard> shards = new ArrayList<>();
    private static volatile boolean built = false;
    // Ids whose shard judgment is no longer trustworthy (post-rename invalidation). Guarded by
    // the class monitor for writes; reads take a volatile snapshot.
    private static volatile RoaringBitmap tombstones = new RoaringBitmap();

    private ContentShardIndex() {}

    /** Opens the catalog and mmaps every listed shard. No-op state if no catalog exists. */
    public static synchronized void loadCatalog(Path indexDir, String inputHash) throws IOException {
        List<ShardCatalog.ShardEntry> entries = ShardCatalog.read(indexDir, inputHash);
        if (entries == null) {
            shards = new ArrayList<>();
            tombstones = new RoaringBitmap();
            built = false;
            return;
        }
        List<ContentShard> loaded = new ArrayList<>(entries.size());
        for (ShardCatalog.ShardEntry e : entries) {
            Path file = indexDir.resolve(e.fileName);
            if (!Files.exists(file)) {
                logger.warn("[ContentShardIndex] Missing shard file {}; skipping (its ids fall back to scan)",
                        e.fileName);
                continue;
            }
            long actual = crc32(file);
            if (actual != e.checksum) {
                logger.warn("[ContentShardIndex] Checksum mismatch on {} (expected {} got {}); skipping",
                        e.fileName, e.checksum, actual);
                continue;
            }
            try {
                loaded.add(ContentShard.open(file));
            } catch (IOException ex) {
                // Structurally invalid shard (bad magic/version/out-of-bounds header) whose
                // checksum still matches its own bytes — e.g. a stale file left by an
                // incompatible build. Skip it like any other corrupt shard rather than failing
                // the whole catalog load.
                logger.warn("[ContentShardIndex] Failed to open shard {}; skipping: {}",
                        e.fileName, ex.getMessage());
            }
        }
        shards = loaded;
        tombstones = new RoaringBitmap();
        built = !loaded.isEmpty();
        logger.info("[ContentShardIndex] Loaded {} shard(s) for hash {}", loaded.size(),
                inputHash.length() >= 8 ? inputHash.substring(0, 8) : inputHash);
    }

    /** Releases shard handles and tombstones (APK switch). */
    public static synchronized void clear() {
        shards = new ArrayList<>();
        tombstones = new RoaringBitmap();
        built = false;
    }

    public static boolean isBuilt() {
        return built;
    }

    /** Marks {@code classId} as no longer trustworthy (post-rename), dropping it from coverage. */
    public static synchronized void tombstone(int classId) {
        RoaringBitmap next = tombstones.clone();
        next.add(classId);
        tombstones = next;
    }

    /** True iff some shard covers {@code classId} and it has not been tombstoned. */
    public static boolean isCovered(int classId) {
        if (tombstones.contains(classId)) {
            return false;
        }
        for (ContentShard s : shards) {
            if (classId >= s.idLo() && classId <= s.idHi() && s.coveredIds().contains(classId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True iff some shard deliberately excluded {@code classId} (recorded via
     * {@code ContentShardBuilder#markExcluded}, e.g. an empty-source inner class whose logical
     * content is inlined into its covered top-level class — see {@code WarmupManager}) and it has
     * not been tombstoned. Excluded ids are disjoint from {@link #isCovered}: a shard never marks
     * the same id both covered and excluded, so callers may safely branch on this after checking
     * coverage.
     */
    public static boolean isExcluded(int classId) {
        if (tombstones.contains(classId)) {
            return false;
        }
        for (ContentShard s : shards) {
            if (classId >= s.idLo() && classId <= s.idHi() && s.excludedIds().contains(classId)) {
                return true;
            }
        }
        return false;
    }

    public static TermLookupResult candidatesForTerm(String term) {
        RoaringBitmap candidates = new RoaringBitmap();
        RoaringBitmap covered = new RoaringBitmap();
        if (term == null || term.length() < 3) {
            return new TermLookupResult(candidates, covered);
        }
        List<ContentShard> snapshot = shards;
        RoaringBitmap tomb = tombstones;
        int len = term.length();

        for (ContentShard s : snapshot) {
            covered.or(s.coveredIds());

            // Intersect the postings of every usable (non-whitespace) term trigram within this shard.
            RoaringBitmap shardCandidates = null;
            int usableTrigrams = 0;
            boolean definitiveNegative = false;
            for (int i = 0; i <= len - 3; i++) {
                char c0 = term.charAt(i);
                char c1 = term.charAt(i + 1);
                char c2 = term.charAt(i + 2);
                if (c0 <= ' ' && c1 <= ' ' && c2 <= ' ') {
                    // Whitespace-only trigram: indexing skipped it, so it carries no filtering
                    // power and its absence must NOT be read as a negative.
                    continue;
                }
                usableTrigrams++;
                ImmutableRoaringBitmap posting = s.postingsFor(c0, c1, c2);
                if (posting == null) {
                    // No covered class in this shard contains this trigram -> none contains the
                    // term -> every covered id here is a definitive negative for this shard.
                    definitiveNegative = true;
                    break;
                }
                RoaringBitmap p = toRoaring(posting);
                if (shardCandidates == null) {
                    shardCandidates = p;
                } else {
                    shardCandidates.and(p);
                }
                if (shardCandidates.isEmpty()) {
                    break;
                }
            }

            if (definitiveNegative) {
                continue; // contributes no candidates
            }
            if (usableTrigrams == 0) {
                // Term is all whitespace-skippable trigrams: no narrowing possible, so every
                // covered class in this shard is a candidate (sound: no false negatives).
                candidates.or(s.coveredIds());
            } else if (shardCandidates != null) {
                candidates.or(shardCandidates);
            }
        }

        if (!tomb.isEmpty()) {
            covered.andNot(tomb);
            candidates.andNot(tomb);
        }
        return new TermLookupResult(candidates, covered);
    }

    public static Map<String, Object> getStats() {
        List<ContentShard> snapshot = shards;
        RoaringBitmap tomb = tombstones;
        RoaringBitmap allCovered = new RoaringBitmap();
        RoaringBitmap allExcluded = new RoaringBitmap();
        long trigramTotal = 0;
        for (ContentShard s : snapshot) {
            allCovered.or(s.coveredIds());
            allExcluded.or(s.excludedIds());
            trigramTotal += s.trigramCount();
        }
        if (!tomb.isEmpty()) {
            allCovered.andNot(tomb);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("built", built);
        stats.put("shard_count", snapshot.size());
        stats.put("covered_classes", allCovered.getCardinality());
        stats.put("excluded_classes", allExcluded.getCardinality());
        stats.put("tombstoned", tomb.getCardinality());
        stats.put("trigram_total", trigramTotal);
        return stats;
    }

    private static RoaringBitmap toRoaring(ImmutableRoaringBitmap src) {
        RoaringBitmap out = new RoaringBitmap();
        src.forEach((org.roaringbitmap.IntConsumer) out::add);
        return out;
    }

    private static long crc32(Path file) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
            }
        }
        return crc.getValue();
    }
}
