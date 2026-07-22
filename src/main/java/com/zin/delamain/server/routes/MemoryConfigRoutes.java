package com.zin.delamain.server.routes;

import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.MemoryConfig;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.server.AuthConfig;

import io.javalin.Javalin;
import io.javalin.http.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP tools and HTTP endpoints for runtime memory configuration.
 *
 * <h2>Endpoints</h2>
 * <pre>
 *   GET  /memory-config          — get_memory_config    : current thresholds + live heap stats
 *   POST /memory-config          — update_memory_config : hot-update one or more fields
 * </pre>
 *
 * <h2>update_memory_config fields</h2>
 * <pre>
 *   pressure_high_threshold  double  (0.40, 0.98]  — heap-used% above which workers throttle
 *   safety_margin            double  [0.20, 0.90]  — fraction of headroom allocated to workers
 *   per_worker_heap_mb       long    [256, 8192]   — estimated MB per concurrent decompile worker
 *   gc_throttle_ms           long    [1000,300000] — min ms between explicit System.gc() calls
 *   pressure_sleep_base_ms   long    [100, 10000]  — base sleep per pressure-handler hit (ms)
 *   phase4_fraction          double  [0.10, 1.0]   — phase-4 workers = floor(phase1 × fraction)
 *   reset_to_defaults        bool                  — revert everything to auto-computed defaults
 * </pre>
 *
 * <h2>Small-memory tuning guide</h2>
 * On a machine with &le; 8 GB heap, the server auto-sizes conservatively (pressureHigh=0.75,
 * safetyMargin=0.55). If warmup is still OOMing, try:
 * <pre>
 *   POST /memory-config  {"pressure_high_threshold": 0.70, "safety_margin": 0.45}
 * </pre>
 * This triggers pressure throttling earlier and leaves more headroom between workers.
 */
public class MemoryConfigRoutes {

    private static final Logger logger = LoggerFactory.getLogger(MemoryConfigRoutes.class);

    private static final long MB = 1024 * 1024;

    public void register(Javalin app, AuthConfig auth) {
        app.get("/memory-config",  ctx -> handleGet(ctx));
        app.post("/memory-config", ctx -> handlePost(ctx));
        app.get("/memory-diagnostics", ctx -> handleDiagnostics(ctx));
    }

    // -------------------------------------------------------------------------
    // GET /memory-diagnostics?gc=true  →  GC-then-measure
    // -------------------------------------------------------------------------

    private void handleDiagnostics(Context ctx) {
        boolean runGc = !"false".equalsIgnoreCase(ctx.queryParam("gc"));
        ctx.json(buildMemoryDiagnostics(runGc));
    }

    /**
     * Heap/RSS snapshot, optionally after forcing a collection first.
     *
     * <p>Live "used heap" mixes live objects with uncollected garbage, so it cannot answer what
     * delamain's clean steady state costs for a given APK. Collect first, then measure — and
     * report {@code committed} and the process RSS next to it, because the memory that matters to
     * the host is what the JVM took from the OS and has not returned, not what the heap logically
     * holds (production: G1 sitting on ~48 GB of RSS against a far smaller live set).</p>
     *
     * @param runGc request a collection before measuring. {@code System.gc()} is a request, not a
     *              command, but with G1 and no {@code -XX:+DisableExplicitGC} it does collect.
     */
    Map<String, Object> buildMemoryDiagnostics(boolean runGc) {
        Runtime rt = Runtime.getRuntime();
        long usedBefore = rt.totalMemory() - rt.freeMemory();

        Map<String, Object> gc = new LinkedHashMap<>();
        long gcElapsedMs = 0;
        if (runGc) {
            long t0 = System.currentTimeMillis();
            System.gc();
            // G1's concurrent cycle keeps working after System.gc() returns; a short settle makes
            // the "after" figure reflect the collection rather than catching it mid-flight.
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gcElapsedMs = System.currentTimeMillis() - t0;
        }
        gc.put("ran", runGc);
        gc.put("elapsed_ms", gcElapsedMs);

        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        Map<String, Object> heap = new LinkedHashMap<>();
        heap.put("max_mb", max / MB);
        heap.put("committed_mb", rt.totalMemory() / MB);
        heap.put("used_mb", used / MB);
        heap.put("usage_percentage", max > 0 ? (int) (used * 100 / max) : 0);
        if (runGc) {
            heap.put("used_before_gc_mb", usedBefore / MB);
            heap.put("freed_mb", usedBefore / MB - used / MB);
        }

        Map<String, Object> process = new LinkedHashMap<>();
        process.put("rss_mb", readProcSelfStatusMb("VmRSS"));
        process.put("virtual_mb", readProcSelfStatusMb("VmSize"));

        Map<String, Object> container = new LinkedHashMap<>();
        Long limitBytes = readCgroupMemoryLimitBytes();
        container.put("memory_limit_mb", limitBytes == null ? null : limitBytes / MB);

        Map<String, Object> consumers = new LinkedHashMap<>();
        consumers.put("trigram_count", CodeContentIndex.trigramCount());
        consumers.put("trigram_indexed_classes", CodeContentIndex.indexedClassCount());
        consumers.put("shard_index_built", ContentShardIndex.isBuilt());

        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("gc", gc);
        diag.put("heap", heap);
        diag.put("process", process);
        diag.put("container", container);
        diag.put("consumers", consumers);
        diag.put("note", "heap.used_mb after gc.ran=true is the clean steady state; "
            + "process.rss_mb far above heap.committed_mb means the JVM is holding memory the "
            + "heap no longer needs (see the container-derived -Xmx in gateway/src/heap_config.py). "
            + "The shard index is mmap'd, so it counts in RSS but never in the heap.");
        return diag;
    }

