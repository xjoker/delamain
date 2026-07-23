package com.zin.delamain.server.routes;

import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.utils.ClassCacheManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live xref path decompiles every referrer on the request thread. On production (XHS, 237 931
 * classes) that ran for minutes against a high-fan-in class; the client gave up at 120 s but the
 * server thread kept running — and kept holding the analysis read lock, which is what took the
 * whole service down on 2026-07-22.
 *
 * <p>Contract pinned here: the live path stops at a server-side deadline and returns what it has,
 * labelled {@code partial_results}, instead of running unbounded. The deadline value is the upper
 * bound on how long one request can hold the read lock, so it is deliberately well under the
 * gateway's 120 s ceiling.</p>
 */
class XrefsRoutesLiveDeadlineTest {

    private HeadlessJadxWrapper wrapper;
    private XrefsRoutes routes;

    @Test
    void syntheticVisitorStopsAtExactDeadlineBoundary() throws Exception {
        AtomicLong ticker = new AtomicLong();
        XrefsRoutes.Deadline deadline = XrefsRoutes.Deadline.in(3, ticker::get);
        List<Integer> items = IntStream.range(0, 100).boxed().toList();

        XrefsRoutes.BoundedVisitResult result = XrefsRoutes.visitUntilStopped(
            items,
            deadline,
            item -> {
                ticker.addAndGet(1_000_000L);
                return item == 1;
            });

        assertEquals(3, result.visitedCount);
        assertTrue(result.anyAccepted);
        assertTrue(result.stoppedEarly);
        assertTrue(deadline.truncated());
    }

    @Test
    void deadlineNoneStillStopsAfterVisitorInterruptsThirdItem() throws Exception {
        List<Integer> items = IntStream.range(0, 100).boxed().toList();
        try {
            XrefsRoutes.BoundedVisitResult result = XrefsRoutes.visitUntilStopped(
                items,
                XrefsRoutes.Deadline.none(),
                item -> {
                    if (item == 2) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                });

            assertEquals(3, result.visitedCount);
            assertTrue(result.anyAccepted);
            assertTrue(result.stoppedEarly);
            assertTrue(Thread.currentThread().isInterrupted(),
                "cooperative cancellation must preserve the interrupt for outer cleanup");
        } finally {
            Thread.interrupted();
        }
    }

    @BeforeEach
    void setUp(@TempDir Path workDir) throws Exception {
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        wrapper = new HeadlessJadxWrapper(List.of(apk), new File(workDir.toFile(), "out"), 2);
        wrapper.load();
        routes = new XrefsRoutes(wrapper, null);

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

    /** Referrer classes of {@code target}, i.e. the work list the live path walks. */
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

    @Test
    void anExpiredDeadlineTruncatesTheLivePathInsteadOfWalkingEveryReferrer() throws Exception {
        JavaClass target = targetWithReferrers(1);
        List<JavaClass> referrers = referrersOf(target);

        List<Map<String, Object>> full = routes.collectPreciseReferences(
            target, referrers, Collections.emptyList(), XrefsRoutes.Deadline.none());
        assertFalse(full.isEmpty(), "precondition: the live path must produce references at all");

        XrefsRoutes.Deadline expired = XrefsRoutes.Deadline.in(0);
        List<Map<String, Object>> partial = routes.collectPreciseReferences(
            target, referrers, Collections.emptyList(), expired);

        assertTrue(partial.size() < full.size(),
            "an expired deadline must stop the referrer walk, not run it to completion "
                + "(full=" + full.size() + ", partial=" + partial.size() + ")");
        assertTrue(expired.truncated(),
            "the deadline must record that results were cut short, otherwise the response lies");
    }

    @Test
    void truncatedResultsAreLabelledPartialForTheCaller() throws Exception {
        XrefsRoutes.Deadline expired = XrefsRoutes.Deadline.in(0);
        JavaClass target = targetWithReferrers(1);
        routes.collectPreciseReferences(target, referrersOf(target), Collections.emptyList(), expired);

        Map<String, Object> response = new HashMap<>();
        XrefsRoutes.annotatePartial(response, expired);

        assertEquals(Boolean.TRUE, response.get("partial_results"),
            "an AI client must be able to tell a truncated xref from a complete one");
        assertNotNull(response.get("partial_reason"), "the truncation must explain itself");
    }

    @Test
    void aCompleteRunIsNotLabelledPartial() throws Exception {
        XrefsRoutes.Deadline none = XrefsRoutes.Deadline.none();
        JavaClass target = targetWithReferrers(1);
        routes.collectPreciseReferences(target, referrersOf(target), Collections.emptyList(), none);

        Map<String, Object> response = new HashMap<>();
        XrefsRoutes.annotatePartial(response, none);

        assertFalse(response.containsKey("partial_results"),
            "complete results must stay clean — a permanent partial_results flag would be ignored");
    }

    /**
     * The method xref path was left behind when the class path stopped snippeting the full table
     * (20260722.5): it still attached a snippet to every reference row before pagination, so asking
     * for one row paid for a live decompile of every referrer.
     */
    @Test
    void theMethodPathDoesNotSnippetTheWholeTable() throws Exception {
        JavaMethod referenced = null;
        int best = 0;
        for (JavaClass cls : ClassCacheManager.getCache().values()) {
            for (JavaMethod m : cls.getMethods()) {
                int n = m.getUseIn().size();
                if (n > best) {
                    best = n;
                    referenced = m;
                }
            }
        }
        assertNotNull(referenced, "test APK must contain a referenced method");
        assertTrue(best >= 1, "test APK must contain a method with referrers");

        XrefsRoutes.XrefComputeResult computed = routes.computeMethodXrefs(
            List.of(referenced), true, 3, XrefsRoutes.Deadline.none());

        assertFalse(computed.references.isEmpty(), "precondition: the method must have references");
        for (Map<String, Object> ref : computed.references) {
            assertFalse(ref.containsKey("snippet"),
                "the method path must leave snippets to the caller's page, like the class path — "
                    + "otherwise one row costs a decompile of every referrer: " + ref);
        }
    }
}
