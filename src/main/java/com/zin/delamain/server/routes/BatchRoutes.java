package com.zin.delamain.server.routes;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.JadxApiAdapter;
import com.zin.delamain.utils.JadxSearchLock;
import com.zin.delamain.utils.ManifestInfoService;
import com.zin.delamain.utils.PaginationUtils;
import com.zin.delamain.utils.SmartChunker;

import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.ResourceFile;
import jadx.core.utils.android.AndroidManifestParser;
import jadx.core.utils.exceptions.JadxRuntimeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final int STREAM_FLUSH_EVERY = 16;

    private final HeadlessJadxWrapper wrapper;
    private final PaginationUtils paginationUtils;
    private final SearchRoutes searchRoutes;
    private final ManifestInfoService manifestInfoService = ManifestInfoService.getInstance();

    public BatchRoutes(HeadlessJadxWrapper wrapper, PaginationUtils paginationUtils, SearchRoutes searchRoutes) {
        this.wrapper = wrapper;
        this.paginationUtils = paginationUtils;
        this.searchRoutes = searchRoutes;
    }

    public void register(Javalin app, AuthConfig authConfig) {
        app.get("/batch-class-source", this::handleBatchClassSource);
        app.get("/main-application-classes-names", this::handleMainApplicationClassesNames);
        app.get("/main-activity", this::handleMainActivity);
        app.get("/main-application-classes-code", this::handleMainApplicationClassesCode);
    }

    // ------------------------------- Request Handlers --------------------------

    public void handleBatchClassSource(Context ctx) {
        String classNamesParam = ctx.queryParam("class_names");
        if (classNamesParam == null || classNamesParam.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing 'class_names' parameter. Provide comma-separated class names."));
            return;
        }

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

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("classes", results);
            response.put("total", classNames.length);
            response.put("found", foundCount);

            boolean forceChunk = "true".equalsIgnoreCase(ctx.queryParam("force_chunk"));
            boolean forceRaw   = "true".equalsIgnoreCase(ctx.queryParam("force_raw"));

            if (forceChunk) {
                String responseJson = new Gson().toJson(response);
                Map<String, Object> chunkedResponse = SmartChunker.chunkResponse(responseJson, chunk, "batch_result");
                ctx.json(chunkedResponse);
                return;
            }

            if (forceRaw) { ctx.json(response); return; }

            int estimatedBytes = 512;
            for (Map<String, Object> cls : results) {
                Object content = cls.get("content");
                if (content instanceof String) estimatedBytes += ((String) content).length();
                Object err = cls.get("error");
                if (err instanceof String) estimatedBytes += ((String) err).length();
                Object name = cls.get("name");
                if (name instanceof String) estimatedBytes += ((String) name).length() + 32;
            }

            if (estimatedBytes <= INLINE_RESPONSE_MAX_BYTES) {
                ctx.json(response);
            } else {
                int actualBytes;
                try {
                    actualBytes = OBJECT_MAPPER.writeValueAsBytes(response).length;
                } catch (Exception serEx) {
                    logger.warn("Size check serialization failed: {}", serEx.getMessage());
                    ctx.json(response);
                    return;
                }
                if (actualBytes <= INLINE_RESPONSE_MAX_BYTES) {
                    ctx.json(response);
                } else {
                    Map<String, Object> transferHint = new HashMap<>();
                    transferHint.put("response_too_large", true);
                    transferHint.put("size_bytes", actualBytes);
                    transferHint.put("items_count", classNames.length);
                    transferHint.put("found", foundCount);
                    transferHint.put("message",
                        "Response exceeds inline threshold (" + INLINE_RESPONSE_MAX_BYTES + " bytes). "
                        + "Reduce class count or request individual classes.");
                    ctx.json(transferHint);
                }
            }

        } catch (Exception e) {
            logger.error("Internal error retrieving batch class sources: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error",
                "Internal error retrieving batch class sources: " + e.getMessage()));
        }
    }

    public void handleMainApplicationClassesNames(Context ctx) {
        try {
            List<ResourceFile> resources = wrapper.getJadx().getResources();

            ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(resources);
            if (manifestRes == null) {
                ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
                return;
            }

            String manifestXml = manifestRes.loadContent().getText().getCodeStr();
            Document manifestDoc = parseManifestXml(manifestXml);
            Element manifestElement = (Element) manifestDoc.getElementsByTagName("manifest").item(0);
            String packageName = manifestElement.getAttribute("package");

            if (packageName.isEmpty()) {
                ctx.status(404).json(Map.of("error", "Package name not found in AndroidManifest.xml"));
                return;
            }

            List<JavaClass> matchedClasses = wrapper.getJadx().getClassesWithInners().stream()
                    .filter(cls -> cls.getFullName().startsWith(packageName))
                    .collect(Collectors.toList());

            List<Map<String, Object>> classesInfo = new ArrayList<>();
            for (JavaClass cls : matchedClasses) {
                Map<String, Object> classInfo = new HashMap<>();
                classInfo.put("name", cls.getFullName());
                classInfo.put("raw_name", JadxApiAdapter.getClassRawName(cls));
                classesInfo.add(classInfo);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("classes", classesInfo);
            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error fetching main application classes names: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error",
                "Internal error while trying to fetch all classes names: " + e.getMessage()));
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

    public void handleMainApplicationClassesCode(Context ctx) {
        try {
            List<ResourceFile> resources = wrapper.getJadx().getResources();
            ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(resources);
            if (manifestRes == null) {
                ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
                return;
            }

            String manifestXml = manifestRes.loadContent().getText().getCodeStr();
            Document manifestDoc = parseManifestXml(manifestXml);
            Element manifestElement = (Element) manifestDoc.getElementsByTagName("manifest").item(0);
            String packageName = manifestElement.getAttribute("package");

            if (packageName.isEmpty()) {
                ctx.status(404).json(Map.of("error", "Package name not found in AndroidManifest.xml"));
                return;
            }

            logger.info("delamain: Package name: {}", packageName);
            List<JavaClass> matchedClasses = wrapper.getJadx().getClassesWithInners().stream()
                    .filter(cls -> cls.getFullName().startsWith(packageName))
                    .collect(Collectors.toList());
            logger.info("delamain: Found {} classes in package {}", matchedClasses.size(), packageName);

            PaginationWindow paginationWindow = resolvePaginationWindow(ctx, matchedClasses.size());
            boolean forceChunk = "true".equalsIgnoreCase(ctx.queryParam("force_chunk"));
            if (forceChunk) {
                handleMainApplicationClassesCodeBuffered(ctx, matchedClasses, paginationWindow);
                return;
            }

            // Streaming path
            List<String[]> decompiledPage = new ArrayList<>();
            if (paginationWindow.hasResults()) {
                if (!JadxSearchLock.tryAcquire()) {
                    searchRoutes.sendSearchDecompilationBusyResponse(ctx);
                    return;
                }
                try {
                    for (JavaClass cls : matchedClasses.subList(
                            paginationWindow.getStartIndex(), paginationWindow.getEndIndex())) {
                        String fullName = cls.getFullName();
                        String rawName = JadxApiAdapter.getClassRawName(cls);
                        String content;
                        try {
                            content = cls.getCode();
                            logger.debug("delamain: Decompiled {} ({} chars)", fullName, content.length());
                        } catch (Exception e) {
                            logger.warn("Failed to decompile class {}: {}", fullName, e.getMessage());
                            content = "// Error decompiling class: " + e.getMessage();
                        }
                        decompiledPage.add(new String[]{fullName, rawName, content});
                    }
                } finally {
                    JadxSearchLock.release();
                }
            }

            ctx.contentType("application/json");
            OutputStream out = ctx.outputStream();
            try (JsonGenerator gen = JSON_FACTORY.createGenerator(out)) {
                gen.writeStartObject();
                gen.writeStringField("type", "application-classes");
                gen.writeNumberField("requested_count", paginationWindow.getRequestedLimit());

                gen.writeObjectFieldStart("pagination");
                gen.writeNumberField("total", matchedClasses.size());
                gen.writeNumberField("offset", paginationWindow.getOffset());
                gen.writeNumberField("limit", paginationWindow.getLimit());
                gen.writeNumberField("count", decompiledPage.size());
                gen.writeBooleanField("has_more", paginationWindow.hasMore());
                if (paginationWindow.hasMore()) gen.writeNumberField("next_offset", paginationWindow.getNextOffset());
                if (paginationWindow.getOffset() > 0) {
                    int prevOffset = Math.max(0, paginationWindow.getOffset() - paginationWindow.getLimit());
                    gen.writeNumberField("prev_offset", prevOffset);
                }
                if (paginationWindow.getLimit() > 0) {
                    int currentPage = (paginationWindow.getOffset() / paginationWindow.getLimit()) + 1;
                    int totalPages = (int) Math.ceil((double) matchedClasses.size() / paginationWindow.getLimit());
                    gen.writeNumberField("current_page", currentPage);
                    gen.writeNumberField("total_pages", totalPages);
                    gen.writeNumberField("page_size", paginationWindow.getLimit());
                }
                gen.writeEndObject(); // pagination

                gen.writeArrayFieldStart("classes");
                int written = 0;
                for (String[] entry : decompiledPage) {
                    gen.writeStartObject();
                    gen.writeStringField("name", entry[0]);
                    gen.writeStringField("raw_name", entry[1]);
                    gen.writeStringField("type", "code/java");
                    gen.writeStringField("content", entry[2]);
                    gen.writeEndObject();
                    written++;
                    if (written % STREAM_FLUSH_EVERY == 0) gen.flush();
                }
                gen.writeEndArray();
                gen.writeEndObject();
            }
        } catch (PaginationUtils.PaginationException e) {
            logger.error("Pagination error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error",
                "Pagination error in handleMainApplicationClassesCode: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Error in handleMainApplicationClassesCode: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error",
                "Internal error retrieving main application classes' code: " + e.getMessage()));
        }
    }

    private void handleMainApplicationClassesCodeBuffered(
            Context ctx, List<JavaClass> matchedClasses, PaginationWindow paginationWindow) {

        List<Map<String, Object>> classInfoList = new ArrayList<>();
        if (paginationWindow.hasResults()) {
            if (!JadxSearchLock.tryAcquire()) {
                searchRoutes.sendSearchDecompilationBusyResponse(ctx);
                return;
            }
            try {
                for (JavaClass cls : matchedClasses.subList(
                        paginationWindow.getStartIndex(), paginationWindow.getEndIndex())) {
                    Map<String, Object> classInfo = new HashMap<>();
                    classInfo.put("name", cls.getFullName());
                    classInfo.put("raw_name", JadxApiAdapter.getClassRawName(cls));
                    classInfo.put("type", "code/java");
                    try {
                        classInfo.put("content", cls.getCode());
                    } catch (Exception e) {
                        classInfo.put("content", "// Error decompiling class: " + e.getMessage());
                    }
                    classInfoList.add(classInfo);
                }
            } finally {
                JadxSearchLock.release();
            }
        }

        Map<String, Object> result = buildPaginatedResponse(
            classInfoList, matchedClasses.size(), paginationWindow, "application-classes", "classes");

        int estimatedBytes = 512;
        for (Map<String, Object> cls : classInfoList) {
            Object content = cls.get("content");
            if (content instanceof String) estimatedBytes += ((String) content).length();
            Object name = cls.get("name");
            if (name instanceof String) estimatedBytes += ((String) name).length() + 32;
        }

        if (estimatedBytes <= INLINE_RESPONSE_MAX_BYTES) {
            ctx.json(result);
            return;
        }
        int actualBytes;
        try {
            actualBytes = OBJECT_MAPPER.writeValueAsBytes(result).length;
        } catch (Exception serEx) {
            ctx.json(result);
            return;
        }
        if (actualBytes <= INLINE_RESPONSE_MAX_BYTES) {
            ctx.json(result);
        } else {
            Map<String, Object> transferHint = new HashMap<>();
            transferHint.put("response_too_large", true);
            transferHint.put("size_bytes", actualBytes);
            transferHint.put("total_classes", matchedClasses.size());
            transferHint.put("message", "Use smaller count or omit force_chunk=true for streaming.");
            ctx.json(transferHint);
        }
    }

    // ------------------------------- Pagination helpers ------------------------

    PaginationWindow resolvePaginationWindow(Context ctx, int totalItems) throws PaginationUtils.PaginationException {
        String offsetParam = ctx.queryParam("offset");
        String limitParam = ctx.queryParam("limit");
        String countParam = ctx.queryParam("count");
        String pageSizeParam = limitParam != null ? limitParam : countParam;

        int offset = 0;
        int requestedLimit = 0;
        boolean hasCustomLimit = pageSizeParam != null && !pageSizeParam.isEmpty();

        if (offsetParam != null && !offsetParam.isEmpty()) {
            try {
                offset = Integer.parseInt(offsetParam.trim());
                if (offset < 0) throw paginationUtils.new PaginationException("Offset must be non-negative, got: " + offset);
                if (offset > paginationUtils.MAX_OFFSET) throw paginationUtils.new PaginationException("Offset too large, maximum: " + paginationUtils.MAX_OFFSET);
            } catch (NumberFormatException e) {
                throw paginationUtils.new PaginationException("Invalid offset format: '" + offsetParam + "'");
            }
        }

        if (hasCustomLimit) {
            try {
                requestedLimit = Integer.parseInt(pageSizeParam.trim());
                if (requestedLimit < 0) throw paginationUtils.new PaginationException("Limit must be non-negative, got: " + requestedLimit);
                if (requestedLimit > paginationUtils.MAX_PAGE_SIZE) throw paginationUtils.new PaginationException("Limit too large, maximum: " + paginationUtils.MAX_PAGE_SIZE);
            } catch (NumberFormatException e) {
                throw paginationUtils.new PaginationException("Invalid limit format: '" + pageSizeParam + "'");
            }
        }

        int effectiveLimit;
        if (hasCustomLimit) {
            effectiveLimit = requestedLimit == 0 ? Math.max(0, totalItems - offset) : requestedLimit;
        } else {
            effectiveLimit = Math.min(paginationUtils.DEFAULT_PAGE_SIZE, Math.max(0, totalItems - offset));
        }
        effectiveLimit = Math.max(0, Math.min(effectiveLimit, totalItems - offset));

        if (offset >= totalItems) {
            return new PaginationWindow(offset, effectiveLimit, requestedLimit, 0, 0, false, totalItems);
        }

        int startIndex = offset;
        int endIndex = Math.min(startIndex + effectiveLimit, totalItems);
        boolean hasMore = endIndex < totalItems;
        int nextOffset = hasMore ? endIndex : -1;
        return new PaginationWindow(offset, effectiveLimit, requestedLimit, startIndex, endIndex, hasMore, nextOffset);
    }

    private Map<String, Object> buildPaginatedResponse(
        List<?> data, int totalItems, PaginationWindow window, String dataType, String itemsKey
    ) {
        Map<String, Object> result = new HashMap<>();
        result.put("type", dataType);
        result.put(itemsKey, data);

        Map<String, Object> pagination = new HashMap<>();
        pagination.put("total", totalItems);
        pagination.put("offset", window.getOffset());
        pagination.put("limit", window.getLimit());
        pagination.put("count", data.size());
        pagination.put("has_more", window.hasMore());
        if (window.hasMore()) pagination.put("next_offset", window.getNextOffset());
        if (window.getOffset() > 0) pagination.put("prev_offset", Math.max(0, window.getOffset() - window.getLimit()));
        if (window.getLimit() > 0) {
            pagination.put("current_page", (window.getOffset() / window.getLimit()) + 1);
            pagination.put("total_pages", (int) Math.ceil((double) totalItems / window.getLimit()));
            pagination.put("page_size", window.getLimit());
        }

        result.put("requested_count", window.getRequestedLimit());
        result.put("pagination", pagination);
        return result;
    }

    private Document parseManifestXml(String xmlContent) {
        try (InputStream xmlStream = new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8))) {
            Document doc = wrapper.getArgs().getSecurity().parseXml(xmlStream);
            doc.getDocumentElement().normalize();
            return doc;
        } catch (Exception e) {
            throw new JadxRuntimeException("Failed to parse AndroidManifest.xml", e);
        }
    }

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

    // ------------------------------- Inner class: PaginationWindow -------------

    static final class PaginationWindow {
        private final int offset;
        private final int limit;
        private final int requestedLimit;
        private final int startIndex;
        private final int endIndex;
        private final boolean hasMore;
        private final int nextOffset;

        PaginationWindow(int offset, int limit, int requestedLimit, int startIndex, int endIndex, boolean hasMore, int nextOffset) {
            this.offset = offset;
            this.limit = limit;
            this.requestedLimit = requestedLimit;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.hasMore = hasMore;
            this.nextOffset = nextOffset;
        }

        public int getOffset() { return offset; }
        public int getLimit() { return limit; }
        public int getRequestedLimit() { return requestedLimit; }
        public int getStartIndex() { return startIndex; }
        public int getEndIndex() { return endIndex; }
        public boolean hasMore() { return hasMore; }
        public int getNextOffset() { return nextOffset; }
        public boolean hasResults() { return endIndex > startIndex; }
    }
}
