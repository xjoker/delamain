package com.zin.delamain.utils;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2: {@link DeadlineRegex} was extracted from two previously byte-for-byte-duplicated ReDoS
 * guards, but only ever got exercised transitively through its callers ({@code
 * SearchRoutesRegexDeadlineTest}, {@code AnalysisRoutesLiteralSourceTest}). This pins its own
 * contract directly, independent of either caller.
 *
 * <p>Note on scope: {@link DeadlineRegex#MAX_USER_REGEX_LENGTH} is a published constant that
 * {@code SearchRoutes}/{@code AnalysisRoutes} both check themselves before ever calling {@link
 * DeadlineRegex#matchesWithDeadline}; {@code DeadlineRegex} does not enforce that cap itself (it
 * has no method that even sees the pattern's source length, only a compiled {@link Pattern}), so
 * there is no "overlong regex rejected" behavior belonging to this class to test — that
 * contract lives on the callers and is already covered by their own tests. Testing it here would
 * just be re-asserting a literal constant.
 */
class DeadlineRegexTest {

    @Test
    void matchesWithDeadlineFindsAnOrdinaryMatchLikeAnUnwrappedMatcherWould() {
        Pattern greeting = Pattern.compile("hello", Pattern.CASE_INSENSITIVE);

        assertTrue(DeadlineRegex.matchesWithDeadline(greeting, "say HELLO world"),
            "the deadline wrapper must not change matching semantics for an ordinary regex");
    }

    @Test
    void matchesWithDeadlineReportsNoMatchWhenThereGenuinelyIsNone() {
        Pattern greeting = Pattern.compile("hello", Pattern.CASE_INSENSITIVE);

        assertFalse(DeadlineRegex.matchesWithDeadline(greeting, "say goodbye world"),
            "a non-matching input must be reported as no-match, not treated as a deadline trip");
    }

    @Test
    void catastrophicBacktrackingPatternReturnsWithinBudgetInsteadOfHanging() {
        Pattern evil = Pattern.compile("(a+)+$", Pattern.CASE_INSENSITIVE);
        // Classic exponential-backtracking trigger; trailing '!' guarantees no match is possible.
        String longNonMatching = "a".repeat(40) + "!";

        long startNanos = System.nanoTime();
        boolean matched = DeadlineRegex.matchesWithDeadline(evil, longNonMatching);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertFalse(matched,
            "a tripped deadline must be treated as no-match — the safe default, never a false "
                + "positive");
        assertTrue(elapsedMs < DeadlineRegex.REGEX_MATCH_TIMEOUT_MILLIS + 1000,
            "must be bounded by REGEX_MATCH_TIMEOUT_MILLIS (" + DeadlineRegex.REGEX_MATCH_TIMEOUT_MILLIS
                + "ms), not run to whatever conclusion unbounded backtracking reaches — took "
                + elapsedMs + "ms");
    }

    @Test
    void emptyCandidateAgainstAnEmptyMatchingPatternStillMatches() {
        Pattern optionalOnly = Pattern.compile("a*");

        assertTrue(DeadlineRegex.matchesWithDeadline(optionalOnly, ""),
            "an empty CharSequence is a valid input the wrapper must still hand through correctly");
    }

    @Test
    void emptyCandidateAgainstANonEmptyRequiredPatternDoesNotMatch() {
        Pattern requiresChar = Pattern.compile("a+");

        assertFalse(DeadlineRegex.matchesWithDeadline(requiresChar, ""));
    }

    @Test
    void nullCandidatePropagatesAsNullPointerExceptionRatherThanBeingTreatedAsNoMatch() {
        Pattern greeting = Pattern.compile("hello");

        // DeadlineRegex has no null-check of its own — it wraps whatever CharSequence it is given
        // and relies on the Matcher/regex engine to dereference it, exactly like an unwrapped
        // Matcher.find() would. This is a boundary-behavior pin, not an endorsement: callers are
        // expected to never pass null (all current call sites pass non-null Strings).
        assertThrows(NullPointerException.class,
            () -> DeadlineRegex.matchesWithDeadline(greeting, null));
    }

    @Test
    void publishedBudgetConstantsMatchWhatCallersDependOn() {
        // SearchRoutes/AnalysisRoutes read these directly for their own length-cap rejection and
        // error messages; pinning the values here catches an accidental change at the source.
        assertEquals(500, DeadlineRegex.MAX_USER_REGEX_LENGTH);
        assertEquals(2000, DeadlineRegex.REGEX_MATCH_TIMEOUT_MILLIS);
    }
}
