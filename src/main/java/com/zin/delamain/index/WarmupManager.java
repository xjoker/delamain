package com.zin.delamain.index;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.shard.ContentShardBuilder;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.index.shard.ShardCatalog;
import com.zin.delamain.utils.AppVersion;
import com.zin.delamain.utils.ClassCacheManager;

import jadx.api.JavaClass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Background full-cache warmup manager.
 *
 * <p>Two-phase warmup:
 * <ol>
 *   <li>Phase 1 — parallel decompile using up to {@code DELAMAIN_WARMUP_DECOMPILE_WORKERS}
 *       threads (default 8). No {@link com.zin.delamain.utils.JadxSearchLock} held:
 *       JADX handles its own per-class concurrency internally, so searches can run freely
 *       during warmup.</li>
 *   <li>Phase 2 — parallel trigram index fill from the already-populated JADX code cache.
 *       After Phase 2 completes, the index is persisted via PersistentIndexStore.</li>
 * </ol>
 *
 * <p>Only one warmup may run at a time. Starting while one is in progress returns
 * the current status without restarting.</p>
 */
public class WarmupManager {
    private static final Logger logger = LoggerFactory.getLogger(WarmupManager.class);

    private static final String[] LIBRARY_PREFIXES = {
        "android.", "androidx.", "com.google.", "kotlin.", "kotlinx.",
        "okhttp3.", "okio.", "retrofit2.", "com.squareup.", "io.reactivex.",
        "dagger.", "javax.", "com.facebook.", "com.amazonaws.", "org.apache.",
        "org.json.", "com.fasterxml.", "io.netty.", "com.bumptech.glide.",
        "org.greenrobot.", "com.airbnb.", "com.unity3d.", "io.flutter.",
        "com.tencent.", "com.alibaba.", "com.umeng.", "com.adjust."
    };

    // Explicit override for Phase-1 decompile worker count (DELAMAIN_WARMUP_DECOMPILE_WORKERS).
    // -1 ⇒ unset ⇒ auto-size adaptively from heap / cores / class count (see computeWarmupWorkers).
    private static final int DECOMPILE_WORKERS_OVERRIDE;
    // Phase-2 index worker count — configurable via DELAMAIN_WARMUP_INDEX_WORKERS
    private static final int INDEX_WORKERS;
    // Effective decompile workers for the current warmup, resolved once class count is known.
    private static volatile int effectiveDecompileWorkers = 8;

    /** Per-window flush budget for the mmap shard builder; DELAMAIN_SHARD_BUDGET_MB overrides (default 128MB). */
    private static final long SHARD_BUDGET_BYTES;

    static {
        int override = -1;
        String raw = System.getenv("DELAMAIN_WARMUP_DECOMPILE_WORKERS");
        if (raw != null && !raw.isEmpty()) {
            try { override = Math.max(1, Integer.parseInt(raw.trim())); } catch (NumberFormatException ignored) {}
        }
        DECOMPILE_WORKERS_OVERRIDE = override;

        int workers = 4;
        raw = System.getenv("DELAMAIN_WARMUP_INDEX_WORKERS");
        if (raw != null && !raw.isEmpty()) {
            try { workers = Math.max(1, Integer.parseInt(raw.trim())); } catch (NumberFormatException ignored) {}
        }
        INDEX_WORKERS = workers;

        long shardBudgetMB = 128;
        raw = System.getenv("DELAMAIN_SHARD_BUDGET_MB");
        if (raw != null && !raw.isEmpty()) {
            try { shardBudgetMB = Math.max(1, Long.parseLong(raw.trim())); } catch (NumberFormatException ignored) {}
        }
        SHARD_BUDGET_BYTES = shardBudgetMB * 1024L * 1024L;
    }

    /**
     * Auto-size the Phase-1 decompile worker count so warmup completes without OOM/GC-thrash on any
     * machine. Phase-1 is memory-bound, not CPU-bound: each concurrent decompile holds transient
     * IR, and the persistent indices (trigram / usage graph / CodeStore) take a base slice of heap
     * that grows with the class count. Empirically on this codebase, 24 workers on a 38 GB heap with
     * 237 931 classes thrashed (peak ≈ heap limit, ~2× slower); 8 workers stayed ~31 GB and finished.
     *
     * <p>workers = min( (maxHeap − base(classCount)) × 0.70 / perWorkerHeap , cores − 2 , 32 ),
     * floored at 1. Calibrated so the validated 38 GB / 237 931-class case yields 8, low-memory
     * machines drop to 1–2 (slow but safe), and large heaps scale up. An explicit env override wins.
     * The reactive {@link #isHighMemoryPressure()} pause remains the safety net.</p>
     */
    static int computeWarmupWorkers(int classCount) {
        if (DECOMPILE_WORKERS_OVERRIDE > 0) return DECOMPILE_WORKERS_OVERRIDE;
        int cores = Runtime.getRuntime().availableProcessors();
        long maxHeapMB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        long baseMB = 1024L + (long) classCount / 1000L * 80L;       // ~80 MB / 1k classes + 1 GB floor
        MemoryConfig mc = MemoryConfig.get();
        long usableMB = (long) ((maxHeapMB - baseMB) * mc.getSafetyMargin());
        int byHeap = (int) Math.max(1L, usableMB / mc.getPerWorkerHeapMB());
        int byCores = Math.max(1, cores - 2);                        // leave headroom for GC/IO/serving
        int workers = Math.max(1, Math.min(Math.min(byHeap, byCores), 32));
        logger.info("[JAI] Auto-sized decompile workers={} (cores={}, maxHeap={}MB, classes={}, base={}MB, byHeap={}, byCores={})",
                workers, cores, maxHeapMB, classCount, baseMB, byHeap, byCores);
        return workers;
    }

    // -------------------------------------------------------------------------
    // Warmup state — readable via getStatus() at any time
    // -------------------------------------------------------------------------

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private static final AtomicInteger total = new AtomicInteger(0);
    private static final AtomicInteger processed = new AtomicInteger(0);
    private static final AtomicInteger failed = new AtomicInteger(0);
    private static final AtomicInteger skipped = new AtomicInteger(0);
    private static final AtomicLong startTime = new AtomicLong(0);
    private static final AtomicLong endTime = new AtomicLong(0);
    private static final AtomicReference<String> phase = new AtomicReference<>("IDLE");
    private static final Object startLock = new Object();

    // Rate-limit the explicit GC hint inside the pressure handler. Threshold is read from
    // MemoryConfig at each check (volatile read) so runtime updates take effect immediately.
    private static final AtomicLong lastGcCallMs = new AtomicLong(0);

    // Persistent index store — lazily initialised
    private static volatile PersistentIndexStore persistentStore = null;
    private static volatile UsageGraphStore usageGraphStore = null;
    private static volatile UsePlacesStore usePlacesStore = null;
    private static volatile CodeStore codeStore = null;
    private static volatile HeadlessJadxWrapper lastWrapper = null;

    /** Current decompiled-source store (disk-backed), or null before warmup. */
    public static CodeStore codeStore() {
        return codeStore;
    }

