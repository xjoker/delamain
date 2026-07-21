package com.zin.delamain.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages loading of multiple APK/JAR/DEX files into a single {@link HeadlessJadxWrapper}
 * instance.  Supports two modes:
 * <ul>
 *   <li>{@code replace} — closes the current instance and loads only the new files</li>
 *   <li>{@code append}  — merges new files with the already-loaded ones and reloads</li>
 * </ul>
 *
 * <p>The actual decompiler reset is delegated to {@link HeadlessJadxWrapper#reload(List, File, int)};
 * this class is responsible for file validation, mode logic, and returning a structured result.</p>
 *
 * <h2>Thread safety</h2>
 * {@link #loadFiles} is {@code synchronized} to prevent concurrent reloads from racing.
 */
public class MultiFileLoader {

    private static final Logger logger = LoggerFactory.getLogger(MultiFileLoader.class);

    /**
     * Canonical set of file extensions jadx-all 1.5.5 can load, given its bundled input plugins
     * (see {@code jadx.plugins.input.*} — apk/dex/jar/aar/class handled by the default input,
     * plus XApkInputPlugin/.apks/ApkmInputPlugin/AabInputPlugin for the APK-distribution variants).
     * This is the single source of truth: {@code FileManagementRoutes.ALLOWED_EXTENSIONS} (which
     * {@code TransferRoutes} also reuses) points at this same set so the load-file whitelist, the
     * list-available-files "loadable" flag, and the upload whitelist can never drift apart again.
     */
    public static final Set<String> VALID_EXTENSIONS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            ".apk", ".apks", ".xapk", ".apkm", ".aab", ".jar", ".dex", ".aar", ".class", ".zip"
    )));

    private final HeadlessJadxWrapper wrapper;
    private final File outputDir;
    private final int threads;

    /** Snapshot of the files currently loaded into the wrapper. */
    private volatile List<File> currentFiles = Collections.emptyList();

    /**
     * @param wrapper   existing wrapper instance (will be reloaded on {@link #loadFiles})
     * @param outputDir directory passed to JadxArgs.setOutDir on each reload
     * @param threads   decompile thread count
     */
    public MultiFileLoader(HeadlessJadxWrapper wrapper, File outputDir, int threads) {
        this.wrapper = Objects.requireNonNull(wrapper, "wrapper");
        this.outputDir = Objects.requireNonNull(outputDir, "outputDir");
        this.threads = threads;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Load or replace the currently loaded files.
     *
     * @param files list of files to load; must not be null
     * @param mode  {@code "replace"} (default) or {@code "append"}
     * @return structured result containing success flag, errors, and loaded file names
     */
    public synchronized LoadResult loadFiles(List<File> files, String mode) {
        if (files == null) {
            return LoadResult.failure("files list must not be null", Collections.emptyList(), currentFiles);
        }

        List<File> validFiles = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (File f : files) {
            if (f == null) {
                errors.add("Null file entry ignored");
                continue;
            }
            if (!f.exists()) {
                errors.add("File not found: " + f.getAbsolutePath());
            } else if (!f.isFile()) {
                errors.add("Not a file: " + f.getAbsolutePath());
            } else if (!isValidExtension(f)) {
                errors.add("Unsupported file type: " + f.getName()
                        + " (supported: " + String.join(", ", VALID_EXTENSIONS) + ")");
            } else {
                validFiles.add(f);
            }
        }

        if (validFiles.isEmpty()) {
            logger.warn("[MultiFileLoader] No valid files to load; errors: {}", errors);
            return LoadResult.failure("No valid files to load", errors, currentFiles);
        }

        List<File> toLoad;
        if ("append".equalsIgnoreCase(mode)) {
            toLoad = new ArrayList<>(currentFiles);
            // Avoid duplicates by absolute path
            Set<String> existing = new HashSet<>();
            for (File f : currentFiles) existing.add(f.getAbsolutePath());
            for (File f : validFiles) {
                if (existing.add(f.getAbsolutePath())) {
                    toLoad.add(f);
                }
            }
        } else {
            // "replace" is the default
            toLoad = new ArrayList<>(validFiles);
        }

        try {
            logger.info("[MultiFileLoader] Reloading with {} file(s) (mode={})", toLoad.size(), mode);
            wrapper.reload(toLoad, outputDir, threads);
            currentFiles = Collections.unmodifiableList(new ArrayList<>(toLoad));
            logger.info("[MultiFileLoader] Reload complete: {} file(s) loaded", currentFiles.size());
            return LoadResult.success("Files loaded successfully", errors, currentFiles);
        } catch (Exception e) {
            logger.error("[MultiFileLoader] Reload failed: {}", e.getMessage(), e);
            return LoadResult.failure("Load failed: " + e.getMessage(), errors, currentFiles);
        }
    }

    /** Returns an immutable snapshot of the files currently loaded. */
    public List<File> getCurrentFiles() {
        return currentFiles;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static boolean isValidExtension(File f) {
        String name = f.getName().toLowerCase(Locale.ROOT);
        for (String ext : VALID_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * Structured result returned by {@link #loadFiles}.
     */
    public static final class LoadResult {
        public final boolean success;
        public final String message;
        /** Non-fatal file-validation errors (e.g. missing files). */
        public final List<String> errors;
        /** Files that are actually loaded after this operation. */
        public final List<File> loadedFiles;

        private LoadResult(boolean success, String message, List<String> errors, List<File> loadedFiles) {
            this.success = success;
            this.message = message;
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
            this.loadedFiles = Collections.unmodifiableList(new ArrayList<>(loadedFiles));
        }

        static LoadResult success(String message, List<String> errors, List<File> loadedFiles) {
            return new LoadResult(true, message, errors, loadedFiles);
        }

        static LoadResult failure(String message, List<String> errors, List<File> loadedFiles) {
            return new LoadResult(false, message, errors, loadedFiles);
        }

        /**
         * Returns a JSON-friendly map suitable for Javalin's {@code ctx.json()} or
         * manual Jackson serialization.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", success);
            m.put("message", message);
            m.put("errors", errors);
            m.put("loaded_files", loadedFiles.stream().map(File::getName).collect(Collectors.toList()));
            m.put("loaded_count", loadedFiles.size());
            // Hints callers to poll decompile-status endpoint after a successful load
            m.put("poll_with", success ? "decompile-status" : null);
            return m;
        }
    }
}
