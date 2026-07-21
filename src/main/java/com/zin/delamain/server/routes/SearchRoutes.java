package com.zin.delamain.server.routes;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.CodeStore;
import com.zin.delamain.index.WarmupManager;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.index.shard.TermLookupResult;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.CodeSearchCoordinator;
import com.zin.delamain.utils.JadxApiAdapter;
import com.zin.delamain.utils.JadxSearchLock;
import com.zin.delamain.utils.PaginationUtils;

import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.JavaClass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Handles all search-related MCP endpoints (headless port).
 */
public class SearchRoutes {
    private static final Logger logger = LoggerFactory.getLogger(SearchRoutes.class);

    final ExecutorService searchExecutor = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors() - 1));

    // Dedicated pool for the parallel batch fan-out inside executeSearch(). The code-search
    // leader task (submitted to searchExecutor by handleSubmitCodeSearch) calls executeSearch(),
    // which itself submits per-batch work and blocks on Future.get() waiting for it. Submitting
    // batches to the SAME pool as the leader caused starvation once concurrent leaders filled
    // every searchExecutor thread: each leader occupies a thread blocked on its own batch
    // futures, but those batches never get a thread to run on. A separate pool breaks the cycle.
    final ExecutorService batchExecutor = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors() - 1));

    private static final Pattern OBFUSCATED_PACKAGE_PATTERN = Pattern.compile("^p\\d+$");

    private static final int DEFAULT_RESULT_LIMIT = 50;
    private static final int MAX_RESULT_LIMIT = 200;
    private static final int SEARCH_TIMEOUT_SECONDS = 60;
    private static final int SEARCH_FOLLOWER_TIMEOUT_SECONDS = Integer.parseInt(
        System.getenv().getOrDefault("DELAMAIN_SEARCH_FOLLOWER_TIMEOUT_SECONDS", "15")
    );
    private static final int PER_CLASS_DECOMPILE_TIMEOUT_SECONDS = Integer.parseInt(
        System.getenv().getOrDefault("DELAMAIN_PER_CLASS_TIMEOUT", "30")
    );
    // Broad-word guard (W1): the shard index knows a term's candidate-class cardinality in O(1)
    // (RoaringBitmap.getCardinality) BEFORE any content scan starts. A term like "AES" can yield
    // tens of thousands of candidates on a large APK — scanning them all under cold cache blows the
    // SEARCH_TIMEOUT_SECONDS deadline and the caller waits the full 60s for a partial result it
    // could have gotten immediately. When the candidate count exceeds this threshold we skip the
    // full scan entirely and return a bounded sample + an explicit hint instead. Package-private and
    // non-final (not just env-derived) so tests can override the threshold without restarting the
    // JVM; production code always reads the mutable field, never the DEFAULT_ constant directly.
    static final int DEFAULT_BROAD_CANDIDATE_THRESHOLD = Integer.parseInt(
        System.getenv().getOrDefault("DELAMAIN_BROAD_CANDIDATE_THRESHOLD", "3000")
    );
    static final int DEFAULT_BROAD_SAMPLE_SCAN = Integer.parseInt(
        System.getenv().getOrDefault("DELAMAIN_BROAD_SAMPLE_SCAN", "300")
    );
    static int BROAD_CANDIDATE_THRESHOLD = DEFAULT_BROAD_CANDIDATE_THRESHOLD;
    static int BROAD_SAMPLE_SCAN = DEFAULT_BROAD_SAMPLE_SCAN;
    private static final ScheduledExecutorService DECOMPILE_WATCHDOG =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jadx-decompile-watchdog");
            t.setDaemon(true);
            return t;
        });

    public enum SearchLocation {
        CLASS_NAME, METHOD_NAME, FIELD_NAME, CODE, COMMENT
    }

    public enum MatchMode {
        SUBSTRING, EXACT, PREFIX, REGEX
    }

    private static final Map<String, SearchLocation> SEARCH_LOCATION_MAP = new HashMap<>();
    static {
        SEARCH_LOCATION_MAP.put("class", SearchLocation.CLASS_NAME);
        SEARCH_LOCATION_MAP.put("class_name", SearchLocation.CLASS_NAME);
        SEARCH_LOCATION_MAP.put("method", SearchLocation.METHOD_NAME);
        SEARCH_LOCATION_MAP.put("method_name", SearchLocation.METHOD_NAME);
        SEARCH_LOCATION_MAP.put("field", SearchLocation.FIELD_NAME);
        SEARCH_LOCATION_MAP.put("field_name", SearchLocation.FIELD_NAME);
        SEARCH_LOCATION_MAP.put("code", SearchLocation.CODE);
        SEARCH_LOCATION_MAP.put("comment", SearchLocation.COMMENT);
    }

    private final HeadlessJadxWrapper wrapper;
    private final PaginationUtils paginationUtils;

    public SearchRoutes(HeadlessJadxWrapper wrapper, PaginationUtils paginationUtils) {
        this.wrapper = wrapper;
        this.paginationUtils = paginationUtils;
    }

    public void register(Javalin app, AuthConfig authConfig) {
        app.get("/search-classes-by-keyword", this::handleSearchClassesByKeyword);
        app.post("/submit-code-search", this::handleSubmitCodeSearch);
        app.get("/code-search-status", this::handleCodeSearchStatus);
    }

    public void shutdownSearchExecutor() {
        searchExecutor.shutdownNow();
        batchExecutor.shutdownNow();
    }

    public Future<?> submitWarmupTask(JavaClass cls) {
        return searchExecutor.submit(() -> {
            try {
                cls.getCode();
            } catch (Exception ignored) {
            }
        });
    }

    // ------------------------------- Request Handlers --------------------------

    public void handleSearchClassesByKeyword(Context ctx) {
        String searchTerm = ctx.queryParam("search_term");
        if (searchTerm == null || searchTerm.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing 'search_term' parameter."));
            return;
        }

        String packageFilter = ctx.queryParam("package");
        String searchInParam = ctx.queryParam("search_in");
        String excludeParam = ctx.queryParam("exclude");
        List<String> excludePrefixes = new ArrayList<>();
        if (excludeParam != null && !excludeParam.isEmpty()) {
            for (String prefix : excludeParam.split(",")) {
                String trimmed = prefix.trim();
                if (!trimmed.isEmpty()) excludePrefixes.add(trimmed);
            }
        }

        MatchMode matchMode = parseMatchMode(ctx.queryParam("match_mode"));
        // Default for search-classes-by-keyword is metadata-only (class+method+field names).
        // Code search is slow (holds write lock per-class decompile) and belongs in submit-code-search.
        Set<SearchLocation> searchLocations = parseSearchLocations(
            searchInParam != null ? searchInParam : "class,method,field");
        boolean isCodeSearch = searchLocations.contains(SearchLocation.CODE)
            || searchLocations.contains(SearchLocation.COMMENT);

        // Compiled regardless of isCodeSearch: the code-search content path (see
        // classMatchesAnyContentLocation) now honours REGEX too, instead of silently matching by
        // substring while the response still echoed match_mode=regex (dishonest result).
        Pattern regexPattern = null;
        if (matchMode == MatchMode.REGEX) {
            try {
                regexPattern = Pattern.compile(searchTerm, Pattern.CASE_INSENSITIVE);
            } catch (java.util.regex.PatternSyntaxException e) {
                ctx.status(400).json(Map.of("error", "Invalid regex pattern: " + e.getMessage()));
                return;
            }
        }
        final Pattern compiledRegex = regexPattern;

        int offset = paginationUtils.getIntParam(ctx, "offset", 0);
        int count = paginationUtils.getIntParam(ctx, "count", DEFAULT_RESULT_LIMIT);
        count = Math.min(count, MAX_RESULT_LIMIT);
        if (validatePaginationParams(ctx, offset, count)) return;

        try {
            List<JavaClass> allClasses = wrapper.getClassesWithInners();
            List<JavaClass> filteredClasses = filterSearchClasses(allClasses, packageFilter, excludePrefixes);

            if (isCodeSearch) {
                handleCoordinatedCodeSearch(ctx, allClasses, filteredClasses,
                    searchTerm, packageFilter, excludeParam, searchInParam,
                    searchLocations, offset, count, matchMode, compiledRegex);
                return;
            }

            if (matchMode == MatchMode.EXACT || matchMode == MatchMode.SUBSTRING || matchMode == MatchMode.PREFIX) {
                CodeSearchCoordinator.SearchResult indexResult = tryNameIndexFastPath(
                    searchTerm, searchLocations, packageFilter, excludePrefixes, allClasses, matchMode);
                if (indexResult != null) {
                    ctx.json(buildSearchResponse(indexResult, offset, count, matchMode));
                    return;
                }
            }

            SearchExecution searchExecution = executeSearchWithMatchMode(
                allClasses, filteredClasses, searchTerm, searchLocations,
                false, offset + count + 1, matchMode, compiledRegex);
            ctx.json(buildSearchResponse(searchExecution.getResult(), offset, count, matchMode));
        } catch (Exception e) {
            logger.error("Internal error in search: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error in search: " + e.getMessage()));
        }
    }

    public void handleSubmitCodeSearch(Context ctx) {
        String searchTerm = ctx.queryParam("search_term");
        if (searchTerm == null || searchTerm.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing 'search_term' parameter."));
            return;
        }

        String packageFilter = ctx.queryParam("package");
        String searchInParam = ctx.queryParam("search_in");
        String excludeParam = ctx.queryParam("exclude");
        MatchMode matchMode = parseMatchMode(ctx.queryParam("match_mode"));

        List<String> excludePrefixes = new ArrayList<>();
        if (excludeParam != null && !excludeParam.isEmpty()) {
            for (String prefix : excludeParam.split(",")) {
                String trimmed = prefix.trim();
                if (!trimmed.isEmpty()) excludePrefixes.add(trimmed);
            }
        }

        Set<SearchLocation> searchLocations = parseSearchLocations(searchInParam);
        boolean isCodeSearch = searchLocations.contains(SearchLocation.CODE)
            || searchLocations.contains(SearchLocation.COMMENT);
        if (!isCodeSearch) {
            ctx.status(400).json(Map.of("error",
                "submit-code-search only supports search_in=code|comment."));
            return;
        }

        Pattern regexPattern = null;
        if (matchMode == MatchMode.REGEX) {
            try {
                regexPattern = Pattern.compile(searchTerm, Pattern.CASE_INSENSITIVE);
            } catch (java.util.regex.PatternSyntaxException e) {
                ctx.status(400).json(Map.of("error", "Invalid regex pattern: " + e.getMessage()));
                return;
            }
        }
        final Pattern compiledRegex = regexPattern;

        try {
            List<JavaClass> allClasses = wrapper.getClassesWithInners();
            List<JavaClass> filteredClasses = filterSearchClasses(allClasses, packageFilter, excludePrefixes);

            CodeSearchCoordinator.SearchReservation reservation = CodeSearchCoordinator.reserve(
                wrapper, searchTerm, packageFilter, excludeParam, searchInParam, matchMode.name());

            if (reservation.hasCachedResult()) {
                CompletableFuture<CodeSearchCoordinator.SearchResult> preDone =
                    CompletableFuture.completedFuture(reservation.getCachedResult());
                String ticket = CodeSearchCoordinator.registerTicket(preDone);
                ctx.json(Map.of("ticket", ticket, "status", "done", "retry_after_seconds", 0));
                return;
            }

            CompletableFuture<CodeSearchCoordinator.SearchResult> future = reservation.getFuture();
            String ticket = CodeSearchCoordinator.registerTicket(future);

            if (reservation.isLeader()) {
                CodeSearchCoordinator.SearchKey key = reservation.getKey();
                final List<JavaClass> allClassesFinal = allClasses;
                final List<JavaClass> filteredClassesFinal = filteredClasses;
                final Set<SearchLocation> locationsFinal = searchLocations;
                searchExecutor.submit(() -> {
                    try {
                        SearchExecution execution = executeSearch(
                            allClassesFinal, filteredClassesFinal, searchTerm, locationsFinal, true, Integer.MAX_VALUE,
                            matchMode, compiledRegex);
                        if (execution.isTimedOut()) {
                            if (!execution.getResult().getMatches().isEmpty()) {
                                // Partial matches found before the deadline — return them (DONE +
                                // timed_out/hint in searchInfo) instead of an empty timeout. Not cached.
                                CodeSearchCoordinator.completePartial(
                                    key, future, execution.getResult(), execution.getElapsedMs());
                            } else {
                                CodeSearchCoordinator.completeFailure(
                                    key, future, new TimeoutException("Code search exceeded timeout window"));
                            }
                        } else {
                            CodeSearchCoordinator.completeSuccess(key, future, execution.getResult(), execution.getElapsedMs());
                        }
                    } catch (Exception e) {
                        CodeSearchCoordinator.completeFailure(key, future, e);
                    }
                });
            }

            ctx.json(Map.of("ticket", ticket, "status", "submitted", "retry_after_seconds", 5));
        } catch (Exception e) {
            logger.error("Failed to submit code search: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to submit code search: " + e.getMessage()));
        }
    }

    public void handleCodeSearchStatus(Context ctx) {
        String ticket = ctx.queryParam("ticket");
        if (ticket == null || ticket.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing 'ticket' parameter"));
            return;
        }

        int offset = paginationUtils.getIntParam(ctx, "offset", 0);
        int count = Math.min(paginationUtils.getIntParam(ctx, "count", DEFAULT_RESULT_LIMIT), MAX_RESULT_LIMIT);
        MatchMode matchMode = parseMatchMode(ctx.queryParam("match_mode"));
        if (validatePaginationParams(ctx, offset, count)) return;

        CodeSearchCoordinator.TicketPollResult poll = CodeSearchCoordinator.pollByTicket(ticket);

        switch (poll.getStatus()) {
            case DONE:
                ctx.json(buildSearchResponse(poll.getResult(), offset, count, matchMode));
                break;
            case RUNNING:
                ctx.json(Map.of("status", "running", "retry_after_seconds", 5,
                    "message", "Search in progress. Poll again shortly."));
                break;
            case TIMED_OUT:
                ctx.json(Map.of("status", "timed_out", "retry_after_seconds", 0,
                    "message", poll.getMessage()));
                break;
            case CANCELLED:
                ctx.json(Map.of("status", "cancelled", "retry_after_seconds", 1,
                    "message", poll.getMessage()));
                break;
            case NOT_FOUND:
                ctx.status(404).json(Map.of("status", "not_found", "message", poll.getMessage()));
                break;
            case ERROR:
            default:
                ctx.status(500).json(Map.of("status", "error", "message", poll.getMessage()));
                break;
        }
    }

    // ------------------------------- Internal helpers --------------------------

    private void handleCoordinatedCodeSearch(
        Context ctx,
        List<JavaClass> allClasses,
        List<JavaClass> filteredClasses,
        String searchTerm,
        String packageFilter,
        String excludeParam,
        String searchInParam,
        Set<SearchLocation> searchLocations,
        int offset,
        int count,
        MatchMode matchMode,
        Pattern compiledRegex
    ) throws Exception {
        CodeSearchCoordinator.SearchReservation reservation = CodeSearchCoordinator.reserve(
            wrapper, searchTerm, packageFilter, excludeParam, searchInParam, matchMode.name());

        if (reservation.hasCachedResult()) {
            ctx.json(buildSearchResponse(reservation.getCachedResult(), offset, count, matchMode));
            return;
        }

        if (reservation.isFollower()) {
            CompletableFuture<CodeSearchCoordinator.SearchResult> leaderFuture = reservation.getFuture();
            CompletableFuture<Object> followerFuture = leaderFuture
                .orTimeout(SEARCH_FOLLOWER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .handle((value, ex) -> {
                    if (ex != null) {
                        Throwable cause = (ex instanceof ExecutionException) ? ex.getCause() : ex;
                        if (cause instanceof TimeoutException) {
                            return (Object) Map.of("status", "still_searching",
                                "message", "Search still in progress (follower timeout). Retry shortly.",
                                "retry_after_seconds", 5);
                        }
                        if (cause instanceof CancellationException) {
                            return (Object) Map.of("status", "cancelled",
                                "message", "Search cancelled. Retry.",
                                "retry_after_seconds", 1);
                        }
                        return (Object) Map.of("status", "error",
                            "message", cause != null && cause.getMessage() != null
                                ? cause.getMessage() : ex.getClass().getSimpleName());
                    }
                    return (Object) buildSearchResponse(value, offset, count, matchMode);
                });
            ctx.future(() -> followerFuture);
            return;
        }

        CompletableFuture<CodeSearchCoordinator.SearchResult> future = reservation.getFuture();
        CodeSearchCoordinator.SearchKey key = reservation.getKey();
        if (future == null || key == null) {
            ctx.status(503).json(Map.of("error", "Search coordinator state is unavailable",
                "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS));
            return;
        }

        try {
            SearchExecution execution = executeSearch(allClasses, filteredClasses, searchTerm,
                searchLocations, true, Integer.MAX_VALUE, matchMode, compiledRegex);
            if (execution.isTimedOut()) {
                if (!execution.getResult().getMatches().isEmpty()) {
                    // Partial matches found before the deadline — deliver them to followers too
                    // (DONE + hint), instead of failing their joined future. Not cached.
                    CodeSearchCoordinator.completePartial(key, future, execution.getResult(), execution.getElapsedMs());
                } else {
                    CodeSearchCoordinator.completeFailure(key, future,
                        new TimeoutException("Code search exceeded timeout window"));
                }
            } else {
                CodeSearchCoordinator.completeSuccess(key, future, execution.getResult(), execution.getElapsedMs());
            }
            ctx.json(buildSearchResponse(execution.getResult(), offset, count, matchMode));
        } catch (Exception e) {
            CodeSearchCoordinator.completeFailure(key, future, e);
            throw e;
        }
    }

    private CodeSearchCoordinator.SearchResult tryNameIndexFastPath(
        String searchTerm,
        Set<SearchLocation> searchLocations,
        String packageFilter,
        List<String> excludePrefixes,
        List<JavaClass> allClasses,
        MatchMode matchMode
    ) {
        if (searchTerm == null || searchTerm.isEmpty()
                || searchTerm.contains("*") || searchTerm.contains("?")
                || searchTerm.contains(" ") || searchTerm.contains(".")) {
            return null;
        }
        if (searchLocations.size() != 1) return null;
        SearchLocation loc = searchLocations.iterator().next();
        String kind;
        switch (loc) {
            case CLASS_NAME:  kind = "class";  break;
            case METHOD_NAME: kind = "method"; break;
            case FIELD_NAME:  kind = "field";  break;
            default: return null;
        }

        List<JavaClass> candidates;
        switch (matchMode) {
            case EXACT:
                candidates = ClassCacheManager.findByExactName(kind, searchTerm);
                break;
            case SUBSTRING:
                candidates = ClassCacheManager.findBySubstringName(kind, searchTerm);
                break;
            case PREFIX:
                candidates = ClassCacheManager.findByPrefixName(kind, searchTerm);
                break;
            default:
                return null;
        }
        if (candidates == null) return null;

        List<String> matchedNames = new ArrayList<>();
        boolean applyPackage = packageFilter != null && !packageFilter.isEmpty();
        Set<JavaClass> allClassesSet = applyPackage ? new HashSet<>(allClasses) : null;
        for (JavaClass cls : candidates) {
            if (applyPackage && (allClassesSet == null || !allClassesSet.contains(cls))) continue;
            if (applyPackage && !matchesPackageFilter(cls, packageFilter)) continue;
            if (!excludePrefixes.isEmpty()) {
                boolean excluded = false;
                for (String prefix : excludePrefixes) {
                    if (cls.getFullName().startsWith(prefix)) { excluded = true; break; }
                }
                if (excluded) continue;
            }
            matchedNames.add(cls.getFullName());
        }

        Map<String, Object> searchInfo = new HashMap<>();
        searchInfo.put("total_found", matchedNames.size());
        searchInfo.put("total_classes", allClasses.size());
        searchInfo.put("filtered_classes", matchedNames.size());
        searchInfo.put("elapsed_seconds", 0L);
        searchInfo.put("timed_out", false);
        searchInfo.put("parallel_batches", 0);
        searchInfo.put("search_locations", searchLocations.toString());
        searchInfo.put("index_hit", true);

        return new CodeSearchCoordinator.SearchResult(matchedNames, searchInfo, matchMode.name().toLowerCase());
    }

    SearchExecution executeSearch(
        List<JavaClass> allClasses,
        List<JavaClass> filteredClasses,
        String searchTerm,
        Set<SearchLocation> searchLocations,
        boolean collectAllResults,
        int resultsNeeded
    ) {
        return executeSearch(allClasses, filteredClasses, searchTerm, searchLocations,
            collectAllResults, resultsNeeded, MatchMode.SUBSTRING, null);
    }

    SearchExecution executeSearch(
        List<JavaClass> allClasses,
        List<JavaClass> filteredClasses,
        String searchTerm,
        Set<SearchLocation> searchLocations,
        boolean collectAllResults,
        int resultsNeeded,
        MatchMode matchMode,
        Pattern compiledRegex
    ) {
        final String term = searchTerm.toLowerCase();
        final Set<String> matchedClasses = ConcurrentHashMap.newKeySet();
        final AtomicInteger totalMatches = new AtomicInteger(0);
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        long startTimeMs = System.currentTimeMillis();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(SEARCH_TIMEOUT_SECONDS);
        boolean timedOut = false;
        int batchCount = 0;
        boolean requiresContentSearch = requiresContentSearch(searchLocations);

        final Set<JavaClass> trigramCandidates;
        final boolean trigramDefinitivelyEmpty;
        // Additive shard pruning layer (Wave B): the mmap shard index gives an authoritative
        // definitive-negative for every class id it covers, letting a broad-term search skip the
        // bulk of the corpus without a per-class scan. It sits ON TOP of the residual
        // CodeContentIndex layer below — shard-covered ids use the shard's judgment, everything
        // else falls back to the byte-for-byte unchanged residual logic. Only populated for a
        // non-REGEX CODE search (same gate as the trigram prefilter).
        final TermLookupResult shardResult;
        final AtomicInteger shardCoveredPruned = new AtomicInteger(0);
        // The trigram index is built from literal code substrings, so it cannot pre-filter a
        // REGEX search term (e.g. "public\s+class" never appears verbatim in decompiled code).
        // Skip the trigram shortcut for REGEX and fall through to a full per-class scan instead.
        if (requiresContentSearch && searchLocations.contains(SearchLocation.CODE)
                && matchMode != MatchMode.REGEX) {
            shardResult = ContentShardIndex.isBuilt() ? ContentShardIndex.candidatesForTerm(term) : null;
            BitSet candidateBits = CodeContentIndex.candidatesForTerm(term);
            if (candidateBits != null && !candidateBits.isEmpty()) {
                Set<JavaClass> tc = new HashSet<>();
                for (int bit = candidateBits.nextSetBit(0); bit >= 0; bit = candidateBits.nextSetBit(bit + 1)) {
                    JavaClass resolved = CodeContentIndex.resolveClass(bit);
                    if (resolved != null) tc.add(resolved);
                }
                trigramCandidates = tc.isEmpty() ? null : tc;
                trigramDefinitivelyEmpty = false;
            } else if (candidateBits != null) {
                trigramCandidates = null;
                trigramDefinitivelyEmpty = true;
            } else {
                trigramCandidates = null;
                trigramDefinitivelyEmpty = false;
            }
        } else {
            shardResult = null;
            trigramCandidates = null;
            trigramDefinitivelyEmpty = false;
        }

        // Broad-word guard: shard already told us the candidate cardinality is huge — don't run
        // the full parallel/serial content scan at all, return a bounded sample immediately.
        // Narrow terms (candidates <= threshold) and no-shard-built cases fall through unchanged.
        if (requiresContentSearch && isContentOnly(searchLocations) && shardResult != null
                && shardResult.candidates.getCardinality() > BROAD_CANDIDATE_THRESHOLD) {
            return executeBroadTermSearch(allClasses, filteredClasses, searchTerm, term,
                searchLocations, matchMode, compiledRegex, shardResult, startTimeMs);
        }

        // Parallel-batch any search over a non-trivial corpus.
        // Previously gated on `collectAllResults`, which meant the high-frequency
        // metadata search path (search-classes-by-keyword) ran a sequential
        // 137 k-class scan in the calling Jetty thread, serialising under load.
        if (filteredClasses.size() > 100) {
            // Batch over the full filtered set (outer AND inner classes). This previously built
            // a `topClasses` list that dropped every `cls.isInner()` entry before batching, which
            // silently excluded ALL inner classes from class/method/field matching in this
            // high-frequency (>100-class) parallel branch — inner classes are already present in
            // filteredClasses via wrapper.getClassesWithInners(), so they were just discarded.
            int processorCount = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
            List<List<JavaClass>> batches = splitIntoBatches(filteredClasses, processorCount);
            batchCount = batches.size();
            logger.info("delamain: Code search '{}' using {} parallel batches", searchTerm, batchCount);

            List<Future<?>> futures = new ArrayList<>();
            ConcurrentLinkedQueue<JavaClass> contentCandidates = new ConcurrentLinkedQueue<>();

            for (List<JavaClass> batch : batches) {
                Future<?> future = batchExecutor.submit(() -> {
                    for (JavaClass cls : batch) {
                        if (cancelled.get() || Thread.currentThread().isInterrupted()) return;
                        if (System.nanoTime() >= deadlineNanos) { cancelled.set(true); return; }
                        try {
                            if (classMatchesAnyMetadataLocation(cls, term, searchLocations)) {
                                if (matchedClasses.add(cls.getFullName())) {
                                    int total = totalMatches.incrementAndGet();
                                    if (!collectAllResults && total >= resultsNeeded) {
                                        cancelled.set(true); return;
                                    }
                                }
                            } else if (requiresContentSearch) {
                                contentCandidates.add(cls);
                            }
                        } catch (Exception ignored) {}
                    }
                });
                futures.add(future);
            }

            for (Future<?> future : futures) {
                try {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) { timedOut = true; future.cancel(true); continue; }
                    future.get(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    timedOut = true; cancelled.set(true); future.cancel(true);
                } catch (Exception ignored) {}
            }

            if (!timedOut && requiresContentSearch && !contentCandidates.isEmpty()) {
                // Parallelize the content scan across the same pool as the metadata phase above
                // (the metadata futures have already joined, so batchExecutor threads are free).
                // Post-H6 this scan must visit EVERY unindexed class — the trigram prefilter is
                // unsound for classes it never indexed, so they can't be rejected on a prefilter
                // miss (see the per-class guard below). On a large APK that is hundreds of
                // thousands of classes; running it single-threaded was the dominant cost — the
                // serial scan blew the deadline regardless of per-class speed. Each class's source
                // is now a CodeStore disk read (see classMatchesAnyContentLocation), so the scan
                // scales near-linearly with cores. matchedClasses/totalMatches/cancelled are all
                // concurrency-safe; the deadline/cancel checks are re-evaluated per class.
                List<List<JavaClass>> contentBatches =
                    splitIntoBatches(new ArrayList<>(contentCandidates), processorCount);
                List<Future<?>> contentFutures = new ArrayList<>();
                for (List<JavaClass> batch : contentBatches) {
                    contentFutures.add(batchExecutor.submit(() -> {
                        for (JavaClass cls : batch) {
                            if (cancelled.get() || Thread.currentThread().isInterrupted()) return;
                            if (System.nanoTime() >= deadlineNanos) { cancelled.set(true); return; }
                            // H6 soundness guard (shard layer + residual fallback). Only classes a
                            // sound index authoritatively rejects are skipped; anything uncertain
                            // falls through to the real content scan (lazily decompiling +
                            // self-healing) rather than being silently excluded.
                            if (isDefinitivelyAbsent(cls, shardResult, trigramCandidates,
                                    trigramDefinitivelyEmpty, shardCoveredPruned)) continue;
                            if (classMatchesAnyContentLocation(cls, term, searchLocations, matchMode, compiledRegex)
                                    && matchedClasses.add(cls.getFullName())) {
                                int total = totalMatches.incrementAndGet();
                                if (!collectAllResults && total >= resultsNeeded) { cancelled.set(true); return; }
                            }
                        }
                    }));
                }
                for (Future<?> future : contentFutures) {
                    try {
                        long remainingNanos = deadlineNanos - System.nanoTime();
                        if (remainingNanos <= 0) { timedOut = true; cancelled.set(true); future.cancel(true); continue; }
                        future.get(remainingNanos, TimeUnit.NANOSECONDS);
                    } catch (TimeoutException e) {
                        timedOut = true; cancelled.set(true); future.cancel(true);
                    } catch (Exception ignored) {}
                }
            }
        } else {
            for (JavaClass cls : filteredClasses) {
                if (cancelled.get()) break;
                if (System.nanoTime() >= deadlineNanos) { timedOut = true; break; }
                boolean metadataMatched = classMatchesAnyMetadataLocation(cls, term, searchLocations);
                if (metadataMatched) {
                    if (matchedClasses.add(cls.getFullName())) {
                        int total = totalMatches.incrementAndGet();
                        if (!collectAllResults && total >= resultsNeeded) cancelled.set(true);
                    }
                } else if (requiresContentSearch) {
                    // H6 fix: same soundness guard as the parallel branch above — see comment there.
                    if (isDefinitivelyAbsent(cls, shardResult, trigramCandidates,
                            trigramDefinitivelyEmpty, shardCoveredPruned)) continue;
                    if (classMatchesAnyContentLocation(cls, term, searchLocations, matchMode, compiledRegex)
                            && matchedClasses.add(cls.getFullName())) {
                        int total = totalMatches.incrementAndGet();
                        if (!collectAllResults && total >= resultsNeeded) cancelled.set(true);
                    }
                }
            }
        }

        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startTimeMs);
        Map<String, Object> searchInfo = new HashMap<>();
        searchInfo.put("total_found", totalMatches.get());
        searchInfo.put("total_classes", allClasses.size());
        searchInfo.put("filtered_classes", filteredClasses.size());
        searchInfo.put("elapsed_seconds", TimeUnit.MILLISECONDS.toSeconds(elapsedMs));
        searchInfo.put("timed_out", timedOut);
        searchInfo.put("parallel_batches", batchCount);
        searchInfo.put("search_locations", searchLocations.toString());
        if (requiresContentSearch && trigramCandidates != null) {
            searchInfo.put("trigram_pre_filter_candidates", trigramCandidates.size());
        }
        if (requiresContentSearch) {
            searchInfo.put("trigram_definitively_empty", trigramDefinitivelyEmpty);
            // H6 honesty signal: the trigram index is a prefilter only, never the source of
            // truth. Unindexed classes always fall through to a real content scan (with lazy
            // decompile + self-healing indexing) rather than being trusted/rejected on the
            // trigram prefilter alone — so unless the search timed out, a miss means the term
            // genuinely was not found in any scanned class, not that the index failed to cover it.
            searchInfo.put("index_is_prefilter_only", true);
            // Wave B shard layer signals: whether the mmap shard index was available for this
            // query, and how many classes it authoritatively pruned (definitive negatives skipped
            // without any content scan). shard_covered_pruned is 0 when the shard isn't built.
            searchInfo.put("shard_index_built", ContentShardIndex.isBuilt());
            searchInfo.put("shard_covered_pruned", shardCoveredPruned.get());
        }
        searchInfo.put("trigram_index_size", CodeContentIndex.trigramCount());
        searchInfo.put("trigram_indexed_classes", CodeContentIndex.indexedClassCount());
        if (timedOut) {
            // Surface a partial result rather than nothing: the matches gathered before the
            // deadline are valid, just incomplete. This happens when the term's trigrams are all
            // common (e.g. "getInstance"), so the candidate set is too large to verify in time.
            searchInfo.put("partial_results", true);
            searchInfo.put("hint", "Search hit the " + SEARCH_TIMEOUT_SECONDS
                + "s time limit; results are PARTIAL. The term's trigrams are too common to narrow "
                + "the candidate set — use a longer or rarer substring for complete results.");
        }

        CodeSearchCoordinator.SearchResult result = new CodeSearchCoordinator.SearchResult(
            buildOrderedMatchList(filteredClasses, matchedClasses), searchInfo, matchMode.name().toLowerCase());

        logger.info("delamain: Search '{}' done in {}s - found {} matches (batches: {}, timed_out: {})",
            searchTerm, TimeUnit.MILLISECONDS.toSeconds(elapsedMs), totalMatches.get(), batchCount, timedOut);

        return new SearchExecution(result, elapsedMs, timedOut);
    }

    /**
     * Broad-word guard: {@code shardResult.candidates} is already known (by the caller) to exceed
     * {@link #BROAD_CANDIDATE_THRESHOLD}. Instead of scanning the full candidate set (which is what
     * blows the {@link #SEARCH_TIMEOUT_SECONDS} deadline for common trigram terms like "AES"), scan
     * only the first {@link #BROAD_SAMPLE_SCAN} candidates (ascending id order) that actually resolve
     * to a class within {@code filteredClasses} — i.e. respecting whatever package/prefix filtering
     * the caller already applied — and return immediately with an honest partial-results marker.
     */
    private SearchExecution executeBroadTermSearch(
            List<JavaClass> allClasses,
            List<JavaClass> filteredClasses,
            String searchTerm,
            String term,
            Set<SearchLocation> searchLocations,
            MatchMode matchMode,
            Pattern compiledRegex,
            TermLookupResult shardResult,
            long startTimeMs) {
        long candidateCount = shardResult.candidates.getCardinality();
        Set<JavaClass> filteredSet = new HashSet<>(filteredClasses);
        Set<String> matchedClasses = new HashSet<>();
        int totalMatches = 0;
        int sampledScanned = 0;

        for (int id : shardResult.candidates) {
            if (sampledScanned >= BROAD_SAMPLE_SCAN) break;
            JavaClass cls = CodeContentIndex.resolveClass(id);
            if (cls == null || !filteredSet.contains(cls)) continue;
            sampledScanned++;
            try {
                if (classMatchesAnyContentLocation(cls, term, searchLocations, matchMode, compiledRegex)
                        && matchedClasses.add(cls.getFullName())) {
                    totalMatches++;
                }
            } catch (Exception ignored) {}
        }

        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startTimeMs);
        Map<String, Object> searchInfo = new HashMap<>();
        searchInfo.put("total_found", totalMatches);
        searchInfo.put("total_classes", allClasses.size());
        searchInfo.put("filtered_classes", filteredClasses.size());
        searchInfo.put("elapsed_seconds", TimeUnit.MILLISECONDS.toSeconds(elapsedMs));
        searchInfo.put("timed_out", false);
        searchInfo.put("parallel_batches", 0);
        searchInfo.put("search_locations", searchLocations.toString());
        searchInfo.put("trigram_index_size", CodeContentIndex.trigramCount());
        searchInfo.put("trigram_indexed_classes", CodeContentIndex.indexedClassCount());
        searchInfo.put("shard_index_built", ContentShardIndex.isBuilt());
        searchInfo.put("shard_covered_pruned", 0);
        searchInfo.put("broad_term", true);
        searchInfo.put("candidate_count", candidateCount);
        searchInfo.put("sampled_scanned", sampledScanned);
        searchInfo.put("partial_results", true);
        searchInfo.put("hint", "Term '" + searchTerm + "' matches " + candidateCount
            + " candidate classes (too broad to fully verify); showing matches from first "
            + sampledScanned + " — narrow the term or use search_in=class/method");

        CodeSearchCoordinator.SearchResult result = new CodeSearchCoordinator.SearchResult(
            buildOrderedMatchList(filteredClasses, matchedClasses), searchInfo, matchMode.name().toLowerCase());

        logger.info("delamain: Search '{}' broad-term guard tripped - {} candidates, sampled {}, "
                + "found {} matches in {}s",
            searchTerm, candidateCount, sampledScanned, totalMatches, TimeUnit.MILLISECONDS.toSeconds(elapsedMs));

        return new SearchExecution(result, elapsedMs, false);
    }

    /**
     * H6 soundness guard: decides whether {@code cls} can be safely skipped from the content scan
     * because some sound index is certain it does not contain the term. Additive two-layer rule:
     *
     * <ol>
     *   <li><b>Shard layer (authoritative where it covers).</b> If the shard index covers this
     *       class's id, its judgment is definitive: {@code covered && !candidate} means the class
     *       certainly lacks the term (prune, count it); {@code covered && candidate} means it may
     *       contain the term and MUST still be scanned to verify the substring (never pruned).</li>
     *   <li><b>Shard-excluded layer (W14).</b> If the shard index marked this id excluded — an
     *       empty-source inner class whose logical content is inlined into its (separately scanned
     *       or shard-covered) top-level class, see {@code WarmupManager}'s "Empty inner classes"
     *       contract — the class itself has no content to match, so it is pruned unconditionally.
     *       This never causes a false negative: any term the inner class could "contain" surfaces
     *       via its top-level class instead, which is never itself excluded by this rule.</li>
     *   <li><b>Residual layer (fallback for everything the shard does not cover).</b> For ids the
     *       shard makes no claim about — {@code id < 0}, library classes, self-healed classes — the
     *       existing {@link CodeContentIndex} trigram guard is applied byte-for-byte unchanged: only
     *       a class the index has actually built ({@link CodeContentIndex#isIndexed}) may be
     *       rejected on a prefilter miss; anything else falls through to the real scan.</li>
     * </ol>
     *
     * Soundness invariant: this returns {@code true} only when an index is <em>certain</em> the
     * term is absent. Any uncertainty (uncovered + unindexed) returns {@code false} → the class is
     * scanned. Better to over-scan than to wrongly prune a real match.
     *
     * @param shardCoveredPruned incremented once for each class pruned by the shard layer (covered
     *                           or excluded).
     */
    static boolean isDefinitivelyAbsent(
            JavaClass cls,
            TermLookupResult shardResult,
            Set<JavaClass> trigramCandidates,
            boolean trigramDefinitivelyEmpty,
            AtomicInteger shardCoveredPruned) {
        int id = CodeContentIndex.idOf(cls);
        if (shardResult != null && id >= 0 && shardResult.covered.contains(id)) {
            if (!shardResult.candidates.contains(id)) {
                shardCoveredPruned.incrementAndGet();
                return true; // covered but not a candidate: certainly absent → prune
            }
            return false; // covered AND candidate: possible match, must scan to verify
        }
        if (shardResult != null && id >= 0 && ContentShardIndex.isExcluded(id)) {
            // Shard-verified empty inner class: no content of its own, its logical source is
            // inlined in its top-level class → prune unconditionally, zero false-negative risk.
            shardCoveredPruned.incrementAndGet();
            return true;
        }
        // Shard makes no claim about this id → residual CodeContentIndex layer (unchanged).
        if (trigramCandidates != null && !trigramCandidates.contains(cls)
                && CodeContentIndex.isIndexed(cls)) return true;
        if (trigramDefinitivelyEmpty && CodeContentIndex.isIndexed(cls)) return true;
        return false;
    }

    SearchExecution executeSearchWithMatchMode(
        List<JavaClass> allClasses,
        List<JavaClass> filteredClasses,
        String searchTerm,
        Set<SearchLocation> searchLocations,
        boolean collectAllResults,
        int resultsNeeded,
        MatchMode matchMode,
        Pattern compiledRegex
    ) {
        if (matchMode == null || matchMode == MatchMode.SUBSTRING) {
            return executeSearch(allClasses, filteredClasses, searchTerm,
                searchLocations, collectAllResults, resultsNeeded);
        }

        final String term = searchTerm.toLowerCase();
        final Set<String> matchedClasses = ConcurrentHashMap.newKeySet();
        final AtomicInteger totalMatches = new AtomicInteger(0);
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        long startTimeMs = System.currentTimeMillis();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(SEARCH_TIMEOUT_SECONDS);
        boolean timedOut = false;

        for (JavaClass cls : filteredClasses) {
            if (cancelled.get()) break;
            if (System.nanoTime() >= deadlineNanos) { timedOut = true; break; }
            try {
                boolean matched = classMatchesInLocationWithMode(cls, searchTerm, term,
                    searchLocations, matchMode, compiledRegex);
                if (matched && matchedClasses.add(cls.getFullName())) {
                    int total = totalMatches.incrementAndGet();
                    if (!collectAllResults && total >= resultsNeeded) cancelled.set(true);
                }
            } catch (Exception ignored) {}
        }

        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startTimeMs);
        Map<String, Object> searchInfo = new HashMap<>();
        searchInfo.put("total_found", totalMatches.get());
        searchInfo.put("total_classes", allClasses.size());
        searchInfo.put("filtered_classes", filteredClasses.size());
        searchInfo.put("elapsed_seconds", TimeUnit.MILLISECONDS.toSeconds(elapsedMs));
        searchInfo.put("timed_out", timedOut);
        searchInfo.put("parallel_batches", 0);
        searchInfo.put("search_locations", searchLocations.toString());

        CodeSearchCoordinator.SearchResult result = new CodeSearchCoordinator.SearchResult(
            buildOrderedMatchList(filteredClasses, matchedClasses), searchInfo, matchMode.name().toLowerCase());
        return new SearchExecution(result, elapsedMs, timedOut);
    }

    private boolean classMatchesInLocationWithMode(
        JavaClass cls, String searchTerm, String termLower,
        Set<SearchLocation> searchLocations, MatchMode matchMode, Pattern compiledRegex
    ) {
        for (SearchLocation location : searchLocations) {
            if (location == SearchLocation.CODE || location == SearchLocation.COMMENT) continue;
            switch (location) {
                case CLASS_NAME:
                    if (nameMatches(cls.getName(), searchTerm, matchMode, compiledRegex)) return true;
                    break;
                case METHOD_NAME:
                    for (JadxApiAdapter.MethodInfoSnapshot ms : JadxApiAdapter.getDeclaredMethodInfos(cls)) {
                        if (nameMatches(ms.getName(), searchTerm, matchMode, compiledRegex)) return true;
                    }
                    break;
                case FIELD_NAME:
                    for (JadxApiAdapter.FieldInfoSnapshot fs : JadxApiAdapter.getDeclaredFieldInfos(cls)) {
                        if (nameMatches(fs.getName(), searchTerm, matchMode, compiledRegex)) return true;
                    }
                    break;
                default: break;
            }
        }
        return false;
    }

    private boolean nameMatches(String candidate, String term, MatchMode mode, Pattern compiledRegex) {
        if (candidate == null) return false;
        switch (mode) {
            case EXACT:    return candidate.equalsIgnoreCase(term);
            case PREFIX:   return candidate.toLowerCase().startsWith(term.toLowerCase());
            case REGEX:    return compiledRegex != null && compiledRegex.matcher(candidate).find();
            case SUBSTRING:
            default:       return candidate.toLowerCase().contains(term.toLowerCase());
        }
    }

    List<JavaClass> filterSearchClasses(
        List<JavaClass> allClasses, String packageFilter, List<String> excludePrefixes
    ) {
        boolean applyPackageFilter = isValidPackageFilter(packageFilter);
        if (!applyPackageFilter && excludePrefixes.isEmpty()) return allClasses;

        List<JavaClass> filteredClasses = new ArrayList<>();
        for (JavaClass cls : allClasses) {
            if (applyPackageFilter && !matchesPackageFilter(cls, packageFilter)) continue;
            if (!excludePrefixes.isEmpty()) {
                boolean excluded = false;
                for (String prefix : excludePrefixes) {
                    if (cls.getFullName().startsWith(prefix)) { excluded = true; break; }
                }
                if (excluded) continue;
            }
            filteredClasses.add(cls);
        }
        return filteredClasses;
    }

    private boolean classMatchesAnyMetadataLocation(JavaClass cls, String term, Set<SearchLocation> locations) {
        for (SearchLocation location : locations) {
            if (location == SearchLocation.CODE || location == SearchLocation.COMMENT) continue;
            switch (location) {
                case CLASS_NAME:
                    if (cls.getName().toLowerCase().contains(term)) return true;
                    break;
                case METHOD_NAME:
                    for (JadxApiAdapter.MethodInfoSnapshot ms : JadxApiAdapter.getDeclaredMethodInfos(cls)) {
                        if (ms.getName().toLowerCase().contains(term)) return true;
                        if (ms.isConstructor() && cls.getName().toLowerCase().contains(term)) return true;
                    }
                    break;
                case FIELD_NAME:
                    for (JadxApiAdapter.FieldInfoSnapshot fs : JadxApiAdapter.getDeclaredFieldInfos(cls)) {
                        if (fs.getName().toLowerCase().contains(term)) return true;
                    }
                    break;
                default: break;
            }
        }
        return false;
    }

    private boolean classMatchesAnyContentLocation(
        JavaClass cls, String term, Set<SearchLocation> locations, MatchMode matchMode, Pattern compiledRegex
    ) {
        if (!requiresContentSearch(locations)) return false;
        try {
            // Resolve the class source original-case, cheapest source first:
            //   1. jadx in-memory ICodeCache — present only while the class is hot; every warmup
            //      target is unload()'d after Phase-1, so this misses for most classes.
            //   2. persistent CodeStore (gzipped disk read, ms) — populated for every warmup target.
            //      This is the win: a broad scan over ~250k warmed-but-trigram-evicted classes reads
            //      their source from disk instead of live re-decompiling each (the 60-100s culprit).
            //   3. live decompile (cls.getCode(), lock-guarded, seconds) — last resort, only for
            //      classes never persisted (skipped libraries).
            String originalCode = ClassCacheManager.getCachedCodeDirect(cls);
            if (originalCode == null) originalCode = codeFromStore(cls);

            String normalizedCode;
            if (originalCode != null) {
                normalizedCode = originalCode.toLowerCase();
                CodeContentIndex.index(cls, normalizedCode);
            } else {
                Thread searchThread = Thread.currentThread();
                ScheduledFuture<?> watchdog = DECOMPILE_WATCHDOG.schedule(
                    () -> {
                        logger.warn("[JAI] getCode() exceeded {}s for {}, interrupting search thread",
                            PER_CLASS_DECOMPILE_TIMEOUT_SECONDS, cls.getFullName());
                        searchThread.interrupt();
                    },
                    PER_CLASS_DECOMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                if (!JadxSearchLock.tryAcquire(PER_CLASS_DECOMPILE_TIMEOUT_SECONDS)) {
                    watchdog.cancel(false);
                    return false;
                }
                try {
                    normalizedCode = ClassCacheManager.getCodeAndIndex(cls);
                } finally {
                    JadxSearchLock.release();
                    watchdog.cancel(false);
                    Thread.interrupted();
                }
            }

            if (normalizedCode == null) return false;
            if (locations.contains(SearchLocation.CODE)) {
                boolean codeMatched = (matchMode == MatchMode.REGEX && compiledRegex != null)
                    ? compiledRegex.matcher(normalizedCode).find()
                    : normalizedCode.contains(term);
                if (codeMatched) return true;
            }
            if (locations.contains(SearchLocation.COMMENT)) {
                // COMMENT match needs original-case source. Reuse what we already resolved above;
                // only re-fetch from the in-memory cache when we went the live-decompile path
                // (which just populated it) and thus never held original-case source.
                String forComment = originalCode != null
                    ? originalCode
                    : ClassCacheManager.getCachedCodeDirect(cls);
                if (forComment != null) return CodeSearchCoordinator.matchesCommentContent(forComment, term);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reads a class's persisted decompiled source from the CodeStore (ms-level gz disk read),
     * or {@code null} if the store is not yet initialised or the class was never persisted
     * (e.g. a skipped library class). Keyed by raw name to match {@code CodeStore.put} in
     * {@link WarmupManager} (deobf-stable).
     */
    private static String codeFromStore(JavaClass cls) {
        try {
            CodeStore cs = WarmupManager.codeStore();
            if (cs == null) return null;
            String rawName = cls.getRawName();
            if (rawName == null || rawName.isEmpty()) return null;
            return cs.get(rawName);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean requiresContentSearch(Set<SearchLocation> locations) {
        return locations.contains(SearchLocation.CODE) || locations.contains(SearchLocation.COMMENT);
    }

    /**
     * W13: true only when {@code locations} is content-only (CODE and/or COMMENT, no metadata
     * location). The broad-word guard in {@link #executeSearch} must gate on this rather than on
     * {@link #requiresContentSearch}, because a search that mixes a metadata location (CLASS_NAME/
     * METHOD_NAME/FIELD_NAME) with a content location still needs the normal full path to run --
     * {@link #executeBroadTermSearch} only samples shard content candidates and never performs
     * metadata matching, so short-circuiting into it for a mixed search silently drops class/
     * method/field name matches.
     */
    boolean isContentOnly(Set<SearchLocation> locations) {
        return locations != null && !locations.isEmpty()
            && !locations.contains(SearchLocation.CLASS_NAME)
            && !locations.contains(SearchLocation.METHOD_NAME)
            && !locations.contains(SearchLocation.FIELD_NAME);
    }

    private List<String> buildOrderedMatchList(List<JavaClass> filteredClasses, Set<String> matchedClasses) {
        List<String> ordered = new ArrayList<>();
        for (JavaClass cls : filteredClasses) {
            if (matchedClasses.contains(cls.getFullName())) ordered.add(cls.getFullName());
        }
        return ordered;
    }

    private Map<String, Object> buildSearchResponse(
        CodeSearchCoordinator.SearchResult result, int offset, int count, MatchMode matchMode
    ) {
        List<String> matches = result.getMatches();
        List<String> paginatedResults = new ArrayList<>();
        for (int i = offset; i < Math.min(offset + count, matches.size()); i++) {
            paginatedResults.add(matches.get(i));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("type", "class-list");
        response.put("classes", paginatedResults);
        response.put("offset", offset);
        response.put("count", paginatedResults.size());
        response.put("has_more", matches.size() > offset + paginatedResults.size());
        response.put("next_offset", offset + paginatedResults.size());
        response.put("search_info", result.getSearchInfo());
        // Echo the mode that actually computed these matches (carried on the result itself),
        // not the match_mode query param of *this* request — a /code-search-status poll can omit
        // or vary match_mode from what submit-code-search originally used, and the response must
        // reflect reality rather than restate the caller's (possibly stale/absent) input.
        String effectiveMode = result.getMatchMode() != null ? result.getMatchMode() : matchMode.name().toLowerCase();
        response.put("match_mode", effectiveMode);
        return response;
    }

    /**
     * Rejects negative offset/count before they reach {@link #buildSearchResponse}, where
     * {@code matches.get(offset)} would otherwise throw IndexOutOfBoundsException and surface
     * as an opaque 500 instead of a client input error.
     *
     * @return true if the request was invalid and a 400 response was already written
     */
    private boolean validatePaginationParams(Context ctx, int offset, int count) {
        if (offset < 0) {
            ctx.status(400).json(Map.of("error", "'offset' must be >= 0, got: " + offset));
            return true;
        }
        if (count < 0) {
            ctx.status(400).json(Map.of("error", "'count' must be >= 0, got: " + count));
            return true;
        }
        return false;
    }

    void sendSearchDecompilationBusyResponse(Context ctx) {
        ctx.status(503).json(Map.of(
            "error", "Search/decompilation operation in progress",
            "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS
        ));
    }

    private Set<SearchLocation> parseSearchLocations(String searchIn) {
        Set<SearchLocation> locations = EnumSet.noneOf(SearchLocation.class);
        if (searchIn == null || searchIn.trim().isEmpty()) {
            locations.add(SearchLocation.CODE);
            return locations;
        }
        for (String part : searchIn.toLowerCase().split(",")) {
            String trimmed = part.trim();
            SearchLocation loc = SEARCH_LOCATION_MAP.get(trimmed);
            if (loc != null) {
                locations.add(loc);
            } else {
                logger.warn("Invalid search location '{}', ignoring.", trimmed);
            }
        }
        if (locations.isEmpty()) locations.add(SearchLocation.CODE);
        return locations;
    }

    private MatchMode parseMatchMode(String raw) {
        if (raw == null || raw.isBlank()) return MatchMode.SUBSTRING;
        switch (raw.toLowerCase().trim()) {
            case "exact":    return MatchMode.EXACT;
            case "prefix":   return MatchMode.PREFIX;
            case "regex":    return MatchMode.REGEX;
            default:         return MatchMode.SUBSTRING;
        }
    }

    private boolean isValidPackageFilter(String packageFilter) {
        if (packageFilter == null || packageFilter.trim().isEmpty()) return false;
        if (packageFilter.equals("defpackage")) return false;
        String firstPart = packageFilter.split("\\.")[0];
        return !OBFUSCATED_PACKAGE_PATTERN.matcher(firstPart).matches();
    }

    boolean matchesPackageFilter(JavaClass cls, String packageFilter) {
        if (packageFilter == null || packageFilter.trim().isEmpty()) return true;
        String fullName = cls.getFullName();
        return fullName.startsWith(packageFilter + ".") || fullName.equals(packageFilter);
    }

    private List<List<JavaClass>> splitIntoBatches(List<JavaClass> classes, int batchCount) {
        List<List<JavaClass>> batches = new ArrayList<>();
        if (classes.isEmpty()) return batches;
        int batchSize = Math.max(1, (classes.size() + batchCount - 1) / batchCount);
        for (int i = 0; i < classes.size(); i += batchSize) {
            batches.add(classes.subList(i, Math.min(i + batchSize, classes.size())));
        }
        return batches;
    }

    // ------------------------------- Inner types -------------------------------

    static final class SearchExecution {
        private final CodeSearchCoordinator.SearchResult result;
        private final long elapsedMs;
        private final boolean timedOut;

        SearchExecution(CodeSearchCoordinator.SearchResult result, long elapsedMs, boolean timedOut) {
            this.result = result;
            this.elapsedMs = elapsedMs;
            this.timedOut = timedOut;
        }

        public CodeSearchCoordinator.SearchResult getResult() { return result; }
        public long getElapsedMs() { return elapsedMs; }
        public boolean isTimedOut() { return timedOut; }
    }
}
