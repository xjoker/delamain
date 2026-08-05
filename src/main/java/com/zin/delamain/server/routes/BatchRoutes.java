package com.zin.delamain.server.routes;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.JadxApiAdapter;
import com.zin.delamain.utils.JadxSearchLock;
import com.zin.delamain.utils.ManifestInfoService;
import com.zin.delamain.utils.SmartChunker;

import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.JavaClass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles batch and multi-class decompilation MCP endpoints (headless port).
 */
public class BatchRoutes {
    private static final Logger logger = LoggerFactory.getLogger(BatchRoutes.class);

    static final int INLINE_RESPONSE_MAX_BYTES;
    static {
        int threshold = 32768;
        String envVal = System.getenv("DELAMAIN_INLINE_RESPONSE_MAX_BYTES");
        if (envVal != null && !envVal.isEmpty()) {
            try {
                threshold = Integer.parseInt(envVal.trim());
            } catch (NumberFormatException ignored) {
                LoggerFactory.getLogger(BatchRoutes.class)
                    .warn("Invalid DELAMAIN_INLINE_RESPONSE_MAX_BYTES='{}', using default 32768", envVal);
            }
        }
        INLINE_RESPONSE_MAX_BYTES = threshold;
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HeadlessJadxWrapper wrapper;
    private final SearchRoutes searchRoutes;
    private final ManifestInfoService manifestInfoService = ManifestInfoService.getInstance();

    public BatchRoutes(HeadlessJadxWrapper wrapper, SearchRoutes searchRoutes) {
        this.wrapper = wrapper;
        this.searchRoutes = searchRoutes;
    }

    public void register(Javalin app, AuthConfig authConfig) {
        app.get("/batch-class-source", this::handleBatchClassSource);
        app.get("/main-activity", this::handleMainActivity);
    }

    // ------------------------------- Request Handlers --------------------------

    public void handleBatchClassSource(Context ctx) {
        String classNamesParam = ctx.queryParam("class_names");
        if (classNamesParam == null || classNamesParam.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing 'class_names' parameter. Provide comma-separated class names."));
            return;
        }

        int offset = 0;
        String offsetParam = ctx.queryParam("offset");
        if (offsetParam != null && !offsetParam.isEmpty()) {
            try {
                offset = Integer.parseInt(offsetParam.trim());
                if (offset < 0) {
                    ctx.status(400).json(Map.of("error", "Offset must be non-negative, got: " + offset));
                    return;
                }
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid offset parameter: " + offsetParam));
                return;
            }
        }

        String[] classNames = classNamesParam.split(",");
        final int MAX_BATCH_SIZE = 20;
        if (classNames.length > MAX_BATCH_SIZE) {
            ctx.status(400).json(Map.of("error",
                "Too many classes requested. Maximum " + MAX_BATCH_SIZE + " classes per request."));
            return;
        }

        try {
            if (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
                ClassCacheManager.initCache(wrapper);
            }

            ClassCacheManager.CacheStatus status = ClassCacheManager.getStatus();
            if (status == ClassCacheManager.CacheStatus.LOADING) {
                Map<String, Object> health = ClassCacheManager.getHealthInfo();
                Map<String, Object> response = new HashMap<>();
                response.put("status", "loading");
                response.put("type", "batch-class-source");
                response.put("message", "Class cache is being loaded in background.");
                response.put("retry_after", 10);
                response.put("health", health);
                ctx.json(response);
                return;
            }

            Map<String, JavaClass> classMap = ClassCacheManager.getCache();
            List<Map<String, Object>> results = new ArrayList<>();
            int foundCount = 0;

            List<String> needDecompile = new ArrayList<>();
            Map<String, String> cachedResults = new HashMap<>();
            Map<String, JavaClass> resolvedClasses = new HashMap<>();

            for (String className : classNames) {
                String trimmedName = className.trim();
                JavaClass resolvedClass = ClassCacheManager.findClass(classMap, trimmedName);
                if (resolvedClass != null) resolvedClasses.put(trimmedName, resolvedClass);

                String cacheKey = resolvedClass != null ? resolvedClass.getFullName() : trimmedName;
                String cachedCode = ClassCacheManager.getCachedCode(cacheKey);
                if (cachedCode != null) {
                    cachedResults.put(trimmedName, cachedCode);
                } else if (resolvedClass != null) {
                    needDecompile.add(trimmedName);
                }
            }

            Map<String, String> decompiledResults = new HashMap<>();
            if (!needDecompile.isEmpty()) {
                if (!JadxSearchLock.tryAcquire()) {
                    ctx.status(503).json(Map.of(
                        "error", "Decompilation operation in progress",
                        "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS));
                    return;
                }
                try {
                    for (String name : needDecompile) {
                        String code = ClassCacheManager.getCachedCode(name);
                        if (code != null) { decompiledResults.put(name, code); continue; }
                        JavaClass cls = resolvedClasses.get(name);
                        if (cls != null) {
                            try {
                                code = cls.getCode();
                                ClassCacheManager.putCachedCode(cls.getFullName(), code);
                                decompiledResults.put(name, code);
                            } catch (Exception e) {
                                logger.error("Decompilation failed for {}: {}", name, e.getMessage());
                                decompiledResults.put(name, null);
                            }
                        }
                    }
                } finally {
                    JadxSearchLock.release();
                }
            }

            for (String className : classNames) {
                String trimmedName = className.trim();
                Map<String, Object> classResult = new HashMap<>();
                classResult.put("name", trimmedName);
                // Frida/Xposed can only target the original (pre-deobfuscation) class name; surface
                // it whenever the requested name resolved to a real class (see JadxApiAdapter's
                // raw-name contract).
                JavaClass resolvedForRawName = resolvedClasses.get(trimmedName);
                if (resolvedForRawName != null) {
                    classResult.put("raw_name", JadxApiAdapter.getClassRawName(resolvedForRawName));
                }

                if (cachedResults.containsKey(trimmedName)) {
                    classResult.put("found", true);
                    classResult.put("content", cachedResults.get(trimmedName));
                    foundCount++;
                } else if (decompiledResults.containsKey(trimmedName)) {
                    String code = decompiledResults.get(trimmedName);
                    if (code != null) {
                        classResult.put("found", true);
                        classResult.put("content", code);
                        foundCount++;
                    } else {
                        classResult.put("found", true);
                        classResult.put("error", "Decompilation failed (see server log)");
                    }
                } else if (!resolvedClasses.containsKey(trimmedName)) {
                    classResult.put("found", false);
                    classResult.put("error", "Class not found");
                } else {
                    classResult.put("found", false);
                    classResult.put("error", "Decompilation not attempted");
                }
                results.add(classResult);
            }

            // force_raw is a debug escape hatch: dump every requested class in one unpaginated
            // response, bypassing the byte-budget pagination below. Not used by the gateway.
            boolean forceRaw = "true".equalsIgnoreCase(ctx.queryParam("force_raw"));
            if (forceRaw) {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "success");
                response.put("classes", results);
                response.put("total", classNames.length);
                response.put("found", foundCount);
                ctx.json(response);
                return;
            }

            // Class-granularity pagination: each page carries only whole, structurally-complete
            // class result objects (never a JSON byte fragment) within a byte budget, so an
            // oversized batch degrades to "call again with next_offset" instead of failing outright.
            ctx.json(buildPagedBatchResponse(results, offset, INLINE_RESPONSE_MAX_BYTES));

        } catch (Exception e) {
            logger.error("Internal error retrieving batch class sources: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error",
                "Internal error retrieving batch class sources: " + e.getMessage()));
        }
    }

    public void handleMainActivity(Context ctx) {
        boolean lockAcquired = false;
        try {
            int chunk = 0;
            String chunkParam = ctx.queryParam("chunk");
            if (chunkParam != null && !chunkParam.isEmpty()) {
                try {
                    chunk = Integer.parseInt(chunkParam.trim());
                } catch (NumberFormatException e) {
                    ctx.status(400).json(Map.of("error", "Invalid chunk parameter: " + chunkParam));
                    return;
                }
            }

            // Check if manifest is available
            if (!manifestInfoService.hasManifest(wrapper.getJadx())) {
                com.zin.delamain.utils.NotApplicableResponse.sendMainActivityNotAvailable(
                    ctx, detectFileType());
                return;
            }

            String mainActivityName = manifestInfoService.getMainActivity(wrapper.getJadx());
            if (mainActivityName == null) {
                ctx.status(404).json(Map.of("error", "Main activity not found in manifest."));
                return;
            }

            // Find class
            JavaClass mainActivityClass = null;
            for (JavaClass cls : wrapper.getClassesWithInners()) {
                if (cls.getFullName().equals(mainActivityName) || cls.getRawName().equals(mainActivityName)) {
                    mainActivityClass = cls;
                    break;
                }
            }

            if (mainActivityClass == null) {
                ctx.status(404).json(Map.of("error", "Failed to get activity class: " + mainActivityName));
                return;
            }

            if (!JadxSearchLock.tryAcquire()) {
                searchRoutes.sendSearchDecompilationBusyResponse(ctx);
                return;
            }
            lockAcquired = true;

            String code = mainActivityClass.getCode();
            Map<String, Object> result = SmartChunker.chunkResponse(code, chunk, "content");
            result.put("name", mainActivityClass.getFullName());
            result.put("raw_name", JadxApiAdapter.getClassRawName(mainActivityClass));
            result.put("type", "code/java");

            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error getting main activity: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error",
                "Internal error while trying to get Main Activity class code: " + e.getMessage()));
        } finally {
            if (lockAcquired) JadxSearchLock.release();
        }
    }

    // ------------------------------- Class-granularity batch pagination --------

    /**
     * Slices an already-resolved list of {@code /batch-class-source} class result objects into
     * one page, starting at {@code offset}, that stays within {@code byteBudget} serialized bytes.
     * Each page is self-contained: every entry is the complete {name, found, content/error} object
     * built by {@link #handleBatchClassSource}, never a partial JSON fragment.
     *
     * <p>A single class whose own serialized size exceeds {@code byteBudget} is still returned
     * alone (flagged {@code oversize_single: true}) so pagination always makes forward progress
     * and never returns an empty page while items remain.</p>
     */
    static Map<String, Object> buildPagedBatchResponse(
            List<Map<String, Object>> allResults, int offset, int byteBudget) {
        int total = allResults.size();
        int start = Math.max(0, Math.min(offset, total));

        List<Map<String, Object>> page = new ArrayList<>();
        int bytesUsed = 0;
        int i = start;
        while (i < total) {
            Map<String, Object> cls = allResults.get(i);
            int size = estimateClassResultBytes(cls);
            if (page.isEmpty()) {
                page.add(cls);
                bytesUsed = size;
                if (size > byteBudget) {
                    cls.put("oversize_single", true);
                }
                i++;
                continue;
            }
            if (bytesUsed + size > byteBudget) {
                break;
            }
            page.add(cls);
            bytesUsed += size;
            i++;
        }

        int foundInPage = 0;
        for (Map<String, Object> cls : page) {
            if (Boolean.TRUE.equals(cls.get("found"))) foundInPage++;
        }
        boolean hasMore = i < total;

        Map<String, Object> response = new HashMap<>();
        response.put("status", "success");
        response.put("classes", page);
        response.put("total", total);
        response.put("returned", page.size());
        response.put("offset", start);
        response.put("found", foundInPage);
        response.put("has_more", hasMore);
        if (hasMore) response.put("next_offset", i);
        return response;
    }

    private static int estimateClassResultBytes(Map<String, Object> cls) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(cls).length;
        } catch (Exception e) {
            int estimate = 64;
            Object content = cls.get("content");
            if (content instanceof String) estimate += ((String) content).length();
            Object err = cls.get("error");
            if (err instanceof String) estimate += ((String) err).length();
            Object name = cls.get("name");
            if (name instanceof String) estimate += ((String) name).length();
            return estimate;
        }
    }

    // ------------------------------- Pagination helpers ------------------------

    private String detectFileType() {
        List<java.io.File> inputFiles = wrapper.getInputFiles();
        if (inputFiles == null || inputFiles.isEmpty()) return "unknown";
        String name = inputFiles.get(0).getName().toLowerCase();
        if (name.endsWith(".apk")) return "apk";
        if (name.endsWith(".aar")) return "aar";
        if (name.endsWith(".dex")) return "dex";
        if (name.endsWith(".jar")) return "jar";
        return "unknown";
    }

}
