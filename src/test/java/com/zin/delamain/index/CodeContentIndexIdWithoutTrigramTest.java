package com.zin.delamain.index;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 memory-reduction contract: with the heap trigram index disabled
 * ({@code TRIGRAM_HEAP == false}) the id-assignment machinery must stay fully functional while
 * NOT a single trigram BitSet is built.
 *
 * <p>Why this matters for soundness: the shard layer's coverage judgment in
 * {@code SearchRoutes.isDefinitivelyAbsent} maps a live {@link JavaClass} to the shard's id space
 * through {@link CodeContentIndex#idOf} / {@link CodeContentIndex#resolveClass}. If those degraded
 * when the heap trigram was turned off, shard pruning would resolve candidates to the wrong class
 * (false positives) or lose the id→class mapping entirely. This test pins the split: id machine =
 * always on, trigram heap = off (no BitSets, no candidate narrowing).</p>
 *
 * <p>Uses real {@link JavaClass} instances (the class is {@code final} with package-private
 * constructors) decompiled from a small test APK, mirroring {@link CodeContentIndexTest}.</p>
 */
class CodeContentIndexIdWithoutTrigramTest {

    private static JadxDecompiler jadx;
    private static List<JavaClass> classes;

    private boolean originalTrigramHeap;

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
    void disableTrigramHeap() {
        originalTrigramHeap = CodeContentIndex.TRIGRAM_HEAP;
        CodeContentIndex.TRIGRAM_HEAP = false;
        CodeContentIndex.clear();
    }

    @AfterEach
    void restoreTrigramHeap() {
        CodeContentIndex.clear();
        CodeContentIndex.TRIGRAM_HEAP = originalTrigramHeap;
    }

    @Test
    void idMappingIsBidirectionallyExactWithoutTrigramHeap() {
        List<JavaClass> sorted = new ArrayList<>(classes);
        sorted.sort(Comparator.comparing(JavaClass::getRawName));

        CodeContentIndex.preAssignIds(sorted);

        for (int i = 0; i < sorted.size(); i++) {
            assertEquals(i, CodeContentIndex.idOf(sorted.get(i)),
                    "idOf(sorted.get(i)) must equal i even with the heap trigram index off — "
                            + "the shard coverage judgment depends on it");
            assertSame(sorted.get(i), CodeContentIndex.resolveClass(i),
                    "resolveClass(i) must return sorted.get(i) even with the heap trigram index off");
        }
    }

    @Test
    void candidatesForTermAlwaysNullWhenTrigramHeapOff() {
        List<JavaClass> sorted = new ArrayList<>(classes);
        sorted.sort(Comparator.comparing(JavaClass::getRawName));
        CodeContentIndex.preAssignIds(sorted);

        // Even after feeding source through index(), no candidate set can be produced: the residual
        // trigram prefilter is off, so candidatesForTerm must return null (caller falls back to the
        // shard layer + full scan), never a BitSet.
        CodeContentIndex.index(sorted.get(0), "class renderwidget { void render() {} }");

        assertNull(CodeContentIndex.candidatesForTerm("render"),
                "candidatesForTerm must return null (not an empty/non-empty BitSet) when TRIGRAM_HEAP is off");
        assertNull(CodeContentIndex.candidatesForTerm("void"),
                "candidatesForTerm must return null for any term when TRIGRAM_HEAP is off");
    }

    @Test
    void indexBuildsNoBitSetsAndReportsNotIndexedWhenTrigramHeapOff() {
        List<JavaClass> sorted = new ArrayList<>(classes);
        sorted.sort(Comparator.comparing(JavaClass::getRawName));
        CodeContentIndex.preAssignIds(sorted);

        // index() must be a no-op for trigram building: no BitSet allocated, class not marked indexed.
        for (int i = 0; i < Math.min(4, sorted.size()); i++) {
            CodeContentIndex.index(sorted.get(i), "class aaa { void bbbccc() { ddd(); } }");
        }

        assertEquals(0, CodeContentIndex.trigramCount(),
                "no trigram BitSet may be allocated on the heap when TRIGRAM_HEAP is off");
        assertEquals(0, CodeContentIndex.indexedClassCount(),
                "no class may be marked trigram-built when TRIGRAM_HEAP is off");
        for (int i = 0; i < Math.min(4, sorted.size()); i++) {
            assertFalse(CodeContentIndex.isIndexed(sorted.get(i)),
                    "isIndexed must be false when TRIGRAM_HEAP is off, so SearchRoutes' residual "
                            + "layer never prunes (only the shard layer prunes) — soundness invariant");
        }
    }
}
