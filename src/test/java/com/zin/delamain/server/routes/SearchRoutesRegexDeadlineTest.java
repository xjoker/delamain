package com.zin.delamain.server.routes;

import com.zin.delamain.utils.DeadlineRegex;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F5 (5): {@code match_mode=regex} compiles a user-supplied pattern and matches it via {@code
 * Matcher.find()}, which has no interruption point of its own — a catastrophically backtracking
 * pattern (the classic {@code (a+)+$} shape) run against a long non-matching string can pin the
 * calling thread indefinitely. The per-class/per-batch deadline checks elsewhere in {@link
 * SearchRoutes} only run BETWEEN classes, so they cannot stop a single such call once it starts.
 *
 * <p>Pinned here: {@link DeadlineRegex#matchesWithDeadline} bounds any single match call to a
 * fixed wall-clock budget regardless of how the pattern backtracks, by wrapping the matched text
 * in a {@code DeadlineCharSequence} that throws once the budget is spent — {@code Matcher.find()}
 * sees that as an ordinary exception and unwinds, rather than continuing to backtrack.</p>
 *
 * <p>F9: this logic used to live directly on {@code SearchRoutes} (duplicated identically on
 * {@code AnalysisRoutes}); both now delegate to the shared {@link DeadlineRegex}, which is what
 * this test exercises directly.</p>
 *
 * <p>The red state for this test was a compile error, not an actual hang: {@code
 * matchesWithDeadline} did not exist before this fix, and the old, unguarded call
 * ({@code compiledRegex.matcher(candidate).find()}) genuinely does not return in any reasonable
 * time on the input below — running it unguarded in CI to observe the hang would have meant
 * either an indefinite test run or accepting a suspiciously specific "sometimes times out" flake,
 * neither of which is an acceptable red-test strategy here.</p>
 */
class SearchRoutesRegexDeadlineTest {

    @Test
    void catastrophicRegexReturnsWithinBoundedTimeInsteadOfHanging() {
        Pattern evil = Pattern.compile("(a+)+$", Pattern.CASE_INSENSITIVE);
        // Classic ReDoS trigger: exponential backtracking, and the trailing '!' guarantees no match.
        String longNonMatching = "a".repeat(40) + "!";

        long startNanos = System.nanoTime();
        boolean matched = DeadlineRegex.matchesWithDeadline(evil, longNonMatching);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertFalse(matched, "a string that does not match must be reported as no-match, "
            + "whether that was decided by the regex engine or by a tripped deadline");
        assertTrue(elapsedMs < 5000,
            "a catastrophically backtracking regex must be bounded by the per-call deadline "
                + "(REGEX_MATCH_TIMEOUT_MILLIS), not run unbounded — took " + elapsedMs + "ms");
    }

    @Test
    void normalRegexMatchingStillWorksCorrectlyThroughTheDeadlineWrapper() {
        Pattern greeting = Pattern.compile("hello", Pattern.CASE_INSENSITIVE);

        assertTrue(DeadlineRegex.matchesWithDeadline(greeting, "say HELLO world"),
            "the deadline wrapper must not change matching semantics for an ordinary regex");
        assertFalse(DeadlineRegex.matchesWithDeadline(greeting, "say goodbye world"));
    }
}
