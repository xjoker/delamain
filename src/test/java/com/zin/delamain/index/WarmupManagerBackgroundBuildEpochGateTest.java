package com.zin.delamain.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F7 fix (path C, generalized as F7-b): a background index build's product may only be published
 * if it (a) ran to full completion and (b) was built for the hash that is STILL the process's
 * current APK at publish time. {@link WarmupManager#backgroundBuildStillValid} is the single shared
 * gate for BOTH background builds that can outlive the warmup that started them:
 *
 * <ul>
 *   <li>{@code startBackgroundShardBuild} — gates {@code ShardCatalog.write}/{@code loadCatalog}
 *       (path C, the originally-reported defect).</li>
 *   <li>{@code startBackgroundTrigramBuild} — gates the mid-loop continuation into the SHARED,
 *       hash-unpartitioned {@code CodeContentIndex} heap state, and gates {@code tryPersistIndex}
 *       (F7-b: Hub found this to be the exact same defect, independently, after the C fix landed).</li>
 * </ul>
 *
 * <p>Both background threads are blocked-on-I/O / paused-under-memory-pressure long-runners that
 * can wake up AFTER a newer APK has already loaded. {@code cancelRequested} alone cannot gate this
 * in either case — {@code WarmupManager.start()} resets that flag to {@code false} at the top of
 * every new warmup, so a stale thread sees "not cancelled" again and would otherwise publish the
 * OLD APK's product into state the NEW APK is now serving from (false-negative shard verdicts, or a
 * stale/foreign trigram index persisted to disk under the wrong hash).
 *
 * <p>This exercises the gate directly (unit-level; see the F7 task report for why an end-to-end
 * thread-race test was judged out of scope) — testing it once here covers both call sites, and is
 * exactly the point of extracting one shared function instead of leaving each caller to reinvent
 * (and potentially get wrong) the same epoch check a third time.
 */
class WarmupManagerBackgroundBuildEpochGateTest {

    @Test
    void completedBuildForTheStillCurrentHashIsValid() {
        assertTrue(WarmupManager.backgroundBuildStillValid(true, "hashA", "hashA"),
                "a build that finished for the hash that is still current must be publishable");
    }

    @Test
    void completedBuildForASupersededHashIsRejected() {
        assertFalse(WarmupManager.backgroundBuildStillValid(true, "hashA", "hashB"),
                "a completed build for an OLD hash must not publish once a newer APK's hash has "
                        + "become current — cancelRequested being reset by the new warmup's start() "
                        + "must not let this stale build back in");
    }

    @Test
    void incompleteBuildForTheCurrentHashIsRejected() {
        assertFalse(WarmupManager.backgroundBuildStillValid(false, "hashA", "hashA"),
                "an interrupted/cancelled/memory-paused partial build must never publish, even for "
                        + "the still-current hash");
    }

    @Test
    void incompleteBuildForASupersededHashIsRejected() {
        assertFalse(WarmupManager.backgroundBuildStillValid(false, "hashA", "hashB"),
                "both reasons to reject can hold at once — still must reject");
    }

    @Test
    void nullBuiltForHashIsRejected() {
        assertFalse(WarmupManager.backgroundBuildStillValid(true, null, "hashA"),
                "a build with no captured hash must never be treated as matching the current one");
    }

    /**
     * F7-b's specific scenario, as requested: a trigram build captures hash A, the current apk's
     * hash then changes to B (a newer apk loaded and started its own warmup), and the OLD build
     * subsequently reports "completed" — the gate must still reject it, so
     * {@code startBackgroundTrigramBuild} never calls {@code tryPersistIndex} for it.
     */
    @Test
    void trigramBuildThatCompletesAfterAHashChangeMustNotPersist() {
        String builtForHash = "hashA";
        boolean completedAfterHashChanged = true; // the build's loop ran to the end unimpeded

        String currentHashAtPersistTime = "hashB"; // a newer apk loaded mid-build

        assertFalse(WarmupManager.backgroundBuildStillValid(
                        completedAfterHashChanged, builtForHash, currentHashAtPersistTime),
                "a trigram build that finishes AFTER the current apk's hash moved on must not be "
                        + "persisted — tryPersistIndex would otherwise write the OLD apk's trigram "
                        + "index to disk, and its in-loop tryIndexFromCache calls would have kept "
                        + "mutating the shared CodeContentIndex heap state on the OLD apk's behalf "
                        + "the whole time it kept running");
    }
}
