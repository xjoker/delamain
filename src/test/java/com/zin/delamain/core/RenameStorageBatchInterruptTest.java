package com.zin.delamain.core;

import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.JadxSearchLock;

import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F8 (1)+(3): a batch rename now holds {@link JadxSearchLock}'s write lock ONCE for the whole
 * batch (rename-perf fix) instead of once per item -- which makes a long/stuck batch the thing
 * most likely to run past the watchdog's stale-holder timeout. Before this fix, RenameStorage had
 * no interruption checkpoint anywhere, so the watchdog's {@code Thread.interrupt()} was a no-op:
 * the write lock would be held until the batch finished on its own, wedging every {@code
 * /load-file} in the meantime. This test interrupts the batch-driving thread mid-way and asserts
 * an orderly exit: the write lock is released, and every class actually renamed before the
 * interrupt is fully committed (live node + persisted alias agree) -- no half-renamed state.
 */
class RenameStorageBatchInterruptTest {

    @AfterEach
    void ensureUnlocked() {
        if (JadxSearchLock.isBusy()) {
            JadxSearchLock.forceRelease("test cleanup");
        }
        Thread.interrupted(); // clear this thread's interrupt flag between tests
    }

    @Test
    void interruptingMidBatch_releasesLockAndLeavesNoHalfRenamedClass(@TempDir Path workDir) throws Exception {
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());

        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(apk), new File(workDir.toFile(), "out"), 2);
        try {
            wrapper.load();
            List<JavaClass> classes = new ArrayList<>(wrapper.getClassesWithInners());
            assertTrue(classes.size() >= 3, "test APK must contain at least 3 classes to exercise a multi-item batch");

            ClassCacheManager.initCache(wrapper);
            long deadline = System.currentTimeMillis() + 60_000;
            while (ClassCacheManager.getCache().isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(ClassCacheManager.getCache().isEmpty(), "class index must be built for this test");

            RenameStorage storage = new RenameStorage(Path.of(workDir.toString(), "renames"));

            int interruptAfter = 1; // rename exactly one class, then simulate the watchdog's interrupt signal
            List<String> renamedOriginalNames = new ArrayList<>();
            List<String> renamedNewNames = new ArrayList<>();

            try (RenameStorage.Batch batch = storage.beginBatch()) {
                assertTrue(JadxSearchLock.isBusy(), "a batch must hold the write lock for its whole duration");

                for (int i = 0; i < classes.size(); i++) {
                    if (batch.isInterrupted()) {
                        break; // the only safe checkpoint: nothing for this item has started yet
                    }
                    JavaClass cls = classes.get(i);
                    String originalName = cls.getFullName();
                    String newName = originalName + "Renamed";
                    batch.renameClass(cls, newName);
                    renamedOriginalNames.add(originalName);
                    renamedNewNames.add(newName);
                    if (i == interruptAfter) {
                        // Simulates JadxSearchLock's stale-holder watchdog firing mid-batch, right
                        // after this item fully committed. The NEXT loop iteration's
                        // isInterrupted() check (not this one) is what must stop the batch --
                        // a checkpoint that does nothing would keep renaming every remaining class.
                        Thread.currentThread().interrupt();
                    }
                }
            } // close(): must release the lock and persist whatever completed, even after an interrupt

            assertFalse(JadxSearchLock.isBusy(),
                    "the batch must release the write lock on close(), even after a mid-batch interrupt -- "
                            + "this is exactly the case that used to wedge every future /load-file forever");
            assertTrue(Thread.interrupted(), "consume/clear this test's own interrupt flag");

            assertFalse(renamedOriginalNames.isEmpty(), "at least one class must have been renamed before the interrupt");
            assertTrue(renamedOriginalNames.size() < classes.size(),
                    "the interrupt must have actually cut the batch short (a no-op checkpoint would rename them all)");

            for (int i = 0; i < renamedOriginalNames.size(); i++) {
                String originalName = renamedOriginalNames.get(i);
                String newName = renamedNewNames.get(i);
                assertEquals(newName, storage.getClassAlias(originalName),
                        "every class actually renamed before the interrupt must be fully committed "
                                + "(live node rename + persisted alias in agreement) -- no half-renamed state");
            }

            RenameStorage reloaded = new RenameStorage(Path.of(workDir.toString(), "renames"));
            assertEquals(renamedOriginalNames.size(), reloaded.size(),
                    "only the classes renamed before the interrupt should have been persisted to disk");
        } finally {
            wrapper.close();
        }
    }
}
