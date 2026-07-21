package com.zin.delamain.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime memory configuration for warmup and GC pressure management.
 *
 * <p>All thresholds are computed dynamically from the JVM heap size at first access so the system
 * self-tunes on small-memory machines (e.g. 4 GB) without needing environment-variable overrides.
 * Every field is volatile and can be updated at runtime via the MCP {@code update_memory_config}
 * tool without restarting the server.</p>
 *
 * <h2>Dynamic defaults (by max-heap tier)</h2>
 * <pre>
 *   Heap       pressureHigh  safetyMargin  gcThrottleMs  phase4Fraction
 *    &lt; 4 GB      0.70          0.50          30 000         0.25
 *    4–8 GB      0.75          0.55          20 000         0.25
 *    8–16 GB     0.80          0.60          15 000         0.25
 *    16–32 GB    0.84          0.65          15 000         0.25
 *    &ge; 32 GB  0.88          0.70          15 000         0.25
 * </pre>
 */
public final class MemoryConfig {

    private static final Logger logger = LoggerFactory.getLogger(MemoryConfig.class);

    private static final MemoryConfig INSTANCE = new MemoryConfig();

    public static MemoryConfig get() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Computed fields (volatile for lock-free reads from multiple threads)
    // -------------------------------------------------------------------------

    /** Fraction of max-heap above which the warmup pressure handler fires. */
    private volatile double pressureHighThreshold;

    /** Safety factor in computeWarmupWorkers: usable = (heap - base) × safetyMargin. */
    private volatile double safetyMargin;

    /** Estimated transient heap per concurrent decompile worker (MB). */
    private volatile long perWorkerHeapMB;

    /** Minimum ms between explicit System.gc() calls across all workers. */
    private volatile long gcThrottleMs;

    /** Base sleep per pressure-handler invocation (ms). Workers add 0–400 ms jitter on top. */
    private volatile long pressureSleepBaseMs;

    /** Phase-4 harvest workers = max(1, floor(phase1Workers × phase4Fraction)). */
    private volatile double phase4Fraction;

    /** Source-of-truth for all values — 0=auto-computed, 1=user-overridden. */
    private volatile boolean overridden = false;

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    private MemoryConfig() {
        applyDefaults(Runtime.getRuntime().maxMemory());
        logger.info("[JAI] MemoryConfig initialised: pressureHigh={}, safetyMargin={}, "
                + "perWorkerMB={}, gcThrottleMs={}, pressureSleepMs={}, phase4Fraction={}",
            pressureHighThreshold, safetyMargin, perWorkerHeapMB,
            gcThrottleMs, pressureSleepBaseMs, phase4Fraction);
    }

    private void applyDefaults(long maxBytes) {
        long maxMB = maxBytes / (1024L * 1024L);
        if (maxMB < 4096) {
            pressureHighThreshold = 0.70;
            safetyMargin          = 0.50;
            gcThrottleMs          = 30_000L;
        } else if (maxMB < 8192) {
            pressureHighThreshold = 0.75;
            safetyMargin          = 0.55;
            gcThrottleMs          = 20_000L;
        } else if (maxMB < 16384) {
            pressureHighThreshold = 0.80;
            safetyMargin          = 0.60;
            gcThrottleMs          = 15_000L;
        } else if (maxMB < 32768) {
            pressureHighThreshold = 0.84;
            safetyMargin          = 0.65;
            gcThrottleMs          = 15_000L;
        } else {
            pressureHighThreshold = 0.88;
            safetyMargin          = 0.70;
            gcThrottleMs          = 15_000L;
        }
        perWorkerHeapMB    = 1536L;
        pressureSleepBaseMs = 500L;
        phase4Fraction     = 0.25;
        overridden         = false;
    }

    // -------------------------------------------------------------------------
    // Getters (hot path — all volatile reads, no locking)
    // -------------------------------------------------------------------------

    public double getPressureHighThreshold() { return pressureHighThreshold; }
    public double getSafetyMargin()          { return safetyMargin; }
    public long   getPerWorkerHeapMB()       { return perWorkerHeapMB; }
    public long   getGcThrottleMs()          { return gcThrottleMs; }
    public long   getPressureSleepBaseMs()   { return pressureSleepBaseMs; }

    /** Phase-4 worker count derived from phase-1 worker count. */
    public int computePhase4Workers(int phase1Workers) {
        return Math.max(1, (int) Math.floor(phase1Workers * phase4Fraction));
    }

    /** Current free heap in MB (maxMemory − usedMemory). */
    public long freeHeapMB() {
        Runtime rt = Runtime.getRuntime();
        return (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024L * 1024L);
    }

