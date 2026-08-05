package com.zin.delamain.index;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * K1 (2): warn — not refuse — when the heap available for warmup is not enough for the class
 * count about to be decompiled.
 *
 * <p>Production sample (2026-08, 174 MB APK): jadx reports 255 599 classes / 1 085 389 methods.
 * The loaded-but-undecompiled class tree measured 3 846 MB. Phase-1 then ran on a 240 298-class
 * warmup target and, at 82% done (~197 044 classes actually decompiled, still 18% short), had
 * already pushed resident heap to 14 612 MB against a 16 384 MB ceiling (89%) — and a full GC at
 * that point recovered essentially nothing (freed_mb = -475: everything live, not garbage).
 * Throughput decayed from ~71 to ~25 classes/s over 58 minutes without completing. This is the
 * exact silent-degradation failure {@link WarmupManager#checkCapacity} exists to make visible.</p>
 */
class WarmupCapacityWarningTest {

    private static final int LARGE_APK_CLASS_COUNT = 240_298;
    private static final long SIXTEEN_GB = 16L * 1024 * 1024 * 1024;
    private static final long SIXTY_FOUR_GB = 64L * 1024 * 1024 * 1024;

    @Test
    void estimateRequiredHeapMbGrowsWithClassCount() {
        long small = WarmupManager.estimateRequiredHeapMB(5_000);
        long large = WarmupManager.estimateRequiredHeapMB(LARGE_APK_CLASS_COUNT);
        assertTrue(large > small, "required heap must grow with class count: " + small + " vs " + large);
    }

    @Test
    void estimateRequiredHeapMbMatchesTheProductionIncidentWithinAFactorOfTwo() {
        // The 240 298-class run was still climbing at 14 612 MB / 82% done when it stalled; the
        // estimate for the FULL run must be at least that much (it clearly needed more than what
        // it had already consumed), and must not be a wild overestimate either.
        long required = WarmupManager.estimateRequiredHeapMB(LARGE_APK_CLASS_COUNT);
        assertTrue(required > 14_612,
            "estimate must exceed the measured still-climbing 14 612 MB peak: " + required);
        assertTrue(required < 2 * 14_612,
            "estimate must not wildly overshoot the measured peak: " + required);
    }

    @Test
    void checkCapacityReturnsNullWhenTheEstimateFitsUnderTheHeap() {
        // A small APK on a generously large heap must not warn.
        assertNull(WarmupManager.checkCapacity(5_000, SIXTY_FOUR_GB));
    }

    @Test
    void checkCapacityWarnsWhenTheLargeApkExceedsA16gbHeap() {
        Map<String, Object> warning = WarmupManager.checkCapacity(LARGE_APK_CLASS_COUNT, SIXTEEN_GB);
        assertTrue(warning != null, "240 298 classes on a 16 GB heap must produce a capacity warning");
        assertEquals(LARGE_APK_CLASS_COUNT, warning.get("classes"));
        assertEquals(16384L, warning.get("available_mb"));
        long estimated = (Long) warning.get("estimated_required_mb");
        assertTrue(estimated > 16384L, "estimated_required_mb must exceed available_mb: " + estimated);
        assertEquals(WarmupManager.estimateRequiredHeapMB(LARGE_APK_CLASS_COUNT), estimated);
    }

    @Test
    void statusSurfacesCapacityWarningWhenInsufficientAndOmitsItWhenNot() {
        WarmupManager.updateCapacityWarning(LARGE_APK_CLASS_COUNT, SIXTEEN_GB);
        Map<String, Object> statusWhenShort = WarmupManager.getStatus();
        assertTrue(statusWhenShort.containsKey("capacity_warning"),
            "/warmup-status must expose capacity_warning when the estimate exceeds the heap: " + statusWhenShort);
        @SuppressWarnings("unchecked")
        Map<String, Object> capacityWarning = (Map<String, Object>) statusWhenShort.get("capacity_warning");
        assertEquals(LARGE_APK_CLASS_COUNT, capacityWarning.get("classes"));

        // A subsequent (or smaller) load must clear the previous warning, not leak it forever.
        WarmupManager.updateCapacityWarning(500, SIXTEEN_GB);
        Map<String, Object> statusWhenFine = WarmupManager.getStatus();
        assertFalse(statusWhenFine.containsKey("capacity_warning"),
            "/warmup-status must NOT expose capacity_warning once the estimate fits: " + statusWhenFine);
    }
}
