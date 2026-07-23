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

                        String simpleName = cls.getName();
                        if (simpleName != null && !simpleName.isEmpty()) {
                            clsNameIdx.computeIfAbsent(simpleName.toLowerCase(), k -> new ArrayList<>()).add(cls);
                        }
                        // Bug 1 fix: also bucket the raw (pre-deobfuscation) simple class name, so
                        // an EXACT/SUBSTRING/PREFIX class-name search for the raw DEX identifier
                        // (e.g. "a" when the deobfuscated display name is "C0000a") hits the fast
                        // path too, not just the display name. No-op when raw == display.
                        String rawSimpleName = JadxApiAdapter.getClassRawSimpleName(cls);
                        if (rawSimpleName != null && !rawSimpleName.isEmpty()
                                && !rawSimpleName.equalsIgnoreCase(simpleName)) {
                            clsNameIdx.computeIfAbsent(rawSimpleName.toLowerCase(), k -> new ArrayList<>()).add(cls);
                        }
                        for (JadxApiAdapter.MethodInfoSnapshot m : JadxApiAdapter.getDeclaredMethodInfos(cls)) {
                            String mName = m.getName();
                            if (mName != null && !mName.isEmpty()) {
                                mthNameIdx.computeIfAbsent(mName.toLowerCase(), k -> new ArrayList<>()).add(cls);
                            }
                            String mAlias = m.getAliasName();
                            if (mAlias != null && !mAlias.isEmpty() && !mAlias.equals(mName)) {
                                mthNameIdx.computeIfAbsent(mAlias.toLowerCase(), k -> new ArrayList<>()).add(cls);
                            }
                        }
                        for (JadxApiAdapter.FieldInfoSnapshot f : JadxApiAdapter.getDeclaredFieldInfos(cls)) {
                            String fName = f.getName();
                            if (fName != null && !fName.isEmpty()) {
                                fldNameIdx.computeIfAbsent(fName.toLowerCase(), k -> new ArrayList<>()).add(cls);
                            }
                            String fAlias = f.getAliasName();
                            if (fAlias != null && !fAlias.isEmpty() && !fAlias.equals(fName)) {
                                fldNameIdx.computeIfAbsent(fAlias.toLowerCase(), k -> new ArrayList<>()).add(cls);
                            }
                        }
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

        removeFromIndex(clsIdx, cls);
        removeFromIndex(mthIdx, cls);
        removeFromIndex(fldIdx, cls);

        String simpleName = cls.getName();
        if (simpleName != null && !simpleName.isEmpty()) {
            addToIndex(clsIdx, simpleName.toLowerCase(), cls);
        }
        String rawSimpleName = JadxApiAdapter.getClassRawSimpleName(cls);
        if (rawSimpleName != null && !rawSimpleName.isEmpty()
                && !rawSimpleName.equalsIgnoreCase(simpleName)) {
            addToIndex(clsIdx, rawSimpleName.toLowerCase(), cls);
        }
        for (JadxApiAdapter.MethodInfoSnapshot m : JadxApiAdapter.getDeclaredMethodInfos(cls)) {
            String mName = m.getName();
            if (mName != null && !mName.isEmpty()) {
                addToIndex(mthIdx, mName.toLowerCase(), cls);
            }
            String mAlias = m.getAliasName();
            if (mAlias != null && !mAlias.isEmpty() && !mAlias.equals(mName)) {
                addToIndex(mthIdx, mAlias.toLowerCase(), cls);
            }
        }
        for (JadxApiAdapter.FieldInfoSnapshot f : JadxApiAdapter.getDeclaredFieldInfos(cls)) {
            String fName = f.getName();
            if (fName != null && !fName.isEmpty()) {
                addToIndex(fldIdx, fName.toLowerCase(), cls);
            }
            String fAlias = f.getAliasName();
            if (fAlias != null && !fAlias.isEmpty() && !fAlias.equals(fName)) {
                addToIndex(fldIdx, fAlias.toLowerCase(), cls);
            }
        }
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

    private static void removeFromIndex(Map<String, List<JavaClass>> index, JavaClass cls) {
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
