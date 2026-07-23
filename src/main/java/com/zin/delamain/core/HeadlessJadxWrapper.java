package com.zin.delamain.core;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.args.GeneratedRenamesMappingFileMode;
import jadx.core.dex.nodes.RootNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Headless wrapper around {@link JadxDecompiler}.
 * Replaces the GUI-dependent {@code jadx.gui.JadxWrapper} used in the plugin version.
 */
public class HeadlessJadxWrapper {
    private static final Logger logger = LoggerFactory.getLogger(HeadlessJadxWrapper.class);

    private volatile JadxDecompiler jadx;
    private volatile List<File> inputFiles;
    private volatile File outputDir;
    private volatile int threads;
    private volatile LoadState loadState = LoadState.IDLE;
    private volatile String loadError;
    private volatile String lastReloadError;
    private final Object loadStateLock = new Object();
    private final ReentrantReadWriteLock analysisLock = new ReentrantReadWriteLock(true);
    /**
     * Set by {@link #beginReload()} and cleared when the reload ends (successfully or not). This —
     * not {@link LoadState#LOADING} — is what makes concurrent reloads mutually exclusive. Flipping
     * the load state at reservation time instead took the currently-loaded APK offline for the
     * entire time the reload spent waiting for the write lock, which on 2026-07-22 was "forever".
     */
    private volatile boolean reloadPending;
    /** State to restore when a reservation is abandoned without the reload ever starting. */
    private volatile LoadState stateBeforeReload = LoadState.IDLE;
    /** How long a reload waits for the analysis write lock before giving up. */
    private volatile long reloadLockTimeoutMillis = resolveReloadLockTimeoutMillis();
    /** Quiesce callbacks run under the write lock, immediately before the old decompiler is closed. */
    private final List<Runnable> preCloseQuiesceHooks = new CopyOnWriteArrayList<>();

    private static final long DEFAULT_RELOAD_LOCK_TIMEOUT_MILLIS = 60_000L;

