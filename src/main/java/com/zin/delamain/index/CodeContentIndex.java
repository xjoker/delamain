package com.zin.delamain.index;

import jadx.api.JavaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;

/**
 * Trigram bitmap inverted index for code-content search.
 *
 * <p>Null vs empty BitSet semantics preserved:
 * - empty BitSet: term definitively absent from all indexed classes
 * - null: index cannot narrow candidate set (caller must fall back to full scan)</p>
 */
public final class CodeContentIndex {

    private static final Logger logger = LoggerFactory.getLogger(CodeContentIndex.class);

    static final boolean ENABLED;
    static final int MAX_TRIGRAMS;
    static final int MAX_CLASS_SIZE_BYTES;
    static final boolean EVICTION_ENABLED;
    static final double HEAP_PRESSURE_HIGH;
    static final double HEAP_PRESSURE_LOW;
    static final int HEAP_WATCHER_INTERVAL_SECONDS;

    static {
        String enabledEnv = System.getenv("DELAMAIN_CODE_INDEX_ENABLED");
        ENABLED = enabledEnv == null || !enabledEnv.equalsIgnoreCase("false");

        String maxTrigramsEnv = System.getenv("DELAMAIN_CODE_INDEX_MAX_TRIGRAMS");
        int maxTrigrams = 500_000;
        if (maxTrigramsEnv != null) {
            try { maxTrigrams = Integer.parseInt(maxTrigramsEnv.trim()); } catch (NumberFormatException ignored) {}
        }
        MAX_TRIGRAMS = maxTrigrams;

        String maxSizeEnv = System.getenv("DELAMAIN_CODE_INDEX_MAX_CLASS_SIZE_BYTES");
        int maxSize = 1_048_576;
        if (maxSizeEnv != null) {
            try { maxSize = Integer.parseInt(maxSizeEnv.trim()); } catch (NumberFormatException ignored) {}
        }
        MAX_CLASS_SIZE_BYTES = maxSize;

        String evictionEnabledEnv = System.getenv("DELAMAIN_CODE_INDEX_EVICTION_ENABLED");
        EVICTION_ENABLED = evictionEnabledEnv == null || !evictionEnabledEnv.equalsIgnoreCase("false");

        String pressureHighEnv = System.getenv("DELAMAIN_CODE_INDEX_HEAP_PRESSURE_HIGH");
        double pressureHigh = 0.80;
        if (pressureHighEnv != null) {
            try { pressureHigh = Double.parseDouble(pressureHighEnv.trim()); } catch (NumberFormatException ignored) {}
        }
        HEAP_PRESSURE_HIGH = pressureHigh;

        String pressureLowEnv = System.getenv("DELAMAIN_CODE_INDEX_HEAP_PRESSURE_LOW");
        double pressureLow = 0.70;
        if (pressureLowEnv != null) {
            try { pressureLow = Double.parseDouble(pressureLowEnv.trim()); } catch (NumberFormatException ignored) {}
        }
        HEAP_PRESSURE_LOW = pressureLow;

        String intervalEnv = System.getenv("DELAMAIN_HEAP_WATCHER_INTERVAL_SECONDS");
        int interval = 10;
        if (intervalEnv != null) {
            try { interval = Integer.parseInt(intervalEnv.trim()); } catch (NumberFormatException ignored) {}
        }
        HEAP_WATCHER_INTERVAL_SECONDS = interval;
    }

    private static volatile State state = new State();
    private static final AtomicLong totalEvictions = new AtomicLong(0);
    private static final ScheduledExecutorService heapWatcher;

