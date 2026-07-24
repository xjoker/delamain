package com.zin.delamain.server.routes;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.zin.delamain.core.ApkIdentity;
import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.UsageGraphIndex;
import com.zin.delamain.index.UsePlacesIndex;
import com.zin.delamain.index.WarmupManager;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.FridaTypeConverter;
import com.zin.delamain.utils.JadxApiAdapter;
import com.zin.delamain.utils.JadxSearchLock;

import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.core.dex.instructions.args.ArgType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * General utility routes.
 *
 * Endpoints:
 *   GET /index-stats — unified snapshot of all internal indices
 *
 * Note: /health, /apk-info, /decompile-status are registered directly in DelamainServer.
 * GUI-only endpoints (handleHealth with plugin-specific info, handleGetCurrentClass,
 * handleGetSelectedText) are intentionally omitted in the headless version.
 */
public class GeneralRoutes {
    private static final Logger logger = LoggerFactory.getLogger(GeneralRoutes.class);
    private static final Gson GSON = new Gson();
    private final HeadlessJadxWrapper wrapper;

    public GeneralRoutes(HeadlessJadxWrapper wrapper) {
        this.wrapper = wrapper;
    }

    public void register(Javalin app, AuthConfig auth) {
        app.get("/index-stats", this::handleIndexStats);
        app.get("/native-surface", this::handleNativeSurface);
    }

    /**
     * GET /index-stats
     *
     * Returns a unified health snapshot of all internal plugin indices:
     * name indices (class/method/field), trigram content index, snapshot cache,
     * and a summary of the upstream code cache delegation.
     *
     * Useful for AI clients to decide whether trigram-based code search is warm
     * enough to use efficiently, or whether to fall back to metadata searches.
     */
    public void handleIndexStats(Context ctx) {
        try {
            Map<String, Object> response = new HashMap<>();

            // --- APK identity echo — states which APK the index numbers below actually belong to,
            // so one /index-stats call confirms identity (package/version/input_hash) and index
            // health together. Null-safe via ApkIdentity ({loaded:false} when nothing is loaded). ---
            response.put("apk_identity", ApkIdentity.build(wrapper));

            // --- Name indices (ClassCacheManager) ---
            response.put("name_indices", ClassCacheManager.getNameIndexStats());

            // --- Trigram content index (CodeContentIndex) ---
            response.put("trigram_index", CodeContentIndex.getStats());

            // --- Mmap shard content index (ContentShardIndex) — dual-build coverage, not yet
            // consulted by queries (Wave B). Exposed for real-machine coverage verification. ---
            response.put("shard_index", ContentShardIndex.getStats());

            // --- Prebaked-index manifest (see docs/prebaked-index.md) — tells the AI/operator
            // whether this index volume was fully built (shard index complete + manifest matches
            // the currently loaded APK's input hash), so a copied volume can be trusted for
            // FAST_RESTORE without re-running warmup. Read-only: never written from here. ---
            response.put("index_prebaked", readPrebakedManifest());

            // --- Xref readiness (class-level graph + precise use-places snippets) — tells the AI
            // whether to prefer class-level xref or wait rather than requesting expensive
            // snippet-level xref on a high-fanin class while the harvest is still warming. ---
            response.put("xref_readiness", buildXrefReadiness());

            // --- Snapshot cache (JadxApiAdapter) ---
            response.put("snapshot_cache", JadxApiAdapter.getSnapshotCacheStats());

            // --- Code cache (ClassCacheManager facade over JADX ICodeCache) ---
            Map<String, Object> codeCacheRaw = ClassCacheManager.getCodeCacheStats();
            Map<String, Object> codeCache = new HashMap<>();
            codeCache.put("class_index_size", codeCacheRaw.getOrDefault("class_index_size", 0));
            codeCache.put("delegates_to_jadx_icodecache",
                    codeCacheRaw.getOrDefault("delegates_to_jadx_icodecache", true));
            response.put("code_cache", codeCache);

            ctx.json(response);
        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal Error while handling index-stats request: " + e.getMessage()));
        }
    }

