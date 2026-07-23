package com.zin.delamain.server.routes;

import org.junit.jupiter.api.Test;
import com.zin.delamain.utils.PaginationUtils;
import io.javalin.http.Context;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class XrefsRoutesLineOffsetIndexTest {

    @Test
    void lineIndexMatchesFiniteReferenceAcrossAllBoundaryPositions() {
        assertEquivalent("first\n second\nlast", "\n");
        assertEquivalent("first\r\n second\r\nlast", "\r\n");
        assertEquivalent("", "\n");
        assertEquivalent("only", "\n");
        assertEquivalent("first\n", "\n");
    }

    @Test
    void lineIndexRejectsLfPositionInsideCrLfSeparator() {
        String source = "first\r\nsecond";
        int lfPosition = source.indexOf('\n');
        XrefsRoutes.SourceLineIndex index = XrefsRoutes.SourceLineIndex.build(source, "\r\n");

        assertNull(index.locate(lfPosition));
    }

    @Test
    void lineIndexBuildsOnceForManyPositionsAndRejectsInvalidMetadataPositions() {
        StringBuilder source = new StringBuilder();
        for (int line = 0; line < 1_000; line++) {
            source.append("line-").append(line).append('\n');
        }

        AtomicInteger builds = new AtomicInteger();
        XrefsRoutes.SourceLineIndex index = XrefsRoutes.SourceLineIndex.build(
            source.toString(), "\n", builds::incrementAndGet);

        for (int position = -1; position <= source.length() + 1; position++) {
            assertEquivalentAt(source.toString(), "\n", index, position);
        }

        assertEquals(1, builds.get(), "one referrer source must build its index exactly once");
    }

    @Test
    void invalidMetadataPositionProducesItsOwnPartialReason() {
        XrefsRoutes.Deadline deadline = XrefsRoutes.Deadline.none();
        deadline.markInvalidMetadataPosition();
        Map<String, Object> response = new HashMap<>();

        XrefsRoutes.annotatePartial(response, deadline);

        assertEquals(Boolean.TRUE, response.get("partial_results"));
        assertEquals("Some JADX metadata positions were invalid or could not be resolved in the decompiled "
                + "source and were skipped.",
            response.get("partial_reason"));
    }

    @Test
    void partialReasonKeepsDeadlineAndInvalidMetadataCauses() {
        XrefsRoutes.Deadline deadline = XrefsRoutes.Deadline.in(0, () -> 0L);
        deadline.shouldStop();
        deadline.markInvalidMetadataPosition();
        Map<String, Object> response = new HashMap<>();

        XrefsRoutes.annotatePartial(response, deadline);

        String reason = (String) response.get("partial_reason");
        assertEquals(Boolean.TRUE, response.get("partial_results"));
        org.junit.jupiter.api.Assertions.assertTrue(reason.contains("exceeded the server-side deadline"));
        org.junit.jupiter.api.Assertions.assertTrue(reason.contains("metadata positions were invalid"));
    }

    @Test
    void singlePayloadPartialFieldsSurviveStatusResultCopy() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", "single");
        payload.put("references", Collections.emptyList());
        payload.put("partial_results", true);
        payload.put("partial_reason", "metadata position skipped");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            XrefsRoutes routes = new XrefsRoutes(null, new PaginationUtils(), executor);
            Context pollContext = (Context) Proxy.newProxyInstance(
                Context.class.getClassLoader(), new Class<?>[] { Context.class },
                (proxy, method, args) -> "queryParam".equals(method.getName()) ? null : null);

            Map<String, Object> pollResult = routes.buildXrefStatusResponse(pollContext, payload);

            assertEquals(Boolean.TRUE, pollResult.get("partial_results"));
            assertEquals("metadata position skipped", pollResult.get("partial_reason"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stoppedDeadlineSkipsSourceIndexBuild() {
        AtomicInteger builds = new AtomicInteger();
        XrefsRoutes.Deadline deadline = XrefsRoutes.Deadline.in(0, () -> 0L);

        XrefsRoutes.SourceLineIndex index = XrefsRoutes.buildSourceLineIndexIfRunning(
            "large source\n", "\n", deadline, builds::incrementAndGet);

        assertNull(index);
        assertEquals(0, builds.get());
    }

    private static void assertEquivalent(String source, String newLine) {
        XrefsRoutes.SourceLineIndex index = XrefsRoutes.SourceLineIndex.build(source, newLine);
        for (int position = -1; position <= source.length() + 1; position++) {
            assertEquivalentAt(source, newLine, index, position);
        }
        assertLegacyEquivalentOnlyWhereSafe(source, newLine, index);
    }

    private static void assertEquivalentAt(
            String source, String newLine, XrefsRoutes.SourceLineIndex index, int position) {
        ExpectedLine expected = referenceLineFor(source, newLine, position);
        if (expected == null) {
            assertNull(index.lineNumberFor(position),
                "the index must reject invalid position " + position + " for " + printable(source));
            assertNull(index.lineFor(position));
            return;
        }
        XrefsRoutes.SourceLineIndex.LineLocation actual = index.locate(position);
        assertEquals(expected.number, actual.number,
            "line number at position " + position + " for " + printable(source));
        assertEquals(expected.text, actual.text,
            "line text at position " + position + " for " + printable(source));
    }

    private static void assertLegacyEquivalentOnlyWhereSafe(
            String source, String newLine, XrefsRoutes.SourceLineIndex index) {
        // JADX 1.5.6 loops forever when getLineNumForPos reaches a position without a later newline.
        // Restrict this supplemental compatibility check to positions that demonstrably have one.
        for (int position = 0; position < source.length(); position++) {
            if (source.charAt(position) == '\n'
                    || source.indexOf(newLine, position) < 0
                    || referenceLineFor(source, newLine, position) == null) continue;
            String legacyLine = jadx.api.utils.CodeUtils.getLineForPos(source, position);
            assertEquals(jadx.api.utils.CodeUtils.getLineNumForPos(source, position, newLine),
                index.lineNumberFor(position));
            assertEquals(legacyLine, index.lineFor(position));
        }
    }

    private static ExpectedLine referenceLineFor(String source, String newline, int position) {
        if (position < 0 || position > source.length()) return null;
        String effectiveNewline = (newline == null || newline.isEmpty()) ? "\n" : newline;
        if (position < source.length() && source.charAt(position) == '\n') {
            return null;
        }
        int lineNumber = 1;
        int lineStart = 0;
        int separator;
        while ((separator = source.indexOf(effectiveNewline, lineStart)) >= 0 && separator + 1 <= position) {
            lineNumber++;
            lineStart = separator + effectiveNewline.length();
        }
        int lineEnd = source.indexOf(effectiveNewline, lineStart);
        if (lineEnd < 0) lineEnd = source.length();
        return new ExpectedLine(lineNumber, source.substring(lineStart, lineEnd));
    }

    private static final class ExpectedLine {
        final int number;
        final String text;

        ExpectedLine(int number, String text) {
            this.number = number;
            this.text = text;
        }
    }

    private static String printable(String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }
}