    static {
        if (ENABLED && EVICTION_ENABLED) {
            ScheduledExecutorService svc = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jadx-heap-watcher");
                t.setDaemon(true);
                return t;
            });
            svc.scheduleAtFixedRate(
                    CodeContentIndex::heapWatcherTick,
                    HEAP_WATCHER_INTERVAL_SECONDS,
                    HEAP_WATCHER_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
            heapWatcher = svc;
        } else {
            heapWatcher = null;
        }
    }

    public static void index(JavaClass cls, String code) {
        if (!ENABLED || cls == null || code == null) {
            return;
        }
        State s = state;
        if (s.capReached.get()) {
            return;
        }
        int len = code.length();
        if (len < 3) {
            return;
        }
        if (len > MAX_CLASS_SIZE_BYTES) {
            logger.debug("[JAI] CodeContentIndex: skipping class {} — size {}B > limit {}B",
                    cls.getFullName(), len, MAX_CLASS_SIZE_BYTES);
            return;
        }

        // Get the class's id — it may already have been assigned by preAssignIds() (which
        // pre-seeds ids for ALL classes so BitSet bit positions are stable across restarts).
        // A pre-assigned id must NOT short-circuit trigram building; that conflation was the
        // C10 root cause (Phase-2 indexed 0 trigrams because every class already had an id).
        int id = s.getOrAssignId(cls);
        if (id < 0) {
            return;
        }
        // Build this class's trigrams exactly once, tracked independently of id assignment.
        if (!s.trigramBuilt.add(cls)) {
            return;
        }

        for (int i = 0; i <= len - 3; i++) {
            char c0 = code.charAt(i);
            char c1 = code.charAt(i + 1);
            char c2 = code.charAt(i + 2);
            if (c0 <= ' ' && c1 <= ' ' && c2 <= ' ') {
                continue;
            }
            String trigram = new String(new char[]{c0, c1, c2});
            BitSet existing = s.trigramIndex.get(trigram);
            if (existing == null) {
                if (s.trigramIndex.size() >= MAX_TRIGRAMS) {
                    if (s.capReached.compareAndSet(false, true)) {
                        logger.warn("[JAI] CodeContentIndex: trigram cap ({}) reached.", MAX_TRIGRAMS);
                    }
                    // Undo the trigramBuilt mark from above: this class's postings are only
                    // half-built, so it must NOT read as isIndexed()==true — the H6 guard in
                    // SearchRoutes relies on that to fall back to content-scan for it instead
                    // of wrongly excluding it as a searched-and-missed candidate.
                    s.trigramBuilt.remove(cls);
                    return;
                }
                BitSet newBs = new BitSet();
                BitSet race = s.trigramIndex.putIfAbsent(trigram, newBs);
                existing = (race != null) ? race : newBs;
            }
            synchronized (existing) {
                existing.set(id);
            }
        }
    }

    /**
     * Look up candidate class IDs for a given search term.
     * Returns:
     * - non-empty BitSet: classes containing all trigrams
     * - empty BitSet: term definitively absent
     * - null: cannot narrow (disabled, term &lt; 3 chars, or a trigram is absent from index)
     */
    public static BitSet candidatesForTerm(String term) {
        if (!ENABLED || term == null || term.length() < 3) {
            return null;
        }
        State s = state;
        if (s.trigramIndex.isEmpty()) {
            return null;
        }

        BitSet result = null;
        int len = term.length();
        for (int i = 0; i <= len - 3; i++) {
            String trigram = term.substring(i, i + 3);
            BitSet bs = s.trigramIndex.get(trigram);
            if (bs == null) {
                return null;
            }
            BitSet copy;
            synchronized (bs) {
                copy = (BitSet) bs.clone();
            }
            if (result == null) {
                result = copy;
            } else {
                result.and(copy);
                if (result.isEmpty()) {
                    return result;
                }
            }
        }
        return result;
    }

    /**
     * Returns the id currently assigned to {@code cls}, or -1 if none has been assigned. O(1) map
     * lookup that never assigns. This is the inverse of {@link #resolveClass(int)} and is what the
     * shard layer's H6 guard in SearchRoutes uses to map a live {@link JavaClass} to the shard's id
     * space (shard classId == CodeContentIndex id, guaranteed by {@link #preAssignIds}).
     */
    public static int idOf(JavaClass cls) {
        if (!ENABLED || cls == null) {
            return -1;
        }
        Integer id = state.classToId.get(cls);
        return id != null ? id : -1;
    }

    public static JavaClass resolveClass(int id) {
        State s = state;
        JavaClass[] arr = s.idToClass;
        if (id < 0 || id >= arr.length) {
            return null;
        }
        return arr[id];
    }

    public static void invalidate(JavaClass cls) {
        if (!ENABLED || cls == null) {
            return;
        }
        State s = state;
        s.trigramBuilt.remove(cls);
        int id = s.removeId(cls);
        if (id < 0) {
            return;
        }
        for (BitSet bs : s.trigramIndex.values()) {
            synchronized (bs) {
                bs.clear(id);
            }
        }
        logger.debug("[JAI] CodeContentIndex: invalidated class {}", cls.getFullName());
    }

    public static void clear() {
        if (!ENABLED) {
            return;
        }
        state = new State();
        logger.info("[JAI] CodeContentIndex: cleared");
    }

    public static boolean tryIndexFromCache(JavaClass cls, String codeStr) {
        if (!ENABLED || cls == null || codeStr == null || codeStr.isEmpty()) {
            return false;
        }
        State s = state;
        // Guard on trigram-built (NOT classToId): preAssignIds populates classToId for every
        // class, so a containsKey check here would reject all of them (the C10 bug).
        if (s.trigramBuilt.contains(cls)) {
            return false;
        }
        index(cls, codeStr.toLowerCase());
        return s.trigramBuilt.contains(cls);
    }

    public static int trigramCount() {
        return state.trigramIndex.size();
    }

    public static int indexedClassCount() {
        // Real count of classes that actually have trigrams built — NOT classToId.size(),
        // which is inflated to the full class count by preAssignIds().
        return state.trigramBuilt.size();
    }

    public static boolean isIndexed(JavaClass cls) {
        if (!ENABLED || cls == null) {
            return false;
        }
        return state.trigramBuilt.contains(cls);
    }

    public static long evictionsTotal() {
        return totalEvictions.get();
    }

    public static java.util.Map<String, Object> getStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("enabled", ENABLED);
        int indexedClasses = indexedClassCount();
        int trigramCnt = trigramCount();
        stats.put("indexed_classes", indexedClasses);
        // Denominator for coverage: the FULL class space indexing can ever range over — warmup's
        // app-scope target count plus the library classes it deliberately skips at warmup time
        // (both exposed by WarmupManager.getStatus()). It must include the skipped libraries too:
        // a content search self-heals ANY class it touches, including previously-skipped library
        // ones (see the H6 fix in SearchRoutes), so indexedClasses is not bounded by the narrower
        // app-only target — comparing it against that narrower scope let indexed_pct balloon past
        // 100% once self-healing crossed into library classes. Falls back to the live
        // id-assignment count only if warmup hasn't reported a total yet (e.g. before first warmup).
        Map<String, Object> warmupStatus = WarmupManager.getStatus();
        Object warmupTotal = warmupStatus.get("total");
        Object warmupSkipped = warmupStatus.get("skipped_libraries");
        int totalClasses = (warmupTotal instanceof Integer && warmupSkipped instanceof Integer
                && (Integer) warmupTotal > 0)
            ? (Integer) warmupTotal + (Integer) warmupSkipped
            : state.classCount.get();
        stats.put("total_classes", totalClasses);
        double indexedPct = totalClasses > 0 ? (double) indexedClasses / totalClasses * 100.0 : 0.0;
        stats.put("indexed_pct", Math.round(indexedPct * 100.0) / 100.0);
        stats.put("trigram_count", trigramCnt);
        stats.put("max_trigrams", MAX_TRIGRAMS);
        stats.put("max_class_size_bytes", MAX_CLASS_SIZE_BYTES);
        // BitSet bit positions span all assigned ids (preAssignIds seeds every class), so the
        // per-entry byte estimate is driven by the id width, not the indexed-class count.
        int idWidth = state.classCount.get();
        long bitsetBytesPerEntry = (long) Math.ceil((double) Math.max(idWidth, 1) / 8.0);
        long estimatedBytes = (long) trigramCnt * (40 + bitsetBytesPerEntry);
        stats.put("estimated_memory_mb", (int) (estimatedBytes / (1024 * 1024)));
        double saturation = MAX_TRIGRAMS > 0 ? (double) trigramCnt / MAX_TRIGRAMS : 0.0;
        stats.put("saturation_percent", Math.round(saturation * 10000.0) / 100.0);
        stats.put("evictions_total", totalEvictions.get());
        stats.put("eviction_enabled", EVICTION_ENABLED);
        stats.put("heap_pressure_high", HEAP_PRESSURE_HIGH);
        stats.put("heap_pressure_low", HEAP_PRESSURE_LOW);
        return stats;
    }

    /** Instance method for PersistentIndexStore compatibility (returns live map). */
    public Map<String, BitSet> getIndexMap() {
        return state.trigramIndex;
    }

    /**
     * Pre-assigns class IDs in the given (sorted) order so that IDs are deterministic
     * across JVM restarts for the same APK.  Call this before Phase-2 warmup AND before
     * {@link #bulkRestore} so that bit positions in stored/loaded BitSets always map to
     * the same classes.
     */
    public static void preAssignIds(List<JavaClass> sortedClasses) {
        if (!ENABLED) return;
        State s = state;
        for (JavaClass cls : sortedClasses) {
            s.assignId(cls);
        }
    }

    /**
     * Bulk-restores the trigram → BitSet map loaded from a {@link PersistentIndexStore}.
     * Must be called AFTER {@link #preAssignIds} so that the bit positions in the restored
     * BitSets correctly refer to the current session's class-ID assignments.
     */
    public static void bulkRestore(Map<String, BitSet> loadedData) {
        if (!ENABLED || loadedData == null || loadedData.isEmpty()) return;
        State s = state;
        s.trigramIndex.putAll(loadedData);
        if (loadedData.size() >= MAX_TRIGRAMS) {
            s.capReached.set(true);
        }
        logger.info("[JAI] CodeContentIndex: bulk-restored {} trigrams from persistent store",
                loadedData.size());
    }

    // -------------------------------------------------------------------------
    // Heap watcher and eviction
    // -------------------------------------------------------------------------

    static void heapWatcherTick() {
        try {
            Runtime rt = Runtime.getRuntime();
            double pressure = heapPressure(rt);
            if (pressure > HEAP_PRESSURE_HIGH) {
                runEvictionSweep(pressure);
            }
        } catch (Exception e) {
            logger.warn("[JAI] CodeContentIndex: heap-watcher tick failed: {}", e.getMessage());
        }
    }

    static double heapPressure(Runtime rt) {
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        if (max <= 0) return 0.0;
        return (double) used / max;
    }

    static void runEvictionSweep(double initialPressure) {
        State currentState = state;
        ConcurrentHashMap<String, BitSet> index = currentState.trigramIndex;

        int countBefore = index.size();
        if (countBefore == 0) {
            return;
        }

        int targetCount = (int) (countBefore * (HEAP_PRESSURE_LOW / HEAP_PRESSURE_HIGH));

        List<Map.Entry<String, Integer>> candidates = new ArrayList<>(countBefore);
        for (Map.Entry<String, BitSet> entry : index.entrySet()) {
            if (state != currentState) {
                return;
            }
            int cardinality;
            synchronized (entry.getValue()) {
                cardinality = entry.getValue().cardinality();
            }
            candidates.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), cardinality));
        }

        candidates.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        int evicted = 0;
        for (Map.Entry<String, Integer> candidate : candidates) {
            if (state != currentState) {
                break;
            }
            if (index.size() <= targetCount) {
                break;
            }
            index.remove(candidate.getKey());
            evicted++;
        }

        if (evicted > 0) {
            totalEvictions.addAndGet(evicted);
            currentState.capReached.set(false);
            // Interim correctness fix: eviction removes trigram postings but has no way to
            // know which classes' postings were affected, so any of them could now be
            // incomplete. Clearing trigramBuilt drops all classes back to "unindexed" so the
            // H6 guard in SearchRoutes content-scans them instead of wrongly treating them as
            // fully searched. This is sound but coarser/slower under heap pressure; Step2's
            // mmap-backed index removes eviction entirely, making this moot.
            currentState.trigramBuilt.clear();
            logger.info("[JAI] CodeContentIndex: eviction sweep complete — evicted={}", evicted);
        }
    }

    // -------------------------------------------------------------------------
    // Internal state container
    // -------------------------------------------------------------------------

    static final class State {
        static final int ALREADY_INDEXED = -1;

        final ConcurrentHashMap<String, BitSet> trigramIndex = new ConcurrentHashMap<>();
        final ConcurrentHashMap<JavaClass, Integer> classToId = new ConcurrentHashMap<>();
        // Classes whose trigrams have actually been built. Decoupled from classToId because
        // preAssignIds() seeds ids for every class up-front (for stable BitSet positions).
        final java.util.Set<JavaClass> trigramBuilt = ConcurrentHashMap.newKeySet();
        volatile JavaClass[] idToClass = new JavaClass[256];
        final AtomicInteger nextId = new AtomicInteger(0);
        final AtomicInteger classCount = new AtomicInteger(0);
        final AtomicBoolean capReached = new AtomicBoolean(false);
        final StampedLock idLock = new StampedLock();

        int assignId(JavaClass cls) {
            if (classToId.containsKey(cls)) {
                return ALREADY_INDEXED;
            }

            int id = nextId.getAndIncrement();
            Integer prev = classToId.putIfAbsent(cls, id);
            if (prev != null) {
                nextId.decrementAndGet();
                return ALREADY_INDEXED;
            }

            long stamp = idLock.writeLock();
            try {
                if (id >= idToClass.length) {
                    int newLen = Math.max(idToClass.length * 2, id + 1);
                    JavaClass[] newArr = new JavaClass[newLen];
                    System.arraycopy(idToClass, 0, newArr, 0, idToClass.length);
                    idToClass = newArr;
                }
                idToClass[id] = cls;
            } finally {
                idLock.unlockWrite(stamp);
            }

            classCount.incrementAndGet();
            return id;
        }

        /** Returns the class's id, assigning one if absent. Never returns ALREADY_INDEXED. */
        int getOrAssignId(JavaClass cls) {
            Integer existing = classToId.get(cls);
            if (existing != null) {
                return existing;
            }
            assignId(cls);
            Integer id = classToId.get(cls);
            return id != null ? id : -1;
        }

        int removeId(JavaClass cls) {
            Integer id = classToId.remove(cls);
            if (id == null) {
                return -1;
            }
            long stamp = idLock.writeLock();
            try {
                if (id < idToClass.length) {
                    idToClass[id] = null;
                }
            } finally {
                idLock.unlockWrite(stamp);
            }
            classCount.decrementAndGet();
            return id;
        }
    }
}
