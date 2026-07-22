package com.zin.delamain.server.routes;

import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.shard.ContentShardBuilder;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.index.shard.ShardCatalog;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1 broad-word guard: when the shard index reports a candidate cardinality above
 * {@link SearchRoutes#BROAD_CANDIDATE_THRESHOLD}, {@code executeSearch} must skip the full
 * content scan and instead return an immediate bounded-sample result (candidate_count / hint /
 * partial_results=true), rather than scanning every candidate until the 60s deadline. Narrow
 * terms (candidates <= threshold) must be byte-for-byte unaffected — this is regression coverage
 * for the existing full-scan path, not a behavior change for it.
 */
class SearchRoutesBroadTermTest {

    private static final String HASH = "aabbccddeeff0011";

    private static JadxDecompiler jadx;
    private static List<JavaClass> classes;
    private final SearchRoutes routes = new SearchRoutes(null, null);

    @BeforeAll
    static void setUpClasses() {
        JadxArgs args = new JadxArgs();
        args.setInputFile(new File("test-harness/real/UnCrackable-Level1.apk"));
        args.setSkipResources(true);
        jadx = new JadxDecompiler(args);
        jadx.load();
        classes = jadx.getClasses();
        assertTrue(classes.size() >= 4, "test APK must yield at least 4 classes for these tests");
    }

    @AfterAll
    static void tearDownClasses() {
        if (jadx != null) {
            jadx.close();
        }
    }

    @BeforeEach
    void reset() {
        CodeContentIndex.clear();
        ContentShardIndex.clear();
    }

    @AfterEach
    void tearDown() {
        ContentShardIndex.clear();
        CodeContentIndex.clear();
        SearchRoutes.BROAD_CANDIDATE_THRESHOLD = SearchRoutes.DEFAULT_BROAD_CANDIDATE_THRESHOLD;
        SearchRoutes.BROAD_SAMPLE_SCAN = SearchRoutes.DEFAULT_BROAD_SAMPLE_SCAN;
    }

    private static List<JavaClass> sortedClasses() {
        List<JavaClass> sorted = new ArrayList<>(classes);
        sorted.sort(Comparator.comparing(JavaClass::getRawName));
        return sorted;
    }

    /** Builds a single shard over {@code corpus} (id -> lower-cased code) and loads it. */
    private static void buildAndLoadShard(Path dir, Map<Integer, String> corpus) throws IOException {
        ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30);
        for (Map.Entry<Integer, String> e : corpus.entrySet()) {
            b.addClass(e.getKey(), e.getValue());
        }
        b.close();
        ShardCatalog.write(dir, HASH, new ArrayList<>(b.writtenShards()));
        ContentShardIndex.loadCatalog(dir, HASH);
        assertTrue(ContentShardIndex.isBuilt(), "shard must be built for the test");
    }

    @Test
    void broadTermReturnsImmediateSampleInsteadOfFullScan(@TempDir Path dir) throws IOException {
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted); // idOf(sorted.get(i)) == i, ids 0..3 -> real classes

        // 6 candidate ids for the term "class": ids 0-3 map to real (decompilable) classes, ids
        // 4-5 are shard-only ids with no backing JavaClass (mirrors a corpus far larger than the
        // small test APK — resolveClass() returns null for them and they are skipped).
        Map<Integer, String> corpus = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            corpus.put(i, "class marker" + i + " { void m() {} }");
        }
        buildAndLoadShard(dir, corpus);

        // Materialise the real classes' sources. A bulk scan deliberately never live-decompiles
        // (that is serialised behind the global JadxSearchLock and costs seconds per class — see
        // SearchRoutesNoBulkLiveDecompileTest), so without this the sample would have nothing
        // readable to match and this test would be asserting on the wrong mechanism.
        for (int i = 0; i < 4 && i < sorted.size(); i++) sorted.get(i).getCode();

        // Tiny threshold/sample so the 6-candidate corpus above is "broad" and the scan is capped
        // well under the full 6 candidates.
        SearchRoutes.BROAD_CANDIDATE_THRESHOLD = 2;
        SearchRoutes.BROAD_SAMPLE_SCAN = 3;

        Set<SearchRoutes.SearchLocation> locations = EnumSet.of(SearchRoutes.SearchLocation.CODE);
        SearchRoutes.SearchExecution exec = routes.executeSearch(
            sorted, sorted, "class", locations, true, Integer.MAX_VALUE,
            SearchRoutes.MatchMode.SUBSTRING, null);

        Map<String, Object> info = exec.getResult().getSearchInfo();

        // Red-light assertion: prior to the guard existing, this field is never set.
        assertEquals(true, info.get("broad_term"), "broad-term path must be taken");
        assertEquals(6L, ((Number) info.get("candidate_count")).longValue(),
            "candidate_count must equal the shard's full candidate cardinality (O(1) known upfront)");
        assertEquals(true, info.get("partial_results"), "must be honestly marked partial");
        assertEquals(false, info.get("timed_out"), "broad-term short-circuit is not a timeout");
        assertTrue(((Number) info.get("sampled_scanned")).intValue() <= SearchRoutes.BROAD_SAMPLE_SCAN,
            "must never scan more than the bounded sample size");
        assertTrue(((String) info.get("hint")).contains("too broad"),
            "hint must explain the term is too broad to fully verify");

        // The term "class" is present in every decompiled Java class's own declaration, so the
        // bounded sample (ids 0-2, all real classes) must surface at least one genuine match.
        assertFalse(exec.getResult().getMatches().isEmpty(),
            "a genuinely matching class within the sampled window must appear in results");
    }

    @Test
    void narrowTermIsUnaffectedByGuardAndRunsFullScan(@TempDir Path dir) throws IOException {
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted);

        // Only 1 candidate (id 0) for a rare term — stays under any reasonable threshold, default
        // included, so the existing full-scan path must run unchanged.
        Map<Integer, String> corpus = new LinkedHashMap<>();
        corpus.put(0, "class marker { void wombatsentinel() {} }");
        buildAndLoadShard(dir, corpus);

        Set<SearchRoutes.SearchLocation> locations = EnumSet.of(SearchRoutes.SearchLocation.CODE);
        SearchRoutes.SearchExecution exec = routes.executeSearch(
            sorted, sorted, "wombatsentinel", locations, true, Integer.MAX_VALUE,
            SearchRoutes.MatchMode.SUBSTRING, null);

        Map<String, Object> info = exec.getResult().getSearchInfo();
        assertNull(info.get("broad_term"), "narrow terms must not take the broad-term path");
        assertNull(info.get("candidate_count"), "candidate_count is only emitted on the broad path");
    }

    /**
     * W13: the broad-word guard must only short-circuit when the search is content-only
     * (CODE/COMMENT). When searchLocations also includes a metadata location (CLASS_NAME here),
     * a class that matches by name but whose content does NOT contain the term (so it is never a
     * shard content-candidate at all) must still surface via the normal metadata-matching path —
     * the guard must not swallow it by short-circuiting into the content-only broad-term sample.
     */
    @Test
    void mixedMetadataAndContentSearchDoesNotDropClassNameMatchUnderBroadGuard(@TempDir Path dir) throws IOException {
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted);

        // Find the real class whose name contains "mainactivity" (sg.vantagepoint.uncrackable1.MainActivity
        // in the UnCrackable-Level1 test APK) and note its pre-assigned shard id.
        int mainActivityId = -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getFullName().toLowerCase().contains("mainactivity")) {
                mainActivityId = i;
                break;
            }
        }
        assertTrue(mainActivityId >= 0, "test APK must contain a class with 'mainactivity' in its name");

        // Build a shard corpus where every id EXCEPT mainActivityId contains the term "mainactivity"
        // in its content, so the shard reports a broad (above-threshold) candidate set that never
        // includes mainActivityId at all -- its only possible match is via its class name, not content.
        Map<Integer, String> corpus = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (i == mainActivityId) continue;
            corpus.put(i, "mainactivity trigger marker" + i + " { void m() {} }");
        }
        // Pad with extra shard-only ids (no backing JavaClass) to comfortably clear the threshold.
        corpus.put(1000, "mainactivity trigger marker1000 {}");
        corpus.put(1001, "mainactivity trigger marker1001 {}");
        buildAndLoadShard(dir, corpus);

        SearchRoutes.BROAD_CANDIDATE_THRESHOLD = 2;
        SearchRoutes.BROAD_SAMPLE_SCAN = 2;

        Set<SearchRoutes.SearchLocation> locations =
            EnumSet.of(SearchRoutes.SearchLocation.CLASS_NAME, SearchRoutes.SearchLocation.CODE);
        SearchRoutes.SearchExecution exec = routes.executeSearch(
            sorted, sorted, "mainactivity", locations, true, Integer.MAX_VALUE,
            SearchRoutes.MatchMode.SUBSTRING, null);

        Map<String, Object> info = exec.getResult().getSearchInfo();

        // Fixed behavior: a mixed metadata+content search must never take the content-only
        // broad-term short-circuit -- it always runs the normal path so metadata matches survive.
        assertNull(info.get("broad_term"),
            "mixed metadata+content searches must not take the content-only broad-term path");

        assertTrue(exec.getResult().getMatches().contains(sorted.get(mainActivityId).getFullName()),
            "class-name match must not be silently dropped when the search mixes metadata and content locations");
    }

    @Test
    void isContentOnlyTruthTable() {
        assertTrue(routes.isContentOnly(EnumSet.of(SearchRoutes.SearchLocation.CODE)));
        assertTrue(routes.isContentOnly(EnumSet.of(SearchRoutes.SearchLocation.COMMENT)));
        assertTrue(routes.isContentOnly(
            EnumSet.of(SearchRoutes.SearchLocation.CODE, SearchRoutes.SearchLocation.COMMENT)));
        assertFalse(routes.isContentOnly(
            EnumSet.of(SearchRoutes.SearchLocation.CLASS_NAME, SearchRoutes.SearchLocation.CODE)));
        assertFalse(routes.isContentOnly(EnumSet.of(SearchRoutes.SearchLocation.METHOD_NAME)));
        assertFalse(routes.isContentOnly(EnumSet.of(SearchRoutes.SearchLocation.FIELD_NAME)));
        assertFalse(routes.isContentOnly(EnumSet.noneOf(SearchRoutes.SearchLocation.class)));
    }
}
