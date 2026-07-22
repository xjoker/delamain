package com.zin.delamain.server.routes;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.utils.ClassCacheManager;

import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measured on production 2026-07-22 (XHS APK, 237 931 classes, 9 832 MB heap): a class xref with
 * {@code include_snippet=true} against a high-fan-in utility class
 * ({@code com.xingin.utils.XYUtilsCenter}) drove the heap from 4 461 MB to <b>8 466 MB (86 %)</b>
 * and RSS to 10.5 GB, and had still not returned after <b>4 minutes</b> — well past the gateway's
 * 120 s ceiling. A forced GC at the end reclaimed only 538 MB of 8 016 MB, proving the memory was
 * <em>live</em>, not garbage.
 *
 * <p>Cause: {@code attachSnippets} resolves each referrer's source through
 * {@code fetchSourceLines}, which falls back to {@code cls.getCodeInfo()} — a live decompile that
 * loads and <b>retains</b> the referrer's ClassNode. One request against a class with thousands of
 * referrers therefore pins thousands of decompiled classes in the heap for the rest of the
 * process's life. It survived only because the heap cap forced constant GC; before the cap
 * (49 GB heap) the same path was recorded at an 11.2 GB spike.</p>
 *
 * <p>Contract under test: a referrer this request had to live-decompile must be released once its
 * snippet lines have been extracted. Classes that were already resident before the request are
 * left alone — evicting those would sabotage a user actively working on them.</p>
 */
class XrefsRoutesReferrerRecycleTest {

    private HeadlessJadxWrapper wrapper;
    private XrefsRoutes routes;

    @BeforeEach
    void setUp(@TempDir Path workDir) throws Exception {
        File apk = new File("test-harness/real/UnCrackable-Level1.apk");
        assertTrue(apk.exists(), "test APK must exist: " + apk.getAbsolutePath());
        wrapper = new HeadlessJadxWrapper(List.of(apk), new File(workDir.toFile(), "out"), 2);
        wrapper.load();
        routes = new XrefsRoutes(wrapper, null);

        ClassCacheManager.initCache(wrapper);
        long deadline = System.currentTimeMillis() + 60_000;
        while (ClassCacheManager.getCache().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(!ClassCacheManager.getCache().isEmpty(), "class cache must be built for this test");
    }

    @AfterEach
    void tearDown() {
        if (wrapper != null) wrapper.close();
    }

    private JavaClass anyClassWithSource() throws Exception {
        for (JavaClass cls : ClassCacheManager.getCache().values()) {
            String code = cls.getCode();
            if (code != null && code.split("\n").length > 3) return cls;
        }
        return null;
    }

    /** One reference row pointing at {@code cls}, shaped like the live xref path produces. */
    private static List<Map<String, Object>> refsFor(JavaClass cls) {
        Map<String, Object> ref = new HashMap<>();
        ref.put("class", cls.getFullName());
        ref.put("decompiled_line", 2);
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(ref);
        return list;
    }

    @Test
    void aReferrerDecompiledJustForItsSnippetIsReleasedAgain() throws Exception {
        JavaClass referrer = anyClassWithSource();
        assertNotNull(referrer, "test APK must contain a class with several source lines");

        // Put the class back to "not resident": this is the state every referrer is in during a
        // cold xref, and the state production was in when the heap ran away.
        ClassCacheManager.evictCodeCacheEntry(referrer);
        referrer.unload();
        assertNull(ClassCacheManager.getCachedCodeDirect(referrer),
            "precondition: the referrer must start out non-resident");

        List<Map<String, Object>> refs = refsFor(referrer);
        routes.attachSnippets(refs, 1);

        // The snippet still has to be produced — recycling must not cost correctness.
        Object snippet = refs.get(0).get("snippet");
        assertNotNull(snippet, "the snippet must still be extracted: " + refs.get(0));

        assertNull(ClassCacheManager.getCachedCodeDirect(referrer),
            "a referrer decompiled only to serve this snippet must be released afterwards, "
                + "otherwise one high-fan-in xref pins every referrer in the heap for good");
    }

    @Test
    void aReferrerThatWasAlreadyResidentIsLeftAlone() throws Exception {
        JavaClass referrer = anyClassWithSource();
        assertNotNull(referrer);

        // Simulate a class the caller is actively working on: resident before the xref runs.
        ClassCacheManager.getCodeAndIndex(referrer);
        assertNotNull(ClassCacheManager.getCachedCodeDirect(referrer),
            "precondition: the referrer must start out resident");

        routes.attachSnippets(refsFor(referrer), 1);

        assertNotNull(ClassCacheManager.getCachedCodeDirect(referrer),
            "a class that was already resident must not be evicted by an unrelated xref — "
                + "that would slow down whatever the caller is actually doing");
    }
}
