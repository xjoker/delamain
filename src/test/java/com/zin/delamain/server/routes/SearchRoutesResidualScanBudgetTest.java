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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0, second pass — found by production re-verification of 20260722.2 on the XHS APK, where
 * {@code search_in=code} for "loadLibrary" STILL burned the full 60 s despite the first-pass
 * guards. The response explained why:
 *
 * <pre>
 *   shard_index_built=true, shard_covered_pruned=106614, filtered_classes=237931, timed_out=true
 * </pre>
 *
 * The shard index covered only about half the corpus. The other ~131 k classes are shard-uncovered,
 * so {@code isDefinitivelyAbsent} (correctly) refuses to prune them and every one gets a real
 * content read. Neither first-pass guard applies: the admission control only fires when NO index
 * exists at all, and the broad-term guard only when the shard's candidate cardinality is huge —
 * here the candidate set was small and the shard did exist.
 *
 * <p>The invariant was wrong. What has to be bounded is <b>how many classes actually get content-
 * scanned</b>, not whether some index exists. So every content scan now carries a budget, and the
 * budget is spent shard-candidates-first: classes the index says may match are scanned to
 * completion (that part of the answer stays exact), and the unindexed residue is sampled up to the
 * budget and honestly marked partial.</p>
 */
class SearchRoutesResidualScanBudgetTest {

