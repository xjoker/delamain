package com.zin.delamain.server.routes;

import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.shard.ContentShardBuilder;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.index.shard.ShardCatalog;
import com.zin.delamain.index.shard.TermLookupResult;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity / soundness gate for the Wave B additive shard pruning layer wired into
 * {@link SearchRoutes#isDefinitivelyAbsent}. The rule under test:
 *
 * <ul>
 *   <li>a class the shard authoritatively covers but does NOT flag as a candidate is the ONLY
 *       case that may be pruned as a definitive negative;</li>
 *   <li>a shard-covered class that DOES contain the term (a candidate) is never pruned;</li>
 *   <li>a class the shard does not cover falls back to the residual {@link CodeContentIndex}
 *       layer with byte-for-byte the legacy behavior;</li>
 *   <li>no class that actually contains the term is ever pruned (zero false negatives).</li>
 * </ul>
 *
 * <p>Uses real {@link JavaClass} instances (the class is {@code final} with package-private
 * constructors, so it cannot be constructed directly) decompiled from a small test APK, mirroring
 * {@code CodeContentIndexTest}. The synthetic code fed to the shard builder does not need to match
 * the class's real decompiled source — only the id→class mapping (via {@code preAssignIds}) and the
 * id→trigram mapping (via the builder) must agree on the id, which they do because
 * {@code preAssignIds} assigns id {@code i} to {@code sorted.get(i)} and the builder is fed those
 * same ids.</p>
 */
class SearchRoutesShardPruneTest {

    private static final String HASH = "0011223344556677";

    private static JadxDecompiler jadx;
    private static List<JavaClass> classes;

    @BeforeAll
    static void setUpClasses() {
        JadxArgs args = new JadxArgs();
        args.setInputFile(new File("test-harness/real/UnCrackable-Level1.apk"));
        args.setSkipResources(true);
        jadx = new JadxDecompiler(args);
        jadx.load();
        classes = jadx.getClasses();
        assertTrue(classes.size() >= 4, "test APK must yield at least 4 classes for these tests");
    }

    @AfterAll
    static void tearDownClasses() {
        if (jadx != null) {
            jadx.close();
        }
    }

    @BeforeEach
    void reset() {
        CodeContentIndex.clear();
        ContentShardIndex.clear();
    }

    @AfterEach
    void tearDown() {
        ContentShardIndex.clear();
        CodeContentIndex.clear();
    }

    private static List<JavaClass> sortedClasses() {
        List<JavaClass> sorted = new ArrayList<>(classes);
        sorted.sort(Comparator.comparing(JavaClass::getRawName));
        return sorted;
    }

    /** Builds a single shard over {@code corpus} (id→lower-cased code) and loads it. */
    private static void buildAndLoadShard(Path dir, Map<Integer, String> corpus) throws IOException {
        ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30);
        for (Map.Entry<Integer, String> e : corpus.entrySet()) {
            b.addClass(e.getKey(), e.getValue());
        }
        b.close(); // flushes the trailing window
        ShardCatalog.write(dir, HASH, new ArrayList<>(b.writtenShards()));
        ContentShardIndex.loadCatalog(dir, HASH);
        assertTrue(ContentShardIndex.isBuilt(), "shard must be built for the test");
    }

    @Test
    void shardCoveredClassPrunedOnlyWhenDefinitivelyAbsent(@TempDir Path dir) throws IOException {
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted); // idOf(sorted.get(i)) == i

        JavaClass a = sorted.get(0); // shard-covered, CONTAINS "render"
        JavaClass b = sorted.get(1); // shard-covered, does NOT contain "render"
        JavaClass c = sorted.get(2); // shard-uncovered, flagged by residual layer
        JavaClass d = sorted.get(3); // shard-uncovered, unindexed

        // Shard covers only ids 0 and 1. Ids must be strictly increasing for the builder.
        Map<Integer, String> corpus = new LinkedHashMap<>();
        corpus.put(0, "class aaa { void renderwidget() {} }"); // contains "render"
        corpus.put(1, "class bbb { void updatestate() {} }");  // lacks "render"
        buildAndLoadShard(dir, corpus);

        TermLookupResult shard = ContentShardIndex.candidatesForTerm("render");
        // Residual layer flags c as a possible match (so it must NOT be pruned).
        Set<JavaClass> trigramCandidates = new HashSet<>();
        trigramCandidates.add(c);
        AtomicInteger pruned = new AtomicInteger(0);

        assertFalse(
                SearchRoutes.isDefinitivelyAbsent(a, shard, trigramCandidates, false, pruned),
                "shard-covered class that CONTAINS the term must never be pruned (false negative)");
        assertTrue(
                SearchRoutes.isDefinitivelyAbsent(b, shard, trigramCandidates, false, pruned),
                "shard-covered class lacking the term is the definitive negative and must be pruned");
        assertFalse(
                SearchRoutes.isDefinitivelyAbsent(c, shard, trigramCandidates, false, pruned),
                "shard-uncovered class flagged by the residual layer must be scanned, not pruned");
        assertFalse(
                SearchRoutes.isDefinitivelyAbsent(d, shard, trigramCandidates, false, pruned),
                "shard-uncovered unindexed class carries no claim and must be scanned");

        assertEquals(1, pruned.get(),
                "exactly one class (the shard-covered definitive negative) increments the counter");
    }

    @Test
    void shardCoveredCandidateWithFalsePositiveIsScannedNotPruned(@TempDir Path dir) throws IOException {
        // Trigram over-approximation: a class whose trigrams all appear but not contiguously is a
        // shard candidate yet does not truly contain the term. It must be SCANNED (returns false),
        // never pruned — the real substring check downstream rejects it.
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted);

        JavaClass a = sorted.get(0);
        Map<Integer, String> corpus = new LinkedHashMap<>();
        // Contains ALL trigrams of "render" (ren, end, nde from "xrendex"; der from "border")
        // but never the contiguous substring "render" — the classic trigram false positive.
        corpus.put(0, "class x { xrendex; border(); }");
        buildAndLoadShard(dir, corpus);

        TermLookupResult shard = ContentShardIndex.candidatesForTerm("render");
        AtomicInteger pruned = new AtomicInteger(0);
        assertTrue(shard.covered.contains(0), "class must be shard-covered");
        assertTrue(shard.candidates.contains(0), "trigram over-approximation makes it a candidate");
        assertFalse(SearchRoutes.isDefinitivelyAbsent(a, shard, null, false, pruned),
                "a shard candidate must be scanned, never pruned");
        assertEquals(0, pruned.get(), "a scanned candidate must not increment the prune counter");
    }

    @Test
    void fallsBackToResidualLayerWhenShardNotConsulted() {
        // With shardResult == null the guard must reduce EXACTLY to the legacy residual-layer
        // behavior: only an indexed class absent from trigramCandidates (or with trigram
        // definitively-empty) is pruned; unindexed classes are always scanned.
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted);

        JavaClass indexed = sorted.get(3);
        JavaClass unindexed = sorted.get(2);
        // "ddd" = one trigram, well under the pinned MAX_TRIGRAMS cap, so it fully indexes.
        CodeContentIndex.index(indexed, "ddd");
        assertTrue(CodeContentIndex.isIndexed(indexed));
        assertFalse(CodeContentIndex.isIndexed(unindexed));

        AtomicInteger pruned = new AtomicInteger(0);

        // indexed + not a trigram candidate -> pruned by the residual layer.
        assertTrue(SearchRoutes.isDefinitivelyAbsent(indexed, null, new HashSet<>(), false, pruned));
        // indexed + IS a trigram candidate -> scanned.
        Set<JavaClass> withIndexed = new HashSet<>();
        withIndexed.add(indexed);
        assertFalse(SearchRoutes.isDefinitivelyAbsent(indexed, null, withIndexed, false, pruned));
        // trigramDefinitivelyEmpty prunes indexed classes...
        assertTrue(SearchRoutes.isDefinitivelyAbsent(indexed, null, null, true, pruned));
        // ...but never an unindexed class (must be scanned / self-healed).
        assertFalse(SearchRoutes.isDefinitivelyAbsent(unindexed, null, null, true, pruned));

        assertEquals(0, pruned.get(),
                "residual-layer prunes must not touch the shard-covered counter");
    }

    @Test
    void excludedEmptyInnerClassIsPrunedWithoutLosingTermViaTopLevelClass(@TempDir Path dir) throws IOException {
        // W14 soundness pair: an empty inner class's logical source is inlined into its top-level
        // class. Shard-excluding the (empty) inner id must never cause the term to go missing —
        // it must still surface via the top-level class, which remains a normal scan candidate.
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted);

        JavaClass topLevel = sorted.get(0); // shard-covered, its (synthetic) source contains the term
        JavaClass emptyInner = sorted.get(1); // shard-excluded (stands in for an empty inner class)

        // Ids must be strictly increasing for the builder: id 0 = addClass, id 1 = markExcluded.
        ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30);
        b.addClass(0, "class outer { void renderwidget() { /* inner logic inlined here */ } }");
        b.markExcluded(1); // the empty inner class: no content of its own
        b.close();
        ShardCatalog.write(dir, HASH, new ArrayList<>(b.writtenShards()));
        ContentShardIndex.loadCatalog(dir, HASH);
        assertTrue(ContentShardIndex.isBuilt());

        TermLookupResult shard = ContentShardIndex.candidatesForTerm("render");
        AtomicInteger pruned = new AtomicInteger(0);

        assertTrue(shard.covered.contains(0), "top-level class must be shard-covered");
        assertTrue(ContentShardIndex.isExcluded(1), "empty inner id must be recorded as shard-excluded");
        assertFalse(shard.covered.contains(1), "excluded id must never also be covered");

        // (a) The top-level class carries the term as a candidate and MUST be scanned (not pruned) —
        //     this is where the real match is found.
        assertFalse(
                SearchRoutes.isDefinitivelyAbsent(topLevel, shard, null, false, pruned),
                "shard-covered top-level class containing the term must never be pruned");

        // (c) The excluded empty inner class is pruned unconditionally — it has no content, so
        //     pruning it cannot cause a false negative: the term is only ever found via topLevel.
        assertTrue(
                SearchRoutes.isDefinitivelyAbsent(emptyInner, shard, null, false, pruned),
                "shard-excluded empty inner class must be pruned without being scanned");

        assertEquals(1, pruned.get(), "only the excluded inner class increments the prune counter");
    }

    @Test
    void idOfReturnsAssignedIdAndMinusOneWhenUnassigned() {
        List<JavaClass> sorted = sortedClasses();
        assertEquals(-1, CodeContentIndex.idOf(sorted.get(0)),
                "unassigned class must return -1");
        assertEquals(-1, CodeContentIndex.idOf(null), "null must return -1");

        CodeContentIndex.preAssignIds(sorted);
        for (int i = 0; i < sorted.size(); i++) {
            assertEquals(i, CodeContentIndex.idOf(sorted.get(i)),
                    "idOf must return the id preAssignIds handed to sorted.get(i)");
        }
    }
}
