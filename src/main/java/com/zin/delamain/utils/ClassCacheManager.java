package com.zin.delamain.utils;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.CodeStore;
import com.zin.delamain.index.WarmupManager;
import com.zin.delamain.index.shard.ContentShardIndex;

import jadx.api.ICodeCache;
import jadx.api.ICodeInfo;
import jadx.api.JavaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the plugin-owned class index cache.
 *
 * <p>Uses HeadlessJadxWrapper instead of JadxWrapper; wrapper.getClassesWithInners()
 * replaces wrapper.getIncludedClassesWithInners().</p>
 */
public class ClassCacheManager {
    private static final Logger logger = LoggerFactory.getLogger(ClassCacheManager.class);

    private static final AtomicReference<Map<String, JavaClass>> classCache = new AtomicReference<>();
    private static final AtomicReference<Map<String, JavaClass>> rawNameCache = new AtomicReference<>();

    private static final AtomicReference<Map<String, List<JavaClass>>> classNameIndex = new AtomicReference<>();
    private static final AtomicReference<Map<String, List<JavaClass>>> methodNameIndex = new AtomicReference<>();
    private static final AtomicReference<Map<String, List<JavaClass>>> fieldNameIndex = new AtomicReference<>();

    // Reverse index: for each class, the bucket keys it currently occupies in the corresponding
    // name index above. Lets reindex()/removeFromIndex() remove a renamed class's stale entries
    // with a handful of direct HashMap lookups instead of an O(bucket-count) entrySet() scan of the
    // whole index (rename-perf fix — see removeFromIndex). Populated once at initCache() build time
    // and kept current by reindex() on every subsequent rename.
    private static final AtomicReference<Map<JavaClass, Set<String>>> classKeyIndex = new AtomicReference<>();
    private static final AtomicReference<Map<JavaClass, Set<String>>> methodKeyIndex = new AtomicReference<>();
    private static final AtomicReference<Map<JavaClass, Set<String>>> fieldKeyIndex = new AtomicReference<>();

    private static final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private static final AtomicReference<CompletableFuture<Void>> initFuture = new AtomicReference<>();
    private static final AtomicLong generationToken = new AtomicLong(0);
    private static final AtomicReference<String> cacheOwnerKey = new AtomicReference<>("");
    private static final AtomicReference<ICodeCache> upstreamCodeCacheRef = new AtomicReference<>();

    private static final AtomicLong startTime = new AtomicLong(0);
    private static final AtomicLong completionTime = new AtomicLong(0);
    private static final AtomicReference<String> currentPhase = new AtomicReference<>("NOT_INITIALIZED");
    private static final AtomicLong codeCacheHits = new AtomicLong(0);
    private static final AtomicLong codeCacheMisses = new AtomicLong(0);

    public enum CacheStatus {
        NOT_INITIALIZED,
        LOADING,
        READY,
        ERROR
    }

