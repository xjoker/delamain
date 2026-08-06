package com.zin.delamain.index;

import com.zin.delamain.index.shard.ContentShardIndex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two background costs that dominate a cold warmup are invisible over HTTP.
 *
 * <p>Measured on a 255,599-class APK: Phase-1 took 2h19m and throughput decayed 14x
 * (72 → 5 classes/s) purely from memory-pressure throttling. Nothing reported it. The
 * throttle only slept; the counters that would have explained it were local variables in
 * {@code runPhase1}, reported once at phase end. Meanwhile {@code /warmup-status} did
 * publish {@code trigram_build_running} — a field that is pinned false in the default
 * deployment (TRIGRAM_HEAP defaults off) — while the shard build that actually runs there
 * had no field at all. An operator polling status saw a false signal and no true one.
 *
 * <p>What this test can and cannot cover: it pins that the fields exist, are wired to the
 * real state, and move when the state moves. It does NOT reproduce the 22万-class heap
 * interaction — CI has no fixture at that scale, so the numeric accuracy of these counters
 * under real decay stays unverified here.
 */
class WarmupObservabilityTest {

    @BeforeEach
    void setUp() {
        ContentShardIndex.clear();
        WarmupManager.resetPressureCountersForTests();
    }

    @AfterEach
    void tearDown() {
        ContentShardIndex.clear();
        WarmupManager.resetPressureCountersForTests();
    }

    // ---- shard build state -------------------------------------------------

    @Test
    void statusReportsShardBuildStateNotOnlyTheTrigramOne() {
        Map<String, Object> status = WarmupManager.getStatus();

        assertTrue(status.containsKey("shard_build_running"),
                "the build that actually runs in the default deployment must be reported: " + status);
        assertEquals(WarmupManager.isShardBuildRunning(), status.get("shard_build_running"),
                "shard_build_running must reflect the real flag, not a constant");
    }

    // ---- memory-pressure throttling ----------------------------------------

    @Test
    void statusExposesThrottlingCounters() {
        Map<String, Object> status = WarmupManager.getStatus();

        assertTrue(status.containsKey("throttling"), "missing live throttling signal: " + status);
        assertTrue(status.containsKey("pressure_hits"), "missing pressure_hits: " + status);
        assertTrue(status.containsKey("pressure_sleep_ms"), "missing pressure_sleep_ms: " + status);
    }

    @Test
    void throttlingIsTrueOnlyWhileAThreadIsActuallyStalled() throws Exception {
        assertFalse(WarmupManager.isThrottling(), "nothing is stalled before the test starts");

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread stalled = new Thread(() -> {
            try {
                entered.countDown();
                WarmupManager.throttleSleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        }, "test-throttle");
        stalled.setDaemon(true);
        stalled.start();

        assertTrue(entered.await(2, TimeUnit.SECONDS), "worker thread never started");
        // Sample inside the 300ms sleep window.
        Thread.sleep(80);
        assertTrue(WarmupManager.isThrottling(), "a thread is mid-throttle but status says otherwise");
        assertEquals(true, WarmupManager.getStatus().get("throttling"),
                "the live signal must reach getStatus, not just the internal flag");

        assertTrue(finished.await(3, TimeUnit.SECONDS), "throttle sleep never finished");
        assertFalse(WarmupManager.isThrottling(), "throttling must fall back to false once the stall ends");
    }

    @Test
    void throttleSleepAccumulatesHitsAndTime() throws Exception {
        assertEquals(0, WarmupManager.getStatus().get("pressure_hits"));

        WarmupManager.throttleSleep(40);
        WarmupManager.throttleSleep(40);

        Map<String, Object> status = WarmupManager.getStatus();
        assertEquals(2, status.get("pressure_hits"), "each throttle must count: " + status);
        long sleptMs = ((Number) status.get("pressure_sleep_ms")).longValue();
        assertTrue(sleptMs >= 60,
                "accumulated stall time must reflect real sleeping, got " + sleptMs + "ms: " + status);
    }

    @Test
    void throttleSleepStillCountsTheStallWhenInterrupted() throws Exception {
        Thread t = new Thread(() -> {
            try {
                WarmupManager.throttleSleep(5000);
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
        }, "test-throttle-interrupt");
        t.setDaemon(true);
        t.start();
        Thread.sleep(80);
        t.interrupt();
        t.join(2000);

        assertFalse(WarmupManager.isThrottling(),
                "an interrupted throttle must not leave the live signal stuck on");
        assertEquals(1, WarmupManager.getStatus().get("pressure_hits"),
                "an interrupted stall still happened and must be counted");
    }

    // ---- code_search readiness: three states, not two ----------------------

    @Test
    void codeSearchDistinguishesBuildingFromStalled() {
        assertEquals("ready", WarmupManager.codeSearchCapability(true, false, null));
        assertEquals("ready", WarmupManager.codeSearchCapability(true, true, null),
                "already serving wins over still building");
        assertEquals("building", WarmupManager.codeSearchCapability(false, true, null),
                "a running shard build must be distinguishable from a stalled one");
        assertEquals("warming", WarmupManager.codeSearchCapability(false, false, null));
        assertEquals("skipped:low-heap", WarmupManager.codeSearchCapability(false, false, "heap at 86%"),
                "a skip reason must still surface — it is why the build will never come");
    }

    @Test
    void statusUsesTheSameCapabilityRuleAsTheFunction() {
        Map<String, Object> status = WarmupManager.getStatus();
        @SuppressWarnings("unchecked")
        Map<String, String> caps = (Map<String, String>) status.get("capabilities");

        String expected = WarmupManager.codeSearchCapability(
                ContentShardIndex.isBuilt() || CodeContentIndex.trigramCount() > 0,
                WarmupManager.isShardBuildRunning(),
                null);
        assertEquals(expected, caps.get("code_search"),
                "getStatus must not reimplement the rule it shares with codeSearchCapability");
    }
}