    // Directory where persistent indices (trigram + usage graph) are written.
    // Defaults to ~/.delamain/index-cache but should be set by Main to the configured
    // --index-dir so the cache lands on a mounted volume and survives container recreation.
    private static volatile java.nio.file.Path configuredIndexDir =
        java.nio.file.Paths.get(System.getProperty("user.home"), ".delamain", "index-cache");

    /** Sets the directory for persistent indices. Call once at startup (from Main). */
    public static void setIndexDir(java.nio.file.Path dir) {
        if (dir != null) {
            configuredIndexDir = dir;
            logger.info("[JAI] WarmupManager persistent index dir set to {}", dir);
        }
    }

    private static java.nio.file.Path indexCacheDir() {
        return configuredIndexDir;
    }

    /** Directory currently used for persistent indices — exposed for /index-stats manifest lookup. */
    public static java.nio.file.Path getIndexDir() {
        return configuredIndexDir;
    }

    // inputHash of the currently loaded APK, once computed during warmup — exposed for
    // /index-stats to locate/verify the prebaked-index manifest (see startBackgroundShardBuild).
    private static volatile String currentInputHash = null;

    /** inputHash of the currently loaded APK, or null before it has been computed. */
    public static String getCurrentInputHash() {
        return currentInputHash;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Start a full-cache warmup in the background.
     *
     * <p>Returns immediately. If a warmup is already running, returns the current
     * status without starting a new one.</p>
     *
     * @param wrapper       JADX wrapper for the loaded file
     * @param skipLibraries when true, well-known third-party SDK packages are skipped
     * @return result map — keys: started (boolean), message, total_classes, skipped_libraries
     */
    public static Map<String, Object> start(HeadlessJadxWrapper wrapper, boolean skipLibraries) {
        synchronized (startLock) {
            if (running.get()) {
                return Map.of(
                    "started", false,
                    "message", "Warmup already in progress",
                    "status", getStatus()
                );
            }

            List<JavaClass> allClasses;
            try {
                allClasses = wrapper.getClassesWithInners();
            } catch (Exception e) {
                return Map.of(
                    "started", false,
                    "error", "Failed to get class list: " + e.getMessage()
                );
            }

            List<JavaClass> targets = new ArrayList<>();
            int skippedCount = 0;
            for (JavaClass cls : allClasses) {
                if (skipLibraries && isLibraryClass(cls.getFullName())) {
                    skippedCount++;
                } else {
                    targets.add(cls);
                }
            }

            // Reset counters
            total.set(targets.size());
            processed.set(0);
            failed.set(0);
            skipped.set(skippedCount);
            startTime.set(System.currentTimeMillis());
            endTime.set(0);
            cancelRequested.set(false);
            running.set(true);
            phase.set("PHASE1_DECOMPILE");
            lastWrapper = wrapper;

            logger.info("[JAI] Full warmup started: {} targets, {} library classes skipped",
                targets.size(), skippedCount);

            Thread warmupThread = new Thread(() -> runWarmup(wrapper, targets), "jadx-full-warmup");
            warmupThread.setDaemon(true);
            warmupThread.start();

            return Map.of(
                "started", true,
                "total_classes", targets.size(),
                "skipped_libraries", skippedCount,
                "message", "Full cache warmup started in background. "
                    + "Poll get_decompile_status or /cache/warmup-status for progress."
            );
        }
    }

    /** Signal the running warmup to stop at the next class boundary. */
    public static void cancel() {
        cancelRequested.set(true);
        logger.info("[JAI] Full warmup cancel requested");
    }

    /**
     * Return a progress snapshot suitable for a JSON API response.
     *
     * <p>Fields: phase, running, total, processed, failed, skipped_libraries,
     * percentage, elapsed_seconds.</p>
     */
    public static Map<String, Object> getStatus() {
        int tot = total.get();
        int proc = processed.get();
        int fail = failed.get();
        int skip = skipped.get();
        String ph = phase.get();
        boolean isRunning = running.get();
        long start = startTime.get();
        long end = endTime.get();

        long elapsedSec = isRunning
            ? (start > 0 ? (System.currentTimeMillis() - start) / 1000 : 0)
            : (start > 0 && end > 0 ? (end - start) / 1000 : 0);

        int percentage = tot > 0 ? (proc * 100 / tot) : 0;

        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("phase", ph);
        status.put("running", isRunning);
        status.put("total", tot);
        status.put("processed", proc);
        status.put("failed", fail);
        status.put("skipped_libraries", skip);
        status.put("percentage", percentage);
        status.put("elapsed_seconds", elapsedSec);
        status.put("trigram_build_running", trigramBuildRunning.get());
        status.put("trigram_count", CodeContentIndex.trigramCount());
        // Precise use-places (E2): readiness + one-time harvest progress (surfaced to MCP
        // get_warmup_status so the AI knows whether precise xref is instant or still warming).
        status.put("use_places_ready", UsePlacesIndex.isReady());
        status.put("use_places_harvest_running", UsePlacesIndex.isHarvesting());
        status.put("use_places_harvest_processed", UsePlacesIndex.harvestProcessed());
        status.put("use_places_harvest_total", UsePlacesIndex.harvestTotal());
        String tsReason = trigramSkipReason.get();
        if (tsReason != null) status.put("trigram_skip_reason", tsReason);
        String upReason = usePlacesSkipReason.get();
        if (upReason != null) status.put("use_places_skip_reason", upReason);

        // ---- Cold/hot-start observability: overall progress + ETA + per-capability readiness ----
        // The server serves the moment it is up; warmup opens capabilities progressively. This block
        // tells the AI what is usable NOW and roughly how long until the rest is ready.
        boolean coreWarming = isRunning && !"IDLE".equals(ph) && !"DONE".equals(ph)
                && !"CANCELLED".equals(ph) && !"ERROR".equals(ph);
        // Precise use-places harvest runs on a background thread AFTER core warmup reaches DONE
        // (it no longer gates the critical path). Surface it as "still warming" so the AI knows
        // precise xref-with-snippet isn't instant yet (it falls through to the live path meanwhile).
        boolean harvesting = UsePlacesIndex.isHarvesting();
        boolean warming = coreWarming || harvesting;
        Long eta = null;
        String etaBasis;
        double frac;
        if (coreWarming) {
            frac = computeOverallFraction(ph, proc, tot,
                    UsePlacesIndex.harvestProcessed(), UsePlacesIndex.harvestTotal());
            if (frac > 0.01) {
                eta = (long) Math.max(0, elapsedSec * (1.0 - frac) / frac);
                etaBasis = "FAST_RESTORE".equals(ph) ? "fast-restore-estimate" : "overall-progress-rate";
            } else { etaBasis = "starting"; }
        } else if (harvesting) {
            // Core warmup done; only the background use-places harvest remains. Map it to the last
            // 10% of overall progress and estimate its ETA from its own elapsed + processed/total.
            int hp = UsePlacesIndex.harvestProcessed(), ht = UsePlacesIndex.harvestTotal();
            double hfrac = ht > 0 ? Math.min(1.0, (double) hp / ht) : 0.0;
            frac = 0.90 + 0.10 * hfrac;
            long hStart = harvestStartMs.get();
            if (hStart > 0 && hfrac > 0.01) {
                long hElapsed = (System.currentTimeMillis() - hStart) / 1000;
                eta = (long) Math.max(0, hElapsed * (1.0 - hfrac) / hfrac);
            }
            etaBasis = "useplaces-harvest";
        } else {
            frac = "DONE".equals(ph) ? 1.0 : 0.0;
            if ("DONE".equals(ph)) { eta = 0L; etaBasis = "done"; } else { etaBasis = "idle"; }
        }
        status.put("warming_up", warming);
        status.put("overall_progress_pct", (int) Math.round(frac * 100));
        status.put("eta_seconds", eta);
        status.put("eta_basis", etaBasis);

        // Capability readiness — what the AI can use right now vs what is still warming.
        Map<String, String> caps = new java.util.LinkedHashMap<>();
        caps.put("metadata_search", "ready");   // names/structure: available as soon as the APK is loaded
        caps.put("class_source", "ready");       // served from CodeStore once warm; lazy live-decompile before that
        caps.put("smali", "ready");
        // live_decompile = the one-time JADX engine init is paid; smali / non-cached source are fast after.
        caps.put("live_decompile", isEngineInitDone() ? "ready" : "warming");
        String trigramSkip = trigramSkipReason.get();
        caps.put("code_search", CodeContentIndex.trigramCount() > 0 ? "ready"
            : (trigramSkip != null ? "skipped:low-heap" : "warming"));
        caps.put("xref_class_level", UsageGraphIndex.isReady() ? "ready" : "warming");
        String usePlacesSkip = usePlacesSkipReason.get();
        caps.put("precise_xref_snippets", UsePlacesIndex.isReady() ? "ready"
            : (usePlacesSkip != null ? "skipped:low-heap" : "warming"));
        status.put("capabilities", caps);
        return status;
    }

    /**
     * Best-effort overall warmup progress fraction (0..1) across all phases, weighted by the
     * measured cost share of each phase on a cold run (Phase-1 decompile ≈58%, trigram ≈4%,
     * graph ≈1%, use-places harvest ≈37%). Phases with a fine-grained counter (Phase-1 processed,
     * use-places harvest) interpolate; counter-less phases report their mid-point.
     */
    private static double computeOverallFraction(String phase, int processed, int total,
                                                 int harvestProcessed, int harvestTotal) {
        if (phase == null) return 0.0;
        final double W1 = 0.58, W2 = 0.04, W3 = 0.01; // W4 (use-places) = 0.37
        switch (phase) {
            case "ENGINE_INIT":    return 0.01;  // one-time JADX init, ~13s of a ~1000s cold warm
            case "PHASE1_DECOMPILE":
                return total > 0 ? W1 * Math.min(1.0, (double) processed / total) : 0.0;
            case "PHASE2_INDEX":   return W1 + W2 * 0.5;
            case "PHASE3_GRAPH":   return W1 + W2 + W3 * 0.5;
            case "PHASE4_USEPLACES":
                double base = W1 + W2 + W3;
                double hp = harvestTotal > 0 ? Math.min(1.0, (double) harvestProcessed / harvestTotal) : 0.0;
                return base + (1.0 - base) * hp;
            case "FAST_RESTORE":   return 0.5;  // quick path; refined by elapsed-based ETA
            case "DONE":           return 1.0;
            default:               return 0.0;  // IDLE / CANCELLED / ERROR
        }
    }

    // -------------------------------------------------------------------------
    // Internal — two-phase warmup loop
    // -------------------------------------------------------------------------

    private static void runWarmup(HeadlessJadxWrapper wrapper, List<JavaClass> targets) {
        try {
            // Deterministic order keyed by RAW name: stable class IDs for graph + trigram across
            // restarts AND independent of deobfuscation state (getFullName() returns aliases when
            // deobf is on, which would shuffle ids; getRawName() is immutable).
            List<JavaClass> sorted = new ArrayList<>(targets);
            sorted.sort(Comparator.comparing(JavaClass::getRawName));

            // Adaptively size decompile parallelism for this machine + APK so warmup finishes
            // without OOM/GC-thrash (low-spec hosts auto-limit to 1-2 workers; large heaps scale up).
            // Use the WITH-INNERS class count (top-level + nested, i.e. every JavaClass JADX knows
            // about) so the base-memory estimate reflects the full persistent-index footprint.
            // wrapper.getTotalClassCount() is top-level only (~138k for XHS), which underestimates
            // the base and lets the formula allocate too many workers (12 instead of 8), causing OOM.
            // sorted.size() is the filtered target list (~222k), also slightly under the full
            // 237k-with-inners count — that 15k delta was enough to shift byHeap from 8 → 9 on the
            // reference benchmark machine, which also OOMed. getClassesWithInners() is cached O(1).
            effectiveDecompileWorkers = computeWarmupWorkers(wrapper.getClassesWithInners().size());

            // Eager JADX engine init: the FIRST live decompile pays a large one-time cost
            // (~13s on XHS — global type inference + cross-ref graph build). Subsequent classes
            // are ms-level. Pay it now, up-front, in this background thread so the AI's first real
            // class-source / smali / live-decompile request never eats it. Critical for the
            // FAST_RESTORE (hot) path, which serves source from CodeStore and would otherwise never
            // pay the init until the first smali / non-cached op surprises a caller with ~13s.
            if (!cancelRequested.get() && !sorted.isEmpty()) {
                phase.set("ENGINE_INIT");
                long t0 = System.currentTimeMillis();
                try {
                    sorted.get(0).getCode();
                    engineInitDone.set(true);
                    logger.info("[JAI] JADX engine init paid up-front in {}ms (one-time first-decompile cost)",
                            System.currentTimeMillis() - t0);
                } catch (Exception e) {
                    logger.warn("[JAI] Eager JADX engine init failed: {}", e.getMessage());
                }
            }

            // Initialise persistent stores + compute input hash.
            String hash = null;
            try {
                ensureStores();
                hash = persistentStore.computeInputHash(new ArrayList<>(wrapper.getInputFiles()));
                currentInputHash = hash;
                codeStore = new CodeStore(indexCacheDir(), hash);
            } catch (Exception e) {
                logger.warn("[JAI] Warmup: persistent store init failed: {}", e.getMessage());
            }

            CodeContentIndex.preAssignIds(sorted);
            UsageGraphIndex.assignIds(sorted);
            UsePlacesIndex.assignIds(sorted);

            String newLineStr = wrapper.getArgs().getCodeNewLineStr();

            boolean graphRestored = (hash != null) && tryRestoreUsageGraph(hash, sorted.size());
            boolean codeReady = (codeStore != null) && codeStore.isComplete();

            if (graphRestored && codeReady && !cancelRequested.get()) {
                // FAST RESTART: every static artifact is on disk. Skip the multi-minute Phase-1
                // decompile entirely — source is served from CodeStore, xref from the graph,
                // and metadata search from the name indices built below. Per-class live decompile
                // still happens lazily on demand for anything not covered.
                phase.set("FAST_RESTORE");
                tryRestoreIndex(wrapper, sorted); // best-effort trigram restore (harmless if empty)
                // Precise use-places: restore from disk so /xrefs-to-class?include_snippet=true is
                // instant after restart. If absent (older build / first restart after this feature),
                // deep-warm harvests them at its tail once jadx state is hot again.
                boolean usePlacesRestored = (hash != null) && tryRestoreUsePlaces(hash, sorted.size());
                logger.info("[JAI] Warmup FAST RESTART: usage graph + code store present — "
                    + "skipped Phase-1 decompile and Phase-2 index build (use-places restored={})",
                    usePlacesRestored);
                // If no trigram index was restored (e.g. first restart after a fixed build, or a
                // prior cold warm that persisted an empty index), rebuild it in the background
                // from the CodeStore so code-content search works without a full Phase-1 re-run.
                if (CodeContentIndex.trigramCount() == 0 && codeStore != null && codeStore.isComplete()) {
                    startBackgroundTrigramBuild(wrapper, sorted);
                }
                // Dual-build: also build the mmap shard index from the CodeStore, in parallel with
                // (and independent of) the trigram gate above. This must NOT reuse the trigramCount
                // gate — a restored .idx makes trigramCount!=0 while the shard catalog is still
                // absent, so we gate solely on the catalog. Queries do not yet consult the shard
                // layer (Wave B), so this is additive and search-behavior-neutral.
                if (hash != null && codeStore != null && codeStore.isComplete()) {
                    try {
                        ContentShardIndex.loadCatalog(indexCacheDir(), hash);
                    } catch (Exception e) {
                        logger.warn("[JAI] Shard catalog load failed: {}", e.getMessage());
                    }
                    if (!ContentShardIndex.isBuilt()) {
                        startBackgroundShardBuild(wrapper, sorted, hash);
                    }
                }
                // The server is usable NOW (xref←graph, source←CodeStore, search←names). If the
                // precise use-places store was missing (first restart after this feature / older
                // build), harvest+persist it in the background so /xrefs-to-class?include_snippet
                // becomes instant after the next restart. The harvest is memory-bounded (each
                // referrer is decompiled once then unloaded), so it never balloons the heap — this
                // replaces the old full deep-warm, which retained all decompiled state and throttled
                // under memory pressure. Other live paths (heavy smali, method/field precise) simply
                // decompile on demand.
                if (!usePlacesRestored) {
                    startBackgroundUsePlacesHarvest(sorted, newLineStr, hash);
                }
            } else {
                // COLD: full decompile (write-through to CodeStore) + indices + graph.
                // Restore the phase label after ENGINE_INIT (which overwrote it) so progress/ETA
                // reflect Phase-1 instead of staying pinned at the ENGINE_INIT weight.
                phase.set("PHASE1_DECOMPILE");
                runPhase1(targets);

                if (!cancelRequested.get()) {
                    // One-shot GC hint at the Phase-1/Phase-2 boundary. Phase-1 may leave
                    // JADX's internal decompile IR (ClassNode state, intermediate AST) in heap.
                    // Phase-2 reads exclusively from the disk-backed CodeStore and needs none of
                    // that state. Nudging the JVM here — once, at a natural boundary, not per-class —
                    // gives it a window to reclaim the Phase-1 transient heap before Phase-2 starts
                    // allocating trigram BitSets, reducing the peak overlap between the two phases.
                    System.gc();
                    phase.set("PHASE2_INDEX");
                    boolean restoredFromCache = tryRestoreIndex(wrapper, sorted);
                    if (!restoredFromCache) {
                        runPhase2(targets);
                        if (!cancelRequested.get()) tryPersistIndex(wrapper);
                    }
                }

                if (!cancelRequested.get() && !graphRestored) {
                    phase.set("PHASE3_GRAPH");
                    UsageGraphIndex.build(sorted);
                    if (hash != null && usageGraphStore != null) {
                        try {
                            usageGraphStore.save(UsageGraphIndex.snapshotAdjacency(), hash);
                            logger.info("[JAI] Warmup: usage graph persisted (hash={}...)", hash.substring(0, 8));
                        } catch (Exception e) {
                            logger.warn("[JAI] Warmup: usage graph save failed: {}", e.getMessage());
                        }
                    }
                }

                // CodeStore is complete after Phase-1 (write-through) — mark it now, independent of
                // the use-places harvest, so a restart can FAST_RESTORE immediately.
                if (codeStore != null && !cancelRequested.get()) {
                    codeStore.markComplete(sorted.size());
                }

                // Cold-path shard build: mirror the FAST_RESTORE dual-build trigger (see above) so a
                // first-ever cold load also gets the mmap shard index, instead of waiting for the next
                // restart to hit FAST_RESTORE. Gated the same way: catalog-presence check + the
                // shardBuildRunning CAS inside startBackgroundShardBuild make this idempotent, so it is
                // safe even if a future code path also calls it for the same hash.
                if (hash != null && codeStore != null && codeStore.isComplete() && !cancelRequested.get()) {
                    try {
                        ContentShardIndex.loadCatalog(indexCacheDir(), hash);
                    } catch (Exception e) {
                        logger.warn("[JAI] Shard catalog load failed: {}", e.getMessage());
                    }
                    if (!ContentShardIndex.isBuilt()) {
                        startBackgroundShardBuild(wrapper, sorted, hash);
                    }
                }

                // Phase 4 — precise use-places harvest moved OFF the warmup critical path. Core
                // capabilities (class-source / metadata / code-search / class-level xref) are all
                // ready now; harvesting all 222k referrers up-front previously added ~336s (33% of
                // cold warmup). Run it on a low-priority background thread instead: precise
                // xref-with-snippet falls through to the (post-engine-init, now ms-level) live path
                // until the harvest completes + persists, after which it is instant and survives a
                // restart (E2 semantics preserved). Progress is visible via use_places_harvest_* +
                // the precise_xref_snippets capability.
                if (!cancelRequested.get()) {
                    startBackgroundUsePlacesHarvest(sorted, newLineStr, hash);
                }
            }

            // Name indices (class/method/field buckets) for metadata search — always; pure
            // metadata, no decompilation, cheap even on the fast-restart path.
            if (!cancelRequested.get()) {
                try {
                    ClassCacheManager.initCache(wrapper);
                    logger.info("[JAI] Triggered ClassCacheManager.initCache() after warmup");
                } catch (Exception e) {
                    logger.warn("[JAI] initCache after warmup failed: {}", e.getMessage());
                }
            }

            // Disk-cache LRU retention: mark this inputHash as just-used, then enforce the
            // configured quota (JADX_CACHE_MAX_GB) across ALL analyzed APKs' artifacts. Only runs
            // on a successful, non-cancelled completion — a cancelled/errored warmup's artifacts
            // may be incomplete and shouldn't be treated as "freshly used". currentInputHash (this
            // hash) is passed as the never-evict exclusion since it is the APK now loaded.
            if (!cancelRequested.get() && currentInputHash != null) {
                try {
                    IndexCacheManager.touchLastUsed(indexCacheDir(), currentInputHash);
                    IndexCacheManager.enforceQuota(indexCacheDir(), currentInputHash);
                } catch (Exception e) {
                    logger.warn("[JAI] Index cache quota enforcement failed: {}", e.getMessage());
                }
            }

            phase.set(cancelRequested.get() ? "CANCELLED" : "DONE");
        } catch (Exception e) {
            phase.set("ERROR");
            logger.warn("[JAI] Full warmup failed: {}", e.getMessage());
        } finally {
            endTime.set(System.currentTimeMillis());
            running.set(false);
            logger.info("[JAI] Full warmup finished — processed={}, failed={}, phase={}",
                processed.get(), failed.get(), phase.get());
        }
    }

    /** Lazily initialise the persistent trigram + usage-graph + use-places stores. */
    private static void ensureStores() throws IOException {
        if (persistentStore == null) persistentStore = new PersistentIndexStore(indexCacheDir());
        if (usageGraphStore == null) usageGraphStore = new UsageGraphStore(indexCacheDir());
        if (usePlacesStore == null) usePlacesStore = new UsePlacesStore(indexCacheDir());
    }

    private static final AtomicBoolean usePlacesHarvestRunning = new AtomicBoolean(false);
    private static final AtomicLong harvestStartMs = new AtomicLong(0);

    // Degradation skip reasons — set when an index phase is bypassed due to insufficient heap.
    // Exposed via getStatus() so callers know a capability is permanently degraded this session.
    private static final AtomicReference<String> trigramSkipReason = new AtomicReference<>(null);
    private static final AtomicReference<String> usePlacesSkipReason = new AtomicReference<>(null);

    /** True while the one-time precise use-places harvest is running in the background. */
    public static boolean isUsePlacesHarvestRunning() {
        return usePlacesHarvestRunning.get();
    }

    /**
     * Recycler used by the harvest: after a referrer is consumed, free its decompiled state so the
     * heap stays bounded — {@link JavaClass#unload()} drops the (heavy) processed IR, and the jadx
     * code-cache entry is evicted too. The trigram search index is NOT touched.
     */
    private static void recycle(JavaClass cls) {
        try { cls.unload(); } catch (Exception ignored) {}
        try { ClassCacheManager.evictCodeCacheEntry(cls); } catch (Exception ignored) {}
    }

    /**
     * Runs the memory-bounded, source-driven precise use-places harvest in a low-priority background
     * thread, then persists it. Replaces the old full deep-warm: instead of decompiling every class
     * and retaining it (which ballooned the heap and throttled under memory pressure), each referrer
     * is decompiled once and recycled immediately, so the heap stays bounded. Idempotent.
     */
    private static void startBackgroundUsePlacesHarvest(List<JavaClass> sorted, String newLineStr, String hash) {
        if (!usePlacesHarvestRunning.compareAndSet(false, true)) return;
        // Graceful degradation: skip harvest if heap is already at or above the pressure threshold.
        // Attempting the harvest would push a memory-constrained JVM over the edge.
        MemoryConfig mc = MemoryConfig.get();
        if (!mc.canAffordUsePlacesHarvest()) {
            String reason = String.format("heap at %.1f%% >= pressure threshold %.1f%%",
                mc.heapUsageFraction() * 100, mc.getPressureHighThreshold() * 100);
            logger.warn("[JAI] use-places harvest SKIPPED (low heap): {} — precise xref snippets "
                + "unavailable this session; restart on a larger-heap machine to enable", reason);
            usePlacesSkipReason.set(reason);
            usePlacesHarvestRunning.set(false);
            return;
        }
        usePlacesSkipReason.set(null);
        harvestStartMs.set(System.currentTimeMillis());
        Thread t = new Thread(() -> {
            try {
                logger.info("[JAI] Background use-places harvest started: {} classes (memory-bounded, source-driven)",
                    sorted.size());
                harvestAndPersistUsePlaces(sorted, newLineStr, hash);
            } finally {
                usePlacesHarvestRunning.set(false);
            }
        }, "jadx-useplaces-harvest-bg");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Loads the persisted precise use-places into {@link UsePlacesIndex} (ids already assigned). */
    private static boolean tryRestoreUsePlaces(String hash, int classCount) {
        try {
            if (usePlacesStore == null) usePlacesStore = new UsePlacesStore(indexCacheDir());
            int[][] loaded = usePlacesStore.tryLoad(hash, classCount);
            return loaded != null && UsePlacesIndex.bulkRestore(loaded);
        } catch (Exception e) {
            logger.warn("[JAI] Warmup: use-places restore failed: {}", e.getMessage());
            return false;
        }
    }

    /** Harvests precise use-places (memory-bounded, source-driven) and persists them. Best-effort. */
    private static void harvestAndPersistUsePlaces(List<JavaClass> sorted, String newLineStr, String hash) {
        boolean trigramCleared = false;
        try {
            // One-shot GC hint before Phase-4 starts.
            System.gc();

            // In-flight memory fuse: abort the harvest if heap spikes above a critical threshold
            // during processing (5% above the pause threshold). Prevents OOM mid-harvest.
            final AtomicBoolean memFused = new AtomicBoolean(false);
            final double fuseThreshold = Math.min(0.95, MemoryConfig.get().getPressureHighThreshold() + 0.05);
            final BooleanSupplier fusedCancel = () -> {
                if (cancelRequested.get()) return true;
                if (!memFused.get()) {
                    double usage = MemoryConfig.get().heapUsageFraction();
                    if (usage > fuseThreshold) {
                        if (memFused.compareAndSet(false, true)) {
                            String fusedReason = String.format("heap at %.1f%% > fuse threshold %.1f%%",
                                usage * 100, fuseThreshold * 100);
                            logger.warn("[JAI] Phase-4 harvest FUSED mid-run: {} — stopping to prevent OOM",
                                fusedReason);
                            usePlacesSkipReason.set("fused mid-harvest: " + fusedReason);
                        }
                    }
                }
                return memFused.get();
            };

            // Dynamic memory check: if free heap is below the harvest headroom threshold, evict
            // the trigram index before starting. Phase-4 re-decompiles all classes while the
            // usage graph and JADX metadata are already in heap; the trigram index (~3-5 GB on a
            // 237k-class APK) compounds that and causes OOM. Clearing it costs code-search
            // performance during harvest (searches fall back to full scan) but lets Phase-4
            // complete. A background trigram rebuild from CodeStore starts immediately after.
            Runtime rt = Runtime.getRuntime();
            long freeMB = (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024L * 1024L);
            int trigramEntries = CodeContentIndex.trigramCount();
            // Each trigram entry ≈ 30 KB BitSet for 237k classes; estimate total trigram heap.
            long trigramEstMB = (long) trigramEntries * 30L / 1024L;
            // Trigger eviction when free heap < 2× estimated trigram size + 4 GB re-decompile buffer.
            long headroomNeededMB = trigramEstMB * 2 + 4096L;
            if (trigramEntries > 0 && freeMB < headroomNeededMB) {
                logger.info("[JAI] Phase-4 memory: freeMB={}, trigramEst={}MB, threshold={}MB — "
                    + "clearing trigram index to make room; will rebuild from CodeStore after harvest",
                    freeMB, trigramEstMB, headroomNeededMB);
                CodeContentIndex.clear();
                trigramCleared = true;
                System.gc(); // give GC a window to reclaim freed BitSets before harvest starts
            } else {
                logger.info("[JAI] Phase-4 memory: freeMB={}, trigramEst={}MB — enough headroom, "
                    + "keeping trigram index in memory", freeMB, trigramEstMB);
            }

            // Phase-4 uses fewer workers than Phase-1: base memory is now larger (graph already
            // allocated), so concurrent re-decompile transients must be smaller.
            // Use text-search path (CodeStore) if available: reads decompiled source from disk
            // without invoking JADX's decompile engine, eliminating the ~20 GB heap spike that
            // JADX's dependency-loading cascade causes during re-decompilation.
            int harvestWorkers = MemoryConfig.get().computePhase4Workers(effectiveDecompileWorkers);
            CodeStore cs = codeStore;
            int[][] data;
            if (cs != null && cs.isComplete()) {
                logger.info("[JAI] Phase-4 harvest: using disk-based CodeStore path ({} workers, no JADX re-decompile)",
                    harvestWorkers);
                data = UsePlacesIndex.harvest(sorted, newLineStr, harvestWorkers, fusedCancel,
                    null, cs);
            } else {
                logger.info("[JAI] Phase-4 harvest: CodeStore not ready, falling back to JADX re-decompile ({} workers)",
                    harvestWorkers);
                data = UsePlacesIndex.harvest(sorted, newLineStr, harvestWorkers, fusedCancel,
                    WarmupManager::recycle);
            }
            if (data != null && hash != null) {
                if (usePlacesStore == null) usePlacesStore = new UsePlacesStore(indexCacheDir());
                usePlacesStore.save(data, hash);
                logger.info("[JAI] Warmup: precise use-places persisted (hash={}...)", hash.substring(0, 8));
            }
        } catch (Exception e) {
            logger.warn("[JAI] Warmup: use-places harvest/persist failed: {}", e.getMessage());
        } finally {
            // If we cleared the trigram index to make room for Phase-4, rebuild it in the
            // background from the disk-backed CodeStore. The rebuild runs at MIN_PRIORITY so
            // it doesn't compete with serving requests.
            if (trigramCleared) {
                HeadlessJadxWrapper w = lastWrapper;
                if (w != null && codeStore != null) {
                    logger.info("[JAI] Phase-4 done — scheduling background trigram rebuild (was cleared for harvest)");
                    startBackgroundTrigramBuild(w, sorted);
                }
            }
        }
    }

    // True once the one-time JADX decompile-engine init has been paid (see ENGINE_INIT in runWarmup).
    private static final AtomicBoolean engineInitDone = new AtomicBoolean(false);

    /** True once the one-time JADX engine init is paid; live decompile (smali / non-cached) is fast after. */
    public static boolean isEngineInitDone() {
        return engineInitDone.get();
    }

    private static final AtomicBoolean trigramBuildRunning = new AtomicBoolean(false);

    /** True while a background trigram build (from the CodeStore) is in progress. */
    public static boolean isTrigramBuildRunning() {
        return trigramBuildRunning.get();
    }

    /**
     * Builds the trigram code-content index from the persistent CodeStore in a low-priority
     * background thread, then persists it. Used on the FAST_RESTORE path so code search works
     * after a restart without re-running the multi-minute Phase-1 decompile. Idempotent.
     */
    private static void startBackgroundTrigramBuild(HeadlessJadxWrapper wrapper, List<JavaClass> sorted) {
        if (!trigramBuildRunning.compareAndSet(false, true)) return;
        // Graceful degradation: skip trigram build if free heap < 2× estimated index cost.
        // BitSet-based trigram index uses ~30 KB per class; on a 237k-class APK that is ~7 GB.
        MemoryConfig mc = MemoryConfig.get();
        if (!mc.canAffordTrigramBuild(sorted.size())) {
            long freeMB = mc.freeHeapMB();
            long estMB = mc.estimateTrigramMB(sorted.size());
            String reason = String.format("free heap %dMB < 2× estimated trigram %dMB",
                freeMB, estMB);
            logger.warn("[JAI] trigram build SKIPPED (low heap): {} — code-content search "
                + "unavailable this session; restart on a larger-heap machine to enable", reason);
            trigramSkipReason.set(reason);
            trigramBuildRunning.set(false);
            return;
        }
        trigramSkipReason.set(null);
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            int built = 0;
            try {
                CodeStore cs = codeStore;
                if (cs == null) return;
                logger.info("[JAI] Background trigram build started from CodeStore: {} classes", sorted.size());
                for (JavaClass cls : sorted) {
                    if (cancelRequested.get() || Thread.currentThread().isInterrupted()) break;
                    if (isHighMemoryPressure()) {
                        try { Thread.sleep(1500); } catch (InterruptedException ie) { break; }
                    }
                    String code = cs.get(cls.getRawName()); // CodeStore keyed by raw name (deobf-stable)
                    if (code != null && !code.isEmpty() && CodeContentIndex.tryIndexFromCache(cls, code)) {
                        built++;
                    }
                }
                logger.info("[JAI] Background trigram build finished: {} classes indexed, {} trigrams, {}s",
                    built, CodeContentIndex.trigramCount(), (System.currentTimeMillis() - t0) / 1000);
                if (!cancelRequested.get() && CodeContentIndex.trigramCount() > 0) {
                    tryPersistIndex(wrapper);
                }
            } finally {
                trigramBuildRunning.set(false);
            }
        }, "jadx-trigram-build");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static final AtomicBoolean shardBuildRunning = new AtomicBoolean(false);

    /** True while a background mmap shard build (from the CodeStore) is in progress. */
    public static boolean isShardBuildRunning() {
        return shardBuildRunning.get();
    }

    /**
     * Builds the mmap-backed content shard index from the persistent CodeStore in a low-priority
     * background thread, writes the catalog, then loads it into {@link ContentShardIndex}. Runs in
     * parallel with (and independent of) the heap-bound trigram index — the shard layer is not yet
     * consulted by queries (Wave B), so this is a pure additive build with zero search-behavior risk.
     *
     * <h2>Class-id contract (soundness-critical)</h2>
     * {@code sorted} is the exact list passed to {@link CodeContentIndex#preAssignIds}, which assigns
     * id {@code i} to {@code sorted.get(i)} via a from-0 {@code nextId++}. This loop therefore uses
     * the loop index {@code i} directly as the shard class id, so a shard candidate id {@code i}
     * resolves back to the right class through {@link CodeContentIndex#resolveClass(int)}
     * ({@code resolveClass(i) == sorted.get(i)}). Do NOT substitute any other id source here — a
     * mismatch would make Wave B queries resolve candidates to the wrong class. Library / unpersisted
     * classes (CodeStore miss) are skipped entirely (no addClass, no markExcluded): their ids stay
     * holes, excluded from coverage, so queries fall back to a real scan rather than a false judgment.
     *
     * <h2>Empty inner classes (soundness-critical, W14)</h2>
     * {@code JavaClass.getCode()} returns an empty string for inner classes — their decompiled
     * source is inlined into their top-level class, so Phase-1 still writes {@code cs.put(raw, "")}
     * to the CodeStore. Without special handling, these ~250k ids (XHS-scale APK) become holes
     * (neither covered nor excluded), forcing every content search to fall back to a real per-class
     * scan for each of them. Because their logical source lives entirely inside their (covered or
     * scanned) top-level class, any term they could "contain" is guaranteed to surface via that
     * top-level class — an inner class's own empty content never adds a match. It is therefore
     * sound to record CodeStore-hit empty inner classes as {@code markExcluded} (a definitive
     * "no content here, skip me" signal) rather than leaving them as scan-requiring holes. This is
     * gated on both signals — {@code code != null} (CodeStore hit, not a library miss) AND
     * {@code cls.isInner()} — so a non-inner class with genuinely empty/failed source (e.g. a
     * Phase-1 decompile failure) is NOT excluded and still falls back to a real scan.
     */
    private static void startBackgroundShardBuild(HeadlessJadxWrapper wrapper, List<JavaClass> sorted, String hash) {
        if (hash == null) return;
        if (!shardBuildRunning.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                CodeStore cs = codeStore;
                if (cs == null) return;
                java.nio.file.Path dir = indexCacheDir();
                logger.info("[JAI] Background shard build started from CodeStore: {} classes, budget={}MB",
                    sorted.size(), SHARD_BUDGET_BYTES / (1024 * 1024));
                ContentShardBuilder builder = new ContentShardBuilder(dir, hash, SHARD_BUDGET_BYTES);
                int covered = 0;
                try {
                    for (int i = 0; i < sorted.size(); i++) {
                        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) break;
                        if (isHighMemoryPressure()) {
                            try { Thread.sleep(1500); } catch (InterruptedException ie) { break; }
                        }
                        JavaClass cls = sorted.get(i);
                        // id == i by the preAssignIds contract documented above.
                        String code = cs.get(cls.getRawName()); // CodeStore keyed by raw name (deobf-stable)
                        if (code != null && !code.isEmpty()) {
                            builder.addClass(i, code.toLowerCase()); // addClass routes <3-char source to excluded
                            covered++;
                        } else if (code != null && cls.isInner()) {
                            // CodeStore hit but empty source AND inner class: soundly excludable,
                            // see the "Empty inner classes" contract above.
                            builder.markExcluded(i);
                        }
                        // else: CodeStore miss (library/unpersisted) or non-inner empty source —
                        // leave id i as a hole (not excludable without risking a false judgment).
                    }
                } finally {
                    builder.close(); // trailing flush of the final window
                }
                List<ShardCatalog.ShardEntry> shardsWritten = builder.writtenShards();
                if (!cancelRequested.get()) {
                    ShardCatalog.write(dir, hash, shardsWritten);
                    ContentShardIndex.loadCatalog(dir, hash);
                    logger.info("[JAI] Background shard build done: {} shards, {} covered classes, {}s",
                        shardsWritten.size(), covered, (System.currentTimeMillis() - t0) / 1000);
                    writePrebakedManifest(dir, hash, sorted.size(), shardsWritten.size(), covered);
                }
            } catch (Exception e) {
                logger.warn("[JAI] Background shard build failed: {}", e.getMessage());
            } finally {
                shardBuildRunning.set(false);
            }
        }, "jadx-shard-build");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static final Gson MANIFEST_GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Writes a human-readable {@code <inputHash>.manifest.json} into the index dir once the
     * mmap shard index finishes building. Purely observational — describes the prebaked-index
     * volume so it can be verified after copying to another machine (see docs/prebaked-index.md).
     * Best-effort: any failure is logged and does not affect warmup.
     */
    private static void writePrebakedManifest(java.nio.file.Path dir, String hash, int totalClasses,
                                               int shardCount, int shardCoveredClasses) {
        try {
            Map<String, Object> manifest = new java.util.LinkedHashMap<>();
            manifest.put("tool_version", AppVersion.get());
            manifest.put("input_hash", hash);
            manifest.put("total_classes", totalClasses);
            manifest.put("shard_count", shardCount);
            manifest.put("shard_covered_classes", shardCoveredClasses);
            manifest.put("built_at_epoch_ms", System.currentTimeMillis());
            manifest.put("note", "Prebaked index volume — copy the whole --index-dir to another "
                + "machine and start it against the same APK (same input_hash) for FAST_RESTORE.");
            java.nio.file.Path manifestPath = dir.resolve(hash + ".manifest.json");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(manifestPath, MANIFEST_GSON.toJson(manifest));
            logger.info("[JAI] Prebaked-index manifest written: {}", manifestPath);
        } catch (Exception e) {
            logger.warn("[JAI] Prebaked-index manifest write failed (non-fatal): {}", e.getMessage());
        }
    }

    /** Loads the usage graph from disk into {@link UsageGraphIndex} (ids already assigned). */
    private static boolean tryRestoreUsageGraph(String hash, int classCount) {
        try {
            int[][] loaded = usageGraphStore.tryLoad(hash, classCount);
            return loaded != null && UsageGraphIndex.bulkRestore(loaded);
        } catch (Exception e) {
            logger.warn("[JAI] Warmup: usage graph restore failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Phase 1: parallel decompile without JadxSearchLock.
     *
     * JADX handles per-class concurrency internally (each ClassNode has its own lock),
     * so calling cls.getCode() from multiple threads is safe. Removing the global
     * write lock allows searches to run freely while warmup is in progress.
     */
    private static void runPhase1(List<JavaClass> targets) {
        long phaseStart = System.currentTimeMillis();
        int workers = Math.min(effectiveDecompileWorkers, Math.max(1, targets.size()));
        AtomicInteger indexPos = new AtomicInteger(0);
        AtomicInteger decompiled = new AtomicInteger(0);

        // M2 probe — quantify how much wall-clock the reactive memory-pressure safety net itself
        // costs (sleep + System.gc), to verify whether it (not baseline GC) dominates the high-worker
        // slowdown. Aggregated across workers; reported once at phase end.
        AtomicInteger pressureHits = new AtomicInteger(0);
        AtomicLong pressureSleepMs = new AtomicLong(0);
        AtomicLong pressureGcMs = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "jadx-warmup-decompile");
            t.setDaemon(true);
            return t;
        });
        CountDownLatch latch = new CountDownLatch(workers);

        for (int w = 0; w < workers; w++) {
            pool.submit(() -> {
                try {
                    int pos;
                    while ((pos = indexPos.getAndIncrement()) < targets.size()) {
                        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) break;

                        if (isHighMemoryPressure()) {
                            pressureHits.incrementAndGet();
                            // Throttle System.gc(): only the winner of the CAS fires the hint;
                            // all others skip GC but still sleep. Prevents N-worker thundering herd
                            // where every worker simultaneously triggers a full-GC pause.
                            MemoryConfig mc = MemoryConfig.get();
                            long nowMs = System.currentTimeMillis();
                            long prevMs = lastGcCallMs.get();
                            long gcMs = 0;
                            if (nowMs - prevMs >= mc.getGcThrottleMs() && lastGcCallMs.compareAndSet(prevMs, nowMs)) {
                                long gs = System.currentTimeMillis();
                                System.gc();
                                gcMs = System.currentTimeMillis() - gs;
                                logger.debug("[JAI] Warmup: GC hint fired (throttled, 1 per {}ms)", mc.getGcThrottleMs());
                            }
                            pressureGcMs.addAndGet(gcMs);
                            // Jittered sleep: base + 0–400ms per-thread offset prevents thundering-herd wakeup.
                            long jitter = (Thread.currentThread().getId() % 5) * 100L;
                            long ps = System.currentTimeMillis();
                            try { Thread.sleep(mc.getPressureSleepBaseMs() + jitter); } catch (InterruptedException ie) { break; }
                            pressureSleepMs.addAndGet(System.currentTimeMillis() - ps);
                        }

                        JavaClass cls = targets.get(pos);
                        try {
                            String code = cls.getCode();
                            // Write-through to the persistent code store so a restart (or an
                            // evicted cache-miss) reads source from disk instead of re-decompiling.
                            CodeStore cs = codeStore;
                            if (cs != null && code != null) {
                                cs.put(cls.getRawName(), code); // CodeStore keyed by raw name (deobf-stable)
                            }
                            // Evict the decompiled IR from JADX's internal ClassNode cache. Without
                            // this, every ClassNode permanently holds its decompiled source in heap
                            // (JadxDecompiler keeps a strong-ref class list), causing linear growth
                            // (~100 KB/class × 200k classes = ~20 GB) that OOMs a 38 GB heap before
                            // Phase-1 completes. Phase-2 reads from the disk-backed CodeStore, so
                            // unloading here is safe — the code is already persisted.
                            cls.unload();
                            // Reset ProcessState to GENERATED_AND_UNLOADED so later callers (Phase-4
                            // harvest, live smali/xref requests) can re-decompile from bytecode if
                            // needed. cls.unload() clears data but leaves state as PROCESS_COMPLETE,
                            // which blocks re-decompilation: JADX sees "already done" but finds empty
                            // data, causing Method-registers-not-loaded errors in Phase-4.
                            try {
                                //noinspection JadxInternalApiUsage
                                cls.getClassNode().setState(
                                    jadx.core.dex.nodes.ProcessState.GENERATED_AND_UNLOADED);
                            } catch (Exception ignored) {
                                // best-effort; unload above already freed the bulk of memory
                            }
                            processed.incrementAndGet();
                            decompiled.incrementAndGet();
                        } catch (Exception ignored) {
                            failed.incrementAndGet();
                        }

                        int proc = processed.get();
                        if (proc % 500 == 0 && proc > 0) {
                            logger.info("[JAI] Warmup phase-1: {}/{} decompiled", proc, total.get());
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }

        long phaseMs = System.currentTimeMillis() - phaseStart;
        logger.info("[JAI] Warmup phase-1 done: {} decompiled, {} failed, {} workers in {}ms",
            decompiled.get(), failed.get(), workers, phaseMs);

        // M2 probe report. Workers stall in parallel, so the per-worker stall share — not the raw
        // sum — estimates how much of the phase wall-clock the safety net ate: a value near 100%
        // means the loop spent nearly all its time sleeping/gc'ing rather than decompiling.
        long stallSumMs = pressureSleepMs.get() + pressureGcMs.get();
        double perWorkerStallPct = (phaseMs > 0 && workers > 0)
            ? 100.0 * stallSumMs / ((long) workers * phaseMs) : 0.0;
        logger.info("[JAI] Warmup phase-1 M2 probe: pressureHits={}, sleepMs={}, gcMs={}, "
            + "perWorkerStall≈{}% of {}ms wall ({} workers)",
            pressureHits.get(), pressureSleepMs.get(), pressureGcMs.get(),
            String.format("%.1f", perWorkerStallPct), phaseMs, workers);
    }

    /** Phase 2: parallel trigram index fill from already-cached code (no lock needed). */
    private static void runPhase2(List<JavaClass> targets) {
        int workers = Math.min(INDEX_WORKERS, Math.max(1, targets.size()));
        AtomicInteger indexPos = new AtomicInteger(0);
        AtomicInteger indexed = new AtomicInteger(0);
        long phaseStart = System.currentTimeMillis();

        ExecutorService indexPool = Executors.newFixedThreadPool(workers);
        CountDownLatch latch = new CountDownLatch(workers);

        for (int w = 0; w < workers; w++) {
            indexPool.submit(() -> {
                try {
                    int pos;
                    while ((pos = indexPos.getAndIncrement()) < targets.size()) {
                        if (cancelRequested.get()) break;
                        JavaClass cls = targets.get(pos);
                        // Source from the persistent CodeStore (disk, complete after Phase-1)
                        // rather than jadx's transient ICodeCache, which is largely evicted by
                        // the time Phase-2 runs — that miss left trigram coverage at 0 (C10).
                        String code = null;
                        CodeStore cs = codeStore;
                        if (cs != null) {
                            code = cs.get(cls.getRawName()); // CodeStore keyed by raw name (deobf-stable)
                        }
                        if (code == null || code.isEmpty()) {
                            code = ClassCacheManager.getCachedCodeDirect(cls);
                        }
                        if (CodeContentIndex.tryIndexFromCache(cls, code)) {
                            indexed.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(300, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            indexPool.shutdownNow();
        }

        long phaseMs = System.currentTimeMillis() - phaseStart;
        int totalIndexed = CodeContentIndex.indexedClassCount();
        int totalClasses = total.get() + skipped.get();
        double coverage = totalClasses > 0 ? 100.0 * totalIndexed / totalClasses : 0.0;
        logger.info("[JAI] Warmup phase-2 done: {} newly indexed, trigram coverage {}% in {}ms",
            indexed.get(), String.format("%.1f", coverage), phaseMs);
    }

    /**
     * Attempts to restore the trigram index from the persistent store.
     * Must be called AFTER {@link CodeContentIndex#preAssignIds} so that BitSet bit
     * positions are stable.  Returns true if the index was restored and Phase 2 can
     * be skipped; false if a full Phase-2 rebuild is required.
     */
    private static boolean tryRestoreIndex(HeadlessJadxWrapper wrapper, List<JavaClass> sortedTargets) {
        try {
            if (persistentStore == null) {
                persistentStore = new PersistentIndexStore(indexCacheDir());
            }
            List<File> inputFiles = wrapper.getInputFiles();
            String hash = persistentStore.computeInputHash(new ArrayList<>(inputFiles));
            Map<String, BitSet> loaded = persistentStore.tryLoad(hash);
            if (loaded == null || loaded.isEmpty()) {
                return false;
            }
            CodeContentIndex.bulkRestore(loaded);
            logger.info("[JAI] Warmup: trigram index restored from cache ({} trigrams, {} classes) — Phase 2 skipped",
                loaded.size(), sortedTargets.size());
            return true;
        } catch (Exception e) {
            logger.warn("[JAI] Warmup: failed to restore trigram index from cache: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Saves the CodeContentIndex to the PersistentIndexStore after a successful warmup.
     * Failures are logged but do not break the warmup result.
     */
    private static void tryPersistIndex(HeadlessJadxWrapper wrapper) {
        try {
            if (persistentStore == null) {
                persistentStore = new PersistentIndexStore(indexCacheDir());
            }
            java.util.List<java.io.File> inputFiles = wrapper.getInputFiles();
            String hash = persistentStore.computeInputHash(new java.util.ArrayList<>(inputFiles));
            CodeContentIndex indexInstance = new CodeContentIndex();
            persistentStore.save(indexInstance, hash);
            logger.info("[JAI] Warmup: trigram index persisted (hash={}...)", hash.substring(0, 8));
        } catch (Exception e) {
            logger.warn("[JAI] Warmup: failed to persist trigram index: {}", e.getMessage());
        }
    }

    private static boolean isLibraryClass(String fullName) {
        for (String prefix : LIBRARY_PREFIXES) {
            if (fullName.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isHighMemoryPressure() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        return max > 0 && (double) used / max > MemoryConfig.get().getPressureHighThreshold();
    }
}
