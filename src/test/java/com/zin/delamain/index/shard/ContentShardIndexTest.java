package com.zin.delamain.index.shard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 1 — multi-shard query semantics and the soundness contract of
 * {@link ContentShardIndex#candidatesForTerm}. The central guarantee under test:
 * {@code candidates ⊆ covered}, and NO covered class that actually contains the term is
 * ever missing from {@code candidates} (no false negatives). Trigram over-approximation
 * (false positives) is permitted — those are verified by the real scan downstream.
 */
class ContentShardIndexTest {

    private static final String HASH = "0011223344556677";

    /** id -> lower-cased source, split into two shards by a flush at the boundary. */
    private final Map<Integer, String> corpus = new LinkedHashMap<>();

    @BeforeEach
    void reset() {
        ContentShardIndex.clear();
        corpus.clear();
    }

    @AfterEach
    void tearDown() {
        ContentShardIndex.clear();
    }

    /** Builds two shards over {@code corpus}, with {@code excluded} ids skipped, then loads. */
    private void buildAndLoad(Path dir, int splitAfterId, List<Integer> excluded) throws IOException {
        List<ShardCatalog.ShardEntry> entries = new ArrayList<>();
        try (ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30)) {
            boolean flushedAtSplit = false;
            for (Map.Entry<Integer, String> e : corpus.entrySet()) {
                int id = e.getKey();
                if (excluded.contains(id)) {
                    b.markExcluded(id);
                } else {
                    b.addClass(id, e.getValue());
                }
                if (id == splitAfterId) {
                    ShardCatalog.ShardEntry m = b.flush();
                    if (m != null) entries.add(m);
                    flushedAtSplit = true;
                }
            }
            ShardCatalog.ShardEntry tail = b.flush();
            if (tail != null) entries.add(tail);
            assertTrue(flushedAtSplit);
        }
        ShardCatalog.write(dir, HASH, entries);
        ContentShardIndex.loadCatalog(dir, HASH);
        assertTrue(ContentShardIndex.isBuilt());
    }

    private void put(int id, String code) {
        corpus.put(id, code);
    }

    @Test
    void candidatesAreAlwaysASubsetOfCovered(@TempDir Path dir) throws IOException {
        put(0, "class alpha { void render() {} }");
        put(1, "class beta { void render() {} }");
        put(2, "class gamma { void update() {} }");
        put(3, "class delta { void render() {} }");
        buildAndLoad(dir, 1, List.of());

        TermLookupResult r = ContentShardIndex.candidatesForTerm("render");
        for (int id = 0; id <= 3; id++) {
            if (r.candidates.contains(id)) {
                assertTrue(r.covered.contains(id), "every candidate must be covered");
            }
        }
        assertTrue(RoaringBitmap.andNot(r.candidates, r.covered).isEmpty(),
                "candidates must be a subset of covered");
    }

    @Test
    void termPresentAcrossBothShardsUnionsCorrectly(@TempDir Path dir) throws IOException {
        put(0, "class a { void render() {} }");   // shard 1
        put(1, "class b { void update() {} }");   // shard 1
        put(2, "class c { void render() {} }");   // shard 2
        put(3, "class d { void update() {} }");   // shard 2
        buildAndLoad(dir, 1, List.of());

        TermLookupResult r = ContentShardIndex.candidatesForTerm("render");
        assertTrue(r.candidates.contains(0), "match in shard 1 must be found");
        assertTrue(r.candidates.contains(2), "match in shard 2 must be found");
        assertFalse(r.candidates.contains(1));
        assertFalse(r.candidates.contains(3));
        // covered spans all indexed ids across both shards.
        for (int id = 0; id <= 3; id++) assertTrue(r.covered.contains(id));
    }

    @Test
    void termShorterThanTrigramHasEmptyCovered(@TempDir Path dir) throws IOException {
        put(0, "class a { void render() {} }");
        put(1, "class b { void render() {} }");
        buildAndLoad(dir, 0, List.of());

        TermLookupResult r = ContentShardIndex.candidatesForTerm("ab");
        assertTrue(r.covered.isEmpty(), "term < 3 chars: index has no authority (covered empty)");
        assertTrue(r.candidates.isEmpty());
    }

    @Test
    void trigramInNoDictionaryIsDefinitiveNegativeWithinCovered(@TempDir Path dir) throws IOException {
        put(0, "class a { void render() {} }");
        put(1, "class b { void update() {} }");
        buildAndLoad(dir, 0, List.of());

        // "zzz" appears in no class -> no covered class contains it -> definitive negative.
        TermLookupResult r = ContentShardIndex.candidatesForTerm("zzz");
        assertTrue(r.candidates.isEmpty());
        assertFalse(r.covered.isEmpty(), "covered classes remain authoritative");
        assertTrue(r.definitivelyAbsent(0), "a covered class not containing the term is a definitive negative");
        assertTrue(r.definitivelyAbsent(1));
    }

    @Test
    void excludedIdIsNotCoveredSoNeverPrunedAsNegative(@TempDir Path dir) throws IOException {
        put(0, "class a { void render() {} }");
        put(1, "class oversized { }");             // excluded below
        put(2, "class c { void render() {} }");
        buildAndLoad(dir, 1, List.of(1));

        TermLookupResult r = ContentShardIndex.candidatesForTerm("render");
        assertFalse(r.covered.contains(1), "excluded id must not be covered");
        assertFalse(r.definitivelyAbsent(1),
                "an excluded id must never be treated as a definitive negative (must be scanned)");
        assertFalse(ContentShardIndex.isCovered(1));
        assertTrue(ContentShardIndex.isCovered(0));
        assertTrue(ContentShardIndex.isCovered(2));
    }

    @Test
    void idOutsideAnyShardRangeIsNotCovered(@TempDir Path dir) throws IOException {
        put(0, "class a { void render() {} }");
        put(1, "class b { void render() {} }");
        buildAndLoad(dir, 0, List.of());

        // id 999 was never handed to the builder — no shard has authority over it.
        assertFalse(ContentShardIndex.isCovered(999));
        TermLookupResult r = ContentShardIndex.candidatesForTerm("render");
        assertFalse(r.covered.contains(999));
        assertFalse(r.definitivelyAbsent(999));
    }

    @Test
    void tombstoneRemovesIdFromCoverageAndCandidates(@TempDir Path dir) throws IOException {
        put(0, "class a { void render() {} }");
        put(1, "class b { void render() {} }");
        buildAndLoad(dir, 0, List.of());

        assertTrue(ContentShardIndex.isCovered(0));
        TermLookupResult before = ContentShardIndex.candidatesForTerm("render");
        assertTrue(before.candidates.contains(0));

        ContentShardIndex.tombstone(0);

        assertFalse(ContentShardIndex.isCovered(0), "tombstoned id must lose coverage");
        TermLookupResult after = ContentShardIndex.candidatesForTerm("render");
        assertFalse(after.covered.contains(0), "tombstoned id must be absent from covered");
        assertFalse(after.candidates.contains(0), "tombstoned id must never be returned as a candidate");
        assertTrue(after.candidates.contains(1), "untombstoned matches remain");
    }

    @Test
    void soundnessNoFalseNegativesAgainstBruteForce(@TempDir Path dir) throws IOException {
        // A mixed corpus across two shards, with one excluded id.
        put(0, "class loginmanager { string token = getauthtoken(); }");
        put(1, "class networkclient { response fetchdata(request req) {} }");
        put(2, "class cryptohelper { byte[] encryptpayload(byte[] data) {} }");
        put(3, "class oversized_excluded { }");
        put(4, "class usersession { void refreshtoken() { getauthtoken(); } }");
        put(5, "class datastore { void persistdata(response r) {} }");
        buildAndLoad(dir, 2, List.of(3));

        String[] terms = {"token", "fetchdata", "encrypt", "getauthtoken", "response", "data",
                "session", "notpresentanywhere", "ryp", "manager"};

        for (String term : terms) {
            TermLookupResult r = ContentShardIndex.candidatesForTerm(term);
            // candidates ⊆ covered
            assertTrue(RoaringBitmap.andNot(r.candidates, r.covered).isEmpty(),
                    "candidates must be subset of covered for term '" + term + "'");
            for (Map.Entry<Integer, String> e : corpus.entrySet()) {
                int id = e.getKey();
                boolean actuallyContains = e.getValue().contains(term);
                if (r.covered.contains(id)) {
                    if (actuallyContains) {
                        // SOUNDNESS: a covered class that truly contains the term must be a candidate.
                        assertTrue(r.candidates.contains(id),
                                "FALSE NEGATIVE: covered class " + id + " contains '" + term
                                        + "' but was pruned out of candidates");
                    }
                    // definitivelyAbsent must never fire on a class that actually contains the term.
                    if (actuallyContains) {
                        assertFalse(r.definitivelyAbsent(id),
                                "definitivelyAbsent wrongly fired for a real match, term '" + term + "'");
                    }
                } else {
                    // Not covered -> the index makes no claim; must not be a definitive negative.
                    assertFalse(r.definitivelyAbsent(id));
                }
            }
        }
    }

    @Test
    void isExcludedReflectsMarkExcludedIdsOnly(@TempDir Path dir) throws IOException {
        put(0, "class a { void render() {} }"); // covered
        put(1, "class b { }");                   // excluded (e.g. empty-source inner class)
        buildAndLoad(dir, 1, List.of(1));

        assertTrue(ContentShardIndex.isExcluded(1), "explicitly excluded id must report excluded");
        assertFalse(ContentShardIndex.isExcluded(0), "covered id must not report excluded");
        assertFalse(ContentShardIndex.isExcluded(999), "id outside any shard range must not report excluded");
    }

    @Test
    void isExcludedFalseAfterTombstone(@TempDir Path dir) throws IOException {
        put(0, "class a { void render() {} }");
        put(1, "class b { }");
        buildAndLoad(dir, 1, List.of(1));

        assertTrue(ContentShardIndex.isExcluded(1));
        ContentShardIndex.tombstone(1);
        assertFalse(ContentShardIndex.isExcluded(1), "tombstoned excluded id must no longer report excluded");
    }

    @Test
    void statsReportShardAndCoverageCounts(@TempDir Path dir) throws IOException {
        put(0, "class a { void render() {} }");
        put(1, "class b { void render() {} }");
        put(2, "class c { }");
        buildAndLoad(dir, 1, List.of(2));

        Map<String, Object> stats = ContentShardIndex.getStats();
        assertEquals(true, stats.get("built"));
        assertTrue(((Number) stats.get("shard_count")).intValue() >= 2);
        assertEquals(2, ((Number) stats.get("covered_classes")).intValue());
        assertEquals(1, ((Number) stats.get("excluded_classes")).intValue());
    }
}
