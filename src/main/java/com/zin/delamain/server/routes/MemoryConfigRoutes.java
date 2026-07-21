package com.zin.delamain.server.routes;

import com.zin.delamain.index.MemoryConfig;
import com.zin.delamain.server.AuthConfig;

import io.javalin.Javalin;
import io.javalin.http.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public void register(Javalin app, AuthConfig auth) {
        app.get("/memory-config",  ctx -> handleGet(ctx));
        app.post("/memory-config", ctx -> handlePost(ctx));
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
