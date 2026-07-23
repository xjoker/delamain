package com.zin.delamain.server;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.core.RenameStorage;
import com.zin.delamain.server.routes.AnalysisRoutes;
import com.zin.delamain.server.routes.AnnotationRoutes;
import com.zin.delamain.server.routes.ApkInfoRoutes;
import com.zin.delamain.server.routes.BatchRoutes;
import com.zin.delamain.server.routes.ClassRoutes;
import com.zin.delamain.server.routes.DecompileRoutes;
import com.zin.delamain.server.routes.FileManagementRoutes;
import com.zin.delamain.server.routes.FridaRoutes;
import com.zin.delamain.server.routes.GeneralRoutes;
import com.zin.delamain.server.routes.MemoryConfigRoutes;
import com.zin.delamain.server.routes.MethodRoutes;
import com.zin.delamain.server.routes.RefactoringRoutes;
import com.zin.delamain.server.routes.ResourceRoutes;
import com.zin.delamain.server.routes.SearchRoutes;
import com.zin.delamain.server.routes.TransferRoutes;
import com.zin.delamain.server.routes.XrefsRoutes;
import com.zin.delamain.utils.FilePathSandbox;
import com.zin.delamain.utils.PaginationUtils;

import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Javalin-based HTTP server exposing JADX decompile results as a REST API.
 * No GUI dependency; runs fully headless.
 */
public class DelamainServer {
    private static final Logger logger = LoggerFactory.getLogger(DelamainServer.class);

    private static final String JVM_OOM_KEY = "delamain-oom-detected";

    private final HeadlessJadxWrapper wrapper; // may be null if no APK loaded yet
    private final int port;
    private final String bindAddress;
    private final AuthConfig authConfig;

    // Package-private so the contract is directly assertable (see DelamainServerNoFilePathsTest).
    // /memory-diagnostics belongs here for the same reason /memory-config does: it reads only the
    // JVM, /proc and index statics, and its most valuable readings (baseline before a load,
    // verifying the container-derived heap took effect) are taken with nothing loaded.
    static final Set<String> NO_FILE_REQUIRED_PATHS = Set.of(
            "/health", "/apk-info", "/decompile-status", "/memory-config", "/memory-diagnostics",
            "/list-available-files", "/load-file",
            "/create-transfer-token", "/transfer/upload", "/transfer/status");

    // /transfer/upload and /transfer/status authenticate via their own one-time X-Transfer-Token
    // (see the upload-transfer contract), not the standard bearer token — a curl/CLI client may
    // only ever hold the transfer token. /create-transfer-token still goes through standard
    // bearer auth below, matching the other routes.
    private static final Set<String> TRANSFER_TOKEN_AUTH_PATHS = Set.of(
            "/transfer/upload", "/transfer/status");
    private static final String ANALYSIS_LOCK_ATTRIBUTE = "jadx-analysis-lock";
    private static final String IN_FLIGHT_TOKEN_ATTRIBUTE = "jadx-in-flight-token";

    // Diagnostics for /health (see 2026-07-22 incident): tracks requests that passed the
    // load-state gate so a stuck one is visible as "oldest_in_flight_seconds" instead of the
    // service just going dark behind a held read lock.
    private final Map<Object, Long> inFlightRequestStartTimes = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong inFlightTokenSeq = new java.util.concurrent.atomic.AtomicLong();

    private Javalin app;
    private volatile boolean running = false;

    /**
     * @param wrapper     loaded JADX wrapper, or null if no file loaded yet
     * @param port        TCP port to listen on
     * @param bindAddress network interface to bind (e.g. "0.0.0.0" or "127.0.0.1")
     * @param authConfig  authentication configuration
     */
    public DelamainServer(HeadlessJadxWrapper wrapper, int port, String bindAddress, AuthConfig authConfig) {
        this.wrapper = wrapper;
        this.port = port;
        this.bindAddress = bindAddress;
        this.authConfig = authConfig;
    }

