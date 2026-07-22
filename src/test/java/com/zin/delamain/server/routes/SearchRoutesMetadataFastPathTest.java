package com.zin.delamain.server.routes;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.CodeSearchCoordinator;

import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2: the metadata name-index fast path must serve the DEFAULT search shape.
 *
 * <p>{@code search_classes_by_keyword}'s default is {@code search_in='class,method,field'} — three
 * locations. The fast path bailed out on {@code searchLocations.size() != 1}, so the single most
 * common search in the whole product always fell through to the O(N) per-class scan: measured at
 * 814 ms on the 222 779-class XHS APK for a search the name indices can answer from hash buckets.
 *
 * <p>Union semantics are what makes this sound: a multi-location metadata search matches a class if
 * ANY of its requested locations matches, which is exactly the union of the per-kind index lookups.
 * Content locations (CODE/COMMENT) have no name index and must still fall through.</p>
 */
class SearchRoutesMetadataFastPathTest {

    private final SearchRoutes routes = new SearchRoutes(null, null);
    private HeadlessJadxWrapper wrapper;
    private List<JavaClass> allClasses;

    @BeforeEach
    void setUp(@TempDir Path workDir) throws Exception {
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        wrapper = new HeadlessJadxWrapper(List.of(apk), new File(workDir.toFile(), "out"), 2);
        wrapper.load();
        allClasses = new ArrayList<>(wrapper.getClassesWithInners());

        ClassCacheManager.initCache(wrapper);
        long deadline = System.currentTimeMillis() + 60_000;
        while (ClassCacheManager.getCache().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(!ClassCacheManager.getCache().isEmpty(), "class name indices must be built for this test");
    }

    @AfterEach
    void tearDown() {
        if (wrapper != null) wrapper.close();
    }

    private CodeSearchCoordinator.SearchResult fastPath(String term, Set<SearchRoutes.SearchLocation> locations) {
        return routes.tryNameIndexFastPath(term, locations, null, Collections.emptyList(),
            allClasses, SearchRoutes.MatchMode.SUBSTRING);
    }

    @Test
    void singleLocationFastPathStillWorks() {
        CodeSearchCoordinator.SearchResult r =
            fastPath("main", EnumSet.of(SearchRoutes.SearchLocation.CLASS_NAME));
        assertNotNull(r, "the pre-existing single-location fast path must be unchanged");
        assertEquals(true, r.getSearchInfo().get("index_hit"));
    }

    @Test
    void defaultThreeLocationMetadataSearchTakesTheFastPath() {
        CodeSearchCoordinator.SearchResult r = fastPath("main", EnumSet.of(
            SearchRoutes.SearchLocation.CLASS_NAME,
            SearchRoutes.SearchLocation.METHOD_NAME,
            SearchRoutes.SearchLocation.FIELD_NAME));

        assertNotNull(r, "the DEFAULT search shape must be served by the name indices, not an O(N) scan");
        assertEquals(true, r.getSearchInfo().get("index_hit"));
    }

    @Test
    void multiLocationResultIsTheUnionOfThePerLocationResults() {
        Set<SearchRoutes.SearchLocation> all = EnumSet.of(
            SearchRoutes.SearchLocation.CLASS_NAME,
            SearchRoutes.SearchLocation.METHOD_NAME,
            SearchRoutes.SearchLocation.FIELD_NAME);

        // "on" appears in method names (onCreate/onClick) and in class names (…Activity has none,
        // but the union must at minimum contain every single-location hit, whichever they are).
        for (String term : new String[]{"on", "a", "main", "verify"}) {
            List<String> union = fastPath(term, all).getMatches();
            for (SearchRoutes.SearchLocation loc : all) {
                CodeSearchCoordinator.SearchResult single = fastPath(term, EnumSet.of(loc));
                assertNotNull(single, "single-location fast path must serve '" + term + "' @ " + loc);
                assertTrue(union.containsAll(single.getMatches()),
                    "union for '" + term + "' must contain every " + loc + " match; missing="
                        + minus(single.getMatches(), union));
            }
            assertEquals(union.size(), union.stream().distinct().count(),
                "union must not contain duplicates for '" + term + "'");
        }
    }

    @Test
    void mixingAContentLocationStillFallsThroughToTheFullScan() {
        assertNull(fastPath("main", EnumSet.of(
                SearchRoutes.SearchLocation.CLASS_NAME, SearchRoutes.SearchLocation.CODE)),
            "CODE has no name index — a search including it cannot be answered from the fast path");
        assertNull(fastPath("main", EnumSet.of(SearchRoutes.SearchLocation.COMMENT)),
            "COMMENT has no name index either");
    }

    @Test
    void matchedOnIsReportedOnlyForClassNameHits() {
        CodeSearchCoordinator.SearchResult classOnly =
            fastPath("main", EnumSet.of(SearchRoutes.SearchLocation.CLASS_NAME));
        @SuppressWarnings("unchecked")
        Map<String, String> classSources = (Map<String, String>) classOnly.getSearchInfo().get("matched_on");
        assertNotNull(classSources, "class-name searches report which name form matched");

        CodeSearchCoordinator.SearchResult methodOnly =
            fastPath("oncreate", EnumSet.of(SearchRoutes.SearchLocation.METHOD_NAME));
        assertNull(methodOnly.getSearchInfo().get("matched_on"),
            "a method-name hit is not a class-name match and must not claim one");

        CodeSearchCoordinator.SearchResult mixed = fastPath("main", EnumSet.of(
            SearchRoutes.SearchLocation.CLASS_NAME, SearchRoutes.SearchLocation.METHOD_NAME));
        @SuppressWarnings("unchecked")
        Map<String, String> mixedSources = (Map<String, String>) mixed.getSearchInfo().get("matched_on");
        assertNotNull(mixedSources, "the class-name portion of a mixed search still reports matched_on");
        assertTrue(classSources.keySet().containsAll(mixedSources.keySet()),
            "matched_on must only ever describe class-name hits: " + mixedSources);
    }

    private static List<String> minus(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.removeAll(b);
        return out;
    }
}
