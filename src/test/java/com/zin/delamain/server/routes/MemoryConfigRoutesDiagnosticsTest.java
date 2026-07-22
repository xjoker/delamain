package com.zin.delamain.server.routes;

import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.shard.ContentShardIndex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 memory: a GC-then-measure diagnostic.
 *
 * <p>Live heap "used" numbers include garbage that has not been collected yet, so they cannot
 * answer the question this project actually needs answered — what is delamain's <em>clean steady
 * state</em> for a given APK (production observation on the 222 779-class XHS APK: 11.4 GB warm,
 * with an unknown share of it collectable). This endpoint forces a collection, measures after it,
 * and reports the process RSS and container limit alongside the heap, because the production
 * incident was G1 holding ~48 GB of RSS while the heap itself was far smaller.</p>
 */
class MemoryConfigRoutesDiagnosticsTest {

    private final MemoryConfigRoutes routes = new MemoryConfigRoutes();

    @AfterEach
    void tearDown() {
        CodeContentIndex.clear();
        ContentShardIndex.clear();
    }

    @Test
    void withoutGcItMeasuresOnlyAndSaysSo() {
        Map<String, Object> diag = routes.buildMemoryDiagnostics(false);

        @SuppressWarnings("unchecked")
        Map<String, Object> gc = (Map<String, Object>) diag.get("gc");
        assertEquals(false, gc.get("ran"), "gc must not run when not requested: " + diag);

        @SuppressWarnings("unchecked")
        Map<String, Object> heap = (Map<String, Object>) diag.get("heap");
        assertTrue(((Number) heap.get("used_mb")).longValue() > 0, "heap used must be measured: " + heap);
        assertTrue(((Number) heap.get("max_mb")).longValue() > 0, "heap max must be measured: " + heap);
        assertNotNull(heap.get("committed_mb"), "committed (what the JVM took from the OS) must be reported");
    }

    @Test
    void withGcItReportsWhatTheCollectionFreed() {
        Map<String, Object> diag = routes.buildMemoryDiagnostics(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> gc = (Map<String, Object>) diag.get("gc");
        assertEquals(true, gc.get("ran"), "gc must run when requested: " + diag);
        assertTrue(((Number) gc.get("elapsed_ms")).longValue() >= 0, "gc duration must be reported: " + gc);

        @SuppressWarnings("unchecked")
        Map<String, Object> heap = (Map<String, Object>) diag.get("heap");
        assertNotNull(heap.get("used_before_gc_mb"), "the pre-GC figure must be kept for comparison: " + heap);
        assertNotNull(heap.get("freed_mb"), "freed = before - after is the point of the endpoint: " + heap);
        assertEquals(
            ((Number) heap.get("used_before_gc_mb")).longValue() - ((Number) heap.get("used_mb")).longValue(),
            ((Number) heap.get("freed_mb")).longValue(),
            "freed_mb must be exactly before - after, not an estimate");
    }

    /**
     * RSS and the cgroup limit only exist on Linux; on any other platform the endpoint must still
     * answer (with nulls) rather than throw — these same routes run in unit tests and on macOS
     * during development.
     */
    @Test
    void processAndContainerSectionsDegradeGracefullyOffLinux() {
        Map<String, Object> diag = routes.buildMemoryDiagnostics(false);

        assertTrue(diag.containsKey("process"), "process section must always be present: " + diag);
        assertTrue(diag.containsKey("container"), "container section must always be present: " + diag);

        @SuppressWarnings("unchecked")
        Map<String, Object> process = (Map<String, Object>) diag.get("process");
        assertTrue(process.containsKey("rss_mb"),
            "rss_mb must be present (null off Linux) — it is the number the 48GB incident was about");
    }

    @Test
    void reportsTheKnownHeapConsumersSoAResidualCanBeAttributed() {
        CodeContentIndex.clear();
        ContentShardIndex.clear();

        Map<String, Object> diag = routes.buildMemoryDiagnostics(false);

        @SuppressWarnings("unchecked")
        Map<String, Object> consumers = (Map<String, Object>) diag.get("consumers");
        assertNotNull(consumers, "consumers breakdown must be present: " + diag);
        assertEquals(0, ((Number) consumers.get("trigram_count")).intValue(),
            "trigram_count must reflect the live index (cleared here): " + consumers);
        assertEquals(false, consumers.get("shard_index_built"),
            "shard_index_built must reflect the live shard state (cleared here): " + consumers);
    }
}
