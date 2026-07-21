package com.zin.delamain.server.routes;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.JadxApiAdapter;
import com.zin.delamain.utils.JadxSearchLock;
import com.zin.delamain.utils.SmartChunker;

import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.CommentsLevel;
import jadx.api.DecompilationMode;
import jadx.api.ICodeInfo;
import jadx.api.JadxArgs;
import jadx.api.JavaClass;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.ICodeMetadata;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.DecompileModeOverrideAttr;
import jadx.core.dex.attributes.nodes.JadxCommentsAttr;
import jadx.core.dex.attributes.nodes.JadxError;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.nodes.ProcessState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles single-class decompilation and Smali inspection MCP endpoints.
 *
 * <p>Covers: {@code /class-source} (decompiled Java source) and
 * {@code /smali-of-class} (Dalvik bytecode for APK/DEX files).</p>
 *
 * <p>Both endpoints use {@link JadxSearchLock} for serialised access to the
 * JADX decompiler and {@link ClassCacheManager} for cache-first retrieval.</p>
 */
public class DecompileRoutes {
    private static final Logger logger = LoggerFactory.getLogger(DecompileRoutes.class);

    private final HeadlessJadxWrapper wrapper;

    public DecompileRoutes(HeadlessJadxWrapper wrapper) {
        this.wrapper = wrapper;
    }

    /** Structured decompilation-quality verdict: {@code ok|degraded|failed}, plus an optional reason. */
    private static final class DecompileVerdict {
        final String quality;
        final String reason;

        DecompileVerdict(String quality, String reason) {
            this.quality = quality;
            this.reason = reason;
        }
    }

    /**
     * Computes the decompilation-quality verdict for a class from structured JADX signals only
     * (never from scanning the decompiled text — see class-level Javadoc caveat on
     * {@link #buildMarkerCounts}, which is a separate, best-effort diagnostic and must not be
     * conflated with this authoritative verdict):
     *
     * <ul>
     *   <li>{@code classNode == null}, {@link ClassNode#getAll(AType)} JADX_ERROR non-empty, or
     *       {@link ClassNode#getState()} not {@link ProcessState#isProcessComplete()} → {@code failed}</li>
     *   <li>{@link DecompileModeOverrideAttr} present with mode {@link DecompilationMode#FALLBACK}
     *       → {@code degraded}</li>
     *   <li>otherwise → {@code ok}</li>
     * </ul>
     *
     * <p>Must only be called from the actual-decompile path (holding the JADX write lock, right
     * after {@code JavaClass.getCode()}), never from a lockless cache-hit read path.</p>
     */
    @SuppressWarnings("JadxInternalApiUsage")
    static DecompileVerdict computeVerdict(ClassNode classNode) {
        if (classNode == null) {
            return new DecompileVerdict("failed", "no backing class node");
        }

        List<JadxError> jadxErrors = classNode.getAll(AType.JADX_ERROR);
        if (jadxErrors != null && !jadxErrors.isEmpty()) {
            return new DecompileVerdict("failed",
                jadxErrors.size() + (jadxErrors.size() == 1 ? " jadx error" : " jadx errors"));
        }

        ProcessState state = classNode.getState();
        if (state == null || !state.isProcessComplete()) {
            return new DecompileVerdict("failed",
                "process incomplete (state=" + (state != null ? state.name() : "UNKNOWN") + ")");
        }

        DecompileModeOverrideAttr modeAttr = classNode.get(AType.DECOMPILE_MODE_OVERRIDE);
        if (modeAttr != null && modeAttr.getMode() == DecompilationMode.FALLBACK) {
            return new DecompileVerdict("degraded", "fallback mode");
        }

        return new DecompileVerdict("ok", null);
    }

    /** English, actionable next-step hint for a degraded/failed verdict; {@code null} when {@code ok}. */
    private static String hintFor(String quality, String reason) {
        if (quality == null || "ok".equals(quality)) {
            return null;
        }
        String cause = "failed".equals(quality)
            ? "Java decompilation failed (" + reason + ")"
            : "Java decompilation degraded (" + reason + ")";
        return cause + "; the output may be structurally incomplete or unreliable. "
            + "For a complete low-level view call get_smali_of_class; for a full diagnostic call "
            + "get_decompile_diag; to retry with a different mode call decompile_with_mode.";
    }