    public static void initCache(HeadlessJadxWrapper wrapper) {
        if (wrapper == null) {
            logger.warn("[JAI] Cannot initialize class cache: wrapper is null");
            return;
        }
        rotateCacheOwnerIfNeeded(wrapper);
        long generation = generationToken.get();
        if (isInitialized.compareAndSet(false, true)) {
            startTime.set(System.currentTimeMillis());
            currentPhase.set("LOADING");

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    logger.info("[JAI] Loading class cache...");
                    // Snapshot to avoid CME
                    List<JavaClass> allClasses = new ArrayList<>(wrapper.getClassesWithInners());
                    registerUpstreamCodeCache(allClasses);

                    Map<String, JavaClass> temp = new HashMap<>();
                    Map<String, JavaClass> rawTemp = new HashMap<>();
                    Map<String, List<JavaClass>> clsNameIdx = new HashMap<>();
                    Map<String, List<JavaClass>> mthNameIdx = new HashMap<>();
                    Map<String, List<JavaClass>> fldNameIdx = new HashMap<>();
                    // Reverse index seeded alongside the forward buckets above, so the very first
                    // rename of any class (not just ones already reindexed once) gets the O(k)
                    // removeFromIndex fast path instead of falling back to a full entrySet() scan.
                    Map<JavaClass, Set<String>> clsKeysIdx = new HashMap<>();
                    Map<JavaClass, Set<String>> mthKeysIdx = new HashMap<>();
                    Map<JavaClass, Set<String>> fldKeysIdx = new HashMap<>();

                    for (JavaClass cls : allClasses) {
                        if (isLoadStale(generation)) {
                            logger.info("[JAI] Discarding stale class cache load for generation {}", generation);
                            return;
                        }
                        temp.put(cls.getFullName(), cls);
                        String rawName = JadxApiAdapter.getClassRawName(cls);
                        if (rawName != null && !rawName.isEmpty()) {
                            JavaClass existing = rawTemp.get(rawName);
                            if (existing == null) {
                                rawTemp.put(rawName, cls);
                            } else if (cls.getFullName().compareTo(existing.getFullName()) < 0) {
                                rawTemp.put(rawName, cls);
                            }
                        }

                        Set<String> clsKeys = new HashSet<>();
                        Set<String> mthKeys = new HashSet<>();
                        Set<String> fldKeys = new HashSet<>();

                        String simpleName = cls.getName();
                        if (simpleName != null && !simpleName.isEmpty()) {
                            String k = simpleName.toLowerCase();
                            clsNameIdx.computeIfAbsent(k, kk -> new ArrayList<>()).add(cls);
                            clsKeys.add(k);
                        }
                        // Bug 1 fix: also bucket the raw (pre-deobfuscation) simple class name, so
                        // an EXACT/SUBSTRING/PREFIX class-name search for the raw DEX identifier
                        // (e.g. "a" when the deobfuscated display name is "C0000a") hits the fast
                        // path too, not just the display name. No-op when raw == display.
                        String rawSimpleName = JadxApiAdapter.getClassRawSimpleName(cls);
                        if (rawSimpleName != null && !rawSimpleName.isEmpty()
                                && !rawSimpleName.equalsIgnoreCase(simpleName)) {
                            String k = rawSimpleName.toLowerCase();
                            clsNameIdx.computeIfAbsent(k, kk -> new ArrayList<>()).add(cls);
                            clsKeys.add(k);
                        }
                        for (JadxApiAdapter.MethodInfoSnapshot m : JadxApiAdapter.getDeclaredMethodInfos(cls)) {
                            String mName = m.getName();
                            if (mName != null && !mName.isEmpty()) {
                                String k = mName.toLowerCase();
                                mthNameIdx.computeIfAbsent(k, kk -> new ArrayList<>()).add(cls);
                                mthKeys.add(k);
                            }
                            String mAlias = m.getAliasName();
                            if (mAlias != null && !mAlias.isEmpty() && !mAlias.equals(mName)) {
                                String k = mAlias.toLowerCase();
                                mthNameIdx.computeIfAbsent(k, kk -> new ArrayList<>()).add(cls);
                                mthKeys.add(k);
                            }
                        }
                        for (JadxApiAdapter.FieldInfoSnapshot f : JadxApiAdapter.getDeclaredFieldInfos(cls)) {
                            String fName = f.getName();
                            if (fName != null && !fName.isEmpty()) {
                                String k = fName.toLowerCase();
                                fldNameIdx.computeIfAbsent(k, kk -> new ArrayList<>()).add(cls);
                                fldKeys.add(k);
                            }
                            String fAlias = f.getAliasName();
                            if (fAlias != null && !fAlias.isEmpty() && !fAlias.equals(fName)) {
                                String k = fAlias.toLowerCase();
                                fldNameIdx.computeIfAbsent(k, kk -> new ArrayList<>()).add(cls);
                                fldKeys.add(k);
                            }
                        }

                        clsKeysIdx.put(cls, clsKeys);
                        mthKeysIdx.put(cls, mthKeys);
                        fldKeysIdx.put(cls, fldKeys);
                    }

                    if (isLoadStale(generation)) {
                        logger.info("[JAI] Discarding stale class cache result for generation {}", generation);
                        return;
                    }

                    for (Map.Entry<String, List<JavaClass>> e : clsNameIdx.entrySet()) {
                        e.setValue(Collections.unmodifiableList(e.getValue()));
                    }
                    for (Map.Entry<String, List<JavaClass>> e : mthNameIdx.entrySet()) {
                        e.setValue(Collections.unmodifiableList(e.getValue()));
                    }
                    for (Map.Entry<String, List<JavaClass>> e : fldNameIdx.entrySet()) {
                        e.setValue(Collections.unmodifiableList(e.getValue()));
                    }

                    classCache.set(temp);
                    rawNameCache.set(rawTemp);
                    classNameIndex.set(clsNameIdx);
                    methodNameIndex.set(mthNameIdx);
                    fieldNameIndex.set(fldNameIdx);
                    classKeyIndex.set(clsKeysIdx);
                    methodKeyIndex.set(mthKeysIdx);
                    fieldKeyIndex.set(fldKeysIdx);
                    completionTime.set(System.currentTimeMillis());
                    currentPhase.set("READY");

                    long duration = (completionTime.get() - startTime.get()) / 1000;
                    logger.info("[JAI] Class cache loaded: {} classes in {}s", temp.size(), duration);
                } catch (Exception e) {
                    if (isLoadStale(generation)) {
                        logger.info("[JAI] Ignoring stale class cache failure for generation {}", generation);
                        return;
                    }
                    currentPhase.set("ERROR");
                    logger.error("[JAI] Failed to load class cache", e);
                    throw new RuntimeException("Class cache initialization failed", e);
                }
            });

            initFuture.set(future);
            if (isLoadStale(generation)) {
                future.cancel(true);
                initFuture.compareAndSet(future, null);
            }
        }
    }

    public static Map<String, JavaClass> getCache() throws Exception {
        Map<String, JavaClass> cache = classCache.get();
        if (cache != null) {
            return cache;
        }

        CompletableFuture<Void> future = initFuture.get();
        if (future != null) {
            future.get();
            cache = classCache.get();
            if (cache != null) {
                return cache;
            }
            throw new IllegalStateException("Cache initialization was cancelled or invalidated.");
        }

        throw new IllegalStateException("Cache not initialized. Call initCache() first.");
    }

    public static JavaClass findClass(Map<String, JavaClass> classMap, String className) {
        if (classMap == null || className == null || className.isEmpty()) {
            return null;
        }

        JavaClass directMatch = classMap.get(className);
        if (directMatch != null) {
            return directMatch;
        }

        Map<String, JavaClass> rawMap = rawNameCache.get();
        if (rawMap != null) {
            JavaClass rawMatch = rawMap.get(className);
            if (rawMatch != null) {
                return rawMatch;
            }
        }

        return null;
    }

    public static boolean containsClass(Map<String, JavaClass> classMap, String className) {
        return findClass(classMap, className) != null;
    }

    public static List<JavaClass> findByExactName(String kind, String term) {
        if (term == null || term.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, List<JavaClass>> idx = getIndexForKind(kind);
        if (idx == null) return null;
        List<JavaClass> bucket = idx.get(term.toLowerCase());
        return bucket != null ? bucket : Collections.emptyList();
    }

    public static List<JavaClass> findBySubstringName(String kind, String term) {
        if (term == null || term.isEmpty()) return Collections.emptyList();
        String lowerTerm = term.toLowerCase();
        Map<String, List<JavaClass>> idx = getIndexForKind(kind);
        if (idx == null) return null;
        List<JavaClass> result = new ArrayList<>();
        Set<JavaClass> seen = new HashSet<>();
        for (Map.Entry<String, List<JavaClass>> entry : idx.entrySet()) {
            if (entry.getKey().contains(lowerTerm)) {
                for (JavaClass cls : entry.getValue()) {
                    if (seen.add(cls)) result.add(cls);
                }
            }
        }
        return result;
    }

    public static List<JavaClass> findByPrefixName(String kind, String term) {
        if (term == null || term.isEmpty()) return Collections.emptyList();
        String lowerTerm = term.toLowerCase();
        Map<String, List<JavaClass>> idx = getIndexForKind(kind);
        if (idx == null) return null;
        List<JavaClass> result = new ArrayList<>();
        Set<JavaClass> seen = new HashSet<>();
        for (Map.Entry<String, List<JavaClass>> entry : idx.entrySet()) {
            if (entry.getKey().startsWith(lowerTerm)) {
                for (JavaClass cls : entry.getValue()) {
                    if (seen.add(cls)) result.add(cls);
                }
            }
        }
        return result;
    }

    private static Map<String, List<JavaClass>> getIndexForKind(String kind) {
        switch (kind) {
            case "class":  return classNameIndex.get();
            case "method": return methodNameIndex.get();
            case "field":  return fieldNameIndex.get();
            default:       return null;
        }
    }

    public static void reindex(JavaClass cls) {
        if (cls == null) {
            return;
        }
        CodeContentIndex.invalidate(cls);

        Map<String, List<JavaClass>> clsIdx = classNameIndex.get();
        Map<String, List<JavaClass>> mthIdx = methodNameIndex.get();
        Map<String, List<JavaClass>> fldIdx = fieldNameIndex.get();
        if (clsIdx == null || mthIdx == null || fldIdx == null) {
            return;
        }
        // May legitimately be null (reindex() called before the first initCache() build finished
        // publishing them) — removeFromIndex/updateKeysOf below treat that as "no reverse-index yet,
        // fall back to a full scan / skip the bookkeeping", never a NPE.
        Map<JavaClass, Set<String>> clsKeys = classKeyIndex.get();
        Map<JavaClass, Set<String>> mthKeys = methodKeyIndex.get();
        Map<JavaClass, Set<String>> fldKeys = fieldKeyIndex.get();

        removeFromIndex(clsIdx, clsKeys, cls);
        removeFromIndex(mthIdx, mthKeys, cls);
        removeFromIndex(fldIdx, fldKeys, cls);

        Set<String> newClsKeys = new HashSet<>();
        Set<String> newMthKeys = new HashSet<>();
        Set<String> newFldKeys = new HashSet<>();

        String simpleName = cls.getName();
        if (simpleName != null && !simpleName.isEmpty()) {
            String k = simpleName.toLowerCase();
            addToIndex(clsIdx, k, cls);
            newClsKeys.add(k);
        }
        String rawSimpleName = JadxApiAdapter.getClassRawSimpleName(cls);
        if (rawSimpleName != null && !rawSimpleName.isEmpty()
                && !rawSimpleName.equalsIgnoreCase(simpleName)) {
            String k = rawSimpleName.toLowerCase();
            addToIndex(clsIdx, k, cls);
            newClsKeys.add(k);
        }
        for (JadxApiAdapter.MethodInfoSnapshot m : JadxApiAdapter.getDeclaredMethodInfos(cls)) {
            String mName = m.getName();
            if (mName != null && !mName.isEmpty()) {
                String k = mName.toLowerCase();
                addToIndex(mthIdx, k, cls);
                newMthKeys.add(k);
            }
            String mAlias = m.getAliasName();
            if (mAlias != null && !mAlias.isEmpty() && !mAlias.equals(mName)) {
                String k = mAlias.toLowerCase();
                addToIndex(mthIdx, k, cls);
                newMthKeys.add(k);
            }
        }
        for (JadxApiAdapter.FieldInfoSnapshot f : JadxApiAdapter.getDeclaredFieldInfos(cls)) {
            String fName = f.getName();
            if (fName != null && !fName.isEmpty()) {
                String k = fName.toLowerCase();
                addToIndex(fldIdx, k, cls);
                newFldKeys.add(k);
            }
            String fAlias = f.getAliasName();
            if (fAlias != null && !fAlias.isEmpty() && !fAlias.equals(fName)) {
                String k = fAlias.toLowerCase();
                addToIndex(fldIdx, k, cls);
                newFldKeys.add(k);
            }
        }

        if (clsKeys != null) clsKeys.put(cls, newClsKeys);
        if (mthKeys != null) mthKeys.put(cls, newMthKeys);
        if (fldKeys != null) fldKeys.put(cls, newFldKeys);
    }

    /**
     * Refreshes the name-buckets ({@link #reindex}) for a class after a rename, and — if the
     * class's own full name changed (class rename, not method/field) — publishes a new
     * {@code classCache} snapshot so lookups by the new name succeed too.
     *
     * <p>{@code classCache} is keyed by {@code JavaClass.getFullName()} captured once at initial
     * cache build. Since {@code ClassNode.rename()} mutates the live node in place, the original
     * key silently goes stale after a rename (old name still resolves to the same object, but the
     * new alias does not) unless we republish the map. Uses copy-on-write, matching the existing
     * "read without synchronizing, safe for concurrent reads of a volatile-published snapshot"
     * contract already used by {@code classCache}/{@code rawNameCache}.
     *
     * <p>Callers must hold {@link JadxSearchLock}'s write lock while renaming so this update is
     * not racing a concurrent search reading the index buckets.
     */
    public static void reindexRenamedClass(JavaClass cls, String previousFullName) {
        if (cls == null) return;
        reindex(cls);

        String newFullName = cls.getFullName();
        if (newFullName == null || newFullName.equals(previousFullName)) return;

        Map<String, JavaClass> cache = classCache.get();
        if (cache == null) return;
        Map<String, JavaClass> updated = new HashMap<>(cache);
        updated.put(newFullName, cls);
        classCache.set(updated);
    }

    /** One rename to apply as part of a {@link #reindexRenamedClassesBatch} call. */
    public static final class RenamedClass {
        public final JavaClass cls;
        public final String previousFullName;

        public RenamedClass(JavaClass cls, String previousFullName) {
            this.cls = cls;
            this.previousFullName = previousFullName;
        }
    }

    /**
     * Batch counterpart to {@link #reindexRenamedClass} (rename-perf fix). Refreshes the name-index
     * buckets for every class in {@code renames} — same O(k) {@link #reindex} as the single-class
     * path — but publishes exactly ONE new {@code classCache} snapshot at the end instead of
     * copying the full class map once per rename.
     *
     * <p>On a 238k-class APK, a package-wide rename of M classes through the single-class path
     * costs M full {@code new HashMap<>(cache)} copies (O(M×N) just for the classCache
     * republish, on top of M lock acquire/releases in the caller); this collapses the copy to a
     * single O(N) pass regardless of M. Callers must still hold {@link JadxSearchLock}'s write lock
     * across the WHOLE batch (same requirement as the single-class path) so no concurrent search
     * reads a half-updated index.
     *
     * <p>Not yet wired to a caller: {@code RenameStorage}/{@code RefactoringRoutes} still call
     * {@link #reindexRenamedClass} once per class in their batch-rename loops. Switching those loops
     * to collect {@link RenamedClass} entries and call this once at the end is a follow-up outside
     * this class's ownership.
     */
    public static void reindexRenamedClassesBatch(List<RenamedClass> renames) {
        if (renames == null || renames.isEmpty()) return;

        Map<String, JavaClass> cache = classCache.get();
        Map<String, JavaClass> updated = cache != null ? new HashMap<>(cache) : null;

        for (RenamedClass rc : renames) {
            if (rc == null || rc.cls == null) continue;
            reindex(rc.cls);
            String newFullName = rc.cls.getFullName();
            if (updated != null && newFullName != null && !newFullName.equals(rc.previousFullName)) {
                updated.put(newFullName, rc.cls);
            }
        }

        if (updated != null) {
            classCache.set(updated);
        }
    }

    /**
     * Removes {@code cls} from every bucket of {@code index} it occupies.
     *
     * <p>Rename-perf fix: when {@code keyIndex} (the reverse index of bucket keys {@code cls}
     * currently occupies, maintained by {@link #reindex}) has an entry for {@code cls}, this does a
     * handful of direct {@code index.get(key)} lookups instead of an {@code entrySet()} scan of the
     * WHOLE index. On a 238k-class APK the name-index buckets number in the hundreds of thousands;
     * a package-wide batch rename of M classes previously cost O(M×N) (3 full scans per class), the
     * dominant cost of a bulk rename. Falls back to the old full scan only when no reverse-index
     * entry exists yet for this class (e.g. reindex() raced initCache()'s first publish).
     */
    private static void removeFromIndex(Map<String, List<JavaClass>> index,
                                         Map<JavaClass, Set<String>> keyIndex, JavaClass cls) {
        Set<String> keys = keyIndex != null ? keyIndex.get(cls) : null;
        if (keys != null) {
            for (String key : keys) {
                removeFromBucket(index, key, cls);
            }
            return;
        }
        for (Map.Entry<String, List<JavaClass>> entry : index.entrySet()) {
            List<JavaClass> bucket = entry.getValue();
            if (bucket instanceof ArrayList) {
                bucket.remove(cls);
            } else {
                boolean present = false;
                for (JavaClass c : bucket) {
                    if (c == cls) {
                        present = true;
                        break;
                    }
                }
                if (present) {
                    List<JavaClass> mutable = new ArrayList<>(bucket);
                    mutable.remove(cls);
                    entry.setValue(Collections.unmodifiableList(mutable));
                }
            }
        }
    }

    /** Removes {@code cls} from exactly one bucket ({@code index.get(key)}), if present. */
    private static void removeFromBucket(Map<String, List<JavaClass>> index, String key, JavaClass cls) {
        List<JavaClass> bucket = index.get(key);
        if (bucket == null) {
            return;
        }
        if (bucket instanceof ArrayList) {
            bucket.remove(cls);
        } else {
            boolean present = false;
            for (JavaClass c : bucket) {
                if (c == cls) {
                    present = true;
                    break;
                }
            }
            if (present) {
                List<JavaClass> mutable = new ArrayList<>(bucket);
                mutable.remove(cls);
                index.put(key, Collections.unmodifiableList(mutable));
            }
        }
    }

    private static void addToIndex(Map<String, List<JavaClass>> index, String key, JavaClass cls) {
        List<JavaClass> existing = index.get(key);
        if (existing == null) {
            List<JavaClass> newBucket = new ArrayList<>();
            newBucket.add(cls);
            index.put(key, Collections.unmodifiableList(newBucket));
        } else if (existing instanceof ArrayList) {
            if (!existing.contains(cls)) {
                existing.add(cls);
            }
        } else {
            if (!existing.contains(cls)) {
                List<JavaClass> mutable = new ArrayList<>(existing);
                mutable.add(cls);
                index.put(key, Collections.unmodifiableList(mutable));
            }
        }
    }

    public static CacheStatus getStatus() {
        String phase = currentPhase.get();
        return CacheStatus.valueOf(phase);
    }

    public static Map<String, Object> getHealthInfo() {
        Map<String, Object> health = new HashMap<>();

        String phase = currentPhase.get();
        health.put("status", phase);

        if ("LOADING".equals(phase)) {
            long elapsed = (System.currentTimeMillis() - startTime.get()) / 1000;
            health.put("elapsed_seconds", elapsed);
            health.put("message", "Loading all classes into cache...");
        } else if ("READY".equals(phase)) {
            Map<String, JavaClass> cache = classCache.get();
            health.put("total_classes", cache != null ? cache.size() : 0);

            long duration = (completionTime.get() - startTime.get()) / 1000;
            health.put("load_duration_seconds", duration);
        } else if ("ERROR".equals(phase)) {
            health.put("error", "Cache initialization failed");
        }

        return health;
    }

    public static String getCachedCode(String className) {
        String code = getCachedCodeFromJadx(className);
        if (code != null) {
            codeCacheHits.incrementAndGet();
            return code;
        }
        codeCacheMisses.incrementAndGet();
        return null;
    }

    public static String getCodeAndIndex(JavaClass cls) {
        if (cls == null) {
            return null;
        }
        registerUpstreamCodeCache(cls);
        try {
            String code = cls.getCode();
            if (code == null || code.isEmpty()) {
                return null;
            }
            String lower = code.toLowerCase();
            CodeContentIndex.index(cls, lower);
            return lower;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("JadxInternalApiUsage")
    public static String getCachedCodeDirect(JavaClass cls) {
        if (cls == null) {
            return null;
        }
        try {
            ICodeInfo info = cls.getClassNode().getCodeFromCache();
            return info != null ? info.getCodeStr() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void putCachedCode(String className, String code) {
        // Intentionally empty: upstream ICodeCache is populated by JavaClass.getCode().
    }

    public static void invalidateCode(String className) {
        removeCachedCodeFromJadx(className);
        codeQualityCache.remove(className);
        JavaClass cls = resolveIndexedClass(className);
        if (cls != null) {
            // Rename leaves the persistent CodeStore disk cache and the mmap shard index
            // holding the pre-rename source; without this, classMatchesAnyContentLocation's
            // codeFromStore fallback would keep serving stale content under the old name.
            CodeStore cs = WarmupManager.codeStore();
            if (cs != null) {
                String rawName = cls.getRawName();
                if (rawName != null && !rawName.isEmpty()) {
                    cs.remove(rawName);
                }
            }
            int id = CodeContentIndex.idOf(cls);
            if (id >= 0) {
                ContentShardIndex.tombstone(id);
            }
            CodeContentIndex.invalidate(cls);
        }
    }

    /**
     * Evicts only the jadx ICodeCache entry for one class (NOT the trigram CodeContentIndex).
     * Used by the memory-bounded use-places harvest to release a referrer's decompiled code right
     * after it is consumed, so the JVM heap stays bounded. Trigram search data is untouched.
     */
    public static void evictCodeCacheEntry(JavaClass cls) {
        if (cls == null) return;
        codeQualityCache.remove(cls.getFullName());
        try {
            String rawName = JadxApiAdapter.getClassRawName(cls);
            if (rawName == null || rawName.isEmpty()) return;
            ICodeCache codeCache = resolveMutableCodeCache(cls);
            if (codeCache != null) codeCache.remove(rawName);
        } catch (Exception ignored) {
            // best-effort eviction
        }
    }

    public static void clearCodeCache() {
        codeQualityCache.clear();
        Map<String, JavaClass> cache = classCache.get();
        if (cache == null || cache.isEmpty()) {
            logger.info("[JAI] Skipped upstream code cache clear: class index not available");
            return;
        }

        int removed = 0;
        Set<String> rawNames = new HashSet<>();
        for (JavaClass cls : cache.values()) {
            if (cls == null) {
                continue;
            }
            String rawName = JadxApiAdapter.getClassRawName(cls);
            if (rawName != null && !rawName.isEmpty() && rawNames.add(rawName)) {
                ICodeCache codeCache = resolveMutableCodeCache(cls);
                if (codeCache != null) {
                    codeCache.remove(rawName);
                    removed++;
                }
            }
        }
        logger.info("[JAI] Cleared {} upstream code cache entries", removed);
    }

    // --- Decompilation-quality verdict cache -------------------------------------------------
    // Co-located with the code cache so it shares the exact same lifecycle: every
    // clearCodeCache()/invalidateCode()/evictCodeCacheEntry() that drops a class's cached source
    // also drops its quality verdict, so a stale verdict can never outlive the code it describes
    // and the map cannot grow unbounded across reloads. Keyed identically to the code cache
    // (JavaClass.getFullName()). Populated once at real decompile time by DecompileRoutes; a miss
    // means "unknown" — callers must not treat a miss as "ok".

    /** Structured decompilation-quality verdict: {@code ok|degraded|failed} + optional reason. */
    public static final class CodeQuality {
        public final String quality;
        public final String reason;

        public CodeQuality(String quality, String reason) {
            this.quality = quality;
            this.reason = reason;
        }
    }

    private static final Map<String, CodeQuality> codeQualityCache = new ConcurrentHashMap<>();

    /** Stores the verdict computed at decompile time; keyed by {@code JavaClass.getFullName()}. */
    public static void putCodeQuality(String cacheKey, String quality, String reason) {
        codeQualityCache.put(cacheKey, new CodeQuality(quality, reason));
    }

    /** Returns the cached verdict, or {@code null} (== "unknown") on a miss. Lockless read. */
    public static CodeQuality getCodeQuality(String cacheKey) {
        return codeQualityCache.get(cacheKey);
    }

    public static Map<String, Object> getNameIndexStats() {
        Map<String, Object> stats = new HashMap<>();
        Map<String, List<JavaClass>> clsIdx = classNameIndex.get();
        Map<String, List<JavaClass>> mthIdx = methodNameIndex.get();
        Map<String, List<JavaClass>> fldIdx = fieldNameIndex.get();
        Map<String, JavaClass> rawMap = rawNameCache.get();

        stats.put("class_name_buckets", clsIdx != null ? clsIdx.size() : 0);
        stats.put("method_name_buckets", mthIdx != null ? mthIdx.size() : 0);
        stats.put("field_name_buckets", fldIdx != null ? fldIdx.size() : 0);
        stats.put("raw_name_map_size", rawMap != null ? rawMap.size() : 0);
        stats.put("index_ready", clsIdx != null && mthIdx != null && fldIdx != null);
        return stats;
    }

    public static Map<String, Object> getCodeCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        Map<String, JavaClass> cache = classCache.get();
        stats.put("delegates_to_jadx_icodecache", true);
        stats.put("plugin_source_lru_enabled", false);
        stats.put("class_index_size", cache != null ? cache.size() : 0);
        stats.put("code_cache_size", -1);
        stats.put("code_cache_max", -1);
        stats.put("code_cache_hits", codeCacheHits.get());
        stats.put("code_cache_misses", codeCacheMisses.get());
        return stats;
    }

    private static final long CLEAR_DEBOUNCE_MS = 30000;
    private static final AtomicLong lastClearTime = new AtomicLong(0);
    private static final Object clearLock = new Object();

    public static boolean clearCache() {
        long now = System.currentTimeMillis();
        long lastClear = lastClearTime.get();

        if (now - lastClear < CLEAR_DEBOUNCE_MS && !hasActiveCacheState()) {
            long remainingSecs = (CLEAR_DEBOUNCE_MS - (now - lastClear)) / 1000;
            logger.info("[JAI] Cache clear debounced (cooldown: {}s remaining)", remainingSecs);
            return false;
        }

        synchronized (clearLock) {
            lastClear = lastClearTime.get();
            if (now - lastClear < CLEAR_DEBOUNCE_MS && !hasActiveCacheState()) {
                return false;
            }

            clearCacheState("manual clear (index only)", false);
            return true;
        }
    }

    public static boolean clearCacheIncludingDecompiled() {
        long now = System.currentTimeMillis();
        long lastClear = lastClearTime.get();

        if (now - lastClear < CLEAR_DEBOUNCE_MS && !hasActiveCacheState()) {
            long remainingSecs = (CLEAR_DEBOUNCE_MS - (now - lastClear)) / 1000;
            logger.info("[JAI] Full cache clear debounced (cooldown: {}s remaining)", remainingSecs);
            return false;
        }

        synchronized (clearLock) {
            lastClear = lastClearTime.get();
            if (now - lastClear < CLEAR_DEBOUNCE_MS && !hasActiveCacheState()) {
                return false;
            }

            clearCacheState("full clear including decompiled", true);
            return true;
        }
    }

    private static boolean isLoadStale(long generation) {
        return Thread.currentThread().isInterrupted() || generationToken.get() != generation;
    }

    public static long getRemainingCooldown() {
        long now = System.currentTimeMillis();
        long lastClear = lastClearTime.get();
        long remaining = CLEAR_DEBOUNCE_MS - (now - lastClear);
        return remaining > 0 ? remaining / 1000 : 0;
    }

    public static long getCooldownDuration() {
        return CLEAR_DEBOUNCE_MS / 1000;
    }

    private static boolean hasActiveCacheState() {
        return classCache.get() != null
            || initFuture.get() != null
            || isInitialized.get()
            || !"NOT_INITIALIZED".equals(currentPhase.get())
            || !cacheOwnerKey.get().isEmpty();
    }

    private static void rotateCacheOwnerIfNeeded(HeadlessJadxWrapper wrapper) {
        String ownerKey = buildCacheOwnerKey(wrapper);
        String previousOwner = cacheOwnerKey.get();
        if (!previousOwner.isEmpty() && !previousOwner.equals(ownerKey)) {
            synchronized (clearLock) {
                if (!previousOwner.equals(cacheOwnerKey.get())) {
                    cacheOwnerKey.set(ownerKey);
                    return;
                }
                clearCacheState("project owner changed", true);
                // F7 fix (path A): clearCacheState(..., true) just wiped CodeContentIndex's id
                // space via CodeContentIndex.clear(). If this owner-rotate is firing mid-warmup
                // (the exact production timeline: WarmupManager.runWarmup preAssigns ids for the
                // NEW apk, then calls initCache(wrapper) for that SAME apk near the end of the run,
                // which is what rotates the owner away from the PREVIOUS apk), the ids just wiped
                // are the ones the in-flight/just-built mmap shard depends on. Restore them
                // immediately — before any query can observe the gap — from WarmupManager's own
                // record of the class list it preAssigned ids for.
                WarmupManager.reassignIdsForCurrentApk();
                cacheOwnerKey.set(ownerKey);
            }
            logger.info("[JAI] Class index cache invalidated due to project switch");
            return;
        }
        cacheOwnerKey.set(ownerKey);
    }

    private static String buildCacheOwnerKey(HeadlessJadxWrapper wrapper) {
        StringBuilder ownerKey = new StringBuilder();
        ownerKey.append("wrapper@").append(System.identityHashCode(wrapper));
        try {
            for (java.io.File f : wrapper.getInputFiles()) {
                ownerKey.append('|').append(f.getAbsolutePath());
                try {
                    ownerKey.append(':').append(f.length());
                    ownerKey.append(':').append(f.lastModified());
                } catch (Exception ignored) {
                    ownerKey.append(":na:na");
                }
            }
        } catch (Exception e) {
            ownerKey.append("|unknown-input");
        }
        return ownerKey.toString();
    }

    /**
     * Test-only: reset all cache + project-owner state to the clean baseline so a shared-JVM test
     * starts from a known slate. {@link #cacheOwnerKey} is process-global static and otherwise leaks
     * between tests — a later test's warmup-triggered {@link #initCache} then sees a foreign owner and
     * fires a "project owner changed" clear that wipes freshly pre-assigned CodeContentIndex ids
     * mid-test (an order-dependent flake, not a production path).
     */
    public static void resetForTests() {
        clearCacheState("test reset", true);
    }

    private static void clearCacheState(String reason, boolean evictCodeCache) {
        lastClearTime.set(System.currentTimeMillis());
        if (evictCodeCache) {
            clearCodeCache();
            CodeContentIndex.clear();
        }
        long generation = generationToken.incrementAndGet();
        CompletableFuture<Void> future = initFuture.getAndSet(null);
        if (future != null) {
            future.cancel(true);
        }
        classCache.set(null);
        rawNameCache.set(null);
        classNameIndex.set(null);
        methodNameIndex.set(null);
        fieldNameIndex.set(null);
        classKeyIndex.set(null);
        methodKeyIndex.set(null);
        fieldKeyIndex.set(null);
        upstreamCodeCacheRef.set(null);
        cacheOwnerKey.set("");
        isInitialized.set(false);
        startTime.set(0);
        completionTime.set(0);
        currentPhase.set("NOT_INITIALIZED");
        logger.info("[JAI] Class cache cleared (generation {}, reason: {})", generation, reason);
    }

    @SuppressWarnings("JadxInternalApiUsage")
    private static String getCachedCodeFromJadx(String className) {
        JavaClass cls = resolveIndexedClass(className);
        if (cls != null) {
            registerUpstreamCodeCache(cls);
            ICodeInfo codeInfo = cls.getClassNode().getCodeFromCache();
            return codeInfo != null ? codeInfo.getCodeStr() : null;
        }
        ICodeCache codeCache = upstreamCodeCacheRef.get();
        if (codeCache == null || className == null || className.isEmpty()) {
            return null;
        }
        return codeCache.getCode(className);
    }

    private static void removeCachedCodeFromJadx(String className) {
        JavaClass cls = resolveIndexedClass(className);
        if (cls != null) {
            ICodeCache codeCache = resolveMutableCodeCache(cls);
            if (codeCache != null) {
                String rawName = JadxApiAdapter.getClassRawName(cls);
                if (rawName != null && !rawName.isEmpty()) {
                    codeCache.remove(rawName);
                }
                String aliasName = JadxApiAdapter.getClassAliasName(cls);
                if (aliasName != null && !aliasName.isEmpty()) {
                    codeCache.remove(aliasName);
                }
            }
            return;
        }
        ICodeCache codeCache = upstreamCodeCacheRef.get();
        if (codeCache != null && className != null && !className.isEmpty()) {
            codeCache.remove(className);
        }
    }

    private static JavaClass resolveIndexedClass(String className) {
        Map<String, JavaClass> cache = classCache.get();
        return cache != null ? findClass(cache, className) : null;
    }

    private static void registerUpstreamCodeCache(List<JavaClass> allClasses) {
        if (allClasses == null || allClasses.isEmpty()) {
            return;
        }
        registerUpstreamCodeCache(allClasses.get(0));
    }

    @SuppressWarnings("JadxInternalApiUsage")
    private static void registerUpstreamCodeCache(JavaClass cls) {
        if (cls == null) {
            return;
        }
        try {
            ICodeCache codeCache = cls.getClassNode().root().getCodeCache();
            if (codeCache != null) {
                upstreamCodeCacheRef.set(codeCache);
            }
        } catch (Exception ignored) {
            // Not critical
        }
    }

    private static ICodeCache resolveMutableCodeCache(JavaClass cls) {
        registerUpstreamCodeCache(cls);
        return upstreamCodeCacheRef.get();
    }
}
