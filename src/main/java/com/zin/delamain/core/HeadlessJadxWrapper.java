package com.zin.delamain.core;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.args.GeneratedRenamesMappingFileMode;
import jadx.core.dex.nodes.RootNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private final Object loadStateLock = new Object();
    private final ReentrantReadWriteLock analysisLock = new ReentrantReadWriteLock(true);
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
     * Reserves the wrapper for an asynchronous reload. Once reserved, all analysis requests
     * receive a clear 503 until {@link #reloadReserved(List, File, int)} completes.
     */
    public boolean beginReload() {
        synchronized (loadStateLock) {
            if (loadState == LoadState.LOADING) {
                return false;
            }
            loadState = LoadState.LOADING;
            loadError = null;
            return true;
        }
    }

    /**
     * Acquires a shared analysis permit. The caller must release it with
     * {@link #releaseAnalysisAccess()} after the request completes.
     */
    public boolean tryAcquireAnalysisAccess() {
        if (loadState != LoadState.LOADED) {
            return false;
        }
        try {
            if (!analysisLock.readLock().tryLock(0, TimeUnit.MILLISECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (loadState != LoadState.LOADED) {
            analysisLock.readLock().unlock();
            return false;
        }
        return true;
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
        if (loadState != LoadState.LOADING) {
            throw new IllegalStateException("Reload was not reserved");
        }
        analysisLock.writeLock().lock();
        try {
            doReload(newInputFiles, outputDir, threads, postLoadAction);
        } catch (RuntimeException | Error t) {
            markLoadFailed(t);
            throw t;
        } finally {
            analysisLock.writeLock().unlock();
        }
    }

    private void doReload(List<File> newInputFiles, File outputDir, int threads,
                          Runnable postLoadAction) {
        logger.info("[JADX] Reloading: {} file(s), output={}, threads={}",
                newInputFiles.size(), outputDir, threads);
        // Close the existing decompiler before creating a new one
        try {
            jadx.close();
        } catch (Exception e) {
            logger.warn("[JADX] Error closing previous decompiler during reload: {}", e.getMessage());
        }

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
        loadReserved(postLoadAction);
    }

    private void loadReserved() {
        loadReserved(null);
    }

    private void loadReserved(Runnable postLoadAction) {
        analysisLock.writeLock().lock();
        try {
            logger.info("[JADX] Loading {} file(s)...", inputFiles.size());
            long start = System.currentTimeMillis();
            jadx.load();
            classesWithInnersCache = null; // rebuild lazily against the freshly loaded class set
            if (postLoadAction != null) {
                postLoadAction.run();
            }
            long elapsed = System.currentTimeMillis() - start;
            synchronized (loadStateLock) {
                loadState = LoadState.LOADED;
                loadError = null;
            }
            logger.info("[JADX] Load complete: {} classes in {}ms",
                    jadx.getClasses().size(), elapsed);
        } catch (RuntimeException | Error t) {
            markLoadFailed(t);
            throw t;
        } finally {
            analysisLock.writeLock().unlock();
        }
    }

    private void markLoadFailed(Throwable t) {
        synchronized (loadStateLock) {
            loadState = LoadState.FAILED;
            String message = t.getMessage();
            loadError = (message == null || message.isBlank())
                    ? t.getClass().getSimpleName()
                    : message;
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