    /** {@code /proc/self/status} field in MB, or {@code null} off Linux / on any read failure. */
    private static Long readProcSelfStatusMb(String field) {
        Path status = Paths.get("/proc/self/status");
        if (!Files.isReadable(status)) return null;
        try {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith(field + ":")) {
                    String[] parts = line.split("\\s+");
                    return Long.parseLong(parts[1]) / 1024; // reported in kB
                }
            }
        } catch (Exception e) {
            logger.debug("could not read {} from /proc/self/status: {}", field, e.toString());
        }
        return null;
    }

    /**
     * The container's own memory limit (cgroup v2, then v1), or {@code null} when unlimited or
     * unreadable. Mirrors the detection in {@code gateway/src/heap_config.py}, which is what sizes
     * the heap at startup — reporting both here makes a mis-sized heap visible in one call.
     */
    private static Long readCgroupMemoryLimitBytes() {
        Long v2 = readLongFile(Paths.get("/sys/fs/cgroup/memory.max")); // "max" → null
        if (v2 != null && v2 > 0) return v2;
        Long v1 = readLongFile(Paths.get("/sys/fs/cgroup/memory/memory.limit_in_bytes"));
        if (v1 != null && v1 > 0 && v1 < (1L << 62)) return v1; // v1 spells unlimited as a sentinel
        return null;
    }

    private static Long readLongFile(Path path) {
        try {
            if (!Files.isReadable(path)) return null;
            return Long.parseLong(new String(Files.readAllBytes(path)).trim());
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // GET /memory-config  →  get_memory_config
    // -------------------------------------------------------------------------

    private void handleGet(Context ctx) {
        ctx.json(MemoryConfig.get().snapshot());
    }

    // -------------------------------------------------------------------------
    // POST /memory-config  →  update_memory_config
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void handlePost(Context ctx) {
        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid JSON: " + e.getMessage()));
            return;
        }
        if (body == null || body.isEmpty()) {
            ctx.status(400).json(Map.of("error", "empty request body",
                "accepted_fields", acceptedFields()));
            return;
        }
        Map<String, Object> result = MemoryConfig.get().update(body);
        int status = result.containsKey("errors") && ((Map<?,?>) result.get("errors")).size() == body.size()
            ? 400 : 200;
        ctx.status(status).json(result);
    }

    private static Map<String, Object> acceptedFields() {
        return Map.of(
            "pressure_high_threshold", "(0.40, 0.98] — heap% triggering pressure throttle",
            "safety_margin",           "[0.20, 0.90] — fraction of headroom given to workers",
            "per_worker_heap_mb",      "[256, 8192]  — estimated MB per concurrent worker",
            "gc_throttle_ms",          "[1000,300000]— min ms between System.gc() calls",
            "pressure_sleep_base_ms",  "[100, 10000] — base sleep per pressure hit (ms)",
            "phase4_fraction",         "[0.10, 1.0]  — phase-4 workers = floor(phase1 × frac)",
            "reset_to_defaults",       "true         — revert to auto-computed defaults"
        );
    }
}
