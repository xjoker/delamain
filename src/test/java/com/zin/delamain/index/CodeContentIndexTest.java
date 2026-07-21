package com.zin.delamain.index;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for two silent false-negative bugs in {@link CodeContentIndex}:
 *
 * <p>Bug#1 — a class truncated mid-build by the MAX_TRIGRAMS cap was still marked
 * as {@code trigramBuilt} (isIndexed == true) before it had a complete posting set,
 * so SearchRoutes' H6 guard would wrongly exclude it from content-scan fallback.</p>
 *
 * <p>Bug#2 — {@code runEvictionSweep} removed trigram postings but never invalidated
 * {@code trigramBuilt}, so classes whose postings were evicted stayed "indexed"
 * (isIndexed == true) with incomplete data, again defeating the H6 fallback.</p>
 *
 * <p>Requires real {@link JavaClass} instances (the class is {@code final} with
 * package-private constructors, so it cannot be mocked/constructed directly) — a
 * small real APK from test-harness/real is decompiled once in {@link #setUpClasses()}.
 * The {@code DELAMAIN_CODE_INDEX_MAX_TRIGRAMS} env var is pinned to a small value via
 * the surefire plugin config in pom.xml so the cap can be tripped deterministically
 * with short synthetic code strings (the code text fed to {@code index()} does not
 * need to match the class's real decompiled source).</p>
 */
class CodeContentIndexTest {

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
    void resetIndex() {
        CodeContentIndex.clear();
    }

    @Test
    void capTruncatedClassIsNotMarkedIndexed() {
        // MAX_TRIGRAMS is pinned small (see pom.xml surefire env) so this 10-char
        // synthetic string (8 distinct trigrams) alone exceeds it mid-build.
        JavaClass cls = classes.get(0);
        String code = "ABCDEFGHIJ";
        assertTrue(CodeContentIndex.MAX_TRIGRAMS < 8,
                "test assumes MAX_TRIGRAMS is pinned below the trigram count of the sample code");

        CodeContentIndex.index(cls, code);

        assertFalse(CodeContentIndex.isIndexed(cls),
                "a class truncated by the trigram cap must NOT be marked as indexed "
                        + "(H6 guard would otherwise wrongly exclude it from content-scan fallback)");
    }

    @Test
    void preAssignIdsMakesResolveClassReturnSortedElementAtIndex() {
        // Soundness contract that WarmupManager.startBackgroundShardBuild depends on: preAssignIds
        // assigns class id i to sorted.get(i), so the shard build can use the loop index i directly
        // as the class id and a shard candidate id i still resolves back to the correct class via
        // resolveClass(i). If this ever regresses, Wave B queries would resolve candidates to the
        // wrong class (false positives / assertion failures) — hence pinned as an explicit test.
        List<JavaClass> sorted = new ArrayList<>(classes);
        sorted.sort(Comparator.comparing(JavaClass::getRawName));

        CodeContentIndex.preAssignIds(sorted);

        for (int i = 0; i < sorted.size(); i++) {
            assertSame(sorted.get(i), CodeContentIndex.resolveClass(i),
                    "resolveClass(i) must return sorted.get(i) — the shard build uses loop index i "
                            + "as the class id and relies on this to resolve candidates back to classes");
        }
    }

    @Test
    void evictedClassesAreNoLongerMarkedIndexed() {
        // Three classes, each with a single distinct trigram, so total trigram count
        // stays well under the pinned MAX_TRIGRAMS cap and every class fully indexes.
        JavaClass a = classes.get(0);
        JavaClass b = classes.get(1);
        JavaClass c = classes.get(2);
        CodeContentIndex.index(a, "aaa");
        CodeContentIndex.index(b, "bbb");
        CodeContentIndex.index(c, "ccc");

        assertTrue(CodeContentIndex.isIndexed(a));
        assertTrue(CodeContentIndex.isIndexed(b));
        assertTrue(CodeContentIndex.isIndexed(c));

        CodeContentIndex.runEvictionSweep(1.0);

        assertFalse(CodeContentIndex.isIndexed(a),
                "eviction must invalidate trigramBuilt for classes whose postings were evicted");
        assertFalse(CodeContentIndex.isIndexed(b),
                "eviction must invalidate trigramBuilt for classes whose postings were evicted");
        assertFalse(CodeContentIndex.isIndexed(c),
                "eviction must invalidate trigramBuilt for classes whose postings were evicted");
    }
}
