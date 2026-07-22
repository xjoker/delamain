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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 (A1 收尾债) — content-scan admission control.
 *
 * <p>A content search only fits inside {@code SEARCH_TIMEOUT_SECONDS} when some sound index can
 * prune the corpus first. Two holes let an unbounded scan through before this change, both
 * measured on production (XHS, 222 779 classes): {@code search_in=code} for "loadLibrary" burned
 * the full 60 s deadline and returned a partial result.
 *
 * <ol>
 *   <li><b>Leak (a) — no index at all.</b> The shard index is built in the background after
 *       warmup; until it is, {@code shardResult == null}, and with the heap trigram layer off by
 *       default (A1) nothing can prune. The scan silently degraded to a live read of every class
 *       in the corpus (222 779 × ~0.27 ms ≈ 60 s).</li>
 *   <li><b>Leak (b) — broad term on a mixed search.</b> The existing broad-word guard only fires
 *       for a content-<em>only</em> search (it samples content candidates and cannot do metadata
 *       matching). A search that mixes CLASS_NAME with CODE therefore skipped the guard entirely
 *       and scanned every one of the tens of thousands of candidates.</li>
 * </ol>
 *
 * <p>Required behaviour: never scan unbounded. Leak (a) → refuse the content phase up front and
 * say so honestly (metadata locations, if any, still run in full). Leak (b) → keep the full
 * metadata phase but cap the content scan at {@code BROAD_SAMPLE_SCAN} classes and mark the result
 * partial.</p>
 */
class SearchRoutesContentScanAdmissionTest {