    public void register(Javalin app, AuthConfig auth) {
        app.get("/class-source", this::handleClassSource);
        app.get("/smali-of-class", this::handleSmaliOfClass);
        app.get("/decompile-diag", this::handleDecompileDiag);
        app.get("/code-metadata", this::handleCodeMetadata);
        app.get("/decompile-with-mode", this::handleDecompileWithMode);
    }

    // ------------------------------- Request Handlers --------------------------

    /**
     * Handles {@code /class-source}.
     *
     * <p>Returns the decompiled Java source of a single class. Supports chunking for
     * large responses ({@code ?chunk=N}).</p>
     */
    public void handleClassSource(Context ctx) {
        String className = checkClassParam(ctx);
        if (className == null)
            return;

        int chunk = 0;
        String chunkParam = ctx.queryParam("chunk");
        if (chunkParam != null && !chunkParam.isEmpty()) {
            try {
                chunk = Integer.parseInt(chunkParam.trim());
            } catch (NumberFormatException e) {
                logger.warn("Invalid chunk parameter: {}", chunkParam);
                ctx.status(400).json(Map.of("error", "Invalid chunk parameter: " + chunkParam));
                return;
            }
        }

        try {
            JavaClass targetClass = findClassByName(className);
            String cacheKey = targetClass != null ? targetClass.getFullName() : className;
            // CodeStore is keyed by RAW name (deobf-stable); the in-memory ClassCacheManager
            // keeps using the display key. Keep the two distinct so deobf-on doesn't miss disk.
            String rawKey = targetClass != null ? JadxApiAdapter.getClassRawName(targetClass) : className;

            // Check decompiled code cache first (no lock needed for cache read)
            String code = ClassCacheManager.getCachedCode(cacheKey);

            // Disk fallback: an immutable APK's source is persisted by warmup, so a cold
            // restart or an evicted in-memory entry reads from disk (ms) instead of
            // re-decompiling (s) — and without taking the decompile write lock.
            if (code == null) {
                com.zin.delamain.index.CodeStore cs = com.zin.delamain.index.WarmupManager.codeStore();
                if (cs != null) {
                    String disk = cs.get(rawKey);
                    if (disk != null) {
                        code = disk;
                        ClassCacheManager.putCachedCode(cacheKey, disk);
                    }
                }
            }

            if (code != null) {
                Map<String, Object> result = SmartChunker.chunkResponse(code, chunk, "response");
                if (result.containsKey("error")) {
                    logger.warn((String) result.get("error"));
                    ctx.status(400).json(Map.of("error", (String) result.get("error")));
                    return;
                }
                if (chunk == 0) {
                    attachQuality(result, cacheKey);
                }
                ctx.json(result);
                return;
            }

            // Decompilation requires write lock (JADX internal state is not thread-safe).
            // Block up to 5 s so concurrent cold-cache bursts queue instead of failing fast.
            if (!JadxSearchLock.tryAcquire(5)) {
                ctx.status(503).json(Map.of(
                    "error", "Decompilation operation in progress",
                    "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS
                ));
                return;
            }
            try {
                // Re-check cache after acquiring lock (another thread may have decompiled it)
                code = ClassCacheManager.getCachedCode(cacheKey);
                if (code == null) {
                    if (targetClass == null) {
                        targetClass = findClassByName(className);
                    }
                    if (targetClass != null) {
                        code = targetClass.getCode();
                        ClassCacheManager.putCachedCode(targetClass.getFullName(), code);
                        com.zin.delamain.index.CodeStore cs = com.zin.delamain.index.WarmupManager.codeStore();
                        if (cs != null && code != null) cs.put(JadxApiAdapter.getClassRawName(targetClass), code);
                        // Structured quality verdict — computed exactly once, right here at the
                        // real decompile, from JadxError/processComplete/FALLBACK signals only.
                        // Never recomputed on a subsequent cache-hit read.
                        @SuppressWarnings("JadxInternalApiUsage")
                        ClassNode decompiledNode = targetClass.getClassNode();
                        DecompileVerdict verdict = computeVerdict(decompiledNode);
                        ClassCacheManager.putCodeQuality(
                            targetClass.getFullName(), verdict.quality, verdict.reason);
                    }
                }
            } finally {
                JadxSearchLock.release();
            }

            if (code == null) {
                ctx.status(404).json(Map.of("error", "Class " + className + " not found"));
                return;
            }

            Map<String, Object> result = SmartChunker.chunkResponse(code, chunk, "response");
            if (result.containsKey("error")) {
                logger.warn((String) result.get("error"));
                ctx.status(400).json(Map.of("error", (String) result.get("error")));
                return;
            }
            if (chunk == 0) {
                attachQuality(result, cacheKey);
            }
            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error retrieving class source: " + e.getMessage()));
        }
    }

