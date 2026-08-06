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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Id-assignment contract for {@link ClassIdRegistry}: the shard layer's coverage judgment in
 * {@code SearchRoutes.isDefinitivelyAbsent} maps a live {@link JavaClass} to the shard's id space
 * through {@link ClassIdRegistry#idOf} / {@link ClassIdRegistry#resolveClass}. If this ever
 * degraded, shard pruning would resolve candidates to the wrong class (false positives) or lose
 * the id&lt;-&gt;class mapping entirely.
 *
 * <p>Uses real {@link JavaClass} instances (the class is {@code final} with package-private
 * constructors, so it cannot be mocked/constructed directly) — a small real APK from
 * test-harness/real is decompiled once in {@link #setUpClasses()}.</p>
 */
class ClassIdRegistryTest {

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
    void resetRegistry() {
        ClassIdRegistry.clear();
    }

    @AfterEach
    void tearDownRegistry() {
        ClassIdRegistry.clear();
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

        ClassIdRegistry.preAssignIds(sorted);

        for (int i = 0; i < sorted.size(); i++) {
            assertEquals(i, ClassIdRegistry.idOf(sorted.get(i)),
                    "idOf(sorted.get(i)) must equal i — the shard coverage judgment depends on it");
            assertSame(sorted.get(i), ClassIdRegistry.resolveClass(i),
                    "resolveClass(i) must return sorted.get(i) — the shard build uses loop index i "
                            + "as the class id and relies on this to resolve candidates back to classes");
        }
    }
}
