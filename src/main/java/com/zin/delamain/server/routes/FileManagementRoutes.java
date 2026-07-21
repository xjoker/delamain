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
                } catch (Throwable t) {
                    logger.error("load_file: reload failed for {}: {}", finalCanonical, t.getMessage(), t);
                }
            }, "jadx-load-file");
            reloadThread.setDaemon(true);
            reloadThread.start();

            Map<String, Object> result = new HashMap<>();
            result.put("dispatched", true);
            result.put("mode", mode);
            result.put("path", canonical.toString());
            result.put("ready", false);
            result.put("poll_with", "/decompile-status");
            result.put("note", "Decompilation continues asynchronously; poll /decompile-status for progress.");
            ctx.status(202).json(result);
        } catch (SandboxViolation e) {
            logger.warn(e.getMessage());
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "load_file failed: " + e.getMessage()));
        }
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
