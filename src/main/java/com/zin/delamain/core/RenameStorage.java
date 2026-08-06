package com.zin.delamain.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.JadxSearchLock;
import jadx.api.JadxArgs;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaVariable;
import jadx.api.data.ICodeData;
import jadx.api.data.ICodeRename;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRef;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Persistent rename / alias store for headless JADX rename operations.
 *
 * <h2>Two strategies, because nodes and locals do not behave the same</h2>
 * <p><b>Classes, methods, fields — alias on the node.</b> JADX exposes a public API chain that
 * works without any GUI:</p>
 * <ul>
 *   <li>{@code JavaClass.getClassNode().rename(newName)}</li>
 *   <li>{@code JavaMethod.getMethodNode().rename(newName)}</li>
 *   <li>{@code JavaField.getFieldNode().rename(newName)}</li>
 * </ul>
 * <p>Calling {@code rename()} sets the alias on the underlying info object so that
 * subsequent calls to {@code getClassNode().decompile()} or {@code reloadCode()} will
 * reflect the new name.  The previous decompiled code cache should be invalidated by
 * calling {@code classNode.unloadFromCache()} after renaming.</p>
 *
 * <p><b>Local variables — a rule, not an alias.</b> The equivalent {@code VarNode.setName()}
 * does <em>not</em> survive, because a local's name is rebuilt from bytecode on every decompile;
 * the {@code unloadFromCache()} that publishes the other three renames is what erases this one.
 * Locals therefore go through {@code JadxCodeRename} rules in {@link jadx.api.JadxArgs}'s code
 * data, re-applied on each decompile by {@code CodeRenameVisitor}. See
 * {@link #renameVariable}.</p>
 *
 * <h2>Persistence format</h2>
 * Renames are serialised as a JSON file ({@code renames.json}) in the supplied data
 * directory:
 * <pre>
 * {
 *   "classes":   { "com.example.a": "com.example.LoginActivity" },
 *   "methods":   { "com.example.a#a()V": "doLogin" },
 *   "fields":    { "com.example.a#b:Ljava/lang/String;": "userId" },
 *   "variables": { "com.example.a#a()V#2:2": "aesCipher" }
 * }
 * </pre>
 * Keys follow the convention below:
 * <ul>
 *   <li>classes — fully-qualified class name (dots, no leading/trailing separators)</li>
 *   <li>methods — {@code declaringClass#methodShortId}  (shortId = name + descriptor,
 *       e.g. {@code a()V})</li>
 *   <li>fields  — {@code declaringClass#fieldName:fieldType}</li>
 *   <li>variables — {@code declaringClass#methodShortId#reg:ssa} (see {@link #variableKey})</li>
 * </ul>
 *
 * <p>This file records what was renamed; it is not a restore mechanism. Nothing re-applies it to
 * a fresh session — true for all four kinds alike, not a gap specific to variables.</p>
 *
 * <h2>Thread safety</h2>
 * All public mutating methods are {@code synchronized}.  Read methods ({@code getXxxAlias})
 * are not synchronised but operate on a volatile-published snapshot that is safe for
 * concurrent reads.
 */
public class RenameStorage {

    private static final Logger logger = LoggerFactory.getLogger(RenameStorage.class);

    private final Path storagePath;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** Live data; replaced atomically after each save. */
    private volatile RenameData data;

    /** True once a corrupt/unparseable {@code renames.json} forced this session to start with an
     *  empty rename set; see {@link #getDegradedReason()} for what happened and where the
     *  original file was preserved. */
    private volatile boolean degraded = false;
    private volatile String degradedReason = null;

    /**
     * @param dataDir directory where {@code renames.json} is stored; created if absent
     */
    public RenameStorage(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        this.storagePath = dataDir.resolve("renames.json");
        this.data = loadFromDisk();
        logger.info("[RenameStorage] Loaded {} class / {} method / {} field / {} variable rename(s) from {}",
                data.classes.size(), data.methods.size(), data.fields.size(),
                data.variables.size(), storagePath);
    }

    // -------------------------------------------------------------------------
    // Apply rename to live JADX node + persist
    // -------------------------------------------------------------------------

    /**
     * Renames a class in the live JADX tree and persists the mapping.
     *
     * <p>After the rename, the class's cached decompiled code is invalidated so that
     * the next call to {@code getCode()} uses the new name.</p>
     *
     * @param cls     target class; must not be null
     * @param newName new simple class name (without package prefix)
     */
    public synchronized void renameClass(JavaClass cls, String newName) throws IOException {
        Objects.requireNonNull(cls, "cls");
        Objects.requireNonNull(newName, "newName");

        String originalName = cls.getFullName();
        // Hold the search write lock across the live-node mutation + index refresh so a
        // concurrent code search never reads a class mid-rename (stale name buckets / half
        // -renamed node). Also blocks until any in-flight search releases the lock first.
        if (!JadxSearchLock.tryAcquire(JadxSearchLock.LOCK_TIMEOUT_SECONDS)) {
            throw new IOException("Timed out waiting for search lock before renaming class '" + originalName + "'");
        }
        try {
            cls.getClassNode().rename(newName);
            // Invalidate cached decompile output so the new name is reflected
            cls.getClassNode().unloadFromCache();
            // Refresh name-index buckets + classCache key so the new alias is searchable and
            // resolvable by findClass() immediately (previously ClassCacheManager.reindex() was
            // defined but never called from any rename path — new alias was unsearchable
            // and the classCache key went stale).
            ClassCacheManager.reindexRenamedClass(cls, originalName);
        } finally {
            JadxSearchLock.release();
        }

        putAndSave(data.classes, originalName, newName);
        logger.info("[RenameStorage] Renamed class '{}' → '{}'", originalName, newName);
    }

    /**
     * Renames a method in the live JADX tree and persists the mapping.
     *
     * @param method  target method; must not be null
     * @param newName new method name
     */
    public synchronized void renameMethod(JavaMethod method, String newName) throws IOException {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(newName, "newName");

        String key = methodKey(method);
        JavaClass declaringClass = method.getDeclaringClass();
        String declaringClassFullName = declaringClass != null ? declaringClass.getFullName() : null;

        if (!JadxSearchLock.tryAcquire(JadxSearchLock.LOCK_TIMEOUT_SECONDS)) {
            throw new IOException("Timed out waiting for search lock before renaming method '" + key + "'");
        }
        try {
            method.getMethodNode().rename(newName);
            // Invalidate the declaring class cache so decompiled output shows the new name
            if (declaringClass != null) {
                declaringClass.getClassNode().unloadFromCache();
                // Refreshes the method-name index bucket for the declaring class (see renameClass
                // for why this wiring matters — reindex() was previously dead code).
                ClassCacheManager.reindexRenamedClass(declaringClass, declaringClassFullName);
            }
        } finally {
            JadxSearchLock.release();
        }

        putAndSave(data.methods, key, newName);
        logger.info("[RenameStorage] Renamed method '{}' → '{}'", key, newName);
    }

    /**
     * Renames a field in the live JADX tree and persists the mapping.
     *
     * @param field   target field; must not be null
     * @param newName new field name
     */
    public synchronized void renameField(JavaField field, String newName) throws IOException {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(newName, "newName");

        String key = fieldKey(field);
        JavaClass declaringClass = field.getDeclaringClass();
        String declaringClassFullName = declaringClass != null ? declaringClass.getFullName() : null;

        if (!JadxSearchLock.tryAcquire(JadxSearchLock.LOCK_TIMEOUT_SECONDS)) {
            throw new IOException("Timed out waiting for search lock before renaming field '" + key + "'");
        }
        try {
            field.getFieldNode().rename(newName);
            if (declaringClass != null) {
                declaringClass.getClassNode().unloadFromCache();
                ClassCacheManager.reindexRenamedClass(declaringClass, declaringClassFullName);
            }
        } finally {
            JadxSearchLock.release();
        }

        putAndSave(data.fields, key, newName);
        logger.info("[RenameStorage] Renamed field '{}' → '{}'", key, newName);
    }

    /**
     * Renames a local variable and persists the mapping.
     *
     * <h3>Why this one is not like the other three</h3>
     * Class/method/field renames set an alias on a node that outlives decompilation, so
     * {@code node.rename()} + {@code unloadFromCache()} is enough. A local variable's name lives
     * in SSA state that is rebuilt from bytecode on every decompile, so mutating
     * {@code VarNode.setName()} is discarded by the very {@code unloadFromCache()} that is
     * supposed to publish it — measured, not assumed.
     *
     * <p>The durable form is therefore a <em>rule</em>: a {@link JadxCodeRename} keyed by
     * method + (reg, ssa), registered in {@link JadxArgs#setCodeData}, which
     * {@code jadx.core.dex.visitors.rename.CodeRenameVisitor} re-applies on every decompile.
     * {@code reloadCodeData()} only notifies that visitor to rebuild its map, so this costs
     * O(renames) and does not invalidate anything globally — the one class whose code is now
     * stale is unloaded explicitly below.</p>
     *
     * @param wrapper owner of the decompiler whose code data holds the rename rules
     * @param var     target variable; must not be null
     * @param newName new variable name
     */
    public synchronized void renameVariable(HeadlessJadxWrapper wrapper, JavaVariable var, String newName)
            throws IOException {
        Objects.requireNonNull(wrapper, "wrapper");
        Objects.requireNonNull(var, "var");
        Objects.requireNonNull(newName, "newName");

        String key = variableKey(var);
        JavaClass declaringClass = var.getDeclaringClass();

        if (!JadxSearchLock.tryAcquire(JadxSearchLock.LOCK_TIMEOUT_SECONDS)) {
            throw new IOException("Timed out waiting for search lock before renaming variable '" + key + "'");
        }
        try {
            applyVariableRenameRule(wrapper, var, newName);
            if (declaringClass != null) {
                // Only this class's code is now stale; reloadCodeData() itself invalidates nothing.
                declaringClass.getClassNode().unloadFromCache();
                ClassCacheManager.invalidateCode(declaringClass.getFullName());
            }
        } finally {
            JadxSearchLock.release();
        }

        putAndSave(data.variables, key, newName);
        logger.info("[RenameStorage] Renamed variable '{}' → '{}'", key, newName);
    }

    /**
     * Adds (or replaces) the code-rename rule for one variable, preserving every rule already
     * registered. {@link JadxArgs#getCodeData} holds a single immutable-by-convention list, so it
     * is rebuilt rather than mutated in place.
     */
    private static void applyVariableRenameRule(HeadlessJadxWrapper wrapper, JavaVariable var, String newName) {
        JadxArgs args = wrapper.getArgs();
        ICodeData existing = args.getCodeData();

        JadxNodeRef methodRef = JadxNodeRef.forMth(var.getMth());
        JadxCodeRef varRef = JadxCodeRef.forVar(var);

        List<ICodeRename> renames = new ArrayList<>();
        if (existing != null && existing.getRenames() != null) {
            for (ICodeRename r : existing.getRenames()) {
                // Renaming the same variable twice replaces the rule instead of stacking a second
                // one whose relative order would decide the winner.
                if (methodRef.equals(r.getNodeRef()) && varRef.equals(r.getCodeRef())) continue;
                renames.add(r);
            }
        }
        renames.add(new JadxCodeRename(methodRef, varRef, newName));

        JadxCodeData codeData = new JadxCodeData();
        codeData.setRenames(renames);
        if (existing != null && existing.getComments() != null) {
            codeData.setComments(new ArrayList<>(existing.getComments()));
        }
        args.setCodeData(codeData);
        wrapper.getJadx().reloadCodeData();
    }

    // -------------------------------------------------------------------------
    // String-key convenience methods (for serialisation / restore on reload)
    // -------------------------------------------------------------------------

    /** Persists a class rename by original full name (used during session restore). */
    public synchronized void renameClass(String originalFullName, String newName) throws IOException {
        putAndSave(data.classes, originalFullName, newName);
    }

    /** Persists a method rename by key ({@code declaringClass#shortId}). */
    public synchronized void renameMethod(String methodKey, String newName) throws IOException {
        putAndSave(data.methods, methodKey, newName);
    }

    /** Persists a field rename by key ({@code declaringClass#fieldName:type}). */
    public synchronized void renameField(String fieldKey, String newName) throws IOException {
        putAndSave(data.fields, fieldKey, newName);
    }

    // -------------------------------------------------------------------------
    // Read accessors
    // -------------------------------------------------------------------------

    public String getClassAlias(String originalFullName) {
        return data.classes.get(originalFullName);
    }

    public String getMethodAlias(String methodKey) {
        return data.methods.get(methodKey);
    }

    public String getFieldAlias(String fieldKey) {
        return data.fields.get(fieldKey);
    }

    public Map<String, String> getAllClassRenames() {
        return Collections.unmodifiableMap(data.classes);
    }

    public Map<String, String> getAllMethodRenames() {
        return Collections.unmodifiableMap(data.methods);
    }

    public Map<String, String> getAllFieldRenames() {
        return Collections.unmodifiableMap(data.fields);
    }

    public Map<String, String> getAllVariableRenames() {
        return Collections.unmodifiableMap(data.variables);
    }

    /** Returns total number of persisted renames (all node types combined). */
    public int size() {
        RenameData d = data;
        return d.classes.size() + d.methods.size() + d.fields.size() + d.variables.size();
    }

    /** True once loading hit a corrupt/unparseable {@code renames.json} this session -- this
     *  session started with an EMPTY rename set even though the on-disk file was not itself
     *  empty. See {@link #getDegradedReason()} for detail and where the original file went. */
    public boolean isDegraded() {
        return degraded;
    }

    /** Human-readable detail for {@link #isDegraded()}; {@code null} while not degraded. */
    public String getDegradedReason() {
        return degradedReason;
    }

    // -------------------------------------------------------------------------
    // Key builders
    // -------------------------------------------------------------------------

    /** {@code declaringClass#shortId}  e.g. {@code com.example.a#a()V} */
    public static String methodKey(JavaMethod method) {
        String declaringClass = method.getDeclaringClass() != null
                ? method.getDeclaringClass().getFullName() : "<unknown>";
        return declaringClass + "#" + method.getMethodNode().getMethodInfo().getShortId();
    }

    /** {@code declaringClass#fieldName}  e.g. {@code com.example.a#b} */
    public static String fieldKey(JavaField field) {
        String declaringClass = field.getDeclaringClass() != null
                ? field.getDeclaringClass().getFullName() : "<unknown>";
        return declaringClass + "#" + field.getFieldNode().getFieldInfo().getShortId();
    }

    /**
     * {@code declaringClass#methodShortId#reg:ssa}  e.g. {@code com.example.a#a()V#2:2}
     *
     * <p>Register + SSA version rather than the variable's current name, because the name is what
     * is being changed and because one name can map to several distinct variables in a method
     * (two loops each declaring {@code i}). Reg/SSA is what {@link JadxCodeRef#forVar} keys on.</p>
     */
    public static String variableKey(JavaVariable var) {
        JavaMethod mth = var.getMth();
        String declaringClass = mth != null && mth.getDeclaringClass() != null
                ? mth.getDeclaringClass().getFullName() : "<unknown>";
        String shortId = mth != null ? mth.getMethodNode().getMethodInfo().getShortId() : "<unknown>";
        return declaringClass + "#" + shortId + "#" + var.getReg() + ":" + var.getSsa();
    }

    // -------------------------------------------------------------------------
    // Batch rename (rename-perf fix + orderly interruption)
    // -------------------------------------------------------------------------

    /**
     * Begins a batch: acquires {@link JadxSearchLock}'s write lock ONCE for however many items the
     * caller applies through the returned {@link Batch}, instead of once per item (rename-perf fix
     * -- see {@link ClassCacheManager#reindexRenamedClassesBatch}, which this collapses every
     * item's index refresh into a single end-of-batch call for).
     *
     * <p>Holding one lock across a whole batch means a slow/huge batch is now the thing most likely
     * to run past {@link JadxSearchLock}'s stale-holder watchdog timeout -- which is exactly why
     * {@link Batch#isInterrupted()} exists: callers MUST check it before starting each item so the
     * watchdog's interrupt signal can produce an orderly, partial exit (lock released, everything
     * completed so far persisted) instead of a write lock that is held forever because nothing in
     * the loop ever looks at {@code Thread.interrupted()}.</p>
     *
     * <p>Callers MUST close the returned {@link Batch} (try-with-resources) so the lock is always
     * released and the batch's renames are indexed/persisted exactly once.</p>
     */
    public synchronized Batch beginBatch() throws IOException {
        if (!JadxSearchLock.tryAcquire(JadxSearchLock.LOCK_TIMEOUT_SECONDS)) {
            throw new IOException("Timed out waiting for search lock before starting batch rename");
        }
        return new Batch();
    }

    /**
     * A single batch rename session obtained from {@link #beginBatch()}.
     *
     * <pre>
     * try (RenameStorage.Batch batch = storage.beginBatch()) {
     *     for (Item item : items) {
     *         if (batch.isInterrupted()) break;   // safe checkpoint -- see class javadoc
     *         batch.renameClass(item.cls, item.newName);
     *     }
     * } // close(): one reindexRenamedClassesBatch() call, one saveToDisk(), lock released
     * </pre>
     *
     * <h2>Where the interrupt checkpoint is safe, and where it is not</h2>
     * <p>{@link #isInterrupted()} is meant to be checked ONLY between items, never in the middle of
     * one. Once {@link #renameClass}/{@link #renameMethod}/{@link #renameField} starts mutating a
     * live JADX node it always runs the rename + index-bucket refresh for that single node through
     * to completion before returning -- stopping partway through would leave the node's alias
     * updated but its name-index bucket / {@code classCache} key still pointing at the old name (a
     * half-renamed, unsearchable node). That is the exact data corruption the watchdog redesign
     * (dropping the old steal-the-lock {@code forceRelease()} behavior) was meant to prevent, so
     * this class deliberately gives callers no way to interrupt inside a single item -- only
     * between two fully-committed ones.</p>
     */
    public final class Batch implements Closeable {
        private final List<ClassCacheManager.RenamedClass> touched = new ArrayList<>();
        private final Map<String, String> classesSnapshot;
        private final Map<String, String> methodsSnapshot;
        private final Map<String, String> fieldsSnapshot;
        private boolean closed = false;

        private Batch() {
            // Snapshots let close() roll every map mutation this batch made back at once if the
            // single end-of-batch saveToDisk() fails -- same map/disk-divergence concern as
            // putAndSave(), just covering every key this batch touched instead of one.
            this.classesSnapshot = new LinkedHashMap<>(data.classes);
            this.methodsSnapshot = new LinkedHashMap<>(data.methods);
            this.fieldsSnapshot = new LinkedHashMap<>(data.fields);
        }

        /**
         * True once this thread has been cooperatively asked to stop (e.g. by
         * {@link JadxSearchLock}'s stale-holder watchdog calling {@code Thread.interrupt()} on the
         * batch-driving thread). Check this BEFORE starting the next item -- see class javadoc for
         * why that is the only safe place.
         */
        public boolean isInterrupted() {
            return Thread.currentThread().isInterrupted();
        }

        /** Renames one class as part of this batch. Must run to completion once started (see class javadoc). */
        public void renameClass(JavaClass cls, String newName) {
            Objects.requireNonNull(cls, "cls");
            Objects.requireNonNull(newName, "newName");
            String originalName = cls.getFullName();
            cls.getClassNode().rename(newName);
            cls.getClassNode().unloadFromCache();
            touched.add(new ClassCacheManager.RenamedClass(cls, originalName));
            data.classes.put(originalName, newName);
        }

        /** Renames one method as part of this batch. Must run to completion once started (see class javadoc). */
        public void renameMethod(JavaMethod method, String newName) {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(newName, "newName");
            String key = methodKey(method);
            JavaClass declaringClass = method.getDeclaringClass();
            method.getMethodNode().rename(newName);
            if (declaringClass != null) {
                declaringClass.getClassNode().unloadFromCache();
                touched.add(new ClassCacheManager.RenamedClass(declaringClass, declaringClass.getFullName()));
            }
            data.methods.put(key, newName);
        }

        /** Renames one field as part of this batch. Must run to completion once started (see class javadoc). */
        public void renameField(JavaField field, String newName) {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(newName, "newName");
            String key = fieldKey(field);
            JavaClass declaringClass = field.getDeclaringClass();
            field.getFieldNode().rename(newName);
            if (declaringClass != null) {
                declaringClass.getClassNode().unloadFromCache();
                touched.add(new ClassCacheManager.RenamedClass(declaringClass, declaringClass.getFullName()));
            }
            data.fields.put(key, newName);
        }

        /**
         * Refreshes indexes for every touched class in ONE call, persists ONCE, then releases the
         * write lock -- always, even if nothing was renamed (e.g. interrupted before the first
         * item) or if reindexing/saving throws.
         */
        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                if (!touched.isEmpty()) {
                    ClassCacheManager.reindexRenamedClassesBatch(touched);
                }
            } finally {
                JadxSearchLock.release();
            }
            if (touched.isEmpty()) {
                return;
            }
            try {
                saveToDisk();
            } catch (IOException e) {
                // Same map/disk-divergence rollback as putAndSave(), for every key this batch
                // touched at once (see the Batch constructor's snapshot comment).
                data.classes.clear();
                data.classes.putAll(classesSnapshot);
                data.methods.clear();
                data.methods.putAll(methodsSnapshot);
                data.fields.clear();
                data.fields.putAll(fieldsSnapshot);
                throw e;
            }
            logger.info("[RenameStorage] Batch rename persisted; {} class(es) touched", touched.size());
        }
    }

    // -------------------------------------------------------------------------
    // Disk I/O
    // -------------------------------------------------------------------------

    private RenameData loadFromDisk() {
        if (!Files.exists(storagePath)) {
            return new RenameData();
        }
        try (Reader r = Files.newBufferedReader(storagePath)) {
            RenameData loaded = gson.fromJson(r, RenameData.class);
            return loaded != null ? loaded : new RenameData();
        } catch (Exception e) {
            quarantineCorruptFile(e);
            return new RenameData();
        }
    }

    /**
     * A corrupt/unparseable {@code renames.json} used to be silently discarded here (a WARN log
     * line) and then permanently destroyed by the very next {@link #saveToDisk()}, whose atomic
     * tmp-file move overwrites the same path. This is the only place in the project a user's own
     * hand-produced data lives (CodeStore / UsageGraphStore / ContentShard are all regenerable
     * caches, safe to rebuild from the APK) -- so on a parse failure the unreadable file is moved
     * aside instead of being left where the next save can clobber it, and {@link #isDegraded()}
     * gives callers a way to surface the loss instead of it being invisible outside a log line.
     */
    private void quarantineCorruptFile(Exception cause) {
        Path quarantined = storagePath.resolveSibling(
                storagePath.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(storagePath, quarantined, StandardCopyOption.REPLACE_EXISTING);
            degradedReason = "Failed to parse " + storagePath.getFileName() + " (" + cause.getMessage()
                    + "); original file preserved at " + quarantined.getFileName()
                    + " for manual recovery. Starting with an EMPTY rename set -- all previously "
                    + "recorded aliases are inaccessible until that file is restored by hand.";
            logger.error("[RenameStorage] {}", degradedReason, cause);
        } catch (IOException moveFailure) {
            degradedReason = "Failed to parse " + storagePath + " (" + cause.getMessage()
                    + ") AND failed to quarantine it (" + moveFailure.getMessage()
                    + "); starting with an EMPTY rename set. The corrupt file was NOT moved and may "
                    + "still be overwritten by the next save.";
            logger.error("[RenameStorage] {}", degradedReason, moveFailure);
        }
        degraded = true;
    }

    private void saveToDisk() throws IOException {
        Path tmpPath = storagePath.resolveSibling("renames.json.tmp");
        try (Writer w = Files.newBufferedWriter(tmpPath)) {
            gson.toJson(data, w);
        }
        Files.move(tmpPath, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Applies {@code map.put(key, newValue)} then persists, rolling the map entry back to
     * whatever it was before if the save fails.
     *
     * <p>Before this, a failed {@link #saveToDisk()} left three states disagreeing: the live JADX
     * node already renamed, this in-memory map already updated, and the disk file still holding
     * the old value -- so every query for the rest of this session would show the new alias while
     * a restart would silently lose it, with no way for the user to tell which one was "real". This
     * at least keeps the in-memory map and disk in agreement (both without the failed change); the
     * live JADX node rename itself is not undone -- JADX has no supported "undo rename" and the
     * node's alias is not persisted data, so it does not carry the same data-loss risk.</p>
     */
    private void putAndSave(Map<String, String> map, String key, String newValue) throws IOException {
        boolean hadPrevious = map.containsKey(key);
        String previous = map.get(key);
        map.put(key, newValue);
        try {
            saveToDisk();
        } catch (IOException e) {
            if (hadPrevious) {
                map.put(key, previous);
            } else {
                map.remove(key);
            }
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    static class RenameData {
        Map<String, String> classes = new LinkedHashMap<>();
        Map<String, String> methods = new LinkedHashMap<>();
        Map<String, String> fields  = new LinkedHashMap<>();
        // Absent from files written before variable rename existed; Gson leaves the initialiser
        // in place for a missing key, so an older renames.json still loads.
        Map<String, String> variables = new LinkedHashMap<>();
    }
}
