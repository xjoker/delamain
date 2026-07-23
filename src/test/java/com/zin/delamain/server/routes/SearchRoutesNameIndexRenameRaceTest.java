package com.zin.delamain.server.routes;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.core.RenameStorage;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.CodeSearchCoordinator;

import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reproduces the read/write race between the name-index fast path
 * ({@link SearchRoutes#tryNameIndexFastPath}, via {@code findBySubstringName}/{@code
 * findByPrefixName}) and a concurrent rename ({@link RenameStorage#renameClass}, via {@code
 * ClassCacheManager.reindexRenamedClass} -&gt; {@code reindex} -&gt; {@code addToIndex}).
 *
 * <p>{@code addToIndex} does a structural {@code Map.put(newKey, ...)} on the shared
 * {@code classNameIndex} HashMap while holding {@code JadxSearchLock}'s WRITE lock (per its
 * documented contract). Before the fix, the read side iterated {@code entrySet()} without
 * holding {@code JadxSearchLock}'s read lock at all, so a rename landing mid-iteration threw
 * {@code ConcurrentModificationException} — surfaced to callers as a search 500.
 */
class SearchRoutesNameIndexRenameRaceTest {

    private final SearchRoutes routes = new SearchRoutes(null, null);
    private HeadlessJadxWrapper wrapper;
    private RenameStorage renameStorage;
    private List<JavaClass> allClasses;

    @BeforeEach
    void setUp(@TempDir Path workDir) throws Exception {
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        wrapper = new HeadlessJadxWrapper(List.of(apk), new File(workDir.toFile(), "out"), 2);
        wrapper.load();
        allClasses = new ArrayList<>(wrapper.getClassesWithInners());

        ClassCacheManager.initCache(wrapper);
        long deadline = System.currentTimeMillis() + 60_000;
        while (ClassCacheManager.getCache().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(!ClassCacheManager.getCache().isEmpty(), "class name indices must be built for this test");
        assertTrue(allClasses.size() >= 2, "test APK must contain at least 2 classes to rename/search concurrently");

        renameStorage = new RenameStorage(Path.of(workDir.toString(), "renames"));
    }

    @AfterEach
    void tearDown() {
        if (wrapper != null) wrapper.close();
    }

    /**
     * Concurrently renames classes (structural HashMap.put on classNameIndex/methodNameIndex/
     * fieldNameIndex, each landing a brand-new key so it can't be absorbed as a same-key value
     * replacement) while other threads hammer the name-index fast path used by every
     * class/method/field metadata search. Must never surface a ConcurrentModificationException
     * and must never leave the reader with an inconsistent/partial view that throws.
     */
    @Test
    void concurrentRenameAndNameIndexSearchDoesNotThrowCME() throws Exception {
        int renameRounds = 300;
        int readerThreads = 4;
        int readerRoundsPerThread = 2000;

        Set<SearchRoutes.SearchLocation> allLocations = EnumSet.of(
            SearchRoutes.SearchLocation.CLASS_NAME,
            SearchRoutes.SearchLocation.METHOD_NAME,
            SearchRoutes.SearchLocation.FIELD_NAME);

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch readersReady = new CountDownLatch(readerThreads);
        CountDownLatch start = new CountDownLatch(1);

        List<Thread> readers = new ArrayList<>();
        String[] terms = {"a", "on", "main", "c", "verify", "l"};
        for (int i = 0; i < readerThreads; i++) {
            Thread t = new Thread(() -> {
                readersReady.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                int round = 0;
                while (!stop.get() && round < readerRoundsPerThread) {
                    String term = terms[round % terms.length];
                    SearchRoutes.MatchMode mode = (round % 2 == 0)
                        ? SearchRoutes.MatchMode.SUBSTRING
                        : SearchRoutes.MatchMode.PREFIX;
                    try {
                        routes.tryNameIndexFastPath(term, allLocations, null,
                            Collections.emptyList(), allClasses, mode);
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                    round++;
                }
            }, "reader-" + i);
            t.setDaemon(true);
            readers.add(t);
            t.start();
        }

        readersReady.await();
        start.countDown();

        Thread writer = new Thread(() -> {
            for (int i = 0; i < renameRounds && failures.isEmpty(); i++) {
                JavaClass cls = allClasses.get(i % allClasses.size());
                try {
                    renameStorage.renameClass(cls, "RaceRenamed" + i + "Unique");
                } catch (Throwable e) {
                    failures.add(e);
                }
            }
        }, "writer");
        writer.start();
        writer.join(60_000);
        stop.set(true);
        for (Thread t : readers) {
            t.join(10_000);
        }

        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder("Concurrent rename + name-index search failed:\n");
            for (Throwable e : failures) {
                sb.append(" - ").append(e).append('\n');
            }
            fail(sb.toString());
        }
    }
}