    /**
     * Attaches {@code decompile_quality} (and, when degraded/failed, {@code hint}) to a
     * {@code /class-source} response — zero-cost, cache-only read; never recomputes or scans.
     * A cache miss (evicted entry, disk-restored-only source, or a class cached before this
     * verdict cache existed) reports {@code "unknown"} rather than guessing.
     */
    private static void attachQuality(Map<String, Object> result, String cacheKey) {
        ClassCacheManager.CodeQuality verdict = ClassCacheManager.getCodeQuality(cacheKey);
        result.put("decompile_quality", verdict != null ? verdict.quality : "unknown");
        if (verdict != null) {
            String hint = hintFor(verdict.quality, verdict.reason);
            if (hint != null) {
                result.put("hint", hint);
            }
        }
    }

    /**
     * Handles {@code /smali-of-class}.
     *
     * <p>Returns the Dalvik bytecode (Smali) for a class. Only available for APK/DEX
     * files. Supports chunking for large responses ({@code ?chunk=N}).</p>
     */
    public void handleSmaliOfClass(Context ctx) {
        String className = checkClassParam(ctx);
        if (className == null)
            return;

        int chunk = 0;
        String chunkParam = ctx.queryParam("chunk");
        if (chunkParam != null && !chunkParam.isEmpty()) {
            try {
                chunk = Integer.parseInt(chunkParam.trim());
            } catch (NumberFormatException e) {
                logger.warn("Invalid chunk parameter: {}", chunkParam);
                ctx.status(400).json(Map.of("error", "Invalid chunk parameter: " + chunkParam));
                return;
            }
        }

        boolean lockAcquired = false;
        try {
            // Block up to 5 s so concurrent cold-cache bursts queue instead of failing fast.
            if (!JadxSearchLock.tryAcquire(5)) {
                ctx.status(503).json(Map.of(
                    "error", "Decompilation operation in progress",
                    "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS
                ));
                return;
            }
            lockAcquired = true;

            JavaClass cls = findClassByName(className);
            if (cls != null) {
                String smali = cls.getSmali();
                if (smali == null || smali.isEmpty()) {
                    logger.warn("Smali generation returned empty for class {}", className);
                    ctx.status(404).json(Map.of("error",
                        "Smali generation returned empty for class " + className +
                        ". This may indicate the class was loaded from a non-DEX source."));
                    return;
                }

                Map<String, Object> result = SmartChunker.chunkResponse(smali, chunk, "response");

                if (result.containsKey("error")) {
                    logger.warn((String) result.get("error"));
                    ctx.status(400).json(Map.of("error", (String) result.get("error")));
                    return;
                }

                ctx.json(result);
                return;
            }
            logger.warn("Class {} not found for smali", className);
            ctx.status(404).json(Map.of("error", "Class " + className + " not found."));
        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error retrieving smali: " + e.getMessage()));
        } finally {
            if (lockAcquired) {
                JadxSearchLock.release();
            }
        }
    }

