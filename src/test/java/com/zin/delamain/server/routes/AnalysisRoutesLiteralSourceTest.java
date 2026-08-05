package com.zin.delamain.server.routes;

import com.zin.delamain.utils.DeadlineRegex;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisRoutesLiteralSourceTest {

    private JadxDecompiler jadx;

    @AfterEach
    void tearDown() {
        if (jadx != null) jadx.close();
    }

    @Test
    void forceDecompileDoesNotMaterialiseSourceDuringBulkLiteralScan() {
        JadxArgs args = new JadxArgs();
        args.setInputFile(new File("test-harness/real/UnCrackable-Level1.apk"));
        args.setSkipResources(true);
        jadx = new JadxDecompiler(args);
        jadx.load();
        JavaClass cls = jadx.getClasses().get(0);

        // force_decompile is retained for API compatibility, but a bulk scan must never turn it
        // into cls.getCode(): an unmaterialised class remains unreadable until warmup persists it.
        assertNull(AnalysisRoutes.readBulkLiteralSource(cls, true));

        cls.getCode();
        assertNotNull(AnalysisRoutes.readBulkLiteralSource(cls, true));
    }

    @Test
    void metadata_marksLimitTruncationAsPartial() {
        Map<String, Object> metadata = AnalysisRoutes.literalScanMetadata(
            10, 10, 5, 5, 0, false, false, true, false);

        assertTrue((Boolean) metadata.get("partial_results"));
        assertTrue(((String) metadata.get("coverage_note")).startsWith("PARTIAL SCAN"));
    }

    @Test
    void metadata_usesFilterCandidatesAsCoverageDenominator() {
        Map<String, Object> metadata = AnalysisRoutes.literalScanMetadata(
            100, 4, 2, 3, 0, false, false, false, true);

        assertEquals(4, metadata.get("candidate_classes"));
        assertEquals(50, metadata.get("coverage_pct"));
        assertEquals(75, metadata.get("cached_percentage_at_scan"));
    }

    @Test
    void metadata_reportsFullScanWhenEveryCandidateWasScanned() {
        Map<String, Object> metadata = AnalysisRoutes.literalScanMetadata(
            10, 10, 10, 8, 0, false, false, false, false);

        assertFalse((Boolean) metadata.get("partial_results"));
        assertEquals("FULL SCAN: all 10 classes scanned.", metadata.get("coverage_note"));
    }

    /**
     * F5 (5) regression: search-string-literals?regex=true matches a user pattern against each
     * extracted string literal via Matcher.find(), which has no interruption point of its own —
     * see SearchRoutesRegexDeadlineTest for the full rationale. F9: the guard now lives in the
     * shared {@link DeadlineRegex} (formerly duplicated identically on this class and SearchRoutes).
     */
    @Test
    void matchesWithDeadline_boundsACatastrophicallyBacktrackingRegex() {
        Pattern evil = Pattern.compile("(a+)+$");
        String longNonMatching = "a".repeat(40) + "!";

        long startNanos = System.nanoTime();
        boolean matched = DeadlineRegex.matchesWithDeadline(evil, longNonMatching);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertFalse(matched);
        assertTrue(elapsedMs < 5000,
            "a catastrophically backtracking regex must be bounded, not run unbounded — took "
                + elapsedMs + "ms");
    }
}