    /**
     * Starts the Javalin server, registers middleware and routes.
     */
    public void start() {
        try {
            // Build Javalin with a bounded Jetty thread pool
            org.eclipse.jetty.util.thread.QueuedThreadPool threadPool =
                    new org.eclipse.jetty.util.thread.QueuedThreadPool(
                            64,  /* maxThreads */
                            8,   /* minThreads */
                            60_000, /* idleTimeout ms */
                            new java.util.concurrent.LinkedBlockingQueue<>(64));
            threadPool.setName("delamain-qtp");

            app = Javalin.create(config -> {
                config.showJavalinBanner = false;
                config.jetty.threadPool = threadPool;
                config.jetty.defaultHost = bindAddress;
            });

            // --- Authentication middleware ---
            installAuthMiddleware();
            installLoadStateMiddleware();

            // Warmup Phase-1 doesn't go through the load-state middleware and holds no
            // analysisLock read lock (see WarmupManager), so a reload's write lock can now win
            // the race against it — making jadx.close() run underneath a still-running warmup
            // thread. Quiesce warmup before the reload closes the old decompiler.
            wrapper.addPreCloseQuiesceHook(this::quiesceWarmupBeforeClose);

            // --- Route registration ---
            registerRoutes();

            // --- OOM handler ---
            installOomHandler();

            // Start accepting connections ONLY after all middleware + routes are registered.
            // Calling .start(port) before registration lets Jetty serve while the route table is
            // still being mutated; concurrent inbound traffic (e.g. a gateway polling) then races
            // with registration and can silently drop routes (observed: newer routes 404 after a
            // restart-under-load). Registering first eliminates the race.
            app.start(port);

            running = true;

            logger.info("=======================================================");
            logger.info("  delamain server started");
            logger.info("  Listening on http://{}:{}/", bindAddress, port);
            logger.info("  APK loaded: {}", wrapper.isLoaded());
            if (authConfig.isAuthEnabled()) {
                logger.info("  Auth: ENABLED (token: {}...)", maskToken(authConfig.getAuthToken()));
            } else {
                logger.info("  Auth: DISABLED");
            }
            logger.info("=======================================================");

        } catch (Exception e) {
            logger.error("Failed to start server: {}", e.getMessage(), e);
            stop();
            throw new RuntimeException("Failed to start DelamainServer", e);
        }
    }

    /**
     * Stops the server and releases resources.
     */
    public void stop() {
        try {
            if (app != null) {
                app.stop();
                logger.info("Server stopped");
            }
        } catch (Exception e) {
            logger.error("Error during shutdown: {}", e.getMessage(), e);
        } finally {
            app = null;
            running = false;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    // -------------------------------------------------------------------------
    // Middleware
    // -------------------------------------------------------------------------

    private void installAuthMiddleware() {
        app.before(ctx -> {
            // OOM circuit-breaker: refuse non-health requests when heap is exhausted
            if (isOomDetected() && !"/health".equals(ctx.path())) {
                ctx.status(503).json(Map.of(
                        "error", "oom_detected",
                        "message", "JVM heap exhaustion detected; restart required",
                        "restart_required", true
                ));
                ctx.skipRemainingHandlers();
                return;
            }

            // /health is always allowed without a token
            if ("/health".equals(ctx.path())) {
                return;
            }

            // These authenticate via their own one-time X-Transfer-Token instead of the
            // standard bearer token; TransferRoutes itself rejects missing/invalid/expired ones.
            if (TRANSFER_TOKEN_AUTH_PATHS.contains(ctx.path())) {
                return;
            }

            if (!authConfig.isAuthEnabled()) {
                return;
            }

            String authHeader = ctx.header("Authorization");
            String token = null;
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7);
            }

            if (!authConfig.validateToken(token)) {
                logger.warn("Unauthorized request from {} to {}", ctx.ip(), ctx.path());
                ctx.status(401).json(Map.of(
                        "error", "Unauthorized",
                        "message", "Invalid or missing authentication token"
                ));
                ctx.skipRemainingHandlers();
            }
        });
    }

