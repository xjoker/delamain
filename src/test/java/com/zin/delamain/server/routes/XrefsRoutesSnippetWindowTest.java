package com.zin.delamain.server.routes;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measured on production (XHS, 237 931 classes): {@code /xrefs-to-class} with
 * {@code include_snippet=true&count=1} against a high-fan-in class did not return inside the
 * gateway's 120 s ceiling — even after the referrer-recycling fix bounded its memory.
 *
 * <p>The reason is scope, not speed: {@code computeClassXrefs} attached a snippet to <b>every</b>
 * reference row, and pagination only happened afterwards in the handler. Asking for one row still
 * live-decompiled every referrer of the target — thousands of decompiles to render one snippet.
 * Recycling made that memory-safe; it could not make it fast, because the work itself was never
 * needed.
 *
 * <p>Contract: snippet attachment is bounded to the rows a caller will actually receive. This test
 * pins the bounding helper directly, since the cost being removed is exactly "how many rows get
 * touched".</p>
 */
class XrefsRoutesSnippetWindowTest {

    private static List<Map<String, Object>> rows(int n) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> ref = new HashMap<>();
            ref.put("class", "com.example.Referrer" + i);
            ref.put("decompiled_line", 2);
            list.add(ref);
        }
        return list;
    }

    @Test
    void windowIsTheRequestedPageOnly() {
        List<Map<String, Object>> all = rows(1000);

        List<Map<String, Object>> window = XrefsRoutes.snippetWindow(all, 0, 20);

        assertEquals(20, window.size(),
            "a caller asking for 20 rows must not cause work on the other 980");
        assertEquals("com.example.Referrer0", window.get(0).get("class"));
    }

    @Test
    void windowFollowsTheOffset() {
        List<Map<String, Object>> all = rows(1000);

        List<Map<String, Object>> window = XrefsRoutes.snippetWindow(all, 500, 10);

        assertEquals(10, window.size());
        assertEquals("com.example.Referrer500", window.get(0).get("class"),
            "the window must track the requested page, not just the first N rows");
    }

    @Test
    void windowRowsAreTheSameObjectsSoMutationIsVisibleToTheCaller() {
        List<Map<String, Object>> all = rows(10);

        List<Map<String, Object>> window = XrefsRoutes.snippetWindow(all, 2, 3);
        window.get(0).put("snippet", "attached");

        assertEquals("attached", all.get(2).get("snippet"),
            "snippets must land on the rows that get serialised, not on copies");
    }

    @Test
    void outOfRangeAndDegenerateWindowsAreEmptyRatherThanThrowing() {
        List<Map<String, Object>> all = rows(5);

        assertTrue(XrefsRoutes.snippetWindow(all, 99, 10).isEmpty(), "offset past the end");
        assertTrue(XrefsRoutes.snippetWindow(all, -1, 10).isEmpty(), "negative offset");
        assertTrue(XrefsRoutes.snippetWindow(all, 0, 0).isEmpty(), "zero count");
        assertTrue(XrefsRoutes.snippetWindow(null, 0, 10).isEmpty(), "null list");
    }

    @Test
    void aWindowLongerThanTheListStopsAtTheEnd() {
        List<Map<String, Object>> all = rows(5);

        assertEquals(3, XrefsRoutes.snippetWindow(all, 2, 100).size());
    }

    /**
     * The async path has no 120 s ceiling but must still not decompile an unbounded number of
     * referrers — a ticket that runs for an hour is its own kind of broken.
     */
    @Test
    void asyncCapIsBoundedAndSane() {
        assertTrue(XrefsRoutes.ASYNC_SNIPPET_CAP > 0 && XrefsRoutes.ASYNC_SNIPPET_CAP <= 500,
            "async snippet cap must bound the work, got " + XrefsRoutes.ASYNC_SNIPPET_CAP);
    }
}
