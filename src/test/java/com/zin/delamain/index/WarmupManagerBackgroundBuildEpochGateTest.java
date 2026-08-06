package com.zin.delamain.index;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F7 fix (path C): a background index build's product may only be published if it (a) ran to full
 * completion and (b) was built for the hash that is STILL the process's current APK at publish
 * time. {@link WarmupManager#backgroundBuildStillValid} is the shared gate for
 * {@code startBackgroundShardBuild}, which gates {@code ShardCatalog.write}/{@code loadCatalog}.
 *
 * <p>The background build thread is a blocked-on-I/O / paused-under-memory-pressure long-runner
 * that can wake up AFTER a newer APK has already loaded. {@code cancelRequested} alone cannot gate
 * this — {@code WarmupManager.start()} resets that flag to {@code false} at the top of every new
 * warmup, so a stale thread sees "not cancelled" again and would otherwise publish the OLD APK's
 * product into state the NEW APK is now serving from (false-negative shard verdicts).
 *
 * <p>This exercises the gate directly (unit-level; an end-to-end thread-race test was judged out of
 * scope) — testing it once here is exactly the point of extracting one shared function.
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
     * F7-b's specific scenario, as requested: a shard build captures hash A, the current apk's
     * hash then changes to B (a newer apk loaded and started its own warmup), and the OLD build
     * subsequently reports "completed" — the gate must still reject it, so
     * {@code startBackgroundShardBuild} never publishes the catalog for it.
     */
    @Test
    void buildThatCompletesAfterAHashChangeMustNotPersist() {
        String builtForHash = "hashA";
        boolean completedAfterHashChanged = true; // the build's loop ran to the end unimpeded

        String currentHashAtPersistTime = "hashB"; // a newer apk loaded mid-build

        assertFalse(WarmupManager.backgroundBuildStillValid(
                        completedAfterHashChanged, builtForHash, currentHashAtPersistTime),
                "a build that finishes AFTER the current apk's hash moved on must not be "
                        + "persisted — publishing would otherwise write the OLD apk's product "
                        + "to disk on the OLD apk's behalf after a newer apk is already current");
    }
}