    /**
     * All analysis routes are registered even before a file is loaded. This middleware rejects
     * them with a stable 503 while a load is pending and holds a shared lock for the full request
     * so a reload cannot close JADX underneath an active analysis handler.
     */
    private void installLoadStateMiddleware() {
        app.before(ctx -> {
            if (NO_FILE_REQUIRED_PATHS.contains(ctx.path())) {
                return;
            }
            if (!wrapper.tryAcquireAnalysisAccess()) {
                Map<String, Object> response = new HashMap<>();
                response.put("error", "apk_not_ready");
                response.put("load_state", wrapper.getLoadState().name().toLowerCase());
                response.put("message", "No APK/JAR is ready for analysis. Load a file and poll /decompile-status.");
                if (wrapper.getLoadError() != null) {
                    response.put("load_error", wrapper.getLoadError());
                }
                ctx.status(503).json(response);
                ctx.skipRemainingHandlers();
                return;
            }
            ctx.attribute(ANALYSIS_LOCK_ATTRIBUTE, Boolean.TRUE);
            Long token = inFlightTokenSeq.incrementAndGet();
            ctx.attribute(IN_FLIGHT_TOKEN_ATTRIBUTE, token);
            inFlightRequestStartTimes.put(token, System.currentTimeMillis());
        });
        app.after(ctx -> {
            if (Boolean.TRUE.equals(ctx.attribute(ANALYSIS_LOCK_ATTRIBUTE))) {
                wrapper.releaseAnalysisAccess();
            }
            Long token = ctx.attribute(IN_FLIGHT_TOKEN_ATTRIBUTE);
            if (token != null) {
                inFlightRequestStartTimes.remove(token);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Routes
    // -------------------------------------------------------------------------

    private void registerRoutes() {
        // --- Instantiate route groups ---
        PaginationUtils paginationUtils = new PaginationUtils();

        // RenameStorage: store renames in user home dir under .delamain/
        RenameStorage renameStorage = null;
        try {
            renameStorage = new RenameStorage(
                Paths.get(System.getProperty("user.home"), ".delamain", "renames"));
        } catch (Exception e) {
            logger.warn("Failed to initialise RenameStorage, rename persistence disabled: {}", e.getMessage());
        }

        // Group 1 routes. They stay registered during idle/loading states; the middleware above
        // returns 503 until the wrapper is ready instead of exposing a changing route table.
        new GeneralRoutes(wrapper).register(app, authConfig);
        new DecompileRoutes(wrapper).register(app, authConfig);
        new ApkInfoRoutes(wrapper).register(app, authConfig);
        new FridaRoutes(wrapper).register(app, authConfig);
        new AnnotationRoutes(wrapper).register(app, authConfig);
        new AnalysisRoutes(wrapper).register(app, authConfig);
        new FileManagementRoutes(wrapper, FilePathSandbox.fromEnvironment()).register(app, authConfig);
        new TransferRoutes(FilePathSandbox.fromEnvironment()).register(app, authConfig);

        // Group 2 routes
        new ClassRoutes(wrapper, paginationUtils).register(app, authConfig);
        SearchRoutes searchRoutes = new SearchRoutes(wrapper, paginationUtils);
        searchRoutes.register(app, authConfig);
        new BatchRoutes(wrapper, paginationUtils, searchRoutes).register(app, authConfig);
        new XrefsRoutes(wrapper, paginationUtils).register(app, authConfig);
        new MethodRoutes(wrapper).register(app, authConfig);
        new ResourceRoutes(wrapper, paginationUtils).register(app, authConfig);
        new RefactoringRoutes(wrapper, renameStorage).register(app, authConfig);

        // Memory config — always accessible (no APK required), no auth guard
        new MemoryConfigRoutes().register(app, authConfig);

        // GET /health — always accessible, no auth required
        app.get("/health", ctx -> {
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "healthy");
            resp.put("version", com.zin.delamain.utils.AppVersion.get());
            resp.put("jadx_version", jadx.core.Jadx.getVersion());
            resp.put("apk_loaded", wrapper.isLoaded());
            resp.put("load_state", wrapper.getLoadState().name().toLowerCase());
            if (wrapper.getLoadError() != null) {
                resp.put("load_error", wrapper.getLoadError());
            }
            resp.put("oom_detected", isOomDetected());

            resp.put("search_lock", com.zin.delamain.utils.JadxSearchLock.getStatus());
            // /health keeps analysis_lock/requests/last_reload_error flattened at the top level
            // (pre-existing contract, see DelamainServerHealthLockDiagnosticsTest) rather than
            // nested under runtime_diagnostics like /decompile-status.
            resp.putAll(buildRuntimeDiagnostics());

            Runtime rt = Runtime.getRuntime();
            long max = rt.maxMemory();
            long used = rt.totalMemory() - rt.freeMemory();
            Map<String, Object> mem = new HashMap<>();
            mem.put("max_mb", max / (1024 * 1024));
            mem.put("used_mb", used / (1024 * 1024));
            mem.put("usage_percentage", max > 0 ? (int) (used * 100 / max) : 0);
            resp.put("memory", mem);

            ctx.json(resp);
        });

        // GET /apk-info — basic info about loaded file(s)
        app.get("/apk-info", ctx -> {
            if (!wrapper.isLoaded()) {
                ctx.json(Map.of(
                        "load_state", wrapper.getLoadState().name().toLowerCase(),
                        "message", "No APK/JAR has been loaded. Use /load-file to load one."
                ));
                return;
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("load_state", "loaded");
            resp.put("total_classes", wrapper.getTotalClassCount());

            String pkg = wrapper.getApkPackageName();
            resp.put("package_name", pkg != null ? pkg : "unknown");

            java.util.List<String> files = new java.util.ArrayList<>();
            for (java.io.File f : wrapper.getInputFiles()) {
                files.add(f.getName());
            }
            resp.put("input_files", files);

            ctx.json(resp);
        });

        // GET /decompile-status — mirrors /health's lock/queue diagnostics (see
        // buildRuntimeDiagnostics) so gateway callers can honor the documented
        // search_lock.locked backoff contract (mcp_server.py MCP_INSTRUCTIONS, class_tools.py).
        app.get("/decompile-status", ctx -> {
            if (!wrapper.isLoaded()) {
                Map<String, Object> response = new HashMap<>();
                response.put("status", wrapper.getLoadState().name().toLowerCase());
                response.put("total_classes", 0);
                response.put("message", "No file ready for analysis");
                if (wrapper.getLoadError() != null) {
                    response.put("load_error", wrapper.getLoadError());
                }
                response.put("search_lock", com.zin.delamain.utils.JadxSearchLock.getStatus());
                response.put("runtime_diagnostics", buildRuntimeDiagnostics());
                ctx.json(response);
                return;
            }

            int total = wrapper.getTotalClassCount();
            Runtime rt = Runtime.getRuntime();
            long max = rt.maxMemory();
            long used = rt.totalMemory() - rt.freeMemory();

            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "idle");
            resp.put("total_classes", total);
            resp.put("search_lock", com.zin.delamain.utils.JadxSearchLock.getStatus());
            resp.put("runtime_diagnostics", buildRuntimeDiagnostics());

            Map<String, Object> mem = new HashMap<>();
            mem.put("max_mb", max / (1024 * 1024));
            mem.put("used_mb", used / (1024 * 1024));
            mem.put("usage_percentage", max > 0 ? (int) (used * 100 / max) : 0);
            resp.put("memory", mem);

            ctx.json(resp);
        });
    }

    /**
     * Shared with /health: analysis_lock (read_lock_count/reload_pending), requests
     * (in_flight/oldest_in_flight_seconds) and, when present, last_reload_error. Deliberately
     * excludes memory and search_lock — each caller places those at the level its documented
     * contract requires (flattened on /health, search_lock flattened but the rest namespaced
     * under runtime_diagnostics on /decompile-status).
     */
    private Map<String, Object> buildRuntimeDiagnostics() {
        Map<String, Object> diagnostics = new HashMap<>();

        Map<String, Object> analysisLock = new HashMap<>();
        analysisLock.put("read_lock_count", wrapper.getAnalysisReadLockCount());
        analysisLock.put("reload_pending", wrapper.isReloadPending());
        diagnostics.put("analysis_lock", analysisLock);

        long oldestStart = Long.MAX_VALUE;
        for (long start : inFlightRequestStartTimes.values()) {
            if (start < oldestStart) {
                oldestStart = start;
            }
        }
        Map<String, Object> requests = new HashMap<>();
        requests.put("in_flight", inFlightRequestStartTimes.size());
        requests.put("oldest_in_flight_seconds",
                oldestStart == Long.MAX_VALUE ? 0 : (System.currentTimeMillis() - oldestStart) / 1000);
        diagnostics.put("requests", requests);

        if (wrapper.getLastReloadError() != null) {
            diagnostics.put("last_reload_error", wrapper.getLastReloadError());
        }

        assert !diagnostics.containsKey("memory") : "runtime diagnostics must not include memory";
        return diagnostics;
    }

    // -------------------------------------------------------------------------
    // Warmup quiesce
    // -------------------------------------------------------------------------

    private static final long WARMUP_QUIESCE_TIMEOUT_MILLIS = 15_000;
    private static final long WARMUP_QUIESCE_POLL_MILLIS = 100;

    /**
     * Runs under the reload write lock, just before {@code jadx.close()}. Bounded so a warmup
     * thread that ignores cancellation can never turn into another indefinite reload stall — the
     * failure mode this whole diagnostics change exists to avoid.
     */
    private void quiesceWarmupBeforeClose() {
        com.zin.delamain.index.WarmupManager.cancel();
        long deadline = System.currentTimeMillis() + WARMUP_QUIESCE_TIMEOUT_MILLIS;
        while (warmupStillActive()) {
            if (System.currentTimeMillis() > deadline) {
                logger.warn("Warmup did not quiesce within {}ms before reload close; proceeding anyway",
                        WARMUP_QUIESCE_TIMEOUT_MILLIS);
                return;
            }
            try {
                Thread.sleep(WARMUP_QUIESCE_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * True while any warmup work still touches the live JADX engine. The main warmup thread
     * ({@code "running"}) is only part of it: {@code startBackgroundShardBuild} /
     * {@code startBackgroundTrigramBuild} / {@code startBackgroundUsePlacesHarvest} are separate
     * daemon threads that keep calling {@code getCode()}/{@code getRawName()} after {@code running}
     * has flipped to false. Waiting only on {@code running} let {@code jadx.close()} race them
     * (use-after-close), and a subsequent {@code triggerAutoWarmup} could even reset the cancel
     * flag before they observed it — so quiesce must drain all four.
     */
    private boolean warmupStillActive() {
        return Boolean.TRUE.equals(com.zin.delamain.index.WarmupManager.getStatus().get("running"))
                || com.zin.delamain.index.WarmupManager.isShardBuildRunning()
                || com.zin.delamain.index.WarmupManager.isTrigramBuildRunning()
                || com.zin.delamain.index.WarmupManager.isUsePlacesHarvestRunning();
    }

    // -------------------------------------------------------------------------
    // OOM detection
    // -------------------------------------------------------------------------

    private void installOomHandler() {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (isOomRelated(throwable)) {
                System.getProperties().put(JVM_OOM_KEY, System.currentTimeMillis());
                try {
                    logger.error("[OOM] OutOfMemoryError on thread '{}'. Instance marked degraded.", thread.getName());
                } catch (OutOfMemoryError ignored) {
                    // heap fully exhausted; flag already set
                }
            }
            if (prev != null) {
                prev.uncaughtException(thread, throwable);
            }
        });
    }

    private static boolean isOomRelated(Throwable t) {
        int depth = 0;
        while (t != null && depth < 50) {
            if (t instanceof OutOfMemoryError) return true;
            t = t.getCause();
            depth++;
        }
        return false;
    }

    public static boolean isOomDetected() {
        return System.getProperties().containsKey(JVM_OOM_KEY);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String maskToken(String token) {
        if (token == null || token.length() <= 8) return "********";
        return token.substring(0, 8) + "********";
    }
}
