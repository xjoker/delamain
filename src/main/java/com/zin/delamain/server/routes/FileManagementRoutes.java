package com.zin.delamain.server.routes;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.core.MultiFileLoader;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.FilePathSandbox;
import com.zin.delamain.utils.FilePathSandbox.SandboxViolation;

import io.javalin.Javalin;
import io.javalin.http.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * File-management routes: list the sandboxed file root and load an APK/JAR
 * into the running JADX instance without GUI interaction.
 *
 * Endpoints:
 *   GET  /list-available-files?subdir=&pattern=*.apk[&recursive=true]
 *   POST /load-file                              body: {path, mode}
 *
 * Both endpoints reject any request when the sandbox root is unavailable.
 * Path sandbox semantics are owned by {@link FilePathSandbox}.
 */
public class FileManagementRoutes {
    private static final Logger logger = LoggerFactory.getLogger(FileManagementRoutes.class);

    // Package-private (not private): reused by TransferRoutes so the upload endpoint enforces
    // the exact same extension whitelist as load-file, without duplicating the list.
    // Single source of truth is MultiFileLoader.VALID_EXTENSIONS (the real load-time gate) —
    // this just adapts it to a List for callers that don't need Set semantics.
    static final List<String> ALLOWED_EXTENSIONS = List.copyOf(MultiFileLoader.VALID_EXTENSIONS);

    private final HeadlessJadxWrapper wrapper;
    private final FilePathSandbox sandbox;

    public FileManagementRoutes(HeadlessJadxWrapper wrapper, FilePathSandbox sandbox) {
        this.wrapper = wrapper;
        this.sandbox = sandbox;
    }

    public void register(Javalin app, AuthConfig auth) {
        app.get("/list-available-files", this::handleListAvailableFiles);
        app.post("/load-file", this::handleLoadFile);
    }

    public void handleListAvailableFiles(Context ctx) {
        if (!sandbox.isEnabled()) {
            logger.warn("Sandbox not configured");
            ctx.status(503).json(Map.of("error", "Sandbox not configured. Set JADX_FILE_ROOT or mount a directory at /apks."));
            return;
        }
        try {
            String subdir = ctx.queryParam("subdir");
            String pattern = ctx.queryParam("pattern");
            boolean recursive = Boolean.parseBoolean(ctx.queryParamAsClass("recursive", String.class)
                    .getOrDefault("false"));

            Path base = (subdir == null || subdir.isEmpty())
                    ? sandbox.getRoot()
                    : sandbox.resolveWithinRoot(subdir);
            if (!Files.isDirectory(base)) {
                logger.warn("subdir is not a directory: {}", subdir);
                ctx.status(400).json(Map.of("error", "subdir is not a directory: " + subdir));
                return;
            }

            String glob = (pattern == null || pattern.isEmpty()) ? "*" : pattern;
            List<Map<String, Object>> files = new ArrayList<>();
            collect(base, glob, recursive, files);
            files.sort((a, b) -> ((String) a.get("path")).compareTo((String) b.get("path")));

            Map<String, Object> result = new HashMap<>();
            result.put("root", sandbox.getRoot().toString());
            result.put("base", base.toString());
            result.put("count", files.size());
            result.put("files", files);
            ctx.json(result);
        } catch (SandboxViolation e) {
            logger.warn(e.getMessage());
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to list files: " + e.getMessage()));
        }
    }