    /**
     * Handles {@code /decompile-diag}.
     *
     * <p>Reports the decompilation health of a class for AI consumers who need to know how
     * trustworthy the decompiled output is. Includes:</p>
     * <ul>
     *   <li>process state (whether decompilation completed)</li>
     *   <li>per-class errors recorded by JADX during decompilation</li>
     *   <li>decompilation-mode override (e.g. FALLBACK downgrade)</li>
     *   <li>JADX internal comment breakdown by severity level</li>
     *   <li>in-source warning-marker counts scanned from the decompiled text</li>
     *   <li>guidance pointers to smali / disassembled fallback views</li>
     * </ul>
     *
     * <p>Query params: {@code class_name} (required).</p>
     */
    public void handleDecompileDiag(Context ctx) {
        String className = checkClassParam(ctx);
        if (className == null)
            return;

        try {
            JavaClass targetClass = findClassByName(className);
            if (targetClass == null) {
                ctx.status(404).json(Map.of("error", "Class " + className + " not found"));
                return;
            }

            Map<String, Object> diag = new LinkedHashMap<>();
            diag.put("class_name", targetClass.getFullName());
            diag.put("raw_class", targetClass.getRawName());

            // JADX internal ClassNode state/attributes can be mutated by the background
            // deep-warm (cls.getCode()); serialise these reads behind the search lock like
            // the other handlers in this file to avoid racing with a concurrent re-process.
            @SuppressWarnings("JadxInternalApiUsage")
            ClassNode classNode = targetClass.getClassNode();
            if (!JadxSearchLock.tryAcquire(5)) {
                ctx.status(503).json(Map.of(
                    "error", "Decompilation operation in progress",
                    "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS
                ));
                return;
            }
            try {
            // ── 1. Process state ───────────────────────────────────────────────────────
            if (classNode != null) {
                ProcessState state = classNode.getState();
                diag.put("process_state", state != null ? state.name() : "UNKNOWN");
                diag.put("process_complete", state != null && state.isProcessComplete());
            } else {
                diag.put("process_state", "UNKNOWN");
                diag.put("process_complete", false);
            }

            // ── 2. Decompilation-mode override (FALLBACK downgrade detection) ──────────
            String decompileMode = null;
            boolean isFallback = false;
            if (classNode != null) {
                DecompileModeOverrideAttr modeAttr = classNode.get(AType.DECOMPILE_MODE_OVERRIDE);
                if (modeAttr != null) {
                    DecompilationMode mode = modeAttr.getMode();
                    decompileMode = mode != null ? mode.name() : null;
                    isFallback = mode == DecompilationMode.FALLBACK;
                }
            }
            diag.put("decompile_mode_override", decompileMode);
            diag.put("is_fallback_mode", isFallback);

            // ── 3. Per-class JADX errors ───────────────────────────────────────────────
            List<Map<String, String>> errorList = new ArrayList<>();
            if (classNode != null) {
                List<JadxError> jadxErrors = classNode.getAll(AType.JADX_ERROR);
                if (jadxErrors != null) {
                    for (JadxError err : jadxErrors) {
                        Map<String, String> entry = new LinkedHashMap<>();
                        entry.put("message", err.getError());
                        Throwable cause = err.getCause();
                        entry.put("cause", cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : null);
                        errorList.add(entry);
                    }
                }
            }
            diag.put("jadx_error_count", errorList.size());
            diag.put("jadx_errors", errorList);

            // ── 4. JADX internal comment breakdown by CommentsLevel ────────────────────
            Map<String, Integer> commentsByLevel = new LinkedHashMap<>();
            int totalJadxComments = 0;
            if (classNode != null) {
                JadxCommentsAttr commentsAttr = classNode.get(AType.JADX_COMMENTS);
                if (commentsAttr != null) {
                    Map<CommentsLevel, Set<String>> comments = commentsAttr.getComments();
                    if (comments != null) {
                        for (Map.Entry<CommentsLevel, Set<String>> levelEntry : comments.entrySet()) {
                            int count = levelEntry.getValue() != null ? levelEntry.getValue().size() : 0;
                            commentsByLevel.put(levelEntry.getKey().name(), count);
                            totalJadxComments += count;
                        }
                    }
                }
            }
            diag.put("jadx_comment_count", totalJadxComments);
            diag.put("jadx_comments_by_level", commentsByLevel);
            } finally {
                JadxSearchLock.release();
            }

            // ── 5. In-source warning-marker scan (text analysis, no hidden API) ─────────
            // Attempt to get decompiled source (cache-first, no re-decompile if not cached).
            String sourceCode = null;
            String cacheKey = targetClass.getFullName();
            sourceCode = ClassCacheManager.getCachedCode(cacheKey);
            if (sourceCode == null) {
                com.zin.delamain.index.CodeStore cs = com.zin.delamain.index.WarmupManager.codeStore();
                if (cs != null) {
                    // CodeStore is keyed by raw name (deobf-stable)
                    sourceCode = cs.get(JadxApiAdapter.getClassRawName(targetClass));
                }
            }

            Map<String, Object> markerCounts = buildMarkerCounts(sourceCode);
            diag.put("source_available_for_scan", sourceCode != null);
            diag.put("source_warning_markers", markerCounts);

            // ── 6. Guidance pointers ──────────────────────────────────────────────────
            Map<String, String> guidance = new LinkedHashMap<>();
            guidance.put("smali_endpoint", "GET /smali-of-class?class_name=<class> — Dalvik bytecode view (APK/DEX only)");
            guidance.put("source_endpoint", "GET /class-source?class_name=<class> — full decompiled Java source");
            diag.put("alternative_views", guidance);

            ctx.json(diag);
        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error in decompile-diag: " + e.getMessage()));
        }
    }

