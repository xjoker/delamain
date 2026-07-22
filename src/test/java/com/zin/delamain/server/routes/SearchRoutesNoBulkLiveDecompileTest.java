package com.zin.delamain.server.routes;

import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.shard.ContentShardIndex;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0, third pass — the measurement that closed it.
 *
 * <p>After the scan budget landed, production still spent the full 60 s on
 * {@code search_in=code} for "loadLibrary": {@code content_scanned=16053} against a budget of
 * 20 000, i.e. the budget never even bound. 16 053 classes in 60 s is ~3.7 ms per class, not the
 * ~0.27 ms a CodeStore read costs. The difference is the fallback in
 * {@code classMatchesAnyContentLocation}: a class with no in-memory cache entry and no persisted
 * CodeStore source gets <b>live-decompiled</b>, and live decompile is serialised behind the global
 * {@code JadxSearchLock}. A bulk scan that live-decompiles is unbounded by construction — no
 * class-count budget can fix it, because the per-class cost is seconds, not milliseconds.</p>
 *
 * <p>So a bulk content scan must read only sources that are already materialised (jadx's code
 * cache or the persistent CodeStore) and must report how many classes it could not read, rather
 * than decompiling them one by one behind a global lock. Single-class tools (get_class_source,
 * smali, xref) are unaffected — live decompile there is bounded and expected.</p>
 */
class SearchRoutesNoBulkLiveDecompileTest {

    private static final long PER_CLASS_LIVE_DECOMPILE_FLOOR_MS = 3;

    private JadxDecompiler jadx;
    private List<JavaClass> sorted;
    private final SearchRoutes routes = new SearchRoutes(null, null);

    @BeforeEach
    void setUp() {
        // A FRESH decompiler per test: nothing decompiled yet, so no class has materialised
        // source — exactly the state of the ~43 k shard-uncovered classes in production.
        JadxArgs args = new JadxArgs();
        args.setInputFile(new File("test-harness/real/UnCrackable-Level1.apk"));
        args.setSkipResources(true);
        jadx = new JadxDecompiler(args);
        jadx.load();
        sorted = new ArrayList<>(jadx.getClasses());
        sorted.sort(Comparator.comparing(JavaClass::getRawName));
        assertTrue(sorted.size() >= 4, "test APK must yield at least 4 classes");
        CodeContentIndex.clear();
        ContentShardIndex.clear();
        CodeContentIndex.preAssignIds(sorted);
        SearchRoutes.PARALLEL_SCAN_MIN_CLASSES = 1; // drive the branch production takes
    }

    @AfterEach
    void tearDown() {
        SearchRoutes.PARALLEL_SCAN_MIN_CLASSES = SearchRoutes.DEFAULT_PARALLEL_SCAN_MIN_CLASSES;
        CodeContentIndex.clear();
        ContentShardIndex.clear();
        if (jadx != null) jadx.close();
    }

    @Test
    void bulkScanDoesNotLiveDecompileAndSaysHowManyItCouldNotRead() {
        SearchRoutes.SearchExecution exec = routes.executeSearch(
            sorted, sorted, "onclick", EnumSet.of(SearchRoutes.SearchLocation.CODE), true,
            Integer.MAX_VALUE, SearchRoutes.MatchMode.SUBSTRING, null);

        Map<String, Object> info = exec.getResult().getSearchInfo();
        Number unread = (Number) info.get("content_unreadable_classes");
        assertNotNull(unread, "the scan must report classes it could not read without decompiling: " + info);
        assertTrue(unread.intValue() > 0,
            "with nothing decompiled yet, every candidate is unreadable: " + info);
        assertEquals(true, info.get("partial_results"),
            "skipping unreadable classes makes the answer partial: " + info);
        assertTrue(String.valueOf(info.get("hint")).contains("warmup"),
            "the hint must point at warmup, which is what materialises those sources: " + info);
    }

    @Test
    void bulkScanOverUnmaterialisedSourcesIsFast() {
        long t0 = System.nanoTime();
        routes.executeSearch(sorted, sorted, "onclick",
            EnumSet.of(SearchRoutes.SearchLocation.CODE), true, Integer.MAX_VALUE,
            SearchRoutes.MatchMode.SUBSTRING, null);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // Live-decompiling even this 15-class fixture costs far more than this; the ceiling is
        // deliberately generous so the test pins the behaviour, not the machine.
        assertTrue(elapsedMs < sorted.size() * PER_CLASS_LIVE_DECOMPILE_FLOOR_MS + 2000,
            "a bulk scan must not pay live-decompile cost per class; took " + elapsedMs + "ms for "
                + sorted.size() + " classes");
    }

    @Test
    void materialisedSourcesAreStillSearched() {
        // Materialise one class's source through jadx's own code cache (what warmup does at scale),
        // then it must be found by the same bulk scan that refuses to decompile the others.
        JavaClass target = null;
        for (JavaClass cls : sorted) {
            String code = cls.getCode();
            if (code != null && code.toLowerCase().contains("onclick")) { target = cls; break; }
        }
        assertNotNull(target, "test APK must contain a class whose source mentions onClick");

        SearchRoutes.SearchExecution exec = routes.executeSearch(
            sorted, sorted, "onclick", EnumSet.of(SearchRoutes.SearchLocation.CODE), true,
            Integer.MAX_VALUE, SearchRoutes.MatchMode.SUBSTRING, null);

        assertTrue(exec.getResult().getMatches().contains(target.getFullName()),
            "a class with materialised source must still match: " + exec.getResult().getSearchInfo());
    }
}