    public void handleLoadFile(Context ctx) {
        if (!sandbox.isEnabled()) {
            logger.warn("Sandbox not configured");
            ctx.status(503).json(Map.of("error", "Sandbox not configured. Set JADX_FILE_ROOT or mount a directory at /apks."));
            return;
        }
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String userPath = body == null ? null : (String) body.get("path");
            String mode = body == null ? "replace" : (String) body.getOrDefault("mode", "replace");
            if (userPath == null || userPath.isEmpty()) {
                logger.warn("path is required");
                ctx.status(400).json(Map.of("error", "path is required"));
                return;
            }
            if (!"replace".equals(mode) && !"append".equals(mode)) {
                logger.warn("mode must be 'replace' or 'append', got: {}", mode);
                ctx.status(400).json(Map.of("error", "mode must be 'replace' or 'append', got: " + mode));
                return;
            }

            Path canonical = sandbox.resolveWithinRoot(userPath);
            if (!Files.isRegularFile(canonical)) {
                logger.warn("path is not a regular file: {}", canonical);
                ctx.status(400).json(Map.of("error", "path is not a regular file: " + canonical));
                return;
            }
            String name = canonical.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean extOk = ALLOWED_EXTENSIONS.stream().anyMatch(name::endsWith);
            if (!extOk) {
                logger.warn("extension not allowed: {}", name);
                ctx.status(400).json(Map.of("error",
                        "extension not allowed (need one of " + ALLOWED_EXTENSIONS + "): " + name));
                return;
            }

            if (!wrapper.beginReload()) {
                ctx.status(409).json(Map.of(
                        "error", "load_in_progress",
                        "message", "Another file load is already in progress. Poll /decompile-status."));
                return;
            }

            // Headless reload: dispatch asynchronously to avoid blocking the HTTP thread
            final String finalMode = mode;
            final Path finalCanonical = canonical;
            Thread reloadThread = new Thread(() -> {
                try {
                    List<File> newFiles;
                    if ("append".equals(finalMode)) {
                        // Merge existing files + new file
                        newFiles = new ArrayList<>(wrapper.getInputFiles());
                        newFiles.add(finalCanonical.toFile());
                    } else {
                        newFiles = Collections.singletonList(finalCanonical.toFile());
                    }

                    wrapper.reloadReserved(newFiles, wrapper.getOutputDir(), wrapper.getThreads(),
                            ClassCacheManager::clearCacheIncludingDecompiled);
                    logger.info("load_file: reload completed ({}) for {}", finalMode, finalCanonical);
                    triggerAutoWarmup();
                } catch (Throwable t) {
                    logger.error("load_file: reload failed for {}: {}", finalCanonical, t.getMessage(), t);
                }
            }, "jadx-load-file");
            reloadThread.setDaemon(true);
            reloadThread.start();

            ctx.status(202).json(buildLoadDispatchResponse(mode, canonical));
        } catch (SandboxViolation e) {
            logger.warn(e.getMessage());
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "load_file failed: " + e.getMessage()));
        }
    }

    /**
     * The 202 body for a dispatched load. Loading and the auto-warmup it triggers both run in the
     * background, so this is the caller's only chance to learn the shape of the wait: that warmup
     * starts by itself, where its live phase/eta/capabilities are, and which capability gates code
     * search. Without that the agent polls {@code /decompile-status} until the class tree is up and
     * then immediately issues the exact calls (code search, xref) that are slowest while warmup is
     * still saturating the CPU and the content index does not exist yet.
     */
    Map<String, Object> buildLoadDispatchResponse(String mode, Path canonical) {
        boolean autoWarmup = resolveWarmupOnStart(System.getenv("DELAMAIN_WARMUP_ON_START"));

        Map<String, Object> warmupStatus = com.zin.delamain.index.WarmupManager.getStatus();
        Map<String, Object> warmup = new HashMap<>();
        warmup.put("phase", warmupStatus.get("phase"));
        warmup.put("eta_seconds", warmupStatus.get("eta_seconds"));
        warmup.put("capabilities", warmupStatus.get("capabilities"));

        Map<String, Object> result = new HashMap<>();
        result.put("dispatched", true);
        result.put("mode", mode);
        result.put("path", canonical.toString());
        result.put("ready", false);
        result.put("poll_with", "/decompile-status");
        result.put("auto_warmup", autoWarmup);
        result.put("warmup_status_endpoint", "/warmup-status");
        result.put("warmup", warmup);
        result.put("note", "Decompilation continues asynchronously; poll /decompile-status for progress."
            + (autoWarmup ? " Index warmup starts automatically once the load finishes." : ""));
        result.put("_ai_instruction", autoWarmup
            ? "Loading is async and index warmup follows it automatically. Poll get_warmup_status "
              + "(/warmup-status) rather than guessing: it reports phase, eta_seconds and "
              + "per-capability readiness. Metadata search (search_in=class/method/field), "
              + "class source and smali are usable as soon as the load completes. Do NOT issue "
              + "search_in=code or high-fan-in xref calls until capabilities.code_search == "
              + "\"ready\" — before that the content index does not exist, warmup is using the "
              + "CPU, and those calls are at their slowest."
            : "Loading is async. Poll get_warmup_status (/warmup-status) for readiness. Automatic "
              + "warmup is DISABLED (DELAMAIN_WARMUP_ON_START=false), so capabilities.code_search "
              + "stays \"warming\" until you call start_warmup explicitly; metadata search, class "
              + "source and smali work without it.");
        return result;
    }

    /**
     * Mirrors {@code Main}'s auto-warmup-on-start trigger for files loaded after process start via
     * {@code /load-file}. Container deployments (e.g. the delamain entrypoint) never pass
     * {@code --apk}, so {@code Main}'s startup-only trigger (main.java, wrapper.isLoaded() check
     * right after server start) never fires; without this, shard/UsageGraphIndex/UsePlacesIndex
     * fast indexes are never built for load_file-loaded APKs and xref/code-search silently
     * degrade to the slow live-decompile path. Only called after a successful reload.
     *
     * <p>Dedup is handled by {@link com.zin.delamain.index.WarmupManager#start}, which already
     * no-ops (returns {@code started=false}) when a warmup is already running — no extra guard
     * needed here.</p>
     */
    private void triggerAutoWarmup() {
        if (!resolveWarmupOnStart(System.getenv("DELAMAIN_WARMUP_ON_START")) || !wrapper.isLoaded()) {
            return;
        }
        Thread warmupThread = new Thread(() -> {
            try {
                boolean skipLibraries = resolveSkipLibraries(System.getenv("DELAMAIN_WARMUP_INDEX_LIBRARIES"));
                Map<String, Object> result = com.zin.delamain.index.WarmupManager.start(wrapper, skipLibraries);
                logger.info("load_file: auto warmup trigger result: {}", result);
            } catch (Exception e) {
                logger.warn("load_file: auto warmup failed to start: {}", e.getMessage());
            }
        }, "jadx-load-file-warmup-trigger");
        warmupThread.setDaemon(true);
        warmupThread.start();
    }

    /**
     * Resolves whether {@link #triggerAutoWarmup()} should fire, mirroring
     * {@code Main}'s {@code warmupOnStart} flag (default on; {@code DELAMAIN_WARMUP_ON_START=false}
     * opts out). Package-private static so it is directly testable, matching the style of
     * {@code Main#resolveSkipLibraries(String)}.
     */
    static boolean resolveWarmupOnStart(String envValue) {
        return !"false".equalsIgnoreCase(envValue);
    }

    /**
     * Local copy of {@code Main#resolveSkipLibraries(String)} (default: skip library classes).
     * {@code Main}'s version is package-private in {@code com.zin.delamain}, unreachable from this
     * {@code .server.routes} package; duplicating this two-line env check is the smallest change
     * that avoids either widening Main's visibility or adding a new shared-helper class for one
     * flag.
     */
    static boolean resolveSkipLibraries(String envValue) {
        return !"true".equalsIgnoreCase(envValue);
    }

    /** Dot-prefixed entries (e.g. {@code .transfer}, the in-flight upload staging dir) are
     *  never surfaced by list-available-files, regardless of glob/recursive. */
    private static boolean isHidden(Path entry) {
        return entry.getFileName().toString().startsWith(".");
    }

    private void collect(Path base, String glob, boolean recursive,
                         List<Map<String, Object>> out) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base, glob)) {
            for (Path entry : stream) {
                if (Files.isSymbolicLink(entry) || isHidden(entry)) {
                    continue;
                }
                if (Files.isDirectory(entry)) {
                    if (recursive) collect(entry, glob, true, out);
                    continue;
                }
                Map<String, Object> item = new HashMap<>();
                Path rel = sandbox.getRoot().relativize(entry);
                item.put("path", rel.toString());
                item.put("absolute", entry.toString());
                try {
                    item.put("size_bytes", Files.size(entry));
                } catch (IOException ignored) {
                    item.put("size_bytes", -1);
                }
                String n = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                String ext = n.contains(".") ? n.substring(n.lastIndexOf('.')) : "";
                item.put("extension", ext);
                item.put("loadable", ALLOWED_EXTENSIONS.contains(ext));
                out.add(item);
            }
        }

        if (recursive && glob.equals("*")) {
            return;
        }
        if (recursive) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
                for (Path entry : stream) {
                    if (!Files.isSymbolicLink(entry) && !isHidden(entry) && Files.isDirectory(entry)) {
                        collect(entry, glob, true, out);
                    }
                }
            }
        }
    }
}
