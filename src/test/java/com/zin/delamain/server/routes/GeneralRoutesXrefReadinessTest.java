package com.zin.delamain.server.routes;

import com.zin.delamain.index.UsageGraphIndex;
import com.zin.delamain.index.UsePlacesIndex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /index-stats' xref_readiness section must reflect the two existing xref readiness signals
 * ({@link UsageGraphIndex#isReady()} for class-level xref, {@link UsePlacesIndex#isReady()} for
 * precise snippet-level xref) rather than a hardcoded value, so the AI knows whether a precise
 * xref-with-snippet request is instant or falls through to the (possibly slow) live path.
 */
class GeneralRoutesXrefReadinessTest {

    private final GeneralRoutes routes = new GeneralRoutes(null);

    @AfterEach
    void tearDown() {
        UsageGraphIndex.clear();
        UsePlacesIndex.clear();
    }

    @Test
    void reportsBuildingAndSkippedBeforeAnyIndexIsReady() {
        UsageGraphIndex.clear();
        UsePlacesIndex.clear();

        Map<String, Object> xref = routes.buildXrefReadiness();

        assertEquals("building", xref.get("class_level"),
                "class_level must be 'building' before UsageGraphIndex.isReady(): " + xref);
        assertEquals("skipped", xref.get("precise_snippets"),
                "precise_snippets must be 'skipped' when not ready and not harvesting: " + xref);
        assertTrue(xref.containsKey("live_fallback_cost"),
                "live_fallback_cost hint must be present while precise snippets are not ready: " + xref);
    }

    @Test
    void reportsReadyOnceUnderlyingIndicesAreReady() {
        UsageGraphIndex.clear();
        UsePlacesIndex.clear();

        // Bulk-restore both indices with a trivial (empty) class set — enough to flip isReady()
        // to true without needing a real decompiled APK.
        UsageGraphIndex.assignIds(Collections.emptyList());
        assertTrue(UsageGraphIndex.bulkRestore(new int[0][]));
        UsePlacesIndex.assignIds(Collections.emptyList());
        assertTrue(UsePlacesIndex.bulkRestore(new int[0][]));

        Map<String, Object> xref = routes.buildXrefReadiness();

        assertEquals("ready", xref.get("class_level"), "class_level must track isReady()==true: " + xref);
        assertEquals("ready", xref.get("precise_snippets"), "precise_snippets must track isReady()==true: " + xref);
        assertFalse(xref.containsKey("live_fallback_cost"),
                "live_fallback_cost must be omitted once precise snippets are ready: " + xref);
    }
}
