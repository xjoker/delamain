package com.zin.delamain.server.routes;

import com.zin.delamain.index.CodeContentIndex;
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
 * HTTP endpoint for runtime memory diagnostics.
 *
 * <h2>Endpoints</h2>
 * <pre>
 *   GET  /memory-diagnostics     — GC-then-measure heap/RSS snapshot
 * </pre>
 */
public class MemoryConfigRoutes {

    private static final Logger logger = LoggerFactory.getLogger(MemoryConfigRoutes.class);

    private static final long MB = 1024 * 1024;

    public void register(Javalin app, AuthConfig auth) {
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

}