    /** Current heap usage as a fraction in [0, 1]. */
    public double heapUsageFraction() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return rt.maxMemory() > 0 ? (double) used / rt.maxMemory() : 1.0;
    }

    /** Estimated heap for the trigram index in MB (~30 KB BitSet per class). */
    public long estimateTrigramMB(int classCount) {
        return (long) classCount * 30L / 1024L;
    }

    /**
     * Whether current heap has enough headroom to start a background trigram build.
     * Returns false when free heap &lt; 2× estimated trigram cost; the build would likely
     * cause severe GC thrash or OOM.
     */
    public boolean canAffordTrigramBuild(int classCount) {
        return freeHeapMB() >= estimateTrigramMB(classCount) * 2;
    }

    /**
     * Whether current heap can afford starting the use-places harvest.
     * Returns false when usage is already at or above the pressure threshold; adding
     * re-decompile or text-search overhead would push it over the edge.
     */
    public boolean canAffordUsePlacesHarvest() {
        return heapUsageFraction() < pressureHighThreshold;
    }

    // -------------------------------------------------------------------------
    // Update (MCP / HTTP — accepts partial updates)
    // -------------------------------------------------------------------------

    /**
     * Apply a partial update map. Only recognised keys are applied; unrecognised keys are ignored.
     * Returns a result map summarising what changed.
     */
    public synchronized Map<String, Object> update(Map<String, Object> params) {
        Map<String, Object> changed = new LinkedHashMap<>();
        Map<String, String> errors  = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : params.entrySet()) {
            try {
                switch (e.getKey()) {
                    case "pressure_high_threshold" -> {
                        double v = toDouble(e.getValue());
                        if (v <= 0.40 || v > 0.98) { errors.put(e.getKey(), "must be (0.40, 0.98]"); break; }
                        double old = pressureHighThreshold; pressureHighThreshold = v;
                        changed.put(e.getKey(), Map.of("old", old, "new", v));
                    }
                    case "safety_margin" -> {
                        double v = toDouble(e.getValue());
                        if (v < 0.20 || v > 0.90) { errors.put(e.getKey(), "must be [0.20, 0.90]"); break; }
                        double old = safetyMargin; safetyMargin = v;
                        changed.put(e.getKey(), Map.of("old", old, "new", v));
                    }
                    case "per_worker_heap_mb" -> {
                        long v = toLong(e.getValue());
                        if (v < 256 || v > 8192) { errors.put(e.getKey(), "must be [256, 8192]"); break; }
                        long old = perWorkerHeapMB; perWorkerHeapMB = v;
                        changed.put(e.getKey(), Map.of("old", old, "new", v));
                    }
                    case "gc_throttle_ms" -> {
                        long v = toLong(e.getValue());
                        if (v < 1000 || v > 300_000) { errors.put(e.getKey(), "must be [1000, 300000]"); break; }
                        long old = gcThrottleMs; gcThrottleMs = v;
                        changed.put(e.getKey(), Map.of("old", old, "new", v));
                    }
                    case "pressure_sleep_base_ms" -> {
                        long v = toLong(e.getValue());
                        if (v < 100 || v > 10_000) { errors.put(e.getKey(), "must be [100, 10000]"); break; }
                        long old = pressureSleepBaseMs; pressureSleepBaseMs = v;
                        changed.put(e.getKey(), Map.of("old", old, "new", v));
                    }
                    case "phase4_fraction" -> {
                        double v = toDouble(e.getValue());
                        if (v < 0.10 || v > 1.0) { errors.put(e.getKey(), "must be [0.10, 1.0]"); break; }
                        double old = phase4Fraction; phase4Fraction = v;
                        changed.put(e.getKey(), Map.of("old", old, "new", v));
                    }
                    case "reset_to_defaults" -> {
                        if (Boolean.TRUE.equals(e.getValue()) || "true".equals(e.getValue())) {
                            applyDefaults(Runtime.getRuntime().maxMemory());
                            changed.put("reset_to_defaults", true);
                        }
                    }
                    default -> errors.put(e.getKey(), "unknown field");
                }
            } catch (Exception ex) {
                errors.put(e.getKey(), "invalid value: " + ex.getMessage());
            }
        }

        if (!changed.isEmpty()) {
            // reset_to_defaults calls applyDefaults() which sets overridden=false; don't override that
            if (!changed.containsKey("reset_to_defaults")) overridden = true;
            logger.info("[JAI] MemoryConfig updated: {}", changed);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changed", changed);
        if (!errors.isEmpty()) result.put("errors", errors);
        result.put("config", snapshot());
        return result;
    }

    // -------------------------------------------------------------------------
    // Snapshot for GET / logging
    // -------------------------------------------------------------------------

    public Map<String, Object> snapshot() {
        Runtime rt = Runtime.getRuntime();
        long usedBytes  = rt.totalMemory() - rt.freeMemory();
        long maxBytes   = rt.maxMemory();
        long maxMB      = maxBytes / (1024L * 1024L);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pressure_high_threshold",  pressureHighThreshold);
        m.put("safety_margin",            safetyMargin);
        m.put("per_worker_heap_mb",       perWorkerHeapMB);
        m.put("gc_throttle_ms",           gcThrottleMs);
        m.put("pressure_sleep_base_ms",   pressureSleepBaseMs);
        m.put("phase4_fraction",          phase4Fraction);
        m.put("overridden",               overridden);
        m.put("heap_max_mb",              maxMB);
        m.put("heap_used_mb",             usedBytes / (1024L * 1024L));
        m.put("heap_used_pct",            maxBytes > 0
            ? Math.round(100.0 * usedBytes / maxBytes * 10.0) / 10.0 : 0.0);
        m.put("pressure_active",          maxBytes > 0
            && (double) usedBytes / maxBytes > pressureHighThreshold);
        m.put("note", overridden
            ? "values manually overridden — call update_memory_config with reset_to_defaults=true to revert"
            : "values auto-computed from heap size at startup");
        return m;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        return Double.parseDouble(v.toString());
    }

    private static long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        return Long.parseLong(v.toString());
    }
}
