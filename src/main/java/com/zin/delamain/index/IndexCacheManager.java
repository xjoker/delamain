package com.zin.delamain.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Disk-cache LRU retention for per-{@code inputHash} index artifacts.
 *
 * <p><b>Why.</b> Every distinct APK analyzed leaves a full set of on-disk artifacts (decompiled
 * source in {@link CodeStore}, trigram index, usage graph, precise use-places, mmap content
 * shards, manifest) under {@code --index-dir}. Nothing previously bounded the total size across
 * multiple analyzed APKs, so the volume grows unbounded. This class enforces a size quota by
 * deleting the least-recently-used complete artifact group (all files for one {@code inputHash})
 * until usage drops back under a low-water mark, while never touching the group of the APK
 * currently loaded/loading and never touching anything outside {@code --index-dir} (the APK
 * source files themselves live in a separate sandbox root and are never referenced here).</p>
 *
 * <h2>Artifact group (one {@code inputHash}, deleted atomically as a unit)</h2>
 * <ul>
 *   <li>{@code code/{inputHash8}/} — {@link CodeStore} directory (usually the largest piece)</li>
 *   <li>{@code {inputHash}.idx} — {@link PersistentIndexStore} trigram index</li>
 *   <li>{@code {inputHash}.graph} — {@link UsageGraphStore} usage graph</li>
 *   <li>{@code {inputHash}.useplaces} — {@link UsePlacesStore} precise use-places</li>
 *   <li>{@code {inputHash}.shard.N} + {@code {inputHash}.shardcat} — mmap content shards
 *       ({@code com.zin.delamain.index.shard.ContentShardBuilder} / {@code ShardCatalog})</li>
 *   <li>{@code {inputHash}.manifest.json} — human-readable prebaked-index manifest</li>
 *   <li>{@code {inputHash}.lastused} — LRU marker maintained by this class (see {@link #touchLastUsed})</li>
 * </ul>
 *
 * <h2>LRU basis</h2>
 * The last-modified time of {@code {inputHash}.lastused} (touched by {@link #touchLastUsed} on
 * every successful load/FAST_RESTORE), not filesystem atime (frequently disabled by {@code noatime}
 * mounts). A group with no {@code .lastused} marker (legacy artifacts predating this feature) is
 * treated as the oldest possible and evicted first.
 */
public final class IndexCacheManager {

    private static final Logger logger = LoggerFactory.getLogger(IndexCacheManager.class);

    /** Env var overriding the quota; unset/blank defaults to 50 GB. {@code 0} disables eviction. */
    private static final String ENV_MAX_GB = "JADX_CACHE_MAX_GB";
    private static final long DEFAULT_MAX_GB = 50L;
    /** Eviction stops once usage drops to this fraction of the quota (hysteresis / anti-thrash). */
    private static final double LOW_WATER_RATIO = 0.8;

    private static final Pattern SHARD_FILE_PATTERN = Pattern.compile("^(.+)\\.shard\\.\\d+$");

    private IndexCacheManager() {}

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /** @return configured quota in bytes; {@code 0} means eviction is disabled. */
    public static long resolveMaxBytes() {
        String raw = System.getenv(ENV_MAX_GB);
        long gb = DEFAULT_MAX_GB;
        if (raw != null && !raw.isBlank()) {
            try {
                gb = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                logger.warn("[IndexCacheManager] Invalid {}={}, falling back to default {}GB",
                        ENV_MAX_GB, raw, DEFAULT_MAX_GB);
                gb = DEFAULT_MAX_GB;
            }
        }
        if (gb <= 0) return 0L;
        return gb * 1024L * 1024L * 1024L;
    }

    // -------------------------------------------------------------------------
    // LRU marker
    // -------------------------------------------------------------------------

    /**
     * Marks {@code inputHash} as just-used by touching (creating or refreshing the mtime of)
     * its {@code .lastused} file. Call after every successful load / FAST_RESTORE / warmup
     * completion, before {@link #enforceQuota}.
     */
    public static void touchLastUsed(Path indexDir, String inputHash) {
        if (indexDir == null || inputHash == null || inputHash.isEmpty()) return;
        try {
            Files.createDirectories(indexDir);
            Path marker = indexDir.resolve(inputHash + ".lastused");
            Files.write(marker, new byte[0]);
            Files.setLastModifiedTime(marker, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
        } catch (IOException e) {
            logger.warn("[IndexCacheManager] Failed to touch lastused marker for {}: {}",
                    tag(inputHash), e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Quota enforcement
    // -------------------------------------------------------------------------

    /**
     * Enforces the disk-cache quota using the env-configured limit ({@link #resolveMaxBytes()}).
     *
     * @param currentHash inputHash of the APK currently loaded/loading — never evicted; may be null.
     */
    public static void enforceQuota(Path indexDir, String currentHash) {
        enforceQuota(indexDir, currentHash, resolveMaxBytes());
    }

    /**
     * Core, directly-testable quota enforcement.
     *
     * @param indexDir    root index directory (as configured by {@code --index-dir})
     * @param currentHash inputHash never eligible for eviction (the APK currently loaded/loading); may be null
     * @param maxBytes    quota in bytes; {@code <= 0} disables eviction entirely
     */
    public static void enforceQuota(Path indexDir, String currentHash, long maxBytes) {
        if (indexDir == null || maxBytes <= 0) {
            return; // disabled
        }
        if (!Files.isDirectory(indexDir)) {
            return;
        }

        List<Group> groups;
        try {
            groups = discoverGroups(indexDir);
        } catch (IOException e) {
            logger.warn("[IndexCacheManager] Failed to scan index dir {}: {}", indexDir, e.getMessage());
            return;
        }

        long total = 0L;
        for (Group g : groups) total += g.sizeBytes;

        if (total <= maxBytes) {
            return; // within quota, nothing to do
        }

        long lowWater = (long) (maxBytes * LOW_WATER_RATIO);

        List<Group> deletable = new ArrayList<>();
        for (Group g : groups) {
            if (currentHash != null && currentHash.equals(g.hash)) continue;
            deletable.add(g);
        }
        deletable.sort(Comparator.comparingLong(g -> g.lastUsedMillis)); // oldest first

        for (Group g : deletable) {
            if (total <= lowWater) break;
            try {
                long freed = deleteGroup(indexDir, g.hash);
                total -= freed;
                long unusedMinutes = g.lastUsedMillis > 0
                        ? (System.currentTimeMillis() - g.lastUsedMillis) / 60_000L
                        : -1L;
                logger.info("[IndexCacheManager] Evicted index cache group {} — freed {} bytes, "
                        + "unused for {} (total now {} / quota {})",
                        tag(g.hash), freed,
                        unusedMinutes >= 0 ? unusedMinutes + "min" : "unknown",
                        total, maxBytes);
            } catch (IOException e) {
                logger.warn("[IndexCacheManager] Failed to evict index cache group {}: {} — skipping",
                        tag(g.hash), e.getMessage());
            }
        }

        if (total > lowWater) {
            logger.warn("[IndexCacheManager] Could not reach low-water mark ({} bytes) after evicting "
                    + "all eligible groups — remaining usage {} bytes likely dominated by the "
                    + "currently-loaded APK, which is never evicted", lowWater, total);
        }
    }

    // -------------------------------------------------------------------------
    // Group discovery / sizing / deletion
    // -------------------------------------------------------------------------

    private static final class Group {
        final String hash;
        final long sizeBytes;
        final long lastUsedMillis; // 0 if never touched (treated as oldest)

        Group(String hash, long sizeBytes, long lastUsedMillis) {
            this.hash = hash;
            this.sizeBytes = sizeBytes;
            this.lastUsedMillis = lastUsedMillis;
        }
    }

    private static List<Group> discoverGroups(Path indexDir) throws IOException {
        Set<String> hashes = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(indexDir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) continue; // "code" dir handled per-hash below
                String name = p.getFileName().toString();
                String hash = extractHash(name);
                if (hash != null) hashes.add(hash);
            }
        }

        List<Group> groups = new ArrayList<>(hashes.size());
        for (String hash : hashes) {
            long size = sizeOfGroup(indexDir, hash);
            long lastUsed = lastUsedMillis(indexDir, hash);
            groups.add(new Group(hash, size, lastUsed));
        }
        return groups;
    }

    private static String extractHash(String name) {
        if (name.endsWith(".idx")) return name.substring(0, name.length() - 4);
        if (name.endsWith(".graph")) return name.substring(0, name.length() - 6);
        if (name.endsWith(".useplaces")) return name.substring(0, name.length() - 10);
        if (name.endsWith(".shardcat")) return name.substring(0, name.length() - 9);
        if (name.endsWith(".manifest.json")) return name.substring(0, name.length() - 14);
        if (name.endsWith(".lastused")) return name.substring(0, name.length() - 9);
        if (name.endsWith(".tmp")) return null; // in-flight temp write, not a stable group member
        Matcher m = SHARD_FILE_PATTERN.matcher(name);
        if (m.matches()) return m.group(1);
        return null;
    }

    private static String tag8(String hash) {
        return hash.length() >= 8 ? hash.substring(0, 8) : hash;
    }

    private static long sizeOfGroup(Path indexDir, String hash) {
        long size = 0L;
        size += sizeOfDirectory(indexDir.resolve("code").resolve(tag8(hash)));
        size += sizeOfFile(indexDir.resolve(hash + ".idx"));
        size += sizeOfFile(indexDir.resolve(hash + ".graph"));
        size += sizeOfFile(indexDir.resolve(hash + ".useplaces"));
        size += sizeOfFile(indexDir.resolve(hash + ".shardcat"));
        size += sizeOfFile(indexDir.resolve(hash + ".manifest.json"));
        size += sizeOfFile(indexDir.resolve(hash + ".lastused"));
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(indexDir, hash + ".shard.*")) {
            for (Path p : ds) size += sizeOfFile(p);
        } catch (IOException ignored) {
            // best-effort sizing; a scan failure here just under-counts, doesn't block eviction
        }
        return size;
    }

    private static long sizeOfFile(Path p) {
        try {
            return Files.exists(p) ? Files.size(p) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long sizeOfDirectory(Path dir) {
        if (!Files.isDirectory(dir)) return 0L;
        final long[] total = {0L};
        try {
            Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // best-effort; a partial walk still gives a usable size estimate
        }
        return total[0];
    }

    private static long lastUsedMillis(Path indexDir, String hash) {
        Path marker = indexDir.resolve(hash + ".lastused");
        try {
            if (Files.exists(marker)) {
                return Files.getLastModifiedTime(marker).toMillis();
            }
        } catch (IOException ignored) {
            // fall through to "never used" default below
        }
        return 0L; // no marker — treat as oldest, prioritised for eviction
    }

    /** Deletes every artifact belonging to {@code hash}. Throws if any deletion step fails. */
    private static long deleteGroup(Path indexDir, String hash) throws IOException {
        long freed = sizeOfGroup(indexDir, hash);

        deleteDirectoryRecursive(indexDir.resolve("code").resolve(tag8(hash)));
        Files.deleteIfExists(indexDir.resolve(hash + ".idx"));
        Files.deleteIfExists(indexDir.resolve(hash + ".graph"));
        Files.deleteIfExists(indexDir.resolve(hash + ".useplaces"));
        Files.deleteIfExists(indexDir.resolve(hash + ".shardcat"));
        Files.deleteIfExists(indexDir.resolve(hash + ".manifest.json"));
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(indexDir, hash + ".shard.*")) {
            for (Path p : ds) Files.deleteIfExists(p);
        }
        // Delete the LRU marker last: if an earlier step throws, the marker survives so the
        // group's "last used" timestamp isn't lost before a retry on the next enforceQuota call.
        Files.deleteIfExists(indexDir.resolve(hash + ".lastused"));

        return freed;
    }

    private static void deleteDirectoryRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        List<Path> toDelete = new ArrayList<>();
        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                toDelete.add(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException exc) {
                toDelete.add(d);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        for (Path p : toDelete) {
            Files.delete(p);
        }
    }

    private static String tag(String hash) {
        return hash != null && hash.length() >= 8 ? hash.substring(0, 8) : String.valueOf(hash);
    }
}