    /**
     * Reads the {@code <inputHash>.manifest.json} written by
     * {@code WarmupManager#writePrebakedManifest} for the currently loaded APK, if present.
     * Returns {@code complete=false} when no warmup has run yet, the manifest file is missing
     * (e.g. shard build still in progress or an older index volume without this feature), or its
     * {@code input_hash} does not match the currently loaded APK — any of which mean the caller
     * should not assume this index volume is a trustworthy prebaked copy.
     */
    // Public (not private) so WarmupManagerColdShardBuildTest can exercise it directly without
    // standing up a Javalin HTTP context.
    public Map<String, Object> readPrebakedManifest() {
        String currentHash = WarmupManager.getCurrentInputHash();
        if (currentHash == null) {
            return Map.of("complete", false, "reason", "no warmup has run yet for this APK");
        }
        Path manifestPath = WarmupManager.getIndexDir().resolve(currentHash + ".manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            return Map.of("complete", false, "reason", "manifest not found (shard build not finished yet, or older index volume)");
        }
        try {
            String json = Files.readString(manifestPath);
            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> manifest = GSON.fromJson(json, mapType);
            if (manifest == null || !currentHash.equals(manifest.get("input_hash"))) {
                return Map.of("complete", false, "reason", "manifest input_hash does not match currently loaded APK");
            }
            Map<String, Object> result = new HashMap<>(manifest);
            result.put("complete", true);
            return result;
        } catch (JsonSyntaxException | java.io.IOException e) {
            logger.warn("Failed to read prebaked-index manifest {}: {}", manifestPath, e.getMessage());
            return Map.of("complete", false, "reason", "manifest unreadable: " + e.getMessage());
        }
    }

    /**
     * Xref readiness snapshot: whether class-level xref (the usage graph) and precise
     * snippet-level xref (the use-places harvest) are ready, built purely from existing static
     * readiness signals — no new state is introduced.
     *
     * <p>{@code class_level} tracks {@link UsageGraphIndex#isReady()}.
     * {@code precise_snippets} tracks {@link UsePlacesIndex#isReady()},
     * distinguishing "building" (the background harvest is running) from "skipped" (not ready
     * and not currently harvesting — e.g. degraded due to low heap, or not started yet). When
     * precise snippets are not ready, {@code live_fallback_cost} warns that a request for
     * {@code include_snippet=true} against a high-fanin class falls through to the live
     * (re-decompile) path and may be slow.
     */
    // Public (not private) so tests can exercise it directly without standing up a Javalin
    // HTTP context — mirrors readPrebakedManifest() above.
    public Map<String, Object> buildXrefReadiness() {
        Map<String, Object> xref = new HashMap<>();
        xref.put("class_level", UsageGraphIndex.isReady() ? "ready" : "building");

        boolean preciseReady = UsePlacesIndex.isReady();
        String preciseStatus = preciseReady ? "ready"
                : (UsePlacesIndex.isHarvesting() ? "building" : "skipped");
        xref.put("precise_snippets", preciseStatus);

        if (!preciseReady) {
            xref.put("live_fallback_cost",
                    "high-fanin xref may be slow; prefer class-level or wait for harvest");
        }
        return xref;
    }

    // ------------------------------- Native surface -----------------------------

    private static final Pattern LOAD_LIBRARY_LITERAL = Pattern.compile(
        "System\\.(?:loadLibrary|load)\\(\\s*\"([^\"]*)\"\\s*\\)");

