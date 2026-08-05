package com.zin.delamain.server.routes;

import jadx.api.JavaClass;
import jadx.api.JavaNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.PaginationUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F5 (1): {@code POST /submit-xref}'s inline fast-path branch (the {@code isXrefFastPathLikely +
 * JadxSearchLock.tryAcquireRead()} check in {@link XrefsRoutes#handleSubmitXref}) runs on the
 * calling Javalin request thread — it is not the background-pool ticket path the surrounding
 * comment used to claim. {@code isXrefFastPathLikely} is only a prediction: {@code
 * computeClassXrefs}'s {@code coversAllReferrers} gate can still force a full live decompile of
 * every referrer when the precomputed index's coverage is incomplete (a documented, expected
 * case — synthetic-field referrers). Before this fix, {@link XrefsRoutes#computeSingleXref}
 * hardcoded {@code Deadline.none()} internally, so that live-decompile fallback ran unbounded on
 * the request thread while holding the read lock — the exact shape of the 2026-07-22 outage.
 *
 * <p>Pinned here: {@code computeSingleXref} now takes the deadline from its caller, so the
 * request-thread call site can (and does) pass a bounded {@code Deadline.in(...)} and the live
 * path inside it actually stops instead of running to completion.</p>
 */
class XrefsRoutesSingleXrefDeadlineTest {

    private HeadlessJadxWrapper wrapper;
    private XrefsRoutes routes;

    @BeforeEach
    void setUp(@TempDir Path workDir) throws Exception {
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        wrapper = new HeadlessJadxWrapper(List.of(apk), new File(workDir.toFile(), "out"), 2);
        wrapper.load();
        routes = new XrefsRoutes(wrapper, new PaginationUtils());

        ClassCacheManager.initCache(wrapper);
        long until = System.currentTimeMillis() + 60_000;
        while (ClassCacheManager.getCache().isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertFalse(ClassCacheManager.getCache().isEmpty(), "class cache must be built for this test");
    }

    @AfterEach
    void tearDown() {
        if (wrapper != null) wrapper.close();
    }

    private static List<JavaClass> referrersOf(JavaClass target) {
        List<JavaClass> referrers = new ArrayList<>();
        for (JavaNode node : target.getUseIn()) {
            if (node instanceof JavaClass && !referrers.contains(node)) {
                referrers.add((JavaClass) node);
            }
        }
        return referrers;
    }

    /** A class whose live xref actually has several referrers to walk (so "partial" is meaningful). */
    private JavaClass targetWithReferrers(int atLeast) throws Exception {
        JavaClass best = null;
        int bestCount = 0;
        for (JavaClass cls : ClassCacheManager.getCache().values()) {
            int n = referrersOf(cls).size();
            if (n > bestCount) {
                bestCount = n;
                best = cls;
            }
        }
        assertNotNull(best, "test APK must contain a referenced class");
        assertTrue(bestCount >= atLeast,
            "test APK must contain a class with at least " + atLeast + " referrers, best was " + bestCount);
        return best;
    }

    /**
     * UsePlacesIndex/UsageGraphIndex are not built in this unit test (no warmup ran), so
     * computeSingleXref("class", ..., includeSnippet=true, ...) always falls through to the live
     * path here — exactly the branch the request-thread fast-path call must bound.
     */
    @Test
    void requestThreadFastPathHonorsCallerSuppliedDeadlineInsteadOfRunningUnbounded() throws Exception {
        JavaClass target = targetWithReferrers(1);

        Map<String, Object> full = routes.computeSingleXref(
            "class", target.getFullName(), null, true, 3, XrefsRoutes.Deadline.none());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fullRefs = (List<Map<String, Object>>) full.get("references");
        assertFalse(fullRefs.isEmpty(), "precondition: the live path must produce references at all");
        assertFalse(Boolean.TRUE.equals(full.get("partial_results")),
            "an unbounded deadline must not be marked partial");

        Map<String, Object> partial = routes.computeSingleXref(
            "class", target.getFullName(), null, true, 3, XrefsRoutes.Deadline.in(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> partialRefs = (List<Map<String, Object>>) partial.get("references");

        assertTrue(partialRefs.size() < fullRefs.size(),
            "an expired deadline passed in by the request-thread fast-path caller must actually "
                + "cut the live-decompile referrer walk short, not run it to completion "
                + "(full=" + fullRefs.size() + ", partial=" + partialRefs.size() + ")");
        assertEquals(Boolean.TRUE, partial.get("partial_results"),
            "a truncated request-thread computation must be labelled partial, not silently "
                + "returned as if it were complete");
    }
}
