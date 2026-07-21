package com.zin.delamain;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.server.DelamainServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for the delamain standalone JAR.
 *
 * CLI arguments:
 *   --port        int     HTTP listen port (default: 8650)
 *   --auth-token  String  Bearer token for API auth (required)
 *   --apk         String  APK/JAR/DEX path to load; can be repeated
 *   --output-dir  String  Decompile output dir (default: ~/.delamain/output)
 *   --index-dir   String  Index storage dir (default: ~/.delamain/indices)
 *   --workers     int     Decompile thread count (default: 8)
 *   --bind        String  Bind address (default: 0.0.0.0)
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    // --- Defaults ---
    private static final int DEFAULT_PORT = 8650;
    private static final String DEFAULT_BIND = "0.0.0.0";
    private static final int DEFAULT_WORKERS = 8;
    private static final String DEFAULT_OUTPUT_DIR =
            System.getProperty("user.home") + File.separator + ".delamain" + File.separator + "output";
    private static final String DEFAULT_INDEX_DIR =
            System.getProperty("user.home") + File.separator + ".delamain" + File.separator + "indices";

    public static void main(String[] args) {
        // ---- Parse arguments ----
        int port = DEFAULT_PORT;
        String authToken = null;
        List<String> apkPaths = new ArrayList<>();
        String outputDir = DEFAULT_OUTPUT_DIR;
        String indexDir = DEFAULT_INDEX_DIR;
        int workers = DEFAULT_WORKERS;
        String bind = DEFAULT_BIND;
        // Auto-start background warmup after the server is up (default on). The server serves
        // immediately during warmup; capabilities open progressively (see WarmupManager status).
        boolean warmupOnStart = !"false".equalsIgnoreCase(
                String.valueOf(System.getenv("DELAMAIN_WARMUP_ON_START")));

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port":
                    port = parseInt(args, i, "--port");
                    i++;
                    break;
                case "--auth-token":
                    authToken = requireValue(args, i, "--auth-token");
                    i++;
                    break;
                case "--apk":
                    apkPaths.add(requireValue(args, i, "--apk"));
                    i++;
                    break;
                case "--output-dir":
                    outputDir = requireValue(args, i, "--output-dir");
                    i++;
                    break;
                case "--index-dir":
                    indexDir = requireValue(args, i, "--index-dir");
                    i++;
                    break;
                case "--workers":
                    workers = parseInt(args, i, "--workers");
                    i++;
                    break;
                case "--bind":
                    bind = requireValue(args, i, "--bind");
                    i++;
                    break;
                case "--no-warmup-on-start":
                    warmupOnStart = false;
                    break;
                case "--warmup-on-start":
                    warmupOnStart = true;
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                    System.exit(1);
            }
        }

        // ---- Environment variable overrides (lower priority than CLI args) ----
        if (authToken == null || authToken.isEmpty()) {
            String envToken = System.getenv("DELAMAIN_AUTH_TOKEN");
            if (envToken != null && !envToken.isEmpty()) {
                authToken = envToken;
                logger.info("Using auth token from DELAMAIN_AUTH_TOKEN env var");
            }
        }
        {
            String envPort = System.getenv("DELAMAIN_PORT");
            if (envPort != null && !envPort.isEmpty()) {
                try {
                    port = Integer.parseInt(envPort.trim());
                    logger.info("Using port {} from DELAMAIN_PORT env var", port);
                } catch (NumberFormatException e) {
                    System.err.println("WARN: DELAMAIN_PORT is not a valid integer: " + envPort);
                }
            }
        }

        // --auth-token (or DELAMAIN_AUTH_TOKEN) is required
        if (authToken == null || authToken.isEmpty()) {
            System.err.println("ERROR: --auth-token is required (or set DELAMAIN_AUTH_TOKEN env var)");
            printUsage();
            System.exit(1);
        }

        // ---- Banner ----
        printBanner(port, bind, apkPaths, outputDir, workers);

        // ---- Configure persistent index location (trigram + usage graph) ----
        // Without this the indices default to ~/.delamain/index-cache (lost on container
        // recreate); pointing them at --index-dir puts them on the mounted volume.
        try {
            File idxDir = new File(indexDir);
            if (!idxDir.exists() && !idxDir.mkdirs()) {
                logger.warn("Could not create index directory: {}", indexDir);
            }
            com.zin.delamain.index.WarmupManager.setIndexDir(idxDir.toPath());
            // Disk-cache LRU retention (JADX_CACHE_MAX_GB, default 50GB): enforce the quota once
            // at startup so a volume grown unbounded across restarts by previously-analyzed APKs
            // gets trimmed before this session's APK is loaded. No APK is loaded yet, so there is
            // no "currently loaded" hash to exclude.
            com.zin.delamain.index.IndexCacheManager.enforceQuota(idxDir.toPath(), null);
        } catch (Exception e) {
            logger.warn("Failed to configure index dir '{}': {}", indexDir, e.getMessage());
        }

        // ---- Ensure output directory exists ----
        File outDir = new File(outputDir);
        if (!outDir.exists() && !outDir.mkdirs()) {
            logger.warn("Could not create output directory: {}", outputDir);
        }

        // ---- Create a wrapper even with no file, so /load-file can load safely at runtime ----
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(), outDir, workers);
        if (!apkPaths.isEmpty()) {
            List<File> inputFiles = new ArrayList<>();
            for (String path : apkPaths) {
                File f = new File(path);
                if (!f.exists()) {
                    System.err.println("ERROR: APK/JAR file not found: " + path);
                    System.exit(1);
                }
                inputFiles.add(f);
            }

            logger.info("Loading {} file(s)...", inputFiles.size());
            try {
                wrapper = new HeadlessJadxWrapper(inputFiles, outDir, workers);
                wrapper.load();
                logger.info("Loaded {} classes", wrapper.getTotalClassCount());
            } catch (Exception e) {
                logger.error("Failed to load input files: {}", e.getMessage(), e);
                System.exit(1);
            }
        } else {
            logger.info("No --apk provided; server will start without a loaded file");
        }

        // ---- Start server ----
        AuthConfig authConfig = new AuthConfig(authToken);
        DelamainServer server = new DelamainServer(wrapper, port, bind, authConfig);

        // Graceful shutdown on SIGINT/SIGTERM
        final HeadlessJadxWrapper finalWrapper = wrapper;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received, stopping server...");
            server.stop();
            if (finalWrapper != null) {
                finalWrapper.close();
            }
            logger.info("Shutdown complete");
        }, "delamain-shutdown"));

        try {
            server.start();
        } catch (Exception e) {
            logger.error("Server failed to start: {}", e.getMessage(), e);
            System.exit(1);
        }

        // ---- Auto-start background warmup ----
        // The server already serves requests now; warmup runs on a background daemon thread
        // (multi-threaded, see WarmupManager) and opens capabilities progressively. Disable with
        // --no-warmup-on-start or DELAMAIN_WARMUP_ON_START=false (e.g. CI smoke tests).
        if (warmupOnStart && wrapper.isLoaded()) {
            final HeadlessJadxWrapper warmWrapper = wrapper;
            final boolean skipLibraries = resolveSkipLibraries();
            if (!skipLibraries) {
                logger.info("Library indexing ENABLED (DELAMAIN_WARMUP_INDEX_LIBRARIES=true): "
                        + "libraries will be decompiled+indexed (slower warmup)");
            }
            Thread t = new Thread(() -> {
                try {
                    com.zin.delamain.index.WarmupManager.start(warmWrapper, skipLibraries);
                    logger.info("Auto warmup started in background (query /warmup-status for progress + ETA)");
                } catch (Exception e) {
                    logger.warn("Auto warmup failed to start: {}", e.getMessage());
                }
            }, "jadx-auto-warmup-trigger");
            t.setDaemon(true);
            t.start();
        } else if (!warmupOnStart) {
            logger.info("Auto warmup disabled (--no-warmup-on-start); call POST /start-warmup manually");
        }

        // ---- Keep process alive ----
        logger.info("Server is running. Press Ctrl+C to stop.");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------
    // CLI helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves whether auto-warmup should skip library classes (default: skip).
     * <p>
     * Set DELAMAIN_WARMUP_INDEX_LIBRARIES=true to opt in to full library indexing: library
     * classes are decompiled and written into the CodeStore/shard so code search covers them
     * too, instead of falling back to a slower live-decompile scan. This trades significantly
     * more warmup time and memory (especially on large APKs or low-spec machines) for that
     * coverage, which is why it defaults off.
     */
    static boolean resolveSkipLibraries() {
        return resolveSkipLibraries(System.getenv("DELAMAIN_WARMUP_INDEX_LIBRARIES"));
    }

    /** Testable core of {@link #resolveSkipLibraries()}: takes the raw env value directly. */
    static boolean resolveSkipLibraries(String envValue) {
        return !"true".equalsIgnoreCase(envValue);
    }

    private static String requireValue(String[] args, int i, String flag) {
        if (i + 1 >= args.length) {
            System.err.println("ERROR: " + flag + " requires a value");
            System.exit(1);
        }
        return args[i + 1];
    }

    private static int parseInt(String[] args, int i, String flag) {
        String val = requireValue(args, i, flag);
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            System.err.println("ERROR: " + flag + " must be an integer, got: " + val);
            System.exit(1);
            return 0;
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar delamain.jar [options]");
        System.out.println();
        System.out.println("Required:");
        System.out.println("  --auth-token <token>   Bearer token for API authentication");
        System.out.println();
        System.out.println("Optional:");
        System.out.println("  --port <int>           HTTP listen port (default: 8650)");
        System.out.println("  --apk <path>           APK/JAR/DEX file to load; can be repeated");
        System.out.println("  --output-dir <path>    Decompile output directory (default: ~/.delamain/output)");
        System.out.println("  --index-dir <path>     Index storage directory (default: ~/.delamain/indices)");
        System.out.println("  --workers <int>        Decompile thread count (default: 8)");
        System.out.println("  --bind <address>       Network bind address (default: 0.0.0.0)");
        System.out.println("  --help                 Show this help message");
    }

    private static void printBanner(int port, String bind, List<String> apkPaths,
                                    String outputDir, int workers) {
        System.out.println();
        System.out.println("  ██╗ █████╗ ██████╗ ██╗  ██╗    ███╗   ███╗ ██████╗██████╗");
        System.out.println("  ██║██╔══██╗██╔══██╗╚██╗██╔╝    ████╗ ████║██╔════╝██╔══██╗");
        System.out.println("  ██║███████║██║  ██║ ╚███╔╝     ██╔████╔██║██║     ██████╔╝");
        System.out.println("  ██║██╔══██║██║  ██║ ██╔██╗     ██║╚██╔╝██║██║     ██╔═══╝");
        System.out.println("  ██║██║  ██║██████╔╝██╔╝ ██╗    ██║ ╚═╝ ██║╚██████╗██║");
        System.out.println("  ╚═╝╚═╝  ╚═╝╚═════╝ ╚═╝  ╚═╝    ╚═╝     ╚═╝ ╚═════╝╚═╝  CORE");
        System.out.println();
        System.out.println("  Headless JADX MCP Server  v" + com.zin.delamain.utils.AppVersion.get());
        System.out.println("  Port       : " + port);
        System.out.println("  Bind       : " + bind);
        System.out.println("  APK(s)     : " + (apkPaths.isEmpty() ? "(none)" : String.join(", ", apkPaths)));
        System.out.println("  Output dir : " + outputDir);
        System.out.println("  Workers    : " + workers);
        System.out.println();
    }
}