    private static long resolveReloadLockTimeoutMillis() {
        String raw = System.getenv("DELAMAIN_RELOAD_LOCK_TIMEOUT_SECONDS");
        if (raw != null && !raw.isBlank()) {
            try {
                long seconds = Long.parseLong(raw.trim());
                if (seconds > 0) {
                    return seconds * 1000L;
                }
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return DEFAULT_RELOAD_LOCK_TIMEOUT_MILLIS;
    }

    /** Thrown when a reload could not acquire the analysis write lock within its timeout. */
    public static class ReloadLockTimeoutException extends IllegalStateException {
        public ReloadLockTimeoutException(String message) {
            super(message);
        }
    }
    // Immutable cache of the full class-with-inners list. jadx.getClassesWithInners() is stable
    // after load(); copying it into a fresh ArrayList on every call (there are 230k+ classes, and
    // every search request did this in its preamble) cost ~26ms/request and caused heavy
    // allocation/GC contention under concurrency. Cache once, clear on (re)load.
    private volatile List<JavaClass> classesWithInnersCache;

    /** Lifecycle state exposed by the status endpoints. */
    public enum LoadState {
        IDLE,
        LOADING,
        LOADED,
        FAILED
    }

    // --- C1 deobfuscation (AI-readable aliases) -----------------------------------------
    // JADX native deobf generates readable aliases for obfuscated symbols. Aliases are for
    // AI READING/navigation only — original/runtime names stay available via getRawName()
    // and are MANDATORY for Frida/Xposed hook generation (the runtime only knows raw names).
    private static final boolean DEOBF_ON = true;
    private static final int DEOBF_MIN_LENGTH = 3;
    private static final String DEOBF_MAP_FILENAME = "deobf-renames.jobf";

    /**
     * Stable fingerprint of the deobf configuration. Mixed into the persistent-index input
     * hash so toggling/retuning deobf invalidates raw-built artifacts instead of dirty-reading
     * them (the index key/sort basis differs between deobf states).
     */
    public static String deobfConfigTag() {
        return DEOBF_ON ? ("deobf=on;min=" + DEOBF_MIN_LENGTH + ";mapPersist") : "deobf=off";
    }

    /**
     * Applies deobf settings to a JadxArgs. The generated-renames map is persisted in the
     * output dir (a mounted volume) with READ_OR_SAVE so aliases stay stable across restarts.
     */
    private static void applyDeobfConfig(JadxArgs args, File outputDir) {
        if (!DEOBF_ON) {
            return;
        }
        args.setDeobfuscationOn(true);
        args.setDeobfuscationMinLength(DEOBF_MIN_LENGTH);
        if (outputDir != null) {
            args.setGeneratedRenamesMappingFile(new File(outputDir, DEOBF_MAP_FILENAME));
            args.setGeneratedRenamesMappingFileMode(GeneratedRenamesMappingFileMode.READ_OR_SAVE);
        }
    }

    /**
     * @param inputFiles list of APK/JAR/DEX files to decompile
     * @param outputDir  directory where decompiled sources will be written
     * @param threads    number of decompile worker threads
     */
    public HeadlessJadxWrapper(List<File> inputFiles, File outputDir, int threads) {
        this.inputFiles = new ArrayList<>(inputFiles);
        this.outputDir = outputDir;
        this.threads = threads;

        JadxArgs args = new JadxArgs();
        args.setInputFiles(this.inputFiles);
        args.setOutDir(outputDir);
        args.setThreadsCount(threads);
        applyDeobfConfig(args, outputDir);

        this.jadx = new JadxDecompiler(args);
        logger.info("[JADX] Wrapper initialized with {} file(s), output={}, threads={}, deobf={}",
                inputFiles.size(), outputDir, threads, deobfConfigTag());
    }

    /**
     * Loads and processes the input files.
     * Must be called before any class access.
     */
    public void load() {
        if (!beginReload()) {
            throw new IllegalStateException("A JADX file load is already in progress");
        }
        loadReserved();
    }

    /**
     * Returns a snapshot of all top-level classes.
     * Always copies the live list to avoid ConcurrentModificationException.
     */
    public List<JavaClass> getClasses() {
        return new ArrayList<>(jadx.getClasses());
    }

    /**
     * Returns all classes including inner classes, as an immutable cached list.
     *
     * <p>The list is built once and reused; the class set is stable for a loaded APK (renames do
     * not change it). Callers must not mutate the result — those that need to sort/filter copy it
     * first (e.g. WarmupManager, ClassCacheManager already do). The cache is cleared on (re)load.</p>
     */
    public List<JavaClass> getClassesWithInners() {
        List<JavaClass> cached = classesWithInnersCache;
        if (cached == null) {
            synchronized (this) {
                cached = classesWithInnersCache;
                if (cached == null) {
                    cached = List.copyOf(jadx.getClassesWithInners());
                    classesWithInnersCache = cached;
                }
            }
        }
        return cached;
    }

    /**
     * Total number of top-level classes.
     */
    public int getTotalClassCount() {
        return jadx.getClasses().size();
    }

    /**
     * Returns the underlying {@link JadxDecompiler} for direct API access.
     */
    public JadxDecompiler getJadx() {
        return jadx;
    }

    /**
     * Returns the root node for low-level access.
     */
    public RootNode getRoot() {
        return jadx.getRoot();
    }

    /**
     * Returns the decompiler args (for cache mode, thread count, etc.).
     */
    public JadxArgs getArgs() {
        return jadx.getArgs();
    }

    /**
     * Returns true once {@link #load()} has completed successfully.
     */
    public boolean isLoaded() {
        return loadState == LoadState.LOADED;
    }

    public LoadState getLoadState() {
        return loadState;
    }

    public String getLoadError() {
        return loadError;
    }

    /**
     * Reserves the wrapper for an asynchronous reload. The reservation is mutual exclusion between
     * reloads only — it deliberately does NOT take the currently-loaded APK offline. The load state
     * flips to {@link LoadState#LOADING} inside {@link #reloadReserved} once the write lock is
     * actually held, i.e. once the reload can really start.
     *
     * <p>Reserving used to flip the state immediately, which meant a reload that then waited on the
     * write lock returned {@code 503 apk_not_ready} for every request in the meantime — and since
     * that wait had no timeout, a single stuck analysis request took the whole service down until
     * the container was restarted (production, 2026-07-22).</p>
     */
    public boolean beginReload() {
        synchronized (loadStateLock) {
            if (reloadPending) {
                return false;
            }
            reloadPending = true;
            stateBeforeReload = loadState;
            if (loadState != LoadState.LOADED) {
                // Nothing is being served anyway (first load, or a previous failure): showing
                // LOADING right away is the honest status and keeps /decompile-status meaningful.
                loadState = LoadState.LOADING;
                loadError = null;
            }
            return true;
        }
    }

    /**
     * Acquires a shared analysis permit. The caller must release it with
     * {@link #releaseAnalysisAccess()} after the request completes.
     *
     * <p>Uses the <b>untimed</b> {@code tryLock()} on purpose. {@code analysisLock} is fair, and a
     * fair lock's <i>timed</i> {@code tryLock} refuses to barge past queued writers — so with a
     * reload queued for the write lock, every incoming request was rejected even though the loaded
     * APK could serve it perfectly well. The untimed form barges, which is exactly what keeps the
     * service available while a reload waits its turn. Writer starvation is bounded by the reload's
     * own {@link #reloadLockTimeoutMillis}: a reload that cannot get in fails loudly instead of
     * waiting forever.</p>
     */
    public boolean tryAcquireAnalysisAccess() {
        if (loadState != LoadState.LOADED) {
            return false;
        }
        if (!analysisLock.readLock().tryLock()) {
            return false;
        }
        if (loadState != LoadState.LOADED) {
            analysisLock.readLock().unlock();
            return false;
        }
        return true;
    }

    /**
     * Registers a callback to run under the reload write lock, just before the old decompiler is
     * closed. Used to stop background work that does not go through {@link #tryAcquireAnalysisAccess}
     * (warmup), which would otherwise touch a closed {@link JadxDecompiler}.
     */
    public void addPreCloseQuiesceHook(Runnable hook) {
        if (hook != null) {
            preCloseQuiesceHooks.add(hook);
        }
    }

    /** Test seam / operational override for how long a reload waits for the write lock. */
    public void setReloadLockTimeoutMillis(long millis) {
        this.reloadLockTimeoutMillis = millis;
    }

    public long getReloadLockTimeoutMillis() {
        return reloadLockTimeoutMillis;
    }

    /** Error text of the last failed reload; null once a reload succeeds. */
    public String getLastReloadError() {
        return lastReloadError;
    }

    /** Number of analysis requests currently holding the read lock (diagnostics for /health). */
    public int getAnalysisReadLockCount() {
        return analysisLock.getReadLockCount();
    }

    /** True while a reload is reserved but has not finished (diagnostics for /health). */
    public boolean isReloadPending() {
        return reloadPending;
    }

    public void releaseAnalysisAccess() {
        analysisLock.readLock().unlock();
    }

    /**
     * Returns the list of input files currently loaded into this wrapper.
     */
    public List<File> getInputFiles() {
        return Collections.unmodifiableList(inputFiles);
    }

    public File getOutputDir() {
        return outputDir;
    }

    public int getThreads() {
        return threads;
    }

    /**
     * Closes the current decompiler instance, creates a fresh one with the supplied
     * files/config, and calls {@link #load()} on it.
     *
     * <p>This is the entry-point for {@code MultiFileLoader}: it handles both
     * "replace" and "append" semantics at the file-list level; by the time this
     * method is called the caller has already merged the lists.</p>
     *
     * @param newInputFiles files to load in the new instance
     * @param outputDir     output directory for decompiled sources
     * @param threads       number of decompile worker threads
     */
    public void reload(List<File> newInputFiles, File outputDir, int threads) {
        if (!beginReload()) {
            throw new IllegalStateException("A JADX file load is already in progress");
        }
        reloadReserved(newInputFiles, outputDir, threads);
    }

    /**
     * Runs a reload reserved by {@link #beginReload()}. This is used by the HTTP route so the
     * loading state is visible before its background task begins.
     */
    public void reloadReserved(List<File> newInputFiles, File outputDir, int threads) {
        reloadReserved(newInputFiles, outputDir, threads, null);
    }

    /**
     * Runs a reload reserved by {@link #beginReload()} and performs a cache invalidation callback
     * before making the new input visible to analysis requests.
     *
     * @param postLoadAction optional callback executed under the reload write lock after JADX
     *                       loads and before the state becomes {@link LoadState#LOADED}
     */
    public void reloadReserved(List<File> newInputFiles, File outputDir, int threads,
                               Runnable postLoadAction) {
        if (!reloadPending) {
            throw new IllegalStateException("Reload was not reserved");
        }
        long timeoutMillis = reloadLockTimeoutMillis;
        boolean acquired;
        try {
            acquired = analysisLock.writeLock().tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandonReload("reload interrupted while waiting for the analysis write lock");
            throw new ReloadLockTimeoutException(
                "Reload interrupted while waiting for the analysis write lock");
        }
        if (!acquired) {
            // Giving up here is the whole point: the previous unconditional lock() parked forever
            // behind a stuck reader, and every request 503'd until someone restarted the container.
            String message = "Could not start the reload: an analysis request has held the read lock "
                + "for longer than " + timeoutMillis + "ms ("
                + analysisLock.getReadLockCount() + " read lock holder(s), "
                + analysisLock.getQueueLength() + " thread(s) queued). The currently loaded file "
                + "stays available; retry, or restart the server if the request never finishes.";
            abandonReload(message);
            logger.error("[JADX] {}", message);
            throw new ReloadLockTimeoutException(message);
        }
        try {
            synchronized (loadStateLock) {
                // Only now is the old input really going away, so only now do requests become 503.
                loadState = LoadState.LOADING;
                loadError = null;
                lastReloadError = null;
            }
            doReload(newInputFiles, outputDir, threads, postLoadAction);
        } finally {
            synchronized (loadStateLock) {
                reloadPending = false;
            }
            analysisLock.writeLock().unlock();
        }
    }

    /** Releases a reservation whose reload never started, leaving the loaded file serving. */
    private void abandonReload(String message) {
        synchronized (loadStateLock) {
            reloadPending = false;
            lastReloadError = message;
            loadState = stateBeforeReload;
        }
    }

    private void doReload(List<File> newInputFiles, File outputDir, int threads,
                          Runnable postLoadAction) {
        List<File> previousInputFiles = List.copyOf(this.inputFiles);
        File previousOutputDir = this.outputDir;
        int previousThreads = this.threads;
        boolean previousInputWasLoaded = stateBeforeReload == LoadState.LOADED;
        boolean previousDecompilerNeedsRebuild = false;

        logger.info("[JADX] Reloading: {} file(s), output={}, threads={}",
                newInputFiles.size(), outputDir, threads);
        try {
            // Reject missing/unreadable inputs before quiescing background work or touching the
            // currently-loaded decompiler. This keeps the old APK continuously available.
            validateInputFilesReadable(newInputFiles);

            // Stop background work that does not hold the analysis read lock (warmup) before the
            // decompiler it is walking gets closed underneath it. Holding the write lock only
            // quiesces request handlers; this covers the rest.
            for (Runnable hook : preCloseQuiesceHooks) {
                try {
                    hook.run();
                } catch (Exception e) {
                    logger.warn("[JADX] Pre-close quiesce hook failed: {}", e.getMessage());
                }
            }
            previousDecompilerNeedsRebuild = true;
            // Do not start loading the replacement if the old instance cannot be closed. Keeping
            // that error in the rollback path avoids two fully-loaded large APKs coexisting.
            jadx.close();
            configureDecompiler(newInputFiles, outputDir, threads);
            int totalClasses = loadCurrentDecompiler(postLoadAction);
            markLoadSucceeded();
            logger.info("[JADX] Reload complete: {} classes", totalClasses);
        } catch (RuntimeException | Error reloadFailure) {
            String reloadMessage = throwableMessage(reloadFailure);
            if (!previousInputWasLoaded) {
                markLoadFailed(reloadFailure);
                synchronized (loadStateLock) {
                    lastReloadError = reloadMessage;
                }
                throw reloadFailure;
            }

            if (!previousDecompilerNeedsRebuild) {
                restoreLoadedStateAfterFailedReload(reloadMessage);
                throw reloadFailure;
            }

            try {
                // Closing the failed replacement is part of rollback. Unlike the best-effort close
                // of a successfully quiesced old instance, a failure here must be reported as a
                // rollback failure instead of being hidden behind a warning.
                jadx.close();
                configureDecompiler(previousInputFiles, previousOutputDir, previousThreads);
                // Restoring the old JADX instance must not run the callback: that callback owns
                // external caches and is only valid after a successful replacement.
                int restoredClasses = loadCurrentDecompiler(null);
                restoreLoadedStateAfterFailedReload(reloadMessage);
                logger.warn("[JADX] Reload failed; restored previous input with {} classes: {}",
                        restoredClasses, reloadMessage);
            } catch (RuntimeException | Error rollbackFailure) {
                reloadFailure.addSuppressed(rollbackFailure);
                String combinedMessage = "Reload failed for new input: " + reloadMessage
                        + "; rollback of previous input failed: "
                        + throwableMessage(rollbackFailure);
                markReloadAndRollbackFailed(combinedMessage);
                throw reloadFailure;
            }
            throw reloadFailure;
        }
    }

    private void configureDecompiler(List<File> newInputFiles, File outputDir, int threads) {
        this.inputFiles = Collections.unmodifiableList(new ArrayList<>(newInputFiles));
        this.outputDir = outputDir;
        this.threads = threads;
        this.classesWithInnersCache = null;

        JadxArgs args = new JadxArgs();
        args.setInputFiles(this.inputFiles);
        args.setOutDir(outputDir);
        args.setThreadsCount(threads);
        applyDeobfConfig(args, outputDir);

        this.jadx = new JadxDecompiler(args);
    }

    private void loadReserved() {
        loadReserved(null);
    }

    private void loadReserved(Runnable postLoadAction) {
        analysisLock.writeLock().lock();
        try {
            logger.info("[JADX] Loading {} file(s)...", inputFiles.size());
            int totalClasses = loadCurrentDecompiler(postLoadAction);
            markLoadSucceeded();
            logger.info("[JADX] Load complete: {} classes", totalClasses);
        } catch (RuntimeException | Error t) {
            markLoadFailed(t);
            throw t;
        } finally {
            synchronized (loadStateLock) {
                reloadPending = false;
            }
            analysisLock.writeLock().unlock();
        }
    }

    private int loadCurrentDecompiler(Runnable postLoadAction) {
        validateInputFilesReadable(inputFiles);
        long start = System.currentTimeMillis();
        jadx.load();
        classesWithInnersCache = null; // rebuild lazily against the freshly loaded class set
        int totalClasses = jadx.getClasses().size();
        if (totalClasses == 0) {
            throw new IllegalStateException("JADX loaded zero classes from input file(s): "
                    + describeInputFiles(inputFiles)
                    + ". Verify that the file is a supported APK/JAR/DEX with code and that "
                    + "the service account can read it.");
        }
        if (postLoadAction != null) {
            postLoadAction.run();
        }
        logger.info("[JADX] Loaded {} classes in {}ms",
                totalClasses, System.currentTimeMillis() - start);
        return totalClasses;
    }

    private void validateInputFilesReadable(List<File> files) {
        for (File inputFile : files) {
            Path path = inputFile.toPath();
            String displayPath = path.toAbsolutePath().toString();
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("Cannot load input file " + displayPath
                        + ": file does not exist");
            }
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Cannot load input file " + displayPath
                        + ": not a regular file");
            }
            if (!Files.isReadable(path)) {
                throw new IllegalArgumentException("Cannot load input file " + displayPath
                        + ": file is not readable by the current process");
            }
        }
    }

    private String describeInputFiles(List<File> files) {
        return files.stream()
                .map(file -> file.toPath().toAbsolutePath().toString())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private void markLoadSucceeded() {
        synchronized (loadStateLock) {
            loadState = LoadState.LOADED;
            loadError = null;
        }
    }

    private void restoreLoadedStateAfterFailedReload(String reloadMessage) {
        synchronized (loadStateLock) {
            loadState = LoadState.LOADED;
            loadError = reloadMessage;
            lastReloadError = reloadMessage;
        }
    }

    private void markReloadAndRollbackFailed(String combinedMessage) {
        synchronized (loadStateLock) {
            loadState = LoadState.FAILED;
            loadError = combinedMessage;
            lastReloadError = combinedMessage;
        }
    }

    private String throwableMessage(Throwable t) {
        String message = t.getMessage();
        return (message == null || message.isBlank())
                ? t.getClass().getSimpleName()
                : message;
    }

    private void markLoadFailed(Throwable t) {
        synchronized (loadStateLock) {
            loadState = LoadState.FAILED;
            loadError = throwableMessage(t);
        }
    }

    /**
     * Attempts to extract the Android package name from the loaded APK.
     * Returns null if not available (e.g. JAR input).
     */
    public String getApkPackageName() {
        if (!isLoaded()) {
            return null;
        }
        try {
            RootNode root = jadx.getRoot();
            if (root != null) {
                return root.getAppPackage();
            }
        } catch (Exception e) {
            logger.debug("[JADX] Could not read package name: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Closes the decompiler and releases resources.
     */
    public void close() {
        try {
            jadx.close();
            logger.info("[JADX] Wrapper closed");
        } catch (Exception e) {
            logger.warn("[JADX] Error during close: {}", e.getMessage());
        } finally {
            synchronized (loadStateLock) {
                loadState = LoadState.IDLE;
                loadError = null;
            }
        }
    }
}
