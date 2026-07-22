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
 * A1 soundness gate: when the heap trigram index is off, search must stay sound — pruning is done
 * by the shard layer ALONE, byte-for-byte identical to when the heap trigram is on, and NO class
 * that could contain the term is ever pruned.
 *
 * <p>The load-bearing decision point is {@link SearchRoutes#isDefinitivelyAbsent}: a class is
 * skipped from the content scan only when that method returns true. When {@code TRIGRAM_HEAP} is
 * off, {@link CodeContentIndex#candidatesForTerm} returns {@code null}
 * (proven by {@code CodeContentIndexIdWithoutTrigramTest}), so {@code executeSearch} feeds this
 * method exactly {@code trigramCandidates == null} and {@code trigramDefinitivelyEmpty == false}.
 * These are the inputs under test here — the "heap-off" contract at the decision point.</p>
 *
 * <p>Asserted:</p>
 * <ul>
 *   <li>a shard-covered non-candidate is pruned; a shard-covered candidate is scanned — and the
 *       verdict is <b>invariant</b> to whatever the residual (trigram) inputs are, proving the
 *       shard layer's judgment does not depend on the heap trigram at all;</li>
 *   <li>with the heap-off inputs, the residual layer is fully inert — an unindexed class is never
 *       pruned by it;</li>
 *   <li>a shard-uncovered class (where a real term match could live) is NEVER pruned under the
 *       heap-off inputs — it must fall through to a real scan (anti-false-negative).</li>
 * </ul>
 *
 * <p>Mirrors {@code SearchRoutesShardPruneTest}: real {@link JavaClass} instances from a small APK,
 * synthetic shard code (only the id→class and id→trigram mappings must agree on the id, which they
 * do because {@code preAssignIds} assigns id {@code i} to {@code sorted.get(i)} and the builder is
 * fed those same ids).</p>
 */
class SearchRoutesShardOnlySoundnessTest {

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

    private static void buildAndLoadShard(Path dir, Map<Integer, String> corpus) throws IOException {
        ContentShardBuilder b = new ContentShardBuilder(dir, HASH, 1L << 30);
        for (Map.Entry<Integer, String> e : corpus.entrySet()) {
            b.addClass(e.getKey(), e.getValue());
        }
        b.close();
        ShardCatalog.write(dir, HASH, new ArrayList<>(b.writtenShards()));
        ContentShardIndex.loadCatalog(dir, HASH);
        assertTrue(ContentShardIndex.isBuilt(), "shard must be built for the test");
    }

    @Test
    void shardVerdictInvariantToResidualInputs(@TempDir Path dir) throws IOException {
        // The shard layer resolves ids via CodeContentIndex.idOf (the ID machine, always on) and
        // consults only ContentShardIndex — never the heap trigram. So its prune/scan verdict for a
        // shard-covered class must be identical no matter what residual (trigram) inputs are passed:
        // heap-off inputs (null, false) and any heap-on inputs must agree. That invariance is what
        // makes "search goes through the shard when the heap trigram is off" sound.
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted); // idOf(sorted.get(i)) == i

        JavaClass covContains = sorted.get(0); // shard-covered, CONTAINS "render"
        JavaClass covLacks = sorted.get(1);    // shard-covered, does NOT contain "render"

        Map<Integer, String> corpus = new LinkedHashMap<>();
        corpus.put(0, "class aaa { void renderwidget() {} }"); // contains "render"
        corpus.put(1, "class bbb { void updatestate() {} }");  // lacks "render"
        buildAndLoadShard(dir, corpus);

        TermLookupResult shard = ContentShardIndex.candidatesForTerm("render");

        // Residual-input variants: [0] = heap-off contract (null, false); [1] and [2] = arbitrary
        // heap-on shapes. The shard-covered verdict must be identical across all of them.
        Set<JavaClass> everything = new HashSet<>(sorted);
        Object[][] residualVariants = {
                {null, false},          // heap OFF: candidatesForTerm returned null
                {everything, false},    // heap ON: both classes flagged as trigram candidates
                {null, true},           // heap ON: trigram definitively empty
        };

        for (Object[] v : residualVariants) {
            @SuppressWarnings("unchecked")
            Set<JavaClass> trigramCandidates = (Set<JavaClass>) v[0];
            boolean trigramDefinitivelyEmpty = (Boolean) v[1];
            AtomicInteger pruned = new AtomicInteger(0);

            assertFalse(
                    SearchRoutes.isDefinitivelyAbsent(covContains, shard, trigramCandidates,
                            trigramDefinitivelyEmpty, pruned),
                    "shard-covered class containing the term must never be pruned, regardless of "
                            + "residual inputs");
            assertTrue(
                    SearchRoutes.isDefinitivelyAbsent(covLacks, shard, trigramCandidates,
                            trigramDefinitivelyEmpty, pruned),
                    "shard-covered class lacking the term is pruned by the shard layer, regardless "
                            + "of residual inputs");
            assertEquals(1, pruned.get(),
                    "exactly the shard-covered definitive negative is pruned for every residual variant");
        }
    }

    @Test
    void heapOffInputsLeaveResidualInertAndNeverPruneUncoveredMatch(@TempDir Path dir) throws IOException {
        // Heap-off contract: executeSearch passes trigramCandidates=null, trigramDefinitivelyEmpty=false
        // (because candidatesForTerm returns null when TRIGRAM_HEAP is off). Under these inputs the
        // residual layer must be inert and a shard-uncovered class must never be pruned.
        List<JavaClass> sorted = sortedClasses();
        CodeContentIndex.preAssignIds(sorted);

        JavaClass covered = sorted.get(0);   // shard-covered, lacks the term -> definitive negative
        JavaClass uncovered = sorted.get(2); // shard-uncovered: a real match could live here

        // Even if a stale index somehow marked the uncovered class as trigram-built, the heap-off
        // inputs (null candidates) must prevent the residual layer from pruning it.
        CodeContentIndex.index(uncovered, "ddd");

        Map<Integer, String> corpus = new LinkedHashMap<>();
        corpus.put(0, "class aaa { void updatestate() {} }"); // covered, lacks "render"
        buildAndLoadShard(dir, corpus);

        TermLookupResult shard = ContentShardIndex.candidatesForTerm("render");
        AtomicInteger pruned = new AtomicInteger(0);

        // Covered-but-absent: shard prunes it (sound — the shard authoritatively saw its content).
        assertTrue(
                SearchRoutes.isDefinitivelyAbsent(covered, shard, null, false, pruned),
                "shard-covered class lacking the term is pruned by the shard layer");

        // Uncovered: shard makes no claim; residual inert under heap-off inputs -> MUST be scanned.
        assertFalse(
                SearchRoutes.isDefinitivelyAbsent(uncovered, shard, null, false, pruned),
                "a shard-uncovered class must never be pruned under heap-off inputs — it must be "
                        + "scanned so a real match is not silently dropped (anti-false-negative)");

        assertEquals(1, pruned.get(),
                "only the shard-covered definitive negative is pruned; the uncovered class is scanned");
    }
}
