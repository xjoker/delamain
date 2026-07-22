package com.zin.delamain.server.routes;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug 1 (production report, XHS dogfood via Codex): {@code get_class_source} resolves a raw
 * (pre-deobfuscation) fully-qualified DEX class name instantly (it consults
 * {@code ClassCacheManager}'s raw-name map), but {@code search_classes_by_keyword} with
 * {@code search_in="class", match_mode="exact"} on the same raw FQN returned zero matches — the
 * class-name matcher only ever compared against the deobfuscated display simple name
 * ({@code cls.getName()}), never the raw name or either name's fully-qualified form.
 *
 * <p>{@code test-harness/acme-obf.dex} decompiled with deobfuscation on (matches production
 * {@code HeadlessJadxWrapper} defaults, min length 3) reproduces the same raw != display split as
 * XHS: {@code com.acme.demo.a} (raw) decompiles to display name {@code com.acme.demo.C0000a}.
 */
class SearchRoutesRawClassNameMatchTest {

    private static JadxDecompiler jadx;
    private static List<JavaClass> classes;
    private final SearchRoutes routes = new SearchRoutes(null, null);

    @BeforeAll
    static void setUpClasses() {
        JadxArgs args = new JadxArgs();
        args.setInputFile(new File("test-harness/acme-obf.dex"));
        args.setSkipResources(true);
        args.setDeobfuscationOn(true);
        args.setDeobfuscationMinLength(3);
        jadx = new JadxDecompiler(args);
        jadx.load();
        classes = jadx.getClasses();
        assertTrue(classes.size() >= 2, "acme-obf.dex fixture must yield at least 2 classes");
    }

    @AfterAll
    static void tearDownClasses() {
        if (jadx != null) jadx.close();
    }

    /** Finds the fixture class whose raw simple name is "a" (display renamed to "C0000a"). */
    private static JavaClass findObfuscatedClassA() {
        for (JavaClass cls : classes) {
            if ("com.acme.demo.a".equals(cls.getRawName())) return cls;
        }
        throw new IllegalStateException("acme-obf.dex fixture must contain raw class com.acme.demo.a");
    }

    @Test
    void exactSearchByRawFullyQualifiedNameFindsClass() {
        JavaClass target = findObfuscatedClassA();
        assertEquals("com.acme.demo.a", target.getRawName());
        assertTrue(!target.getRawName().equals(target.getFullName()),
            "fixture precondition: raw name must differ from the deobfuscated display name");

        Set<SearchRoutes.SearchLocation> locations = EnumSet.of(SearchRoutes.SearchLocation.CLASS_NAME);
        SearchRoutes.SearchExecution exec = routes.executeSearchWithMatchMode(
            classes, classes, "com.acme.demo.a", locations, true, Integer.MAX_VALUE,
            SearchRoutes.MatchMode.EXACT, null);

        List<String> matches = exec.getResult().getMatches();
        assertTrue(matches.contains(target.getFullName()),
            "exact search on the raw DEX FQN must find the class, matches=" + matches);

        @SuppressWarnings("unchecked")
        Map<String, String> matchedOn = (Map<String, String>) exec.getResult().getSearchInfo().get("matched_on");
        assertEquals("raw", matchedOn != null ? matchedOn.get(target.getFullName()) : null,
            "matched_on must report this hit came from the raw name, not the display name");
    }

    @Test
    void exactSearchByDisplayFullyQualifiedNameStillFindsClass() {
        JavaClass target = findObfuscatedClassA();

        Set<SearchRoutes.SearchLocation> locations = EnumSet.of(SearchRoutes.SearchLocation.CLASS_NAME);
        SearchRoutes.SearchExecution exec = routes.executeSearchWithMatchMode(
            classes, classes, target.getFullName(), locations, true, Integer.MAX_VALUE,
            SearchRoutes.MatchMode.EXACT, null);

        List<String> matches = exec.getResult().getMatches();
        assertTrue(matches.contains(target.getFullName()),
            "exact search on the deobfuscated display FQN must find the class, matches=" + matches);

        @SuppressWarnings("unchecked")
        Map<String, String> matchedOn = (Map<String, String>) exec.getResult().getSearchInfo().get("matched_on");
        assertEquals("display", matchedOn != null ? matchedOn.get(target.getFullName()) : null,
            "matched_on must report this hit came from the display name");
    }

    @Test
    void exactSearchByUnrelatedNameFindsNothing() {
        Set<SearchRoutes.SearchLocation> locations = EnumSet.of(SearchRoutes.SearchLocation.CLASS_NAME);
        SearchRoutes.SearchExecution exec = routes.executeSearchWithMatchMode(
            classes, classes, "com.acme.demo.NoSuchClassAtAll", locations, true, Integer.MAX_VALUE,
            SearchRoutes.MatchMode.EXACT, null);
        assertTrue(exec.getResult().getMatches().isEmpty(),
            "an unrelated FQN must not match anything");
    }
}
