package com.zin.delamain.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.JadxSearchLock;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Persistent rename / alias store for headless JADX rename operations.
 *
 * <h2>Strategy: Method A — direct ClassNode/MethodNode/FieldNode rename</h2>
 * <p>JADX 1.5.5 exposes the following public API chain that works without any GUI:</p>
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
 * <h2>Persistence format</h2>
 * Renames are serialised as a JSON file ({@code renames.json}) in the supplied data
 * directory:
 * <pre>
 * {
 *   "classes":  { "com.example.a": "com.example.LoginActivity" },
 *   "methods":  { "com.example.a#a()V": "doLogin" },
 *   "fields":   { "com.example.a#b:Ljava/lang/String;": "userId" }
 * }
 * </pre>
 * Keys follow the convention below:
 * <ul>
 *   <li>classes — fully-qualified class name (dots, no leading/trailing separators)</li>
 *   <li>methods — {@code declaringClass#methodShortId}  (shortId = name + descriptor,
 *       e.g. {@code a()V})</li>
 *   <li>fields  — {@code declaringClass#fieldName:fieldType}</li>
 * </ul>
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

    /**
     * @param dataDir directory where {@code renames.json} is stored; created if absent
     */
    public RenameStorage(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        this.storagePath = dataDir.resolve("renames.json");
        this.data = loadFromDisk();
        logger.info("[RenameStorage] Loaded {} class / {} method / {} field rename(s) from {}",
                data.classes.size(), data.methods.size(), data.fields.size(), storagePath);
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

        data.classes.put(originalName, newName);
        saveToDisk();
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

        data.methods.put(key, newName);
        saveToDisk();
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

        data.fields.put(key, newName);
        saveToDisk();
        logger.info("[RenameStorage] Renamed field '{}' → '{}'", key, newName);
    }

    // -------------------------------------------------------------------------
    // String-key convenience methods (for serialisation / restore on reload)
    // -------------------------------------------------------------------------

    /** Persists a class rename by original full name (used during session restore). */
    public synchronized void renameClass(String originalFullName, String newName) throws IOException {
        data.classes.put(originalFullName, newName);
        saveToDisk();
    }

    /** Persists a method rename by key ({@code declaringClass#shortId}). */
    public synchronized void renameMethod(String methodKey, String newName) throws IOException {
        data.methods.put(methodKey, newName);
        saveToDisk();
    }

    /** Persists a field rename by key ({@code declaringClass#fieldName:type}). */
    public synchronized void renameField(String fieldKey, String newName) throws IOException {
        data.fields.put(fieldKey, newName);
        saveToDisk();
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

    /** Returns total number of persisted renames (all node types combined). */
    public int size() {
        RenameData d = data;
        return d.classes.size() + d.methods.size() + d.fields.size();
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
            logger.warn("[RenameStorage] Failed to load renames from {}; starting fresh: {}", storagePath, e.getMessage());
            return new RenameData();
        }
    }

    private void saveToDisk() throws IOException {
        Path tmpPath = storagePath.resolveSibling("renames.json.tmp");
        try (Writer w = Files.newBufferedWriter(tmpPath)) {
            gson.toJson(data, w);
        }
        Files.move(tmpPath, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    static class RenameData {
        Map<String, String> classes = new LinkedHashMap<>();
        Map<String, String> methods = new LinkedHashMap<>();
        Map<String, String> fields  = new LinkedHashMap<>();
    }
}
