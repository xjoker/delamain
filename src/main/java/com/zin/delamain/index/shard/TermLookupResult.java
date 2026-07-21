package com.zin.delamain.index.shard;

import org.roaringbitmap.RoaringBitmap;

/**
 * Result of a term lookup against the shard layer.
 *
 * <p>Generalizes {@code CodeContentIndex.candidatesForTerm}'s null/empty/non-empty three-state
 * contract into an explicit pair of id sets:
 * <ul>
 *   <li>{@link #covered} — the class ids for which this lookup is <em>authoritative</em>: the
 *       shard layer fully indexed them and can give a trustworthy yes/no. Ids outside this set
 *       (excluded classes, never-indexed classes, tombstoned classes) carry no claim and must be
 *       scanned by the caller.</li>
 *   <li>{@link #candidates} — the subset of {@code covered} that (by the trigram filter) may
 *       contain the term. Trigram matching over-approximates, so this can include false positives
 *       that a real content scan will reject; it will <em>never</em> omit a covered class that
 *       actually contains the term (no false negatives).</li>
 * </ul>
 * Invariant: {@code candidates ⊆ covered}.
 *
 * <p>The soundness rule the H6 search guard applies: a class id may be pruned as a definitive
 * negative iff {@code covered.contains(id) && !candidates.contains(id)} — see
 * {@link #definitivelyAbsent(int)}.
 */
public final class TermLookupResult {

    /** Ids known (via the trigram filter) to possibly contain the term. Always ⊆ {@link #covered}. */
    public final RoaringBitmap candidates;

    /** Ids for which this result is authoritative. */
    public final RoaringBitmap covered;

    public TermLookupResult(RoaringBitmap candidates, RoaringBitmap covered) {
        this.candidates = candidates;
        this.covered = covered;
    }

    /** True iff the shard layer has an authoritative judgment for {@code id}. */
    public boolean isCovered(int id) {
        return covered.contains(id);
    }

    /**
     * True iff {@code id} is covered <em>and</em> not a candidate — i.e. the index is certain the
     * class does not contain the term and the caller may safely skip scanning it. Never true for a
     * class outside {@link #covered}.
     */
    public boolean definitivelyAbsent(int id) {
        return covered.contains(id) && !candidates.contains(id);
    }
}
