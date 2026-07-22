package com.zin.delamain.server.routes;

import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.ICodeInfo;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.utils.CodeUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.CodeStore;
import com.zin.delamain.index.UsageGraphIndex;
import com.zin.delamain.index.UsePlacesIndex;
import com.zin.delamain.index.WarmupManager;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.JadxApiAdapter;
import com.zin.delamain.utils.JadxSearchLock;
import com.zin.delamain.utils.PaginationUtils;
import com.zin.delamain.utils.PaginationUtils.PaginationException;
import com.zin.delamain.utils.TicketRegistry;

public class XrefsRoutes {
    private static final Logger logger = LoggerFactory.getLogger(XrefsRoutes.class);
    private final HeadlessJadxWrapper wrapper;
    private final PaginationUtils paginationUtils;

    // ── Async ticket infra (task A: xref-to-ticket) ─────────────────────────────
    // Large-APK xref calls (include_snippet=true with the fast indices not yet ready) can fall
    // back to a full live decompile of every referencing class, which risks the 120s HTTP
    // timeout on big corpora. /submit-xref computes the SAME result as the sync endpoints (via
    // computeClassXrefs/computeMethodXrefs/computeBatchXrefs below) but off the request thread,
    // returning a ticket immediately; /xref-status polls it. Dedicated small pool + daemon
    // threads: xref computation is bounded by JadxSearchLock (one write-lock holder at a time
    // for the live path), so a couple of workers is enough to keep requests moving without
    // piling up unbounded background threads.
    private static final TicketRegistry<Map<String, Object>> XREF_TICKETS = new TicketRegistry<>(600);
    private static final int XREF_CACHE_WAIT_TIMEOUT_SECONDS = 60;
    private static final int MAX_BATCH_SIZE = 10;
    private final ExecutorService xrefExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "jadx-xref-async");
        t.setDaemon(true);
        return t;
    });

    public XrefsRoutes(HeadlessJadxWrapper wrapper, PaginationUtils paginationUtils) {
        this.wrapper = wrapper;
        this.paginationUtils = paginationUtils;
    }

    public void register(Javalin app, AuthConfig authConfig) {
        app.get("/xrefs-to-class",  this::handleXrefsToClass);
        app.get("/xrefs-to-method", this::handleXrefsToMethod);
        app.get("/xrefs-to-field",  this::handleXrefsToField);
        app.get("/batch-xrefs",     this::handleBatchXrefs);
        app.get("/callers-chain",   this::handleCallersChain);
        app.get("/callees-chain",   this::handleCalleesChain);
        app.get("/diag/usage-harvest", this::handleUsageHarvestProbe);
        app.post("/submit-xref",    this::handleSubmitXref);
        app.get("/xref-status",     this::handleXrefStatus);
    }

    /**
     * Forward multi-hop "what does this class transitively call/reach" via instant BFS over the
     * transposed usage graph — the data-flow-direction counterpart of /callers-chain.
     */
    public void handleCalleesChain(Context ctx) {
        String className = validateRequiredParam(ctx, "class_name");
        if (className == null) return;
        int depth = Math.min(Math.max(parseIntParam(ctx.queryParam("depth"), 3), 1), 12);
        int maxNodes = Math.min(Math.max(parseIntParam(ctx.queryParam("max_nodes"), 2000), 1), 50000);
        if (!UsageGraphIndex.isReady()) {
            ctx.status(503).json(Map.of("error", "Usage graph not ready",
                "hint", "Call start-warmup first.", "retry_after", 10));
            return;
        }
        JavaClass target = findClassByName(ctx, className);
        if (target == null) return;
        Map<String, Object> result = UsageGraphIndex.calleesChain(target, depth, maxNodes);
        if (result == null) { ctx.status(404).json(Map.of("error", "Class not in usage graph: " + className)); return; }
        ctx.json(result);
    }

    /**
     * Multi-hop "who can reach this class" via instant BFS over the precomputed usage graph.
     * Answers RE's core navigation question (e.g. "what call paths lead to this crypto class")
     * without decompiling — replaces slow client-side multi-hop xref orchestration.
     */
    public void handleCallersChain(Context ctx) {
        String className = validateRequiredParam(ctx, "class_name");
        if (className == null) return;
        int depth = Math.min(Math.max(parseIntParam(ctx.queryParam("depth"), 3), 1), 12);
        int maxNodes = Math.min(Math.max(parseIntParam(ctx.queryParam("max_nodes"), 2000), 1), 50000);

        if (!UsageGraphIndex.isReady()) {
            ctx.status(503).json(Map.of(
                "error", "Usage graph not ready",
                "hint", "Call start-warmup first; caller-chain requires the precomputed graph.",
                "retry_after", 10));
            return;
        }
        JavaClass target = findClassByName(ctx, className);
        if (target == null) return;
        Map<String, Object> result = UsageGraphIndex.callersChain(target, depth, maxNodes);
        if (result == null) {
            ctx.status(404).json(Map.of("error", "Class not in usage graph: " + className));
            return;
        }
        ctx.json(result);
    }

    /**
     * DIAGNOSTIC PROBE — validates the "precompute usage graph during warmup" hypothesis.
     * After full warmup, measures:
     *   (1) raw getUseIn() cost per class (the graph edges) — should be cheap if usage info
     *       was built during decompile and isn't evicted;
     *   (2) optional precise getUsePlacesFor() cost (decompiles referencing classes) — the
     *       expensive part we'd harvest once and persist.
     * Returns aggregate timings + edge counts + heap delta so we can size a persistent index.
     */
    public void handleUsageHarvestProbe(Context ctx) {
        int limit = parseIntParam(ctx.queryParam("limit"), 2000);
        boolean precise = "true".equalsIgnoreCase(ctx.queryParam("precise"));

        Runtime rt = Runtime.getRuntime();
        long memBefore = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

        List<JavaClass> all = wrapper.getClassesWithInners();
        int n = (limit <= 0) ? all.size() : Math.min(limit, all.size());

        long t0 = System.nanoTime();
        long totalEdges = 0;
        long useInNsTotal = 0, useInNsMax = 0;
        String slowest = null;
        int over1ms = 0, over10ms = 0, over100ms = 0;
        long preciseNsTotal = 0, precisePlaces = 0;
        int errors = 0;

        for (int i = 0; i < n; i++) {
            JavaClass cls = all.get(i);
            long c0 = System.nanoTime();
            List<JavaNode> uses;
            try {
                uses = cls.getUseIn();
            } catch (Exception e) {
                errors++;
                continue;
            }
            long dur = System.nanoTime() - c0;
            useInNsTotal += dur;
            if (dur > useInNsMax) { useInNsMax = dur; slowest = cls.getFullName(); }
            if (dur > 1_000_000L) over1ms++;
            if (dur > 10_000_000L) over10ms++;
            if (dur > 100_000_000L) over100ms++;
            totalEdges += (uses == null ? 0 : uses.size());

            if (precise && uses != null) {
                long p0 = System.nanoTime();
                for (JavaNode u : uses) {
                    if (u instanceof JavaClass) {
                        try {
                            ICodeInfo ci = ((JavaClass) u).getCodeInfo();
                            if (ci != null && ci.hasMetadata()) {
                                precisePlaces += ((JavaClass) u).getUsePlacesFor(ci, cls).size();
                            }
                        } catch (Exception ignored) {}
                    }
                }
                preciseNsTotal += System.nanoTime() - p0;
            }
        }
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;
        long memAfter = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

        Map<String, Object> r = new HashMap<>();
        r.put("classes_probed", n);
        r.put("total_classes", all.size());
        r.put("errors", errors);
        r.put("total_edges", totalEdges);
        r.put("avg_edges_per_class", n > 0 ? (totalEdges / (double) n) : 0);
        r.put("getUseIn_total_ms", useInNsTotal / 1_000_000L);
        r.put("getUseIn_avg_us", n > 0 ? (useInNsTotal / 1000L / n) : 0);
        r.put("getUseIn_max_ms", useInNsMax / 1_000_000L);
        r.put("getUseIn_slowest_class", slowest);
        r.put("getUseIn_calls_over_1ms", over1ms);
        r.put("getUseIn_calls_over_10ms", over10ms);
        r.put("getUseIn_calls_over_100ms", over100ms);
        r.put("precise_enabled", precise);
        r.put("precise_total_ms", preciseNsTotal / 1_000_000L);
        r.put("precise_use_places", precisePlaces);
        r.put("wall_total_ms", totalMs);
        r.put("heap_before_mb", memBefore);
        r.put("heap_after_mb", memAfter);
        r.put("heap_delta_mb", memAfter - memBefore);
        r.put("projected_full_graph_edges", all.size() > 0
            ? (long) (totalEdges / (double) n * all.size()) : 0);
        ctx.json(r);
    }

    // -------------------------------------------------------------------------
    // Route handlers
    // -------------------------------------------------------------------------

    public void handleXrefsToClass(Context ctx) {
        String className = validateRequiredParam(ctx, "class_name");
        if (className == null) return;

        boolean includeSnippet = "true".equalsIgnoreCase(ctx.queryParam("include_snippet"));
        int contextLines = parseIntParam(ctx.queryParam("context_lines"), 3);

        if (!tryAcquireDecompileLock(ctx)) return;
        try {
            JavaClass targetJavaClass = findClassByName(ctx, className);
            if (targetJavaClass == null) return;

            XrefComputeResult computed = computeClassXrefs(targetJavaClass, includeSnippet, contextLines);
            Map<String, Object> result = paginationUtils.handlePagination(
                ctx, computed.references, "xrefs", "references", ref -> ref);
            if (computed.resolution != null) result.put("resolution", computed.resolution);
            if (computed.via != null) result.put("via", computed.via);
            if (computed.hint != null) result.put("hint", computed.hint);
            result.put("distinct_referrer_class_count", countDistinctReferrerClasses(computed.references));
            ctx.json(result);
        } catch (PaginationException e) {
            ctx.status(400).json(Map.of("error", "Pagination error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Internal error in handleXrefsToClass: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error finding class references: " + e.getMessage()));
        } finally {
            JadxSearchLock.releaseRead();
        }
    }

    /**
     * Core computation behind {@code /xrefs-to-class}, extracted so the async {@code /submit-xref}
     * path (see {@link #computeSingleXref}) can share it with the sync handler instead of
     * duplicating the fast-path/precise-path/live-path branching. Must be called with the
     * decompile read lock already held (both callers acquire it around this).
     */
    private XrefComputeResult computeClassXrefs(JavaClass targetJavaClass, boolean includeSnippet, int contextLines) {
        // Cheap referrer set (no decompile) — computed up front so both fast paths below can
        // validate their coverage against it before being trusted (same gate as the batch
        // class-xref path in computeBatchXrefs; see coversAllReferrers). It also feeds the live
        // fallback, so this is not wasted work even when a fast path fires.
        List<JavaClass> classReferences = new ArrayList<>(extractClasses(targetJavaClass.getUseIn()));
        List<JavaMethod> methodReferences = new ArrayList<>(JadxApiAdapter.getClassUseInMethods(targetJavaClass));

        for (JavaMethod javaMethod : targetJavaClass.getMethods()) {
            if (javaMethod.isConstructor()) {
                methodReferences.addAll(extractMethods(javaMethod.getUseIn()));
            }
        }

        // Fast path: precomputed usage graph answers "which classes reference X" instantly,
        // without re-decompiling callers. Precise line numbers are opt-in (include_snippet),
        // which falls through to the live path below. Only trusted when its referrer coverage
        // is a superset of the cheap getUseIn()-based referrer set above — the usage graph is
        // built from class-level getUseIn() edges only and can miss referrers that show up
        // solely via method-level use-tracking (e.g. an anonymous inner class referencing its
        // outer class through a synthetic constructor field). Falling back whenever coverage is
        // incomplete keeps results identical to the live path in every case.
        if (!includeSnippet && UsageGraphIndex.isReady()) {
            List<JavaClass> referrers = UsageGraphIndex.referrersOf(targetJavaClass);
            if (referrers != null && coversAllReferrerClasses(referrers, classReferences, methodReferences)) {
                List<Map<String, Object>> fast = new ArrayList<>(referrers.size());
                for (JavaClass src : referrers) {
                    Map<String, Object> e = new HashMap<>();
                    e.put("class", src.getFullName());
                    e.put("raw_class", JadxApiAdapter.getClassRawName(src));
                    e.put("method", "");
                    e.put("raw_method", "");
                    e.put("from_method", "");
                    e.put("raw_from_method", "");
                    e.put("source_line", null);
                    fast.add(e);
                }
                return new XrefComputeResult(fast, "class-level", "usage-graph",
                    "Class-level referrers from the precomputed usage graph. "
                    + "For precise call sites / line numbers, re-call with include_snippet=true.");
            }
        }

        // Precise fast path: persisted use-places answer "where exactly is X referenced"
        // instantly, without decompiling callers — even right after a FAST_RESTORE when jadx's
        // processed state is still cold. Snippets/lines are reconstructed from the CodeStore.
        // Null ⇒ target unknown to the index ⇒ fall through to the live path below. Same
        // coverage gate as above / as the batch class-xref path: UsePlacesIndex is harvested
        // from UsageGraphIndex's coarser edges and can drop the same synthetic-field referrers.
        if (includeSnippet && UsePlacesIndex.isReady()) {
            List<Map<String, Object>> persisted = buildPersistedPreciseClassRefs(targetJavaClass);
            if (persisted != null && coversAllReferrers(persisted, classReferences, methodReferences)) {
                attachSnippets(persisted, contextLines);
                return new XrefComputeResult(persisted, "precise", "use-places-store",
                    "Precise call sites from the persisted use-places index "
                    + "(instant after restart; no caller decompile needed).");
            }
        }

        Set<String> existingClassNames = new HashSet<>();
        for (JavaClass cls : classReferences) {
            existingClassNames.add(cls.getFullName());
        }
        for (JavaMethod mth : methodReferences) {
            JavaClass parentClass = mth.getDeclaringClass();
            if (parentClass != null && !existingClassNames.contains(parentClass.getFullName())) {
                classReferences.add(parentClass);
                existingClassNames.add(parentClass.getFullName());
            }
        }

        List<Map<String, Object>> referenceList =
            collectPreciseReferences(targetJavaClass, classReferences, methodReferences);

        if (includeSnippet) {
            attachSnippets(referenceList, contextLines);
        }
        // Live fallback (fast indices missing/incomplete): collectPreciseReferences resolves each
        // referrer to its most precise available granularity (exact call-site position when
        // getUsePlacesFor finds one, else the referencing method, else the class itself) by
        // decompiling callers on this request thread — unlike the two fast paths above, which are
        // uniformly class-level or index-precomputed. Tag it explicitly instead of leaving
        // resolution/via null, so callers can't mistake row-count differences from the fast paths
        // for a data-loss bug (see distinct_referrer_class_count for the same referrer set).
        return new XrefComputeResult(referenceList, "live-method-level", "live-decompile",
            "Per-reference-site results from a live decompile of each referrer "
            + "(mixed position/method/class granularity depending on what's resolvable). "
            + "Row count reflects reference sites, not distinct referrer classes — "
            + "see distinct_referrer_class_count.");
    }

    public void handleXrefsToMethod(Context ctx) {
        String className = validateRequiredParam(ctx, "class_name");
        String methodName = validateRequiredParam(ctx, "method_name");
        if (className == null || methodName == null) return;

        boolean includeSnippet = "true".equalsIgnoreCase(ctx.queryParam("include_snippet"));
        int contextLines = parseIntParam(ctx.queryParam("context_lines"), 3);

        if (!tryAcquireDecompileLock(ctx)) return;
        try {
            JavaClass containingClass = findClassByName(ctx, className);
            if (containingClass == null) return;

            List<JavaMethod> matchedMethods = findMethodsByName(ctx, containingClass, methodName);
            if (matchedMethods == null) return;

            List<JavaMethod> relatedMethods = new ArrayList<>();
            for (JavaMethod baseMethod : matchedMethods) {
                for (JavaMethod m : getMethodWithOverrides(baseMethod)) {
                    if (!relatedMethods.contains(m)) {
                        relatedMethods.add(m);
                    }
                }
            }

            XrefComputeResult computed = computeMethodXrefs(relatedMethods, includeSnippet, contextLines);
            Map<String, Object> result = paginationUtils.handlePagination(
                ctx, computed.references, "xrefs", "references", ref -> ref);
            if (computed.resolution != null) result.put("resolution", computed.resolution);
            if (computed.via != null) result.put("via", computed.via);
            if (computed.hint != null) result.put("hint", computed.hint);
            result.put("distinct_referrer_class_count", countDistinctReferrerClasses(computed.references));
            ctx.json(result);
        } catch (PaginationException e) {
            ctx.status(400).json(Map.of("error", "Pagination error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Internal error in handleXrefsToMethod: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error finding method references: " + e.getMessage()));
        } finally {
            JadxSearchLock.releaseRead();
        }
    }

    /**
     * Core computation behind {@code /xrefs-to-method}, extracted so {@link #computeSingleXref}
     * (the {@code /submit-xref} async path) can share it with the sync handler.
     */
    private XrefComputeResult computeMethodXrefs(
            List<JavaMethod> relatedMethods, boolean includeSnippet, int contextLines) {
        // Fast path: getUseIn() (the referrer set) is cheap; the >60s cost was the precise
        // line resolution that decompiles every caller. When snippets aren't requested,
        // return referrer class+method names directly without decompiling callers.
        if (!includeSnippet) {
            List<Map<String, Object>> fast = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JavaMethod relatedMethod : relatedMethods) {
                for (JavaMethod ref : extractMethods(relatedMethod.getUseIn())) {
                    JavaClass dc = ref.getDeclaringClass();
                    String cls = dc != null ? dc.getFullName() : "";
                    String mth = ref.getName();
                    if (!seen.add(cls + "#" + mth)) continue;
                    Map<String, Object> e = new HashMap<>();
                    e.put("class", cls);
                    e.put("raw_class", dc != null ? JadxApiAdapter.getClassRawName(dc) : "");
                    e.put("method", mth);
                    e.put("raw_method", JadxApiAdapter.getMethodRawName(ref));
                    e.put("from_method", "");
                    e.put("source_line", null);
                    fast.add(e);
                }
            }
            return new XrefComputeResult(fast, "method-level", "use-graph",
                "Caller class+method from usage info (no line numbers). "
                + "For precise call sites, re-call with include_snippet=true.");
        }

        List<Map<String, Object>> referenceList = new ArrayList<>();
        Set<String> seenReferences = new HashSet<>();
        for (JavaMethod relatedMethod : relatedMethods) {
            mergeReferences(
                referenceList,
                seenReferences,
                collectMethodReferences(relatedMethod, extractMethods(relatedMethod.getUseIn()))
            );
        }
        attachSnippets(referenceList, contextLines);
        // Live-decompile fallback for method xrefs — tag explicitly like the class-xref live path
        // so row-count differences aren't mistaken for data loss (see distinct_referrer_class_count).
        return new XrefComputeResult(referenceList, "live-method-level", "live-decompile",
            "Per-reference-site results from a live decompile of each referrer; row count reflects "
            + "reference sites, not distinct referrer classes (see distinct_referrer_class_count).");
    }

    public void handleXrefsToField(Context ctx) {
        String className = validateRequiredParam(ctx, "class_name");
        String fieldName = validateRequiredParam(ctx, "field_name");
        if (className == null || fieldName == null) return;

        if (!tryAcquireDecompileLock(ctx)) return;
        try {
            JavaClass containingClass = findClassByName(ctx, className);
            if (containingClass == null) return;

            JavaField targetField = findFieldByName(ctx, containingClass, fieldName);
            if (targetField == null) return;

            sendXrefsResponse(ctx, computeFieldXrefs(targetField));
        } catch (PaginationException e) {
            ctx.status(400).json(Map.of("error", "Pagination error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Internal error in handleXrefsToField: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error finding field references: " + e.getMessage()));
        } finally {
            JadxSearchLock.releaseRead();
        }
    }

    public void handleBatchXrefs(Context ctx) {
        String targetsParam = ctx.queryParam("targets");
        if (targetsParam == null || targetsParam.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing 'targets' parameter. Format: type:class[:member]"));
            return;
        }

        String[] targets = targetsParam.split(",");
        if (targets.length > MAX_BATCH_SIZE) {
            ctx.status(400).json(Map.of("error", "Too many targets. Maximum " + MAX_BATCH_SIZE + " per request."));
            return;
        }

        if (!tryAcquireDecompileLock(ctx)) return;
        try {
            if (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
                ClassCacheManager.initCache(wrapper);
            }

            ClassCacheManager.CacheStatus status = ClassCacheManager.getStatus();
            if (status == ClassCacheManager.CacheStatus.LOADING) {
                Map<String, Object> health = ClassCacheManager.getHealthInfo();
                Map<String, Object> response = new HashMap<>();
                response.put("status", "loading");
                response.put("type", "batch-xrefs");
                response.put("message", "Class cache is being loaded in background.");
                response.put("retry_after", 10);
                response.put("health", health);
                ctx.status(503).json(response);
                return;
            }

            ctx.json(computeBatchXrefs(targets));
        } catch (Exception e) {
            logger.error("Internal error in batch xrefs: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error in batch xrefs: " + e.getMessage()));
        } finally {
            JadxSearchLock.releaseRead();
        }
    }

    /**
     * Core computation behind {@code /batch-xrefs}, extracted so the async {@code /submit-xref}
     * batch mode (see {@link #handleSubmitXref}) can share it. Assumes {@link ClassCacheManager}
     * is READY and the decompile read lock is already held by the caller.
     */
    private Map<String, Object> computeBatchXrefs(String[] targets) throws Exception {
        Map<String, JavaClass> classMap = ClassCacheManager.getCache();
        List<Map<String, Object>> results = new ArrayList<>();

        for (String target : targets) {
            String trimmed = target.trim();
            String[] parts = trimmed.split(":", 3);

            Map<String, Object> result = new HashMap<>();
            result.put("target", trimmed);

            if (parts.length < 2) {
                result.put("found", false);
                result.put("error", "Invalid format. Use type:class[:member]");
                results.add(result);
                continue;
            }

            String type = parts[0].toLowerCase();
            String className = parts[1];

            JavaClass targetClass = ClassCacheManager.findClass(classMap, className);

            if (targetClass == null) {
                result.put("found", false);
                result.put("error", "Class not found");
                results.add(result);
                continue;
            }

            List<Map<String, Object>> xrefs = new ArrayList<>();
            boolean matched = false;
            // Populated per-branch below, then attached to `result` alongside xrefs/xrefs_count —
            // mirrors the resolution/via fields the single-target endpoints already expose, so
            // batch callers get the same "how was this computed / what granularity" signal instead
            // of a silent count with no way to tell class-level from position/method-level rows.
            String resolution = null;
            String via = null;

            try {
                switch (type) {
                    case "class": {
                        matched = true;
                        // getUseIn()/getClassUseInMethods() are cheap (no decompile — see the
                        // /xrefs-to-method fast-path comment below); compute the referrer set up front
                        // either way, since it's also needed for the live fallback and for validating
                        // the fast path below.
                        List<JavaMethod> methodRefs = new ArrayList<>(JadxApiAdapter.getClassUseInMethods(targetClass));
                        for (JavaMethod javaMethod : targetClass.getMethods()) {
                            if (javaMethod.isConstructor()) {
                                methodRefs.addAll(extractMethods(javaMethod.getUseIn()));
                            }
                        }
                        List<JavaClass> classRefs = new ArrayList<>(extractClasses(targetClass.getUseIn()));

                        // Share the same precise-index fast path as /xrefs-to-class?include_snippet=true
                        // (buildPersistedPreciseClassRefs, see computeClassXrefs above). Only trust it
                        // when its referrer coverage is a superset of the cheap getUseIn()-based referrer
                        // set: UsePlacesIndex is harvested from UsageGraphIndex's coarser edges and can
                        // miss referrers that only show up via method-level use-tracking (e.g. an
                        // anonymous inner class that references its outer class solely through a
                        // synthetic constructor field — getUsePlacesFor finds no textual position for it,
                        // so the index harvest drops it entirely, while collectPreciseReferences below
                        // still surfaces it via extractMethodReferenceInfo). Falling back whenever
                        // coverage is incomplete keeps the result identical to the pre-fast-path
                        // behavior in every case; the fast path only fires when it's provably complete.
                        List<Map<String, Object>> persistedClassRefs =
                            UsePlacesIndex.isReady() ? buildPersistedPreciseClassRefs(targetClass) : null;
                        if (persistedClassRefs != null && coversAllReferrers(persistedClassRefs, classRefs, methodRefs)) {
                            xrefs = persistedClassRefs;
                            resolution = "precise";
                            via = "use-places-store";
                            break;
                        }
                        xrefs = collectPreciseReferences(targetClass, classRefs, methodRefs);
                        resolution = "live-method-level";
                        via = "live-decompile";
                        break;
                    }

                    case "method":
                        if (parts.length < 3) {
                            result.put("found", false);
                            result.put("error", "Method name required");
                            results.add(result);
                            continue;
                        }
                        String methodName = parts[2];
                        Set<String> seenMethodRefs = new HashSet<>();
                        for (JavaMethod method : targetClass.getMethods()) {
                            if (JadxApiAdapter.matchesMethodName(method, methodName)) {
                                matched = true;
                                mergeReferences(
                                    xrefs,
                                    seenMethodRefs,
                                    collectMethodReferences(method, extractMethods(method.getUseIn()))
                                );
                            }
                        }
                        if (!matched) {
                            result.put("found", false);
                            result.put("error", "Method '" + methodName + "' not found in class " + className);
                            results.add(result);
                            continue;
                        }
                        resolution = "live-method-level";
                        via = "live-decompile";
                        break;

                    case "field":
                        if (parts.length < 3) {
                            result.put("found", false);
                            result.put("error", "Field name required");
                            results.add(result);
                            continue;
                        }
                        String fieldName = parts[2];
                        Set<String> seenFieldRefs = new HashSet<>();
                        for (JavaField field : targetClass.getFields()) {
                            if (JadxApiAdapter.matchesFieldName(field, fieldName)) {
                                matched = true;
                                mergeReferences(
                                    xrefs,
                                    seenFieldRefs,
                                    collectMethodReferences(field, extractMethods(field.getUseIn()))
                                );
                            }
                        }
                        if (!matched) {
                            result.put("found", false);
                            result.put("error", "Field '" + fieldName + "' not found in class " + className);
                            results.add(result);
                            continue;
                        }
                        resolution = "live-method-level";
                        via = "live-decompile";
                        break;

                    default:
                        result.put("found", false);
                        result.put("error", "Invalid type. Use class/method/field");
                        results.add(result);
                        continue;
                }

                result.put("found", true);
                result.put("xrefs_count", xrefs.size());
                result.put("xrefs", xrefs);
                result.put("distinct_referrer_class_count", countDistinctReferrerClasses(xrefs));
                if (resolution != null) result.put("resolution", resolution);
                if (via != null) result.put("via", via);
            } catch (Exception e) {
                result.put("found", false);
                result.put("error", "Failed: " + e.getMessage());
            }

            results.add(result);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("results", results);
        response.put("total", targets.length);
        return response;
    }

    /**
     * True if every referrer in {@code classRefs}/{@code methodRefs} (the cheap getUseIn()-based
     * referrer set — no decompile) is represented in {@code persisted} (the UsePlacesIndex fast-path
     * result). Used to gate the batch class-xref fast path in {@link #computeBatchXrefs} and the
     * single-target fast paths in {@link #computeClassXrefs}: the persisted index is harvested from
     * {@link UsageGraphIndex}'s edges, which can be coarser than the method-level use-tracking
     * {@link #collectPreciseReferences} falls back on, so trusting it unconditionally can silently
     * drop real referrers. Returning false forces the live fallback.
     */
    private boolean coversAllReferrers(
            List<Map<String, Object>> persisted, List<JavaClass> classRefs, List<JavaMethod> methodRefs) {
        Set<String> covered = new HashSet<>();
        for (Map<String, Object> e : persisted) {
            Object raw = e.get("raw_class");
            if (raw != null) covered.add(raw.toString());
        }
        return coversAllReferrers(covered, classRefs, methodRefs);
    }

    /**
     * Same coverage check as above, for the {@code /xrefs-to-class?include_snippet=false} fast
     * path whose candidate result is a {@code List<JavaClass>} (class-level referrers from
     * {@link UsageGraphIndex}) rather than a list of persisted-index maps. Named distinctly from
     * the {@code List<Map<String,Object>>} overload above since both erase to {@code (List, List,
     * List)} and Java can't overload on generic type parameters alone.
     */
    private boolean coversAllReferrerClasses(
            List<JavaClass> referrerClasses, List<JavaClass> classRefs, List<JavaMethod> methodRefs) {
        Set<String> covered = new HashSet<>();
        for (JavaClass c : referrerClasses) {
            covered.add(JadxApiAdapter.getClassRawName(c));
        }
        return coversAllReferrers(covered, classRefs, methodRefs);
    }

    private boolean coversAllReferrers(Set<String> covered, List<JavaClass> classRefs, List<JavaMethod> methodRefs) {
        for (JavaClass c : classRefs) {
            if (!covered.contains(JadxApiAdapter.getClassRawName(c))) return false;
        }
        for (JavaMethod m : methodRefs) {
            JavaClass parent = m.getDeclaringClass();
            if (parent != null && !covered.contains(JadxApiAdapter.getClassRawName(parent))) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Async ticket handlers (task A: xref-to-ticket)
    // -------------------------------------------------------------------------

    /**
     * Handles {@code POST /submit-xref}. Async counterpart of xrefs-to-class / xrefs-to-method /
     * xrefs-to-field / batch-xrefs — computes the exact same result via the compute* helpers
     * above, but off the request thread, so large-APK xrefs that would risk the 120s HTTP timeout
     * on the sync endpoints (include_snippet=true before the fast indices are ready, or any
     * heavy batch item) return a ticket immediately instead. Poll with {@code GET /xref-status}.
     *
     * <p>Two request shapes, mirroring the sync endpoints:</p>
     * <ul>
     *   <li>batch: {@code targets=type:class[:member],...} — same format/limit as
     *       {@code /batch-xrefs}.</li>
     *   <li>single: {@code target_type=class|method|field&class_name=...&member_name=...}
     *       (+ optional {@code include_snippet}, {@code context_lines}) — mirrors
     *       {@code /xrefs-to-class|method|field}.</li>
     * </ul>
     *
     * <p>When the target's fast index is already ready (precomputed usage graph / persisted
     * use-places for class targets; the use-graph-only path for method targets with
     * {@code include_snippet=false}), the computation is cheap enough to run inline here — the
     * response comes back with {@code status=done} on this very call, mirroring
     * {@code submit-code-search}'s cached-result fast return. Otherwise the ticket comes back
     * {@code status=submitted} and the work continues on a background pool.</p>
     */
    public void handleSubmitXref(Context ctx) {
        String targetsParam = ctx.queryParam("targets");
        if (targetsParam != null && !targetsParam.isEmpty()) {
            String[] targets = targetsParam.split(",");
            if (targets.length > MAX_BATCH_SIZE) {
                ctx.status(400).json(Map.of("error", "Too many targets. Maximum " + MAX_BATCH_SIZE + " per request."));
                return;
            }
            submitAsync(ctx, () -> {
                waitForClassCacheReady();
                if (!JadxSearchLock.tryAcquireRead(XREF_CACHE_WAIT_TIMEOUT_SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for the decompile lock");
                }
                try {
                    Map<String, Object> result = computeBatchXrefs(targets);
                    result.put("kind", "batch");
                    return result;
                } finally {
                    JadxSearchLock.releaseRead();
                }
            });
            return;
        }

        String targetType = ctx.queryParam("target_type");
        String className = ctx.queryParam("class_name");
        String memberName = ctx.queryParam("member_name");
        boolean includeSnippet = "true".equalsIgnoreCase(ctx.queryParam("include_snippet"));
        int contextLines = parseIntParam(ctx.queryParam("context_lines"), 3);

        if (targetType == null || targetType.isEmpty()) {
            ctx.status(400).json(Map.of("error",
                "Missing 'target_type' (class|method|field), or 'targets' for batch mode"));
            return;
        }
        if (className == null || className.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter 'class_name'"));
            return;
        }
        String type = targetType.toLowerCase();
        if (!type.equals("class") && !type.equals("method") && !type.equals("field")) {
            ctx.status(400).json(Map.of("error", "Invalid 'target_type': " + targetType + ". Use class/method/field."));
            return;
        }
        if ((type.equals("method") || type.equals("field")) && (memberName == null || memberName.isEmpty())) {
            ctx.status(400).json(Map.of("error", "'member_name' is required for target_type=" + type));
            return;
        }

        // Fast-path short-circuit: if the target's precomputed index is ready, compute inline
        // (bounded, no caller decompile) and return status=done immediately — no reason to make
        // the caller round-trip through a poll for a lookup that's already effectively instant.
        if (isXrefFastPathLikely(type, includeSnippet) && JadxSearchLock.tryAcquireRead()) {
            try {
                Map<String, Object> result = computeSingleXref(type, className, memberName, includeSnippet, contextLines);
                String ticket = XREF_TICKETS.register(CompletableFuture.completedFuture(result));
                ctx.json(Map.of("ticket", ticket, "status", "done", "retry_after_seconds", 0));
                return;
            } catch (Exception e) {
                logger.debug("Inline fast-path xref compute failed, falling back to async: {}", e.getMessage());
            } finally {
                JadxSearchLock.releaseRead();
            }
        }

        submitAsync(ctx, () -> {
            if (!JadxSearchLock.tryAcquireRead(XREF_CACHE_WAIT_TIMEOUT_SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the decompile lock");
            }
            try {
                return computeSingleXref(type, className, memberName, includeSnippet, contextLines);
            } finally {
                JadxSearchLock.releaseRead();
            }
        });
    }

    /**
     * Handles {@code GET /xref-status}. Polls a ticket from {@link #handleSubmitXref}; applies
     * offset/count pagination (from THIS request's own query params, not the original submit
     * call) to single-target results at poll time, same convention as
     * {@code /code-search-status}.
     */
    public void handleXrefStatus(Context ctx) {
        String ticket = ctx.queryParam("ticket");
        if (ticket == null || ticket.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing 'ticket' parameter"));
            return;
        }
        int offset = paginationUtils.getIntParam(ctx, "offset", 0);
        int count = paginationUtils.getIntParam(ctx, "count", 100);
        if (offset < 0) {
            ctx.status(400).json(Map.of("error", "'offset' must be >= 0, got: " + offset));
            return;
        }
        if (count < 0) {
            ctx.status(400).json(Map.of("error", "'count' must be >= 0, got: " + count));
            return;
        }

        TicketRegistry.PollResult<Map<String, Object>> poll = XREF_TICKETS.poll(ticket);
        switch (poll.getStatus()) {
            case DONE:
                try {
                    ctx.json(buildXrefStatusResponse(ctx, poll.getResult()));
                } catch (PaginationException e) {
                    ctx.status(400).json(Map.of("error", "Pagination error: " + e.getMessage()));
                }
                break;
            case RUNNING:
                ctx.json(Map.of("status", "running", "retry_after_seconds", 3,
                    "message", "Xref resolution in progress. Poll again shortly."));
                break;
            case NOT_FOUND:
                ctx.status(404).json(Map.of("status", "not_found", "message", "Ticket not found or expired"));
                break;
            case ERROR:
            default: {
                Throwable err = poll.getError();
                if (err instanceof XrefLookupException) {
                    ctx.status(404).json(Map.of("status", "not_found", "message", err.getMessage()));
                } else {
                    ctx.status(500).json(Map.of("status", "error",
                        "message", err != null && err.getMessage() != null ? err.getMessage() : "Unknown error"));
                }
                break;
            }
        }
    }

    private Map<String, Object> buildXrefStatusResponse(Context ctx, Map<String, Object> payload) throws PaginationException {
        if ("batch".equals(payload.get("kind"))) {
            // Batch results are already complete and bounded (max MAX_BATCH_SIZE targets) — no
            // extra pagination needed, same as the sync /batch-xrefs response shape.
            Map<String, Object> out = new HashMap<>(payload);
            out.remove("kind");
            out.put("status", "done");
            return out;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> references = (List<Map<String, Object>>) payload.get("references");
        Map<String, Object> result = paginationUtils.handlePagination(ctx, references, "xrefs", "references", ref -> ref);
        result.put("status", "done");
        if (payload.get("resolution") != null) result.put("resolution", payload.get("resolution"));
        if (payload.get("via") != null) result.put("via", payload.get("via"));
        if (payload.get("hint") != null) result.put("hint", payload.get("hint"));
        result.put("distinct_referrer_class_count", countDistinctReferrerClasses(references));
        return result;
    }

    /** Submits work to the xref background pool and registers/returns a ticket (status=submitted). */
    private void submitAsync(Context ctx, java.util.concurrent.Callable<Map<String, Object>> work) {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        xrefExecutor.submit(() -> {
            try {
                future.complete(work.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        String ticket = XREF_TICKETS.register(future);
        ctx.json(Map.of("ticket", ticket, "status", "submitted", "retry_after_seconds", 3));
    }

    private void waitForClassCacheReady() throws InterruptedException {
        if (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
            ClassCacheManager.initCache(wrapper);
        }
        long deadline = System.currentTimeMillis() + XREF_CACHE_WAIT_TIMEOUT_SECONDS * 1000L;
        while (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.LOADING) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Timed out waiting for class cache to finish loading");
            }
            Thread.sleep(500);
        }
    }

    private boolean isXrefFastPathLikely(String type, boolean includeSnippet) {
        switch (type) {
            case "class":
                return (!includeSnippet && UsageGraphIndex.isReady()) || (includeSnippet && UsePlacesIndex.isReady());
            case "method":
                return !includeSnippet;
            default:
                return false;
        }
    }

    /**
     * Core computation for a single (non-batch) {@code /submit-xref} target. Assumes the
     * decompile read lock is already held by the caller (mirrors computeClassXrefs /
     * computeMethodXrefs above). Throws {@link XrefLookupException} when the class/method/field
     * can't be resolved, which {@link #handleXrefStatus} surfaces as a 404 poll result.
     */
    private Map<String, Object> computeSingleXref(
            String type, String className, String memberName, boolean includeSnippet, int contextLines) {
        JavaClass targetClass = findClassByNameCore(className);
        if (targetClass == null) {
            throw new XrefLookupException("Class " + className + " not found.");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", "single");

        switch (type) {
            case "class": {
                XrefComputeResult computed = computeClassXrefs(targetClass, includeSnippet, contextLines);
                putComputeResult(payload, computed);
                break;
            }
            case "method": {
                List<JavaMethod> matchedMethods = findMethodsByNameCore(targetClass, memberName);
                if (matchedMethods.isEmpty()) {
                    throw new XrefLookupException(
                        "Method " + memberName + " not found in class " + targetClass.getFullName());
                }
                List<JavaMethod> relatedMethods = new ArrayList<>();
                for (JavaMethod baseMethod : matchedMethods) {
                    for (JavaMethod m : getMethodWithOverrides(baseMethod)) {
                        if (!relatedMethods.contains(m)) relatedMethods.add(m);
                    }
                }
                XrefComputeResult computed = computeMethodXrefs(relatedMethods, includeSnippet, contextLines);
                putComputeResult(payload, computed);
                break;
            }
            case "field": {
                JavaField targetField = findFieldByNameCore(targetClass, memberName);
                if (targetField == null) {
                    throw new XrefLookupException(
                        "Field " + memberName + " not found in class " + targetClass.getFullName());
                }
                payload.put("references", computeFieldXrefs(targetField));
                break;
            }
            default:
                throw new XrefLookupException("Invalid target_type: " + type);
        }
        return payload;
    }

    private void putComputeResult(Map<String, Object> payload, XrefComputeResult computed) {
        payload.put("references", computed.references);
        if (computed.resolution != null) payload.put("resolution", computed.resolution);
        if (computed.via != null) payload.put("via", computed.via);
        if (computed.hint != null) payload.put("hint", computed.hint);
    }

    /** Signals "target not found" from async xref computation, surfaced as a 404 poll result. */
    private static final class XrefLookupException extends RuntimeException {
        XrefLookupException(String message) {
            super(message);
        }
    }

    /** Result of a single-target xref computation: the reference list plus optional metadata
     * (resolution/via/hint), shared by both the sync handlers and the async ticket path. */
    private static final class XrefComputeResult {
        final List<Map<String, Object>> references;
        final String resolution;
        final String via;
        final String hint;

        XrefComputeResult(List<Map<String, Object>> references, String resolution, String via, String hint) {
            this.references = references;
            this.resolution = resolution;
            this.via = via;
            this.hint = hint;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String validateRequiredParam(Context ctx, String paramName) {
        String value = ctx.queryParam(paramName);
        if (value == null || value.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter '" + paramName + "'"));
            return null;
        }
        return value;
    }

    private JavaClass findClassByName(Context ctx, String className) {
        JavaClass cls = findClassByNameCore(className);
        if (cls == null) {
            ctx.status(404).json(Map.of("error", "Class " + className + " not found."));
        }
        return cls;
    }

    /** Ctx-less core of {@link #findClassByName}, reused by the async {@code /submit-xref} path
     * (which builds its own error payload from an {@link XrefLookupException} instead). */
    private JavaClass findClassByNameCore(String className) {
        try {
            if (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
                ClassCacheManager.initCache(wrapper);
            }
            Map<String, JavaClass> classMap = ClassCacheManager.getCache();
            return ClassCacheManager.findClass(classMap, className);
        } catch (Exception e) {
            logger.warn("Failed to use class cache: {}", e.getMessage());
            return null;
        }
    }

    private List<JavaMethod> findMethodsByName(Context ctx, JavaClass javaClass, String methodName) {
        List<JavaMethod> matchedMethods = findMethodsByNameCore(javaClass, methodName);
        if (matchedMethods.isEmpty()) {
            ctx.status(404).json(Map.of("error", "Method " + methodName + " not found in class " + javaClass.getFullName()));
            return null;
        }
        return matchedMethods;
    }

    /** Ctx-less core of {@link #findMethodsByName}; returns an empty (never null) list on no match. */
    private List<JavaMethod> findMethodsByNameCore(JavaClass javaClass, String methodName) {
        List<JavaMethod> matchedMethods = new ArrayList<>();
        String simpleClassName = javaClass.getName();
        String rawSimpleClassName = JadxApiAdapter.getClassRawSimpleName(javaClass);
        for (JavaMethod method : javaClass.getMethods()) {
            if (!method.isConstructor() && JadxApiAdapter.matchesMethodName(method, methodName)) {
                matchedMethods.add(method);
            } else if (method.isConstructor()
                    && (methodName.equals(simpleClassName)
                    || (rawSimpleClassName != null && methodName.equals(rawSimpleClassName)))) {
                matchedMethods.add(method);
            }
        }
        return matchedMethods;
    }

    private JavaField findFieldByName(Context ctx, JavaClass javaClass, String fieldName) {
        JavaField field = findFieldByNameCore(javaClass, fieldName);
        if (field == null) {
            ctx.status(404).json(Map.of("error", "Field " + fieldName + " not found in class " + javaClass.getFullName()));
        }
        return field;
    }

    /** Ctx-less core of {@link #findFieldByName}. */
    private JavaField findFieldByNameCore(JavaClass javaClass, String fieldName) {
        for (JavaField field : javaClass.getFields()) {
            if (JadxApiAdapter.matchesFieldName(field, fieldName)) return field;
        }
        return null;
    }

    /** Core computation behind {@code /xrefs-to-field}, shared with the async single-target path. */
    private List<Map<String, Object>> computeFieldXrefs(JavaField targetField) {
        return collectMethodReferences(targetField, extractMethods(targetField.getUseIn()));
    }

    private List<JavaMethod> extractMethods(List<JavaNode> nodes) {
        List<JavaMethod> methods = new ArrayList<>();
        if (nodes == null || nodes.isEmpty()) return methods;
        for (JavaNode node : nodes) {
            if (node instanceof JavaMethod) methods.add((JavaMethod) node);
        }
        return methods;
    }

    private List<JavaClass> extractClasses(List<JavaNode> nodes) {
        List<JavaClass> classes = new ArrayList<>();
        if (nodes == null || nodes.isEmpty()) return classes;
        for (JavaNode node : nodes) {
            if (node instanceof JavaClass) classes.add((JavaClass) node);
        }
        return classes;
    }

    private List<Map<String, Object>> collectMethodReferences(JavaNode targetNode, List<JavaMethod> methodNodes) {
        return collectPreciseReferences(targetNode, Collections.emptyList(), methodNodes);
    }

    private void addIfUnique(List<Map<String, Object>> list, Set<String> seen, Map<String, Object> item) {
        if (item != null) {
            String key = item.get("class") + "#" + item.get("method") + "#"
                + String.valueOf(item.getOrDefault("source_line", "")) + "#"
                + String.valueOf(item.getOrDefault("code_snippet", ""));
            if (!seen.contains(key)) {
                seen.add(key);
                list.add(item);
            }
        }
    }

    private void mergeReferences(
            List<Map<String, Object>> target,
            Set<String> seen,
            List<Map<String, Object>> additions) {
        for (Map<String, Object> addition : additions) {
            addIfUnique(target, seen, addition);
        }
    }

    private Map<String, Object> extractMethodReferenceInfo(JavaMethod method) {
        if (method == null) return null;
        try {
            Map<String, Object> refInfo = new HashMap<>();
            JavaClass parent = method.getDeclaringClass();
            JavaClass parentJavaClass = null;
            if (parent != null) {
                refInfo.put("class", parent.getFullName());
                refInfo.put("raw_class", JadxApiAdapter.getClassRawName(parent));
                parentJavaClass = ensureClassDecompiled(parent);
            }
            String name = method.isClassInit() ? "" : method.getName();
            if ("<clinit>".equals(name)) name = "";
            refInfo.put("method", name);
            refInfo.put("raw_method", JadxApiAdapter.getMethodRawName(method));
            JadxApiAdapter.MethodInfoSnapshot methodInfo = JadxApiAdapter.getMethodInfo(method);
            refInfo.put("from_method", methodInfo != null ? methodInfo.getFullId() : method.getFullName());
            refInfo.put("raw_from_method", JadxApiAdapter.getMethodRawFullId(method));
            refInfo.put("source_line", resolveDefinitionSourceLine(parentJavaClass, method.getDefPos()));
            return refInfo;
        } catch (Exception e) {
            logger.warn("Failed to extract reference info: {}", e.getMessage());
            return null;
        }
    }

    private JavaClass ensureClassDecompiled(JavaClass javaClass) {
        if (javaClass == null) return null;
        if (!JadxApiAdapter.isProcessComplete(javaClass)) {
            try {
                javaClass.decompile();
            } catch (Exception e) {
                logger.warn("Failed to decompile class {}: {}", javaClass.getFullName(), e.getMessage());
            }
        }
        return javaClass;
    }

    private List<Map<String, Object>> collectPreciseReferences(
            JavaNode targetNode,
            List<JavaClass> classNodes,
            List<JavaMethod> methodNodes) {
        LinkedHashMap<String, JavaClass> topUseClasses = new LinkedHashMap<>();
        for (JavaClass classNode : classNodes) {
            JavaClass javaClass = ensureClassDecompiled(classNode);
            if (javaClass != null) {
                topUseClasses.put(javaClass.getFullName(), javaClass);
            }
        }
        for (JavaMethod methodNode : methodNodes) {
            JavaClass parentClass = methodNode.getDeclaringClass();
            if (parentClass == null) continue;
            JavaClass javaClass = ensureClassDecompiled(parentClass);
            if (javaClass != null) {
                topUseClasses.put(javaClass.getFullName(), javaClass);
            }
        }

        List<Map<String, Object>> referenceList = new ArrayList<>();
        Set<String> seenReferences = new HashSet<>();

        for (JavaClass topUseClass : topUseClasses.values()) {
            boolean preciseAdded = collectUsePlacesForClass(targetNode, topUseClass, referenceList, seenReferences);
            if (preciseAdded) continue;

            boolean hasMethodReferenceInClass = false;
            for (JavaMethod methodNode : methodNodes) {
                if (methodNode.getDeclaringClass() != null
                        && topUseClass.getFullName().equals(methodNode.getDeclaringClass().getFullName())) {
                    hasMethodReferenceInClass = true;
                    addIfUnique(referenceList, seenReferences, extractMethodReferenceInfo(methodNode));
                }
            }

            if (!hasMethodReferenceInClass) {
                Map<String, Object> classRefInfo = new HashMap<>();
                classRefInfo.put("class", topUseClass.getFullName());
                classRefInfo.put("raw_class", JadxApiAdapter.getClassRawName(topUseClass));
                classRefInfo.put("method", "");
                classRefInfo.put("raw_method", "");
                classRefInfo.put("from_method", "");
                classRefInfo.put("raw_from_method", "");
                classRefInfo.put("source_line", resolveDefinitionSourceLine(topUseClass, topUseClass.getDefPos()));
                addIfUnique(referenceList, seenReferences, classRefInfo);
            }
        }
        return referenceList;
    }

    private List<JavaMethod> getMethodWithOverrides(JavaMethod javaMethod) {
        List<JavaMethod> related = javaMethod.getOverrideRelatedMethods();
        return (related != null && !related.isEmpty()) ? related : Collections.singletonList(javaMethod);
    }

    private boolean collectUsePlacesForClass(
            JavaNode targetNode,
            JavaClass topUseClass,
            List<Map<String, Object>> referenceList,
            Set<String> seenReferences) {
        try {
            ICodeInfo codeInfo = topUseClass.getCodeInfo();
            if (codeInfo == null || !codeInfo.hasMetadata()) return false;

            List<Integer> usePositions = topUseClass.getUsePlacesFor(codeInfo, targetNode);
            if (usePositions.isEmpty()) return false;

            String code = codeInfo.getCodeStr();
            boolean added = false;

            for (int pos : usePositions) {
                String line = CodeUtils.getLineForPos(code, pos).trim();
                if (line.startsWith("import ")) continue;
                // headless: enclosingNode not available — pass null
                JavaNode enclosingNode = null;
                Map<String, Object> refInfo = buildPreciseReferenceInfo(topUseClass, enclosingNode, line, code, pos);
                addIfUnique(referenceList, seenReferences, refInfo);
                added = true;
            }
            return added;
        } catch (Exception e) {
            logger.debug("Precise xref collection failed for {} in {}: {}",
                targetNode.getFullName(), topUseClass.getFullName(), e.getMessage());
            return false;
        }
    }

    private Map<String, Object> buildPreciseReferenceInfo(
            JavaClass topUseClass,
            JavaNode enclosingNode,
            String line,
            String code,
            int position) {
        Map<String, Object> refInfo = new HashMap<>();
        refInfo.put("class", topUseClass.getFullName());
        refInfo.put("raw_class", JadxApiAdapter.getClassRawName(topUseClass));
        refInfo.put("code_snippet", line);

        int decompiledLine = CodeUtils.getLineNumForPos(
            code,
            position,
            wrapper.getArgs().getCodeNewLineStr()
        );
        refInfo.put("decompiled_line", decompiledLine);
        refInfo.put("source_line", topUseClass.getSourceLine(decompiledLine));

        if (enclosingNode instanceof JavaMethod) {
            JavaMethod fromMethod = (JavaMethod) enclosingNode;
            String legacyMethodName = fromMethod.isClassInit() ? "" : fromMethod.getName();
            refInfo.put("method", legacyMethodName);
            refInfo.put("raw_method", JadxApiAdapter.getMethodRawName(fromMethod));
            JadxApiAdapter.MethodInfoSnapshot methodInfo = JadxApiAdapter.getMethodInfo(fromMethod);
            refInfo.put("from_method", methodInfo != null ? methodInfo.getFullId() : fromMethod.getFullName());
            refInfo.put("raw_from_method", JadxApiAdapter.getMethodRawFullId(fromMethod));
        } else {
            refInfo.put("method", "");
            refInfo.put("raw_method", "");
            refInfo.put("from_method", "");
            refInfo.put("raw_from_method", "");
        }
        return refInfo;
    }

    private Integer resolveDefinitionSourceLine(JavaClass javaClass, int definitionPosition) {
        if (javaClass == null || definitionPosition <= 0) return null;
        try {
            ICodeInfo codeInfo = javaClass.getCodeInfo();
            if (codeInfo == null) return null;
            int decompiledLine = CodeUtils.getLineNumForPos(
                codeInfo.getCodeStr(),
                definitionPosition,
                wrapper.getArgs().getCodeNewLineStr()
            );
            return javaClass.getSourceLine(decompiledLine);
        } catch (Exception e) {
            logger.debug("Failed to resolve definition source line for {}: {}", javaClass.getFullName(), e.getMessage());
            return null;
        }
    }

    private void sendXrefsResponse(Context ctx, List<Map<String, Object>> referenceList) throws PaginationException {
        Map<String, Object> result = paginationUtils.handlePagination(ctx, referenceList, "xrefs", "references", ref -> ref);
        result.put("distinct_referrer_class_count", countDistinctReferrerClasses(referenceList));
        ctx.json(result);
    }

    /**
     * Number of distinct referrer classes in {@code references}, deduped by {@code raw_class}
     * (falling back to {@code class} if unset). Computed over the FULL reference list — callers
     * must pass the pre-pagination list, matching {@code pagination.total} semantics — so a
     * class-level response (one row per referrer class) and a position/method-level response
     * (one row per call site) for the same target both report the same class count even though
     * their row counts differ. Lets an AI caller see "3 referrer classes / 4 reference rows" as
     * two consistent numbers instead of mistaking a row-count difference across xref paths for
     * lost data.
     */
    private long countDistinctReferrerClasses(List<Map<String, Object>> references) {
        Set<String> distinct = new HashSet<>();
        for (Map<String, Object> ref : references) {
            Object rawClass = ref.get("raw_class");
            String key = (rawClass != null && !rawClass.toString().isEmpty())
                ? rawClass.toString()
                : (ref.get("class") != null ? ref.get("class").toString() : null);
            if (key != null && !key.isEmpty()) {
                distinct.add(key);
            }
        }
        return distinct.size();
    }

    /**
     * Attaches a source snippet to each reference row.
     *
     * <p>Referrers whose source this method had to live-decompile are released again when it
     * returns. Without that, one {@code include_snippet=true} xref against a high-fan-in class
     * pins every referrer's decompiled ClassNode in the heap for the rest of the process's life:
     * measured on production as heap 4 461 MB → 8 466 MB (86 % of a 9 832 MB cap) with only 538 MB
     * reclaimable by a forced GC, and the request still unfinished after 4 minutes. Classes that
     * were already resident before the call are left as they were — evicting those would slow down
     * whatever the caller is actually working on.</p>
     */
    void attachSnippets(List<Map<String, Object>> referenceList, int contextLines) {
        Map<String, String[]> sourceLineCache = new HashMap<>();
        // Referrers this call had to decompile itself, and therefore owns and must release.
        List<JavaClass> decompiledHere = new ArrayList<>();

        for (Map<String, Object> ref : referenceList) {
            try {
                String fromClassName = (String) ref.get("class");
                if (fromClassName == null || fromClassName.isEmpty()) {
                    ref.put("snippet", null);
                    continue;
                }

                // fetchSourceLines returns the DECOMPILED source, so the multi-line context window
                // must be indexed by decompiled_line — NOT source_line (which is the original .java
                // line from getSourceLine, a different numbering). Using source_line here produced
                // out-of-range / inverted windows (start_line > end_line, empty code).
                Integer targetLine = null;
                Object decompiledLine = ref.get("decompiled_line");
                if (decompiledLine instanceof Number) {
                    targetLine = ((Number) decompiledLine).intValue();
                }
                if (targetLine == null || targetLine <= 0) {
                    // Class-level fallback entries have no precise decompiled line — no snippet.
                    ref.put("snippet", null);
                    continue;
                }

                String[] lines = sourceLineCache.get(fromClassName);
                if (lines == null) {
                    lines = fetchSourceLines(fromClassName, decompiledHere);
                    sourceLineCache.put(fromClassName, lines != null ? lines : new String[0]);
                }
                if (lines == null || lines.length == 0) {
                    ref.put("snippet", null);
                    continue;
                }

                int zeroLine = targetLine - 1;
                int startLine = Math.max(0, zeroLine - contextLines);
                int endLine = Math.min(lines.length - 1, zeroLine + contextLines);

                StringBuilder sb = new StringBuilder();
                for (int i = startLine; i <= endLine; i++) {
                    sb.append(lines[i]);
                    if (i < endLine) sb.append('\n');
                }

                Map<String, Object> snippet = new HashMap<>();
                snippet.put("code", sb.toString());
                snippet.put("start_line", startLine + 1);
                snippet.put("end_line", endLine + 1);
                ref.put("snippet", snippet);

            } catch (Exception e) {
                logger.debug("Failed to attach snippet for xref entry: {}", e.getMessage());
                ref.put("snippet", null);
            }
        }

        // Snippet text is already copied out of the decompiled source above, so releasing the
        // referrers now costs nothing but returns their retained heap.
        for (JavaClass cls : decompiledHere) {
            WarmupManager.recycle(cls);
        }
        if (!decompiledHere.isEmpty()) {
            logger.debug("[xref] released {} referrer(s) decompiled for snippets", decompiledHere.size());
        }
    }

    /**
     * @param decompiledHere collects any class this call had to live-decompile (i.e. it was not
     *                       already resident and the CodeStore did not have it), so the caller can
     *                       release exactly those and nothing else.
     */
    private String[] fetchSourceLines(String className, List<JavaClass> decompiledHere) {
        try {
            Map<String, JavaClass> classMap = ClassCacheManager.getCache();
            JavaClass cls = ClassCacheManager.findClass(classMap, className);
            if (cls == null) return null;
            String newLine = wrapper.getArgs().getCodeNewLineStr();
            // Prefer the persistent CodeStore (keyed by RAW name, deobf-stable): it survives a
            // FAST_RESTORE when jadx's processed state is still cold, keeping snippets available.
            CodeStore cs = WarmupManager.codeStore();
            if (cs != null) {
                String stored = cs.get(JadxApiAdapter.getClassRawName(cls));
                if (stored != null && !stored.isEmpty()) {
                    return stored.split(java.util.regex.Pattern.quote(newLine), -1);
                }
            }
            // Last resort: live decompile. Note it BEFORE doing it, and only when the class was
            // not already resident, so the caller releases exactly what this request created.
            boolean wasResident = ClassCacheManager.getCachedCodeDirect(cls) != null;
            ICodeInfo codeInfo = cls.getCodeInfo();
            if (codeInfo == null) return null;
            String code = codeInfo.getCodeStr();
            if (code == null) return null;
            if (!wasResident && decompiledHere != null) decompiledHere.add(cls);
            return code.split(java.util.regex.Pattern.quote(newLine), -1);
        } catch (Exception e) {
            logger.debug("fetchSourceLines failed for {}: {}", className, e.getMessage());
            return null;
        }
    }

    /**
     * Builds precise class-xref reference records from the persisted {@link UsePlacesIndex}, with
     * one-line {@code code_snippet}s reconstructed from the {@link CodeStore}. Returns {@code null}
     * if the target is unknown to the index (caller falls through to the live path); an empty list
     * means a known target with no referrers.
     */
    private List<Map<String, Object>> buildPersistedPreciseClassRefs(JavaClass target) {
        List<UsePlacesIndex.Ref> refs = UsePlacesIndex.referencesTo(target);
        if (refs == null) return null;
        CodeStore cs = WarmupManager.codeStore();
        String newLine = wrapper.getArgs().getCodeNewLineStr();
        Map<String, String[]> lineCache = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>(refs.size());
        Set<String> seen = new HashSet<>();
        for (UsePlacesIndex.Ref ref : refs) {
            JavaClass r = ref.referrer;
            String rawClass = JadxApiAdapter.getClassRawName(r);
            String key = rawClass + "#" + ref.decompiledLine;
            if (!seen.add(key)) continue;

            Map<String, Object> e = new HashMap<>();
            e.put("class", r.getFullName());
            e.put("raw_class", rawClass);
            e.put("method", "");
            e.put("raw_method", "");
            e.put("from_method", "");
            e.put("raw_from_method", "");
            e.put("source_line", ref.sourceLine > 0 ? ref.sourceLine : null);
            if (ref.decompiledLine > 0) {
                e.put("decompiled_line", ref.decompiledLine);
                String snippet = lineFromStore(cs, rawClass, newLine, ref.decompiledLine, lineCache);
                if (snippet != null) e.put("code_snippet", snippet);
            }
            out.add(e);
        }
        return out;
    }

    /** Trimmed text of {@code decompiledLine} (1-based) from the CodeStore source, or null. */
    private String lineFromStore(CodeStore cs, String rawClass, String newLine, int decompiledLine,
                                 Map<String, String[]> lineCache) {
        if (cs == null || decompiledLine <= 0) return null;
        String[] lines = lineCache.get(rawClass);
        if (lines == null) {
            String code = cs.get(rawClass);
            lines = (code == null || code.isEmpty())
                ? new String[0]
                : code.split(java.util.regex.Pattern.quote(newLine), -1);
            lineCache.put(rawClass, lines);
        }
        if (decompiledLine - 1 >= lines.length) return null;
        return lines[decompiledLine - 1].trim();
    }

    private int parseIntParam(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Xrefs are read-only metadata lookups — use shared read lock, not exclusive write lock. */
    private boolean tryAcquireDecompileLock(Context ctx) {
        // Read lock: independent xref calls run concurrently. A heavy-class xref
        // (decompiles all callers) is slow but must NOT block fast small-class xrefs,
        // so a shared write lock is the wrong tool here.
        if (JadxSearchLock.tryAcquireRead()) return true;
        Map<String, Object> busyResponse = new HashMap<>();
        busyResponse.put("error", "Decompilation operation in progress");
        busyResponse.put("retry_after", JadxSearchLock.RETRY_AFTER_SECONDS);
        busyResponse.put("busy", true);
        ctx.status(503).json(busyResponse);
        return false;
    }
}
