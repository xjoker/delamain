package com.zin.delamain.server.routes;

import com.zin.delamain.index.ClassIdRegistry;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Soundness gate for the shard-only pruning layer: {@link SearchRoutes#isDefinitivelyAbsent} may
 * prune a class only when the shard index authoritatively covers it and is certain the term is
 * absent. A shard-covered class that contains the term must never be pruned, and a shard-uncovered
 * class (where a real match could live) must never be pruned either — it must fall through to a
 * real scan (anti-false-negative).
 *
 * <p>Real {@link JavaClass} instances from a small APK, synthetic shard code (only the id→class and
 * id→content mappings must agree on the id, which they do because {@code preAssignIds} assigns id
 * {@code i} to {@code sorted.get(i)} and the builder is fed those same ids).</p>
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
        ClassIdRegistry.clear();
        ContentShardIndex.clear();
    }

    @AfterEach
    void tearDown() {
        ContentShardIndex.clear();
        ClassIdRegistry.clear();
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
    void shardCoveredVerdictIsSound(@TempDir Path dir) throws IOException {
        List<JavaClass> sorted = sortedClasses();
        ClassIdRegistry.preAssignIds(sorted); // idOf(sorted.get(i)) == i

        JavaClass covContains = sorted.get(0); // shard-covered, CONTAINS "render"
        JavaClass covLacks = sorted.get(1);    // shard-covered, does NOT contain "render"

        Map<Integer, String> corpus = new LinkedHashMap<>();
        corpus.put(0, "class aaa { void renderwidget() {} }"); // contains "render"
        corpus.put(1, "class bbb { void updatestate() {} }");  // lacks "render"
        buildAndLoadShard(dir, corpus);

        TermLookupResult shard = ContentShardIndex.candidatesForTerm("render");
        AtomicInteger pruned = new AtomicInteger(0);

        assertFalse(
                SearchRoutes.isDefinitivelyAbsent(covContains, shard, pruned),
                "shard-covered class containing the term must never be pruned");
        assertTrue(
                SearchRoutes.isDefinitivelyAbsent(covLacks, shard, pruned),
                "shard-covered class lacking the term is pruned by the shard layer");
        assertEquals(1, pruned.get(),
                "exactly the shard-covered definitive negative is pruned");
    }

    @Test
    void shardUncoveredClassIsNeverPruned(@TempDir Path dir) throws IOException {
        List<JavaClass> sorted = sortedClasses();
        ClassIdRegistry.preAssignIds(sorted);

        JavaClass covered = sorted.get(0);   // shard-covered, lacks the term -> definitive negative
        JavaClass uncovered = sorted.get(2); // shard-uncovered: a real match could live here

        Map<Integer, String> corpus = new LinkedHashMap<>();
        corpus.put(0, "class aaa { void updatestate() {} }"); // covered, lacks "render"
        buildAndLoadShard(dir, corpus);

        TermLookupResult shard = ContentShardIndex.candidatesForTerm("render");
        AtomicInteger pruned = new AtomicInteger(0);

        // Covered-but-absent: shard prunes it (sound — the shard authoritatively saw its content).
        assertTrue(
                SearchRoutes.isDefinitivelyAbsent(covered, shard, pruned),
                "shard-covered class lacking the term is pruned by the shard layer");

        // Uncovered: shard makes no claim -> MUST be scanned.
        assertFalse(
                SearchRoutes.isDefinitivelyAbsent(uncovered, shard, pruned),
                "a shard-uncovered class must never be pruned — it must be scanned so a real match "
                        + "is not silently dropped (anti-false-negative)");

        assertEquals(1, pruned.get(),
                "only the shard-covered definitive negative is pruned; the uncovered class is scanned");
    }
}
