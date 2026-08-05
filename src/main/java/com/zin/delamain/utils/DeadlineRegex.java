package com.zin.delamain.utils;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F9: shared ReDoS guard extracted from what used to be two byte-for-byte identical copies in
 * {@code SearchRoutes} and {@code AnalysisRoutes} (constants, {@code matchesWithDeadline} and the
 * {@code DeadlineCharSequence} it depends on). A user-supplied regex is bounded twice: a cheap
 * length cap ({@link #MAX_USER_REGEX_LENGTH}) as a first line of defense, and a hard wall-clock
 * budget ({@link #REGEX_MATCH_TIMEOUT_MILLIS}) enforced during matching as the real backstop —
 * {@code java.util.regex.Matcher} has no built-in timeout or cancellation, so a catastrophically
 * backtracking pattern would otherwise run {@link Matcher#find()} to whatever conclusion it
 * reaches, however long that takes, pinning the calling thread the entire time.
 */
public final class DeadlineRegex {

    public static final int MAX_USER_REGEX_LENGTH = 500;
    public static final long REGEX_MATCH_TIMEOUT_MILLIS = 2000;

    private DeadlineRegex() {
    }

    /**
     * Runs a user-supplied regex against {@code candidate} with a hard wall-clock budget
     * ({@link #REGEX_MATCH_TIMEOUT_MILLIS}) instead of letting {@link Matcher#find()} run to
     * completion. A tripped budget is treated as "no match" — the safe default (never silently
     * report a false positive).
     */
    public static boolean matchesWithDeadline(Pattern compiledRegex, CharSequence candidate) {
        try {
            return compiledRegex.matcher(DeadlineCharSequence.withTimeout(candidate, REGEX_MATCH_TIMEOUT_MILLIS)).find();
        } catch (RegexDeadlineExceededException e) {
            return false;
        }
    }

    /** Internal control-flow signal thrown by {@link DeadlineCharSequence} — never escapes {@link #matchesWithDeadline}. */
    private static final class RegexDeadlineExceededException extends RuntimeException {
        private RegexDeadlineExceededException() {
            super(null, null, false, false); // no message/cause/suppression/stack trace — pure control flow
        }
    }

    /**
     * Wraps a CharSequence so a regex match against it can be interrupted mid-match. {@code
     * java.util.regex.Matcher} has no built-in timeout or cancellation — a catastrophically
     * backtracking pattern runs {@link Matcher#find()} to whatever conclusion it reaches, however
     * long that takes, pinning the calling thread the entire time. The regex engine calls {@link
     * #charAt} on every character position it visits, including every backtracking step, so that
     * is the only interruption point available without patching the JDK. Checking the deadline on
     * every single call would itself add measurable overhead on a multi-hundred-KB decompiled
     * class source; sampling every 4096 calls keeps the check cheap while still bounding wall-clock
     * to within a few ms of the deadline for any reasonably-sized backtracking step.
     */
    private static final class DeadlineCharSequence implements CharSequence {
        private static final RegexDeadlineExceededException DEADLINE_EXCEEDED = new RegexDeadlineExceededException();
        private static final int CHECK_INTERVAL_MASK = 0xFFF; // every 4096 calls

        private final CharSequence delegate;
        private final long deadlineNanos;
        private long charAtCalls;

        /** Starts a fresh budget of {@code timeoutMillis} from now. */
        static DeadlineCharSequence withTimeout(CharSequence delegate, long timeoutMillis) {
            return new DeadlineCharSequence(delegate, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
        }

        private DeadlineCharSequence(CharSequence delegate, long deadlineNanos) {
            this.delegate = delegate;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if ((++charAtCalls & CHECK_INTERVAL_MASK) == 0 && System.nanoTime() >= deadlineNanos) {
                throw DEADLINE_EXCEEDED;
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(delegate.subSequence(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
