package com.zin.delamain.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression introduced 2026-07-22 by the container-derived heap (20260722.2): shrinking the heap
 * from 49 GB to 9.8 GB made {@code computeWarmupWorkers} collapse to <b>1 worker</b> for a large
 * APK, because {@code base(classCount)} alone exceeded the whole heap:
 *
 * <pre>
 *   base = 1024 + 222779/1000 * 80 = 18 784 MB  &gt;  9 832 MB heap  →  usable &lt; 0  →  byHeap = 1
 * </pre>
 *
 * Production did not notice (it FAST_RESTOREs from a persisted index volume and never runs
 * Phase-1), but any cold warmup — a newly loaded APK, or anyone pulling the published image — would
 * decompile ~220 k classes single-threaded.
 *
 * <p>Both constants were calibrated in the heap-resident-trigram era. Today's architecture keeps
 * the content index on disk (mmap shard) and writes Phase-1 output through to the CodeStore before
 * unloading, and the measured clean steady state on production is <b>4 466 MB for 237 931
 * classes</b> (~19 MB per 1 k classes) — roughly a quarter of what the old {@code 80 MB/1k}
 * constant assumes.</p>
 *
 * <p>The second half of these tests pins the CPU dimension. The sizing formula had none: on the
 * 48-core production box it would authorise up to {@code cores - 2 = 46} workers, which is the
 * "warmup saturates the machine" behaviour observed while queries were being served — on a host
 * shared with other services.</p>
 */
class WarmupWorkerSizingTest {

    // Production shape: XHS APK on a 16 GB container (-Xmx9830m) with 48 cores.
    private static final int XHS_CLASSES = 222779;
    private static final long PROD_HEAP_MB = 9832;
    private static final int PROD_CORES = 48;
    private static final double PROD_SAFETY = 0.60;   // MemoryConfig auto-tier for an 8-16 GB heap

    private static int workers(int classCount, long heapMB, int cores, double safety) {
        return WarmupManager.computeWarmupWorkers(classCount, heapMB, cores, safety,
            MemoryConfig.DEFAULT_PER_WORKER_HEAP_MB);
    }

    @Test
    void rightSizedContainerDoesNotCollapseToASingleWorker() {
        int w = workers(XHS_CLASSES, PROD_HEAP_MB, PROD_CORES, PROD_SAFETY);
        assertTrue(w >= 4,
            "a 16 GB container for a 222 779-class APK must warm up with real parallelism, got " + w);
    }

    @Test
    void baseFootprintMustMatchTheMeasuredSteadyState() {
        // 4 466 MB measured for 237 931 classes. The estimate may exceed it (headroom) but must not
        // exceed it by more than ~2x, or it starves the worker budget on a right-sized heap.
        long base = WarmupManager.estimateBaseHeapMB(237931);
        assertTrue(base >= 4466, "base must not underestimate the measured steady state: " + base);
        assertTrue(base <= 8932, "base must not overestimate the measured 4 466 MB by more than 2x: " + base);
    }

    @Test
    void warmupLeavesMostOfTheMachineToServeQueries() {
        // Even with heap to spare, warmup must not authorise a worker per core: this host also
        // serves search/xref requests and shares the box with other containers.
        int w = workers(XHS_CLASSES, 64 * 1024, PROD_CORES, 0.70);
        assertTrue(w <= PROD_CORES / 4,
            "warmup must leave at least three quarters of the cores free, got " + w + " of " + PROD_CORES);
    }

    @Test
    void aGenuinelyTinyHeapStillDropsToOne() {
        // 2 GB heap cannot even hold the class tree; 1 worker (slow but survivable) is correct.
        assertEquals(1, workers(XHS_CLASSES, 2048, 8, 0.50));
    }

    @Test
    void smallMachineKeepsAtLeastTwoWorkersWhenHeapAllows() {
        // 4 cores, ample heap for a small APK: the CPU cap must floor at 2, never 0.
        assertEquals(2, workers(5000, 8192, 4, 0.55));
    }

    @Test
    void resultIsAlwaysAtLeastOneAndNeverAbsurd() {
        for (int classes : new int[]{0, 1000, 50000, 500000}) {
            for (long heap : new long[]{512, 4096, 16384, 131072}) {
                for (int cores : new int[]{1, 2, 8, 48, 256}) {
                    int w = workers(classes, heap, cores, 0.60);
                    assertTrue(w >= 1 && w <= 32,
                        "workers out of range for classes=" + classes + " heap=" + heap
                            + " cores=" + cores + ": " + w);
                }
            }
        }
    }
}