    private static final String HASH = "beefbeefbeefbeef";

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
        if (jadx != null) jadx.close();
    }

    @BeforeEach
    void reset() {
        CodeContentIndex.clear();
        ContentShardIndex.clear();
        // Drive the parallel branch (the one production takes) with this small fixture.
        SearchRoutes.PARALLEL_SCAN_MIN_CLASSES = 1;
    }

    @AfterEach
    void tearDown() {
        ContentShardIndex.clear();
        CodeContentIndex.clear();
        SearchRoutes.PARALLEL_SCAN_MIN_CLASSES = SearchRoutes.DEFAULT_PARALLEL_SCAN_MIN_CLASSES;
        SearchRoutes.CONTENT_SCAN_BUDGET = SearchRoutes.DEFAULT_CONTENT_SCAN_BUDGET;
        SearchRoutes.BROAD_CANDIDATE_THRESHOLD = SearchRoutes.DEFAULT_BROAD_CANDIDATE_THRESHOLD;
        SearchRoutes.BROAD_SAMPLE_SCAN = SearchRoutes.DEFAULT_BROAD_SAMPLE_SCAN;
        SearchRoutes.UNINDEXED_CONTENT_SCAN_MAX = SearchRoutes.DEFAULT_UNINDEXED_CONTENT_SCAN_MAX;
    }

    private static List<JavaClass> sortedClasses() {
        List<JavaClass> sorted = new ArrayList<>(classes);
        sorted.sort(Comparator.comparing(JavaClass::getRawName));
        return sorted;
    }

    private static void buildAndLoadShard(Path dir, Map<Integer, String> corpus) throws IOException {
        ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30);
        for (Map.Entry<Integer, String> e : corpus.entrySet()) b.addClass(e.getKey(), e.getValue());
        b.close();
        ShardCatalog.write(dir, HASH, new ArrayList<>(b.writtenShards()));
        ContentShardIndex.loadCatalog(dir, HASH);
        assertTrue(ContentShardIndex.isBuilt(), "shard must be built for the test");
    }

    /**
     * The production shape: shard built, but only covering part of the corpus. The uncovered
     * residue must be bounded by the budget instead of scanned to the 60 s deadline.
     */
    @Test
    void shardUncoveredResidueIsBoundedByTheScanBudget(@TempDir Path dir) throws IOException {
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted);

        // Shard covers ONLY id 0 (and it is not a candidate for the term) — every other real class
        // is uncovered residue, exactly like the ~131k uncovered classes in production.
        Map<Integer, String> corpus = new LinkedHashMap<>();
        corpus.put(0, "class marker0 { void unrelatedmethod() {} }");
        buildAndLoadShard(dir, corpus);

        SearchRoutes.CONTENT_SCAN_BUDGET = 1;

        Set<SearchRoutes.SearchLocation> locations = EnumSet.of(SearchRoutes.SearchLocation.CODE);
        SearchRoutes.SearchExecution exec = routes.executeSearch(
            sorted, sorted, "onclick", locations, true, Integer.MAX_VALUE,
            SearchRoutes.MatchMode.SUBSTRING, null);

        Map<String, Object> info = exec.getResult().getSearchInfo();
        assertEquals(true, info.get("content_scan_sampled"),
            "an unbounded residue scan must be capped and said so: " + info);
        assertTrue(((Number) info.get("content_scanned")).intValue() <= 1,
            "the budget must actually bind, got content_scanned=" + info.get("content_scanned"));
        assertEquals(true, info.get("partial_results"), "a sampled scan is partial: " + info);
        assertNotNull(info.get("content_candidates_total"),
            "the caller must be able to see how much of the corpus went unscanned: " + info);
        assertEquals(false, info.get("timed_out"), "bounding the scan must prevent the timeout: " + info);
    }

    /**
     * Budget spending order is what keeps the answer useful: classes the shard says may contain the
     * term are scanned first (that part stays exact), and only the unindexed residue is sampled.
     */
    @Test
    void shardCandidatesAreScannedBeforeTheUnindexedResidue(@TempDir Path dir) throws IOException {
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted);

        // Find a real class whose decompiled source genuinely contains "onclick".
        int matchId = -1;
        for (int i = 0; i < sorted.size(); i++) {
            String code = sorted.get(i).getCode();
            if (code != null && code.toLowerCase().contains("onclick")) { matchId = i; break; }
        }
        assertTrue(matchId >= 0, "test APK must contain a class whose source mentions onClick");

        // The shard covers exactly that class and marks it a candidate; everything else is
        // uncovered residue. With a budget of 1, a candidates-first scan finds the match; a
        // residue-first scan spends the single slot on a non-match and finds nothing.
        Map<Integer, String> corpus = new LinkedHashMap<>();
        corpus.put(matchId, "onclick marker { void m() {} }");
        buildAndLoadShard(dir, corpus);

        SearchRoutes.CONTENT_SCAN_BUDGET = 1;

        SearchRoutes.SearchExecution exec = routes.executeSearch(
            sorted, sorted, "onclick", EnumSet.of(SearchRoutes.SearchLocation.CODE), true,
            Integer.MAX_VALUE, SearchRoutes.MatchMode.SUBSTRING, null);

        assertTrue(exec.getResult().getMatches().contains(sorted.get(matchId).getFullName()),
            "the shard candidate must be scanned before the unindexed residue: "
                + exec.getResult().getSearchInfo());
    }

    /** A scan that fits inside the budget must stay exact and must not claim to be sampled. */
    @Test
    void scanWithinBudgetIsNotMarkedSampled(@TempDir Path dir) throws IOException {
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted);
        Map<Integer, String> corpus = new LinkedHashMap<>();
        corpus.put(0, "class marker0 { void unrelatedmethod() {} }");
        buildAndLoadShard(dir, corpus);

        // Materialise every source, so the scan is complete on both counts: within budget AND with
        // nothing skipped as unreadable (a bulk scan never live-decompiles — see
        // SearchRoutesNoBulkLiveDecompileTest — and skipped classes would legitimately make the
        // result partial for a different reason than the one under test here).
        for (JavaClass cls : sorted) cls.getCode();

        // Default budget (20 000) dwarfs this fixture.
        SearchRoutes.SearchExecution exec = routes.executeSearch(
            sorted, sorted, "onclick", EnumSet.of(SearchRoutes.SearchLocation.CODE), true,
            Integer.MAX_VALUE, SearchRoutes.MatchMode.SUBSTRING, null);

        Map<String, Object> info = exec.getResult().getSearchInfo();
        assertNull(info.get("content_scan_sampled"),
            "a scan that fit inside the budget must not be marked sampled: " + info);
        assertNull(info.get("partial_results"), "and must not be marked partial: " + info);
    }
}