    private static final String HASH = "0f0f0f0f0f0f0f0f";

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
        SearchRoutes.UNINDEXED_CONTENT_SCAN_MAX = SearchRoutes.DEFAULT_UNINDEXED_CONTENT_SCAN_MAX;
    }

    private static List<JavaClass> sortedClasses() {
        List<JavaClass> sorted = new ArrayList<>(classes);
        sorted.sort(Comparator.comparing(JavaClass::getRawName));
        return sorted;
    }

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

    private static int indexOfMainActivity(List<JavaClass> sorted) {
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getFullName().toLowerCase().contains("mainactivity")) return i;
        }
        return -1;
    }

    /**
     * Leak (a), content-only: no shard, heap trigram off, corpus above the admission cap → the
     * content scan must be refused immediately with an honest marker + actionable hint, instead of
     * scanning the whole corpus until the 60 s deadline.
     */
    @Test
    void contentOnlySearchWithNoUsableIndexIsRefusedInsteadOfFullScan() {
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted);
        SearchRoutes.UNINDEXED_CONTENT_SCAN_MAX = 2; // corpus (>=4) is "too large to scan blind"

        Set<SearchRoutes.SearchLocation> locations = EnumSet.of(SearchRoutes.SearchLocation.CODE);
        SearchRoutes.SearchExecution exec = routes.executeSearch(
            sorted, sorted, "loadlibrary", locations, true, Integer.MAX_VALUE,
            SearchRoutes.MatchMode.SUBSTRING, null);

        Map<String, Object> info = exec.getResult().getSearchInfo();
        assertEquals(true, info.get("content_scan_skipped"),
            "an unprunable content scan over a large corpus must be refused, not attempted");
        assertEquals(true, info.get("partial_results"), "refusing the scan makes the answer partial");
        assertEquals(false, info.get("timed_out"), "the refusal is immediate, not a timeout");
        assertTrue(((String) info.get("hint")).contains("search_in=class"),
            "hint must point the caller at the metadata search that IS available now");
        assertTrue(exec.getElapsedMs() < 10_000,
            "refusal must be immediate; took " + exec.getElapsedMs() + "ms");
    }

    /**
     * Leak (a), mixed search: refusing the content phase must not cost the caller its metadata
     * matches — CLASS_NAME matching is index-free and stays fully accurate.
     */
    @Test
    void mixedSearchWithNoUsableIndexStillReturnsMetadataMatches() {
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted);
        int mainActivityId = indexOfMainActivity(sorted);
        assertTrue(mainActivityId >= 0, "test APK must contain a class with 'mainactivity' in its name");
        SearchRoutes.UNINDEXED_CONTENT_SCAN_MAX = 2;

        Set<SearchRoutes.SearchLocation> locations =
            EnumSet.of(SearchRoutes.SearchLocation.CLASS_NAME, SearchRoutes.SearchLocation.CODE);
        SearchRoutes.SearchExecution exec = routes.executeSearch(
            sorted, sorted, "mainactivity", locations, true, Integer.MAX_VALUE,
            SearchRoutes.MatchMode.SUBSTRING, null);

        Map<String, Object> info = exec.getResult().getSearchInfo();
        assertEquals(true, info.get("content_scan_skipped"),
            "the content phase is still refused on a mixed search");
        assertTrue(exec.getResult().getMatches().contains(sorted.get(mainActivityId).getFullName()),
            "metadata (class-name) matching is index-free and must survive the content-phase refusal");
    }

    /**
     * A small corpus is cheap to scan blind, so the admission control must not fire for it — the
     * pre-existing full-scan behaviour stays byte-for-byte unchanged below the cap.
     */
    @Test
    void smallCorpusStillRunsTheFullContentScan() {
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted);
        // Default cap (20 000) is far above the test APK's class count.

        Set<SearchRoutes.SearchLocation> locations = EnumSet.of(SearchRoutes.SearchLocation.CODE);
        SearchRoutes.SearchExecution exec = routes.executeSearch(
            sorted, sorted, "onclick", locations, true, Integer.MAX_VALUE,
            SearchRoutes.MatchMode.SUBSTRING, null);

        Map<String, Object> info = exec.getResult().getSearchInfo();
        assertNull(info.get("content_scan_skipped"),
            "a corpus below the cap must be scanned normally");
    }

    /**
     * Leak (b): a mixed metadata+content search over a broad term must keep its full metadata
     * phase but bound the content scan to the sample size, and say the result is partial.
     */
    @Test
    void broadTermOnMixedSearchBoundsTheContentScan(@TempDir Path dir) throws IOException {
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted);
        int mainActivityId = indexOfMainActivity(sorted);
        assertTrue(mainActivityId >= 0, "test APK must contain a class with 'mainactivity' in its name");

        // Every id EXCEPT mainActivityId is a content candidate for "mainactivity"; mainActivity's
        // only route into the results is its class NAME.
        Map<Integer, String> corpus = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (i == mainActivityId) continue;
            corpus.put(i, "mainactivity trigger marker" + i + " { void m() {} }");
        }
        corpus.put(1000, "mainactivity trigger marker1000 {}");
        corpus.put(1001, "mainactivity trigger marker1001 {}");
        buildAndLoadShard(dir, corpus);

        SearchRoutes.BROAD_CANDIDATE_THRESHOLD = 2;
        SearchRoutes.BROAD_SAMPLE_SCAN = 1;

        Set<SearchRoutes.SearchLocation> locations =
            EnumSet.of(SearchRoutes.SearchLocation.CLASS_NAME, SearchRoutes.SearchLocation.CODE);
        SearchRoutes.SearchExecution exec = routes.executeSearch(
            sorted, sorted, "mainactivity", locations, true, Integer.MAX_VALUE,
            SearchRoutes.MatchMode.SUBSTRING, null);

        Map<String, Object> info = exec.getResult().getSearchInfo();
        assertEquals(true, info.get("content_scan_sampled"),
            "a broad term must bound the content scan even on a mixed search");
        assertTrue(((Number) info.get("content_scanned")).intValue() <= SearchRoutes.BROAD_SAMPLE_SCAN,
            "content scan must respect the sample budget, got " + info.get("content_scanned"));
        assertEquals(true, info.get("partial_results"), "a sampled content scan is partial");
        assertNull(info.get("broad_term"),
            "the content-only broad-term short-circuit still must not be taken for a mixed search");
        assertTrue(exec.getResult().getMatches().contains(sorted.get(mainActivityId).getFullName()),
            "the full metadata phase must still run — its match cannot be dropped by the content cap");
    }
}