    /**
     * Handles {@code /decompile-with-mode}.
     *
     * <p><b>Capability boundary — read before use.</b> JADX 1.5.5's {@link DecompilationMode} is
     * consumed exactly once, at decompiler-init time, when the internal {@code RootNode} builds
     * its processing-pass pipeline. There is no supported way to hot-switch the whole running
     * instance's mode afterward — {@code JadxArgs.setDecompilationMode()} only affects a
     * decompiler built from scratch. What JADX <em>does</em> expose is a per-class escape hatch:
     * {@code ClassNode.decompileWithMode(DecompilationMode)}, which re-runs a single class
     * through the pass list for an explicit mode (SIMPLE/FALLBACK skip the full restructuring
     * pipeline) and returns fresh code without touching global state or the class's normal
     * cached source. This endpoint wraps exactly that capability — an on-demand, non-persistent
     * re-decompile of one class in the requested mode. It is not cached: call it again to
     * regenerate, and {@code /class-source} keeps returning the normal (RESTRUCTURE) output for
     * this class afterward. There is no global "switch the server to fallback mode" operation.</p>
     *
     * <p>Intended use: a class fails to decompile cleanly (see {@code /decompile-diag}) — retry
     * it here in {@code SIMPLE} or {@code FALLBACK} mode to get a usable (if less readable) view
     * instead of garbage/partial RESTRUCTURE output.</p>
     *
     * <p>Query params: {@code class_name} (required); {@code mode} — one of
     * {@code RESTRUCTURE|SIMPLE|FALLBACK} (default {@code FALLBACK}); {@code comments_level} —
     * optional JADX {@link CommentsLevel} name, applied only for the duration of this call (the
     * decompile write-lock this handler holds serialises against every other decompile path in
     * this process, so the flip-and-restore of the shared {@code JadxArgs} setting is race-free)
     * and restored to the process's original value before returning; {@code chunk} — pagination,
     * same semantics as {@code /class-source}.</p>
     */
    public void handleDecompileWithMode(Context ctx) {
        String className = checkClassParam(ctx);
        if (className == null)
            return;

        String modeParam = ctx.queryParam("mode");
        DecompilationMode mode;
        if (modeParam == null || modeParam.isBlank()) {
            mode = DecompilationMode.FALLBACK;
        } else {
            DecompilationMode parsed;
            try {
                parsed = DecompilationMode.valueOf(modeParam.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                parsed = null;
            }
            if (parsed != DecompilationMode.RESTRUCTURE && parsed != DecompilationMode.SIMPLE
                    && parsed != DecompilationMode.FALLBACK) {
                ctx.status(400).json(Map.of("error",
                    "Invalid 'mode': " + modeParam + ". Must be one of RESTRUCTURE, SIMPLE, FALLBACK."));
                return;
            }
            mode = parsed;
        }

        CommentsLevel commentsLevel = null;
        String commentsParam = ctx.queryParam("comments_level");
        if (commentsParam != null && !commentsParam.isBlank()) {
            try {
                commentsLevel = CommentsLevel.valueOf(commentsParam.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", "Invalid 'comments_level': " + commentsParam));
                return;
            }
        }

        int chunk = 0;
        String chunkParam = ctx.queryParam("chunk");
        if (chunkParam != null && !chunkParam.isEmpty()) {
            try {
                chunk = Integer.parseInt(chunkParam.trim());
            } catch (NumberFormatException e) {
                logger.warn("Invalid chunk parameter: {}", chunkParam);
                ctx.status(400).json(Map.of("error", "Invalid chunk parameter: " + chunkParam));
                return;
            }
        }

        try {
            JavaClass targetClass = findClassByName(className);
            if (targetClass == null) {
                ctx.status(404).json(Map.of("error", "Class " + className + " not found"));
                return;
            }

            @SuppressWarnings("JadxInternalApiUsage")
            ClassNode classNode = targetClass.getClassNode();
            if (classNode == null) {
                ctx.status(404).json(Map.of("error", "Class " + className + " has no backing node"));
                return;
            }

            // Single-class re-decompile mutates JADX internal state (unload/reload) just like the
            // other handlers in this file — serialise behind the same write lock.
            if (!JadxSearchLock.tryAcquire(5)) {
                ctx.status(503).json(Map.of(
                    "error", "Decompilation operation in progress",
                    "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS
                ));
                return;
            }

            String code;
            try {
                JadxArgs args = commentsLevel != null ? wrapper.getArgs() : null;
                CommentsLevel previousLevel = args != null ? args.getCommentsLevel() : null;
                try {
                    if (args != null) {
                        args.setCommentsLevel(commentsLevel);
                    }
                    @SuppressWarnings("JadxInternalApiUsage")
                    ICodeInfo info = classNode.decompileWithMode(mode);
                    code = info != null ? info.getCodeStr() : null;
                } finally {
                    if (args != null) {
                        args.setCommentsLevel(previousLevel);
                    }
                }
            } finally {
                JadxSearchLock.release();
            }

            if (code == null) {
                ctx.status(500).json(Map.of("error",
                    "decompileWithMode returned no code for " + className));
                return;
            }

            Map<String, Object> result = SmartChunker.chunkResponse(code, chunk, "response");
            if (result.containsKey("error")) {
                logger.warn((String) result.get("error"));
                ctx.status(400).json(Map.of("error", (String) result.get("error")));
                return;
            }
            result.put("class_name", targetClass.getFullName());
            result.put("raw_class", targetClass.getRawName());
            result.put("mode", mode.name());
            result.put("ephemeral", true);
            result.put("note", "Single-class, non-persistent re-decompile in the requested mode. "
                + "JADX has no supported runtime-wide DecompilationMode switch (it is baked into "
                + "the pass pipeline at decompiler init) — this result is not cached, and "
                + "/class-source continues to return the normal RESTRUCTURE output for this class.");
            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error in decompile-with-mode: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error in decompile-with-mode: " + e.getMessage()));
        }
    }

    /**
     * Exposes JADX code metadata (position ↔ semantic node) for a class — the basis for
     * AI "go to definition" / "what symbol is here" navigation.
     *
     * <p>{@code GET /code-metadata?class_name=X} returns the class's symbol references
     * (class/method/field) with source line + raw &amp; alias names. Add {@code &position=N}
     * to resolve the single node at char-offset N. {@code &max=} caps the reference list.</p>
     *
     * <p>Names follow the raw/alias dual-track: {@code raw_name} is the original/runtime name
     * (use for hooks); {@code alias_name} is the deobfuscated display name.</p>
     */
    public void handleCodeMetadata(Context ctx) {
        String className = checkClassParam(ctx);
        if (className == null) return;
        Integer position = null;
        String posParam = ctx.queryParam("position");
        if (posParam != null && !posParam.isEmpty()) {
            try { position = Integer.parseInt(posParam.trim()); }
            catch (NumberFormatException e) { ctx.status(400).json(Map.of("error", "Invalid position: " + posParam)); return; }
        }
        int max = 300;
        String maxParam = ctx.queryParam("max");
        if (maxParam != null && !maxParam.isEmpty()) {
            try { max = Math.max(1, Integer.parseInt(maxParam.trim())); } catch (NumberFormatException ignored) {}
        }

        try {
            JavaClass targetClass = findClassByName(className);
            if (targetClass == null) {
                ctx.status(404).json(Map.of("error", "Class " + className + " not found"));
                return;
            }
            // getCodeInfo() may decompile if not cached — serialise behind the search lock.
            if (!JadxSearchLock.tryAcquire(5)) {
                ctx.status(503).json(Map.of("error", "Decompilation operation in progress",
                        "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS));
                return;
            }
            ICodeInfo ci;
            try {
                ci = targetClass.getCodeInfo();
            } finally {
                JadxSearchLock.release();
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("class_name", targetClass.getFullName());
            resp.put("raw_class", targetClass.getRawName());
            if (ci == null || !ci.hasMetadata()) {
                resp.put("has_metadata", false);
                resp.put("hint", "No code metadata — call /class-source first to decompile this class.");
                ctx.json(resp);
                return;
            }
            resp.put("has_metadata", true);
            ICodeMetadata md = ci.getCodeMetadata();
            Map<Integer, Integer> lineMapping = md.getLineMapping();

            if (position != null) {
                // Point query: prefer the exact annotation at this offset (matches the
                // reference list); fall back to the enclosing node, then nearest-up.
                ICodeNodeRef node = asNodeRef(md.getAt(position));
                String how = "exact";
                if (node == null) { node = md.getNodeAt(position); how = "enclosing"; }
                if (node == null) { node = asNodeRef(md.getClosestUp(position)); how = "closest_up"; }
                Map<String, Object> at = describeNode(node);
                at.put("position", position);
                at.put("resolved_by", node != null ? how : "none");
                Integer line = lineMapping != null ? lineMapping.get(position) : null;
                at.put("line", line);
                resp.put("at", at);
                ctx.json(resp);
                return;
            }

            // Reference list: node-ref annotations across the class, capped.
            java.util.Map<Integer, ICodeAnnotation> ann = md.getAsMap();
            List<Map<String, Object>> refs = new ArrayList<>();
            int total = 0;
            boolean truncated = false;
            java.util.List<Integer> positions = new ArrayList<>(ann.keySet());
            java.util.Collections.sort(positions);
            for (Integer pos : positions) {
                ICodeAnnotation a = ann.get(pos);
                ICodeNodeRef node = asNodeRef(a);
                if (node == null) continue; // skip OFFSET/END/var-only annotations
                total++;
                if (refs.size() >= max) { truncated = true; continue; }
                Map<String, Object> e = describeNode(node);
                e.put("position", pos);
                if (lineMapping != null) e.put("line", lineMapping.get(pos));
                refs.add(e);
            }
            resp.put("reference_count", total);
            resp.put("references", refs);
            resp.put("truncated", truncated);
            ctx.json(resp);
        } catch (Exception e) {
            logger.error("Error in code-metadata: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error in code-metadata: " + e.getMessage()));
        }
    }

    /** Unwrap an annotation to its node ref (handles NodeDeclareRef wrappers), or null. */
    private static ICodeNodeRef asNodeRef(ICodeAnnotation a) {
        if (a instanceof NodeDeclareRef) return ((NodeDeclareRef) a).getNode();
        if (a instanceof ICodeNodeRef) return (ICodeNodeRef) a;
        return null;
    }

    /** Describe a code node ref with raw + alias names (raw = runtime name, for hooks). */
    private static Map<String, Object> describeNode(ICodeNodeRef node) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (node == null) { m.put("kind", "none"); return m; }
        if (node instanceof ClassNode) {
            ClassNode cn = (ClassNode) node;
            m.put("kind", "class");
            m.put("raw_name", cn.getClassInfo().getRawName());
            m.put("alias_name", cn.getClassInfo().getFullName());
        } else if (node instanceof MethodNode) {
            MethodNode mn = (MethodNode) node;
            m.put("kind", "method");
            m.put("raw_name", mn.getMethodInfo().getName());
            m.put("alias_name", mn.getMethodInfo().getAlias());
            m.put("raw_class", mn.getMethodInfo().getDeclClass().getRawName());
            m.put("raw_full_id", mn.getMethodInfo().getRawFullId());
        } else if (node instanceof FieldNode) {
            FieldNode fn = (FieldNode) node;
            m.put("kind", "field");
            m.put("raw_name", fn.getFieldInfo().getName());
            m.put("alias_name", fn.getFieldInfo().getAlias());
            m.put("raw_class", fn.getFieldInfo().getDeclClass().getRawName());
        } else {
            m.put("kind", node.getAnnType() != null ? node.getAnnType().name().toLowerCase() : "unknown");
        }
        m.put("def_position", node.getDefPosition());
        return m;
    }

    // ------------------------------- Helpers -----------------------------------

    /**
     * Scans the decompiled source text for JADX-injected annotation markers and counts each
     * distinct pattern. This approach depends only on the text output, not on internal APIs,
     * so it works regardless of JADX version.
     *
     * <p>Recognised markers (all variants of JADX diagnostic annotations that appear in source):</p>
     * <ul>
     *   <li>{@code // JADX WARNING:} — method/instruction-level warnings</li>
     *   <li>{@code // JADX INFO:} — informational notes</li>
     *   <li>{@code // JADX DEBUG:} — debug-level notes</li>
     *   <li>{@code JADX RESTORE FROM} — fallback / restore markers</li>
     *   <li>{@code /* JADX} — block-comment JADX annotations (e.g. type mismatch)</li>
     *   <li>{@code // !!} — ad-hoc decompiler error comments sometimes emitted inline</li>
     * </ul>
     */
    private static Map<String, Object> buildMarkerCounts(String source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            result.put("total", 0);
            return result;
        }

        // Patterns and their result keys
        String[][] patterns = {
            {"jadx_warning",       "// JADX WARNING:"},
            {"jadx_info",          "// JADX INFO:"},
            {"jadx_debug",         "// JADX DEBUG:"},
            {"jadx_restore",       "JADX RESTORE FROM"},
            {"jadx_block_comment", "/* JADX"},
            {"inline_error_bang",  "// !!"},
        };

        int total = 0;
        for (String[] kv : patterns) {
            int count = countOccurrences(source, kv[1]);
            result.put(kv[0], count);
            total += count;
        }
        result.put("total", total);
        return result;
    }

    /** Counts non-overlapping occurrences of {@code needle} in {@code haystack}. */
    private static int countOccurrences(String haystack, String needle) {
        if (needle == null || needle.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private String checkClassParam(Context ctx) {
        String className = ctx.queryParam("class_name");
        if (className == null || className.isEmpty()) {
            logger.warn("Missing required parameter 'class_name'");
            ctx.status(400).json(Map.of("error", "Missing required parameter 'class_name'"));
            return null;
        }
        return className;
    }

    private JavaClass findClassByName(String className) {
        if (wrapper == null || className == null || className.isEmpty()) {
            return null;
        }

        try {
            ClassCacheManager.CacheStatus status = ClassCacheManager.getStatus();
            if (status == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
                ClassCacheManager.initCache(wrapper);
                status = ClassCacheManager.getStatus();
            }
            if (status == ClassCacheManager.CacheStatus.READY) {
                JavaClass cachedClass = ClassCacheManager.findClass(ClassCacheManager.getCache(), className);
                if (cachedClass != null) {
                    return cachedClass;
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve class '{}' from cache: {}", className, e.getMessage());
        }

        for (JavaClass cls : wrapper.getClassesWithInners()) {
            if (JadxApiAdapter.matchesClassName(cls, className)) {
                return cls;
            }
        }
        return null;
    }
}
