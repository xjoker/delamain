package com.zin.delamain.server.routes;

import com.zin.delamain.index.UsageGraphIndex;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Item 3 ("高层工具可发现性"): {@code /class-source} must attach {@code referrer_count} — from
 * the precomputed {@link UsageGraphIndex} — once the index is ready, and must never attach it
 * (rather than lying with 0) before the index is ready.
 *
 * <p>Builds a real {@link UsageGraphIndex} from UnCrackable-Level2.apk's actual classes (mirrors
 * {@link DecompileRoutesQualityVerdictTest}'s real-APK pattern) so referrer counts reflect real
 * cross-class references, then calls the package-private {@code attachReferrerCount} helper
 * directly — no HTTP layer involved.</p>
 */
class DecompileRoutesReferrerCountTest {

    private static JadxDecompiler jadx;

    @BeforeAll
    static void setUp() {
        File apk = new File("test-harness/real/UnCrackable-Level2.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        JadxArgs args = new JadxArgs();
        args.setInputFile(apk);
        args.setSkipResources(true);
        jadx = new JadxDecompiler(args);
        jadx.load();
    }

    @AfterAll
    static void tearDownAll() {
        if (jadx != null) jadx.close();
    }

    @AfterEach
    void tearDown() {
        UsageGraphIndex.clear();
    }

    @Test
    void attachesReferrerCountOnceIndexIsReady() throws Exception {
        List<JavaClass> sorted = new ArrayList<>(jadx.getClasses());
        sorted.sort(Comparator.comparing(JavaClass::getFullName));
        UsageGraphIndex.build(sorted);
        assertTrue(UsageGraphIndex.isReady(), "precondition: index must be ready after build()");

        // Find a class with at least one real referrer in this APK's own dependency graph
        // (a support-lib base class referenced by its known subclasses is a stable target).
        JavaClass highFanin = findHighestFaninClass(sorted);
        assertNotNull(highFanin);
        int expected = UsageGraphIndex.referrersOf(highFanin).size();
        assertTrue(expected > 0, "test needs a class with at least one referrer, got 0 for " + highFanin.getFullName());

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        invokeAttachReferrerCount(result, highFanin);

        assertEquals(expected, result.get("referrer_count"),
                "referrer_count must equal UsageGraphIndex.referrersOf(cls).size()");
    }

    @Test
    void omitsReferrerCountBeforeIndexIsReady() throws Exception {
        UsageGraphIndex.clear();
        assertFalse(UsageGraphIndex.isReady(), "precondition: index must not be ready");

        JavaClass any = jadx.getClasses().get(0);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        invokeAttachReferrerCount(result, any);

        assertFalse(result.containsKey("referrer_count"),
                "referrer_count must be omitted (not defaulted to 0) while the index is building: " + result);
    }

    private static JavaClass findHighestFaninClass(List<JavaClass> classes) {
        JavaClass best = null;
        int bestCount = -1;
        for (JavaClass cls : classes) {
            List<JavaClass> referrers = UsageGraphIndex.referrersOf(cls);
            int count = referrers != null ? referrers.size() : 0;
            if (count > bestCount) {
                bestCount = count;
                best = cls;
            }
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    private static void invokeAttachReferrerCount(Map<String, Object> result, JavaClass targetClass) throws Exception {
        Method m = DecompileRoutes.class.getDeclaredMethod("attachReferrerCount", Map.class, JavaClass.class);
        m.setAccessible(true);
        m.invoke(null, result, targetClass);
    }
}
