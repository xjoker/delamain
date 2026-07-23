package com.zin.delamain.server.routes;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;

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
}
