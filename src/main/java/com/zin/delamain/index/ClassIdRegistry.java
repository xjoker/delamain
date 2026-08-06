package com.zin.delamain.index;

import jadx.api.JavaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

/**
 * Stable, deterministic class-id assignment used to map live {@link JavaClass} instances to
 * fixed-width integer ids. The mmap shard layer's classId space is defined by
 * {@link #preAssignIds} being called in the same sorted order used to build the shards, so
 * {@link #idOf}/{@link #resolveClass} are the bridge between a live JavaClass and its shard id.
 */
public final class ClassIdRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ClassIdRegistry.class);

    private static volatile State state = new State();

    public static int idOf(JavaClass cls) {
        if (cls == null) {
            return -1;
        }
        Integer id = state.classToId.get(cls);
        return id != null ? id : -1;
    }

    public static JavaClass resolveClass(int id) {
        State s = state;
        JavaClass[] arr = s.idToClass;
        if (id < 0 || id >= arr.length) {
            return null;
        }
        return arr[id];
    }

    public static void invalidate(JavaClass cls) {
        if (cls == null) {
            return;
        }
        State s = state;
        int id = s.removeId(cls);
        if (id < 0) {
            return;
        }
        logger.debug("[JAI] ClassIdRegistry: invalidated class {}", cls.getFullName());
    }

    public static void clear() {
        state = new State();
        logger.info("[JAI] ClassIdRegistry: cleared");
    }

    /**
     * Pre-assigns class IDs in the given (sorted) order so that IDs are deterministic
     * across JVM restarts for the same APK, and match the shard layer's classId space.
     */
    public static void preAssignIds(List<JavaClass> sortedClasses) {
        State s = state;
        for (JavaClass cls : sortedClasses) {
            s.assignId(cls);
        }
    }

    // -------------------------------------------------------------------------
    // Internal state container
    // -------------------------------------------------------------------------

    static final class State {
        static final int ALREADY_INDEXED = -1;

        final ConcurrentHashMap<JavaClass, Integer> classToId = new ConcurrentHashMap<>();
        volatile JavaClass[] idToClass = new JavaClass[256];
        final AtomicInteger nextId = new AtomicInteger(0);
        final AtomicInteger classCount = new AtomicInteger(0);
        final StampedLock idLock = new StampedLock();

        int assignId(JavaClass cls) {
            if (classToId.containsKey(cls)) {
                return ALREADY_INDEXED;
            }

            int id = nextId.getAndIncrement();
            Integer prev = classToId.putIfAbsent(cls, id);
            if (prev != null) {
                nextId.decrementAndGet();
                return ALREADY_INDEXED;
            }

            long stamp = idLock.writeLock();
            try {
                if (id >= idToClass.length) {
                    int newLen = Math.max(idToClass.length * 2, id + 1);
                    JavaClass[] newArr = new JavaClass[newLen];
                    System.arraycopy(idToClass, 0, newArr, 0, idToClass.length);
                    idToClass = newArr;
                }
                idToClass[id] = cls;
            } finally {
                idLock.unlockWrite(stamp);
            }

            classCount.incrementAndGet();
            return id;
        }

        /** Returns the class's id, assigning one if absent. Never returns ALREADY_INDEXED. */
        int getOrAssignId(JavaClass cls) {
            Integer existing = classToId.get(cls);
            if (existing != null) {
                return existing;
            }
            assignId(cls);
            Integer id = classToId.get(cls);
            return id != null ? id : -1;
        }

        int removeId(JavaClass cls) {
            Integer id = classToId.remove(cls);
            if (id == null) {
                return -1;
            }
            long stamp = idLock.writeLock();
            try {
                if (id < idToClass.length) {
                    idToClass[id] = null;
                }
            } finally {
                idLock.unlockWrite(stamp);
            }
            classCount.decrementAndGet();
            return id;
        }
    }
}