    /**
     * GET /native-surface
     *
     * <p>Aggregates a clean, self-contained worklist for handing native-code reverse engineering
     * off to Ghidra/unidbg: every declared {@code native} method (with a JNI mangled-name
     * candidate) plus every {@code System.loadLibrary}/{@code System.load} target found anywhere
     * in the APK. No per-class manual triage needed to find "where do I even start in the .so".</p>
     */
    public void handleNativeSurface(Context ctx) {
        try {
            if (wrapper == null || !wrapper.isLoaded()) {
                ctx.status(500).json(Map.of("error", "Wrapper not initialized"));
                return;
            }

            if (!JadxSearchLock.tryAcquire(5)) {
                ctx.status(503).json(Map.of(
                    "error", "Decompilation operation in progress",
                    "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS
                ));
                return;
            }
            Map<String, Object> response;
            try {
                List<JavaClass> allClasses = wrapper.getClassesWithInners();
                // Bug 2 fix: gate the .so/loadLibrary scan on warmup being fully DONE. Before this,
                // collectLoadedLibraries() ran an unbounded cls.getCode() over every class in the
                // APK regardless of cache state — on a cold, un-warmed large APK (production report:
                // XHS, 138k classes) this blew the >120s request timeout and spiked memory. Native
                // method enumeration is pure metadata (getMethods()/getAccessFlags(), no decompile)
                // so it always stays fast and is never gated.
                boolean warmupReady = "DONE".equals(WarmupManager.getStatus().get("phase"));
                response = buildNativeSurface(allClasses, warmupReady);
            } finally {
                JadxSearchLock.release();
            }
            ctx.json(response);
        } catch (Exception e) {
            logger.error("Error in native-surface: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error in native-surface: " + e.getMessage()));
        }
    }

    /**
     * Builds the {@code /native-surface} response body. Public (not private) and takes
     * {@code warmupReady} as an explicit parameter — rather than reading {@link WarmupManager}
     * internally — so tests can exercise both the ready and not-ready paths deterministically
     * without needing to drive real warmup state (mirrors {@link #buildXrefReadiness()} above).
     *
     * <p>When {@code warmupReady} is {@code false}, the (potentially expensive) loadLibrary scan
     * is skipped entirely and {@code libraries_status="requires_warmup"} is returned instead —
     * never an unbounded cold {@code getCode()} scan over the whole APK.</p>
     */
    public Map<String, Object> buildNativeSurface(List<JavaClass> allClasses, boolean warmupReady) {
        List<Map<String, Object>> nativeMethods = collectNativeMethods(allClasses);
        List<Map<String, Object>> loadedLibraries;
        String librariesStatus;
        if (warmupReady) {
            loadedLibraries = collectLoadedLibraries(allClasses);
            librariesStatus = "ready";
        } else {
            loadedLibraries = List.of();
            librariesStatus = "requires_warmup";
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("native_methods", nativeMethods);
        response.put("native_method_count", nativeMethods.size());
        response.put("loaded_libraries", loadedLibraries);
        response.put("loaded_library_count", loadedLibraries.size());
        response.put("libraries_status", librariesStatus);
        if (!warmupReady) {
            response.put("libraries_hint", "Warmup has not completed yet, so the loadLibrary/.so scan "
                + "was skipped to avoid an unbounded cold decompile of every class. native_methods "
                + "above is unaffected (metadata-only, always fast). Call warm_cache (or wait for "
                + "warmup to finish) and retry to get loaded_libraries.");
        }
        response.put("note", "Worklist for native/JNI reverse-engineering handoff (Ghidra/unidbg). "
            + "jni_name_candidate is the short-form JNI symbol (Java_<class>_<method>); an "
            + "overloaded native may actually be exported under the long-form "
            + "(argument-signature-suffixed) symbol instead if the short form is ambiguous in "
            + "the native library's export table.");
        return response;
    }

    /**
     * Enumerates every declared {@code native} method across {@code classes}, with Frida-style
     * parameter types and a JNI mangled-name candidate ({@link #jniNameCandidate}).
     */
    static List<Map<String, Object>> collectNativeMethods(Iterable<JavaClass> classes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JavaClass cls : classes) {
            List<JavaMethod> methods;
            try {
                methods = cls.getMethods();
            } catch (Exception e) {
                continue;
            }
            for (JavaMethod method : methods) {
                if (method.getAccessFlags() == null || !method.getAccessFlags().isNative()) {
                    continue;
                }
                String rawClassName = JadxApiAdapter.getClassRawName(cls);
                String rawMethodName = JadxApiAdapter.getMethodRawName(method);

                List<String> paramTypesFrida = new ArrayList<>();
                try {
                    for (ArgType argType : method.getArguments()) {
                        paramTypesFrida.add(FridaTypeConverter.toFridaType(argType));
                    }
                } catch (Exception e) {
                    logger.debug("Failed to resolve native method args for {}.{}: {}",
                        cls.getFullName(), method.getName(), e.getMessage());
                }

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("class_name", cls.getFullName());
                entry.put("raw_class_name", rawClassName);
                entry.put("method_name", method.getName());
                entry.put("raw_method_name", rawMethodName);
                entry.put("param_types_frida", paramTypesFrida);
                entry.put("jni_name_candidate", jniNameCandidate(rawClassName, rawMethodName));
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Finds every {@code System.loadLibrary(String)}/{@code System.load(String)} call anywhere
     * in {@code classes} and returns the loaded library-name literal(s) plus the class each was
     * found in.
     *
     * <p><b>Implementation note.</b> JADX frees a method's raw bytecode instructions after code
     * generation (memory-footprint tradeoff for very large APKs — see this class's
     * {@code index-stats}/warmup-adjacent code for the same concern), so a pre-decompile
     * bytecode-level structural filter is not viable here: {@code MethodNode#getInstructions()}
     * is {@code null} both before AND after {@code getCode()} runs. This scans decompiled source
     * text directly instead (same text-scan approach as {@link DecompileRoutes}'s marker-count
     * diagnostic). {@code getCode()} itself is cache-first (JADX's {@code ICodeCache} /
     * this project's warm cache), so calling this after {@code warm_cache} has completed is fast;
     * calling it cold on an un-warmed multi-hundred-thousand-class APK decompiles every class and
     * may take a while — that cost is expected to be paid once per reverse-engineering session,
     * not on a hot path.</p>
     */
    static List<Map<String, Object>> collectLoadedLibraries(Iterable<JavaClass> classes) {
        List<Map<String, Object>> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JavaClass cls : classes) {
            String code;
            try {
                code = cls.getCode();
            } catch (Exception e) {
                logger.debug("Failed to decompile {} while scanning for loadLibrary: {}", cls.getFullName(), e.getMessage());
                continue;
            }
            if (code == null || !code.contains("System.load")) continue;

            Matcher matcher = LOAD_LIBRARY_LITERAL.matcher(code);
            while (matcher.find()) {
                String libName = matcher.group(1);
                if (libName.isEmpty()) continue;
                String dedupeKey = libName + "@" + cls.getFullName();
                if (!seen.add(dedupeKey)) continue;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", libName);
                entry.put("found_in_class", cls.getFullName());
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Builds a JNI short-form mangled-name candidate: {@code Java_<class>_<method>}, escaping per
     * the JNI spec's short-name rules — package/class {@code '.'} separators become {@code '_'},
     * and any original {@code '_'} in a package/class/method component becomes {@code "_1"} (so
     * the two never collide). Original underscores must be escaped BEFORE dots are converted, or
     * a freshly-created '_' (from a dot) would be indistinguishable from a genuine one.
     *
     * <p>Overloaded natives may actually be exported under the JNI long-form (signature-suffixed)
     * symbol instead — this candidate is a starting point for the Ghidra/unidbg symbol search,
     * not a guaranteed exact export name.</p>
     */
    static String jniNameCandidate(String rawClassName, String rawMethodName) {
        return "Java_" + escapeJniComponent(rawClassName) + "_" + escapeJniComponent(rawMethodName);
    }

    private static String escapeJniComponent(String s) {
        if (s == null) return "";
        return s.replace("_", "_1").replace(".", "_");
    }
}
