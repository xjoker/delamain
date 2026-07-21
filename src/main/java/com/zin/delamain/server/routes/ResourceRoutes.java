package com.zin.delamain.server.routes;

import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.api.ResourcesLoader;
import jadx.core.xmlgen.IResTableParser;
import jadx.core.xmlgen.ResContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ManifestInfoService;
import com.zin.delamain.utils.NotApplicableResponse;
import com.zin.delamain.utils.PaginationUtils;
import com.zin.delamain.utils.PaginationUtils.PaginationException;
import com.zin.delamain.utils.SmartChunker;

public class ResourceRoutes {
    private static final Logger logger = LoggerFactory.getLogger(ResourceRoutes.class);
    private final HeadlessJadxWrapper wrapper;
    private final PaginationUtils paginationUtils;
    private final ManifestInfoService manifestInfoService = ManifestInfoService.getInstance();

    // Simple in-memory resource content cache (key = resource deobf name)
    private final ConcurrentHashMap<String, String> resourceContentCache = new ConcurrentHashMap<>();
    // Subfile name list cache
    private volatile List<String> subFileNamesCache = null;
    // Parsed strings per locale (key = "res/values/strings.xml")
    private final ConcurrentHashMap<String, Map<String, String>> parsedStringsCache = new ConcurrentHashMap<>();

    public ResourceRoutes(HeadlessJadxWrapper wrapper, PaginationUtils paginationUtils) {
        this.wrapper = wrapper;
        this.paginationUtils = paginationUtils;
    }

    public void register(Javalin app, AuthConfig authConfig) {
        app.get("/manifest",                   this::handleManifest);
        app.get("/strings",                    this::handleStrings);
        app.get("/get-resource-file",          this::handleGetResourceFile);
        app.get("/list-all-resource-file-names", this::handleListAllResourceFilesNames);
        app.get("/jar-manifest",               this::handleJarManifest);
        app.get("/jar-services",               this::handleJarServices);
        app.get("/jar-dependencies",           this::handleJarDependencies);
        app.get("/config-strings",             this::handleConfigStrings);
        app.get("/list-resources-by-type",     this::handleListResourcesByType);
        app.get("/get-decoded-resource",       this::handleGetDecodedResource);
        app.get("/resolve-resource-id",        this::handleResolveResourceId);
    }

    // -------------------------------------------------------------------------
    // Route handlers
    // -------------------------------------------------------------------------

    public void handleManifest(Context ctx) {
        try {
            int chunk = parseChunkParam(ctx);
            if (chunk < 0) return;

            String fileType = detectFileType();
            if (!isAndroidFile(fileType)) {
                NotApplicableResponse.sendManifestNotAvailable(ctx, fileType);
                return;
            }

            ResourceFile manifest = manifestInfoService.getManifestFile(wrapper.getJadx());
            if (manifest == null) {
                ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
                return;
            }
            String content = manifestInfoService.getManifestContent(manifest);
            if (content == null) {
                ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
                return;
            }

            Map<String, Object> result = SmartChunker.chunkResponse(content, chunk, "content");
            result.put("name", manifest.getOriginalName());
            result.put("type", "manifest/xml");

            sendChunkedResponse(ctx, result);
        } catch (Exception e) {
            logger.error("Error fetching AndroidManifest.xml: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleStrings(Context ctx) {
        try {
            String fileType = detectFileType();
            if (!isAndroidFile(fileType)) {
                NotApplicableResponse.sendStringsNotAvailable(ctx, fileType);
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("type", "resource/strings");

            // Load strings files on demand
            List<ResContainer> stringsFiles = loadStringsFiles();

            String mode = ctx.queryParam("mode") != null ? ctx.queryParam("mode") : "summary";
            String locale = ctx.queryParam("locale") != null ? ctx.queryParam("locale") : "values";
            Integer offsetValue = parseNonNegativeIntParam(ctx, "offset", 0);
            if (offsetValue == null) return;
            Integer limitValue = parseNonNegativeIntParam(ctx, "limit", 50);
            if (limitValue == null) return;
            int offset = offsetValue;
            int limit = Math.min(200, limitValue);

            String targetFile = "res/" + locale + "/strings.xml";
            ResContainer stringsFile = null;
            List<String> availableLocales = new ArrayList<>();

            for (ResContainer file : stringsFiles) {
                String fileName = file.getFileName();
                if (fileName != null && fileName.contains("/strings.xml")) {
                    String loc = fileName.replace("res/", "").replace("/strings.xml", "");
                    availableLocales.add(loc);
                    if (fileName.equals(targetFile)) {
                        stringsFile = file;
                    }
                }
            }

            if (stringsFile == null) {
                result.put("status", "not_found");
                result.put("message", "Locale '" + locale + "' not found");
                result.put("available_locales", availableLocales);
                ctx.json(result);
                return;
            }

            final ResContainer finalStringsFile = stringsFile;
            Map<String, String> strings = parsedStringsCache.computeIfAbsent(targetFile,
                k -> parseStringsXml(getResContainerContent(finalStringsFile)));
            List<String> allKeys = new ArrayList<>(strings.keySet());
            java.util.Collections.sort(allKeys);

            result.put("locale", locale);
            result.put("available_locales", availableLocales);

            switch (mode) {
                case "summary":
                    result.put("status", "success");
                    result.put("mode", "summary");
                    result.put("total_strings", strings.size());
                    result.put("sample_keys", allKeys.subList(0, Math.min(10, allKeys.size())));
                    result.put("usage", Map.of(
                        "list_keys", "?mode=list&offset=0&limit=50",
                        "search", "?mode=search&query=login",
                        "get_specific", "?mode=get&key=app_name",
                        "change_locale", "?locale=values-en"
                    ));
                    break;

                case "list":
                    int endIndex = Math.min(offset + limit, allKeys.size());
                    List<String> pageKeys = offset >= allKeys.size() ? List.of() : allKeys.subList(offset, endIndex);
                    result.put("status", "success");
                    result.put("mode", "list");
                    result.put("total", allKeys.size());
                    result.put("offset", offset);
                    result.put("limit", limit);
                    result.put("keys", pageKeys);
                    result.put("has_more", offset < allKeys.size() && endIndex < allKeys.size());
                    break;

                case "search":
                    String query = ctx.queryParam("query");
                    if (query == null || query.isEmpty()) {
                        result.put("status", "error");
                        result.put("error", "Missing 'query' parameter for search mode");
                        ctx.status(400).json(result);
                        return;
                    }
                    String queryLower = query.toLowerCase();
                    List<Map<String, String>> matches = new ArrayList<>();
                    for (Map.Entry<String, String> entry : strings.entrySet()) {
                        if (entry.getKey().toLowerCase().contains(queryLower) ||
                            entry.getValue().toLowerCase().contains(queryLower)) {
                            if (matches.size() >= limit) break;
                            matches.add(Map.of("key", entry.getKey(), "value", entry.getValue()));
                        }
                    }
                    result.put("status", "success");
                    result.put("mode", "search");
                    result.put("query", query);
                    result.put("matches", matches);
                    result.put("count", matches.size());
                    break;

                case "get":
                    String key = ctx.queryParam("key");
                    if (key == null || key.isEmpty()) {
                        result.put("status", "error");
                        result.put("error", "Missing 'key' parameter for get mode");
                        ctx.status(400).json(result);
                        return;
                    }
                    String value = strings.get(key);
                    if (value != null) {
                        result.put("status", "success");
                        result.put("mode", "get");
                        result.put("key", key);
                        result.put("value", value);
                    } else {
                        result.put("status", "not_found");
                        result.put("mode", "get");
                        result.put("key", key);
                        result.put("message", "String key not found");
                        String keyLower = key.toLowerCase();
                        List<String> suggestions = allKeys.stream()
                            .filter(k2 -> k2.toLowerCase().contains(keyLower))
                            .limit(5)
                            .collect(Collectors.toList());
                        if (!suggestions.isEmpty()) result.put("suggestions", suggestions);
                    }
                    break;

                default:
                    result.put("status", "error");
                    result.put("error", "Unknown mode: " + mode);
                    result.put("valid_modes", List.of("summary", "list", "search", "get"));
                    ctx.status(400).json(result);
                    return;
            }

            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error in handleStrings: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleGetResourceFile(Context ctx) {
        String fileName = ctx.queryParam("file_name");
        if (fileName == null || fileName.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required 'file_name' parameter."));
            return;
        }

        int chunk = parseChunkParam(ctx);
        if (chunk < 0) return;

        try {
            // Check in-memory cache first
            String cachedContent = resourceContentCache.get(fileName);
            if (cachedContent != null) {
                Map<String, Object> chunkedResult = SmartChunker.chunkResponse(cachedContent, chunk, "content");
                Map<String, Object> result = new HashMap<>();
                result.put("type", "resource/text");
                result.put("status", "success");
                result.put("source", "cache");
                result.put("file_name", fileName);
                result.putAll(chunkedResult);
                sendChunkedResponse(ctx, result);
                return;
            }

            // Direct loading from JADX resources
            String content = loadResourceDirect(fileName);
            if (content != null) {
                resourceContentCache.put(fileName, content);

                Map<String, Object> chunkedResult = SmartChunker.chunkResponse(content, chunk, "content");
                Map<String, Object> result = new HashMap<>();
                result.put("type", "resource/text");
                result.put("status", "success");
                result.put("source", "direct_loading");
                result.put("file_name", fileName);
                result.putAll(chunkedResult);
                sendChunkedResponse(ctx, result);
                return;
            }

            ctx.json(Map.of("type", "resource/text", "status", "not_found",
                "error", "Resource not found: " + fileName));

        } catch (Exception e) {
            logger.error("Error in handleGetResourceFile: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleListAllResourceFilesNames(Context ctx) {
        try {
            List<String> resourceFileNames = new ArrayList<>();

            // Use cached list if available
            if (subFileNamesCache != null) {
                resourceFileNames.addAll(subFileNamesCache);
            } else {
                // Build list from resources
                List<ResourceFile> resourceFiles = wrapper.getJadx().getResources();
                if (resourceFiles != null) {
                    for (ResourceFile resFile : resourceFiles) {
                        if (resFile == null || resFile.getDeobfName() == null) continue;
                        String name = resFile.getDeobfName();
                        if ("resources.arsc".equals(name)) {
                            // Expand subfiles
                            try {
                                ResContainer container = resFile.loadContent();
                                if (container != null) {
                                    collectSubFileNames(container, resourceFileNames);
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to expand resources.arsc: {}", e.getMessage());
                            }
                        } else {
                            if (!resourceFileNames.contains(name)) {
                                resourceFileNames.add(name);
                            }
                        }
                    }
                }
                subFileNamesCache = new ArrayList<>(resourceFileNames);
            }

            if (resourceFileNames.isEmpty()) {
                ctx.status(404).json(Map.of("error", "No resources found."));
                return;
            }

            Map<String, Object> result = paginationUtils.handlePagination(
                ctx, resourceFileNames, "application-resources", "files", item -> item);
            result.put("cached", true);
            ctx.json(result);
        } catch (PaginationException e) {
            ctx.status(400).json(Map.of("error", "Pagination error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Error in handleListAllResourceFilesNames: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleJarManifest(Context ctx) {
        try {
            String fileType = detectFileType();
            if (!isJarFile(fileType)) {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "NOT_APPLICABLE");
                response.put("reason", "JAR Manifest is only available for JAR files. This is a " + fileType.toUpperCase() + " file.");
                response.put("file_type", fileType);
                response.put("alternatives", List.of(
                    Map.of("tool", "get_android_manifest", "description", "Get AndroidManifest.xml for APK/AAR files")
                ));
                ctx.json(response);
                return;
            }

            java.io.File jarFile = getLoadedFile();
            if (jarFile == null || !jarFile.exists()) {
                ctx.status(404).json(Map.of("error", "No JAR file loaded."));
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("type", "jar-manifest");
            result.put("file_name", jarFile.getName());

            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
                java.util.jar.Manifest manifest = jar.getManifest();
                if (manifest == null) {
                    result.put("status", "not_found");
                    result.put("message", "JAR file does not contain META-INF/MANIFEST.MF");
                    ctx.json(result);
                    return;
                }

                java.util.jar.Attributes mainAttrs = manifest.getMainAttributes();
                result.put("status", "success");

                addIfPresent(result, "main_class", mainAttrs.getValue("Main-Class"));
                addIfPresent(result, "implementation_title", mainAttrs.getValue("Implementation-Title"));
                addIfPresent(result, "implementation_version", mainAttrs.getValue("Implementation-Version"));
                addIfPresent(result, "implementation_vendor", mainAttrs.getValue("Implementation-Vendor"));
                addIfPresent(result, "specification_title", mainAttrs.getValue("Specification-Title"));
                addIfPresent(result, "specification_version", mainAttrs.getValue("Specification-Version"));
                addIfPresent(result, "class_path", mainAttrs.getValue("Class-Path"));
                addIfPresent(result, "created_by", mainAttrs.getValue("Created-By"));
                addIfPresent(result, "built_by", mainAttrs.getValue("Built-By"));
                addIfPresent(result, "build_jdk", mainAttrs.getValue("Build-Jdk"));
                addIfPresent(result, "bundle_name", mainAttrs.getValue("Bundle-Name"));
                addIfPresent(result, "bundle_symbolic_name", mainAttrs.getValue("Bundle-SymbolicName"));
                addIfPresent(result, "bundle_version", mainAttrs.getValue("Bundle-Version"));
                addIfPresent(result, "spring_boot_version", mainAttrs.getValue("Spring-Boot-Version"));
                addIfPresent(result, "spring_boot_classes", mainAttrs.getValue("Spring-Boot-Classes"));
                addIfPresent(result, "spring_boot_lib", mainAttrs.getValue("Spring-Boot-Lib"));
                addIfPresent(result, "start_class", mainAttrs.getValue("Start-Class"));

                Map<String, String> allAttributes = new HashMap<>();
                for (Object key : mainAttrs.keySet()) {
                    String keyStr = key.toString();
                    String value = mainAttrs.getValue(keyStr);
                    if (value != null) allAttributes.put(keyStr, value);
                }
                result.put("all_attributes", allAttributes);
                result.put("attribute_count", allAttributes.size());

                Map<String, java.util.jar.Attributes> entries = manifest.getEntries();
                if (!entries.isEmpty()) {
                    Map<String, Map<String, String>> sections = new HashMap<>();
                    for (Map.Entry<String, java.util.jar.Attributes> entry : entries.entrySet()) {
                        Map<String, String> sectionAttrs = new HashMap<>();
                        for (Object key : entry.getValue().keySet()) {
                            sectionAttrs.put(key.toString(), entry.getValue().getValue(key.toString()));
                        }
                        sections.put(entry.getKey(), sectionAttrs);
                    }
                    result.put("named_sections", sections);
                    result.put("section_count", sections.size());
                }
            }

            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error reading JAR manifest: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleJarServices(Context ctx) {
        try {
            String fileType = detectFileType();
            if (!isJarFile(fileType)) {
                Map<String, Object> response = new HashMap<>();
                response.put("status", "NOT_APPLICABLE");
                response.put("reason", "JAR Services (SPI) is only available for JAR files. This is a " + fileType.toUpperCase() + " file.");
                response.put("file_type", fileType);
                response.put("alternatives", List.of(
                    Map.of("tool", "search_classes_by_keyword", "description", "Search for interface implementations")
                ));
                ctx.json(response);
                return;
            }

            java.io.File jarFile = getLoadedFile();
            if (jarFile == null || !jarFile.exists()) {
                ctx.status(404).json(Map.of("error", "No JAR file loaded."));
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("type", "jar-services");
            result.put("file_name", jarFile.getName());

            List<Map<String, Object>> services = new ArrayList<>();

            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
                java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith("META-INF/services/") && !entry.isDirectory()) {
                        String interfaceName = name.substring("META-INF/services/".length());
                        if (interfaceName.contains("/")) continue;

                        List<String> implementations = new ArrayList<>();
                        try (InputStream is = jar.getInputStream(entry);
                             java.io.BufferedReader reader = new java.io.BufferedReader(
                                 new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                line = line.trim();
                                if (!line.isEmpty() && !line.startsWith("#")) {
                                    int commentIdx = line.indexOf('#');
                                    if (commentIdx > 0) line = line.substring(0, commentIdx).trim();
                                    if (!line.isEmpty()) implementations.add(line);
                                }
                            }
                        }

                        if (!implementations.isEmpty()) {
                            Map<String, Object> service = new HashMap<>();
                            service.put("interface", interfaceName);
                            service.put("implementations", implementations);
                            service.put("count", implementations.size());
                            services.add(service);
                        }
                    }
                }
            }

            result.put("status", "success");
            result.put("services", services);
            result.put("total_services", services.size());
            if (services.isEmpty()) {
                result.put("note", "No META-INF/services found. This JAR may not use Java SPI.");
            }

            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error reading JAR services: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleJarDependencies(Context ctx) {
        try {
            String fileType = detectFileType();
            if (!isJarFile(fileType)) {
                List<NotApplicableResponse.Alternative> alts = new ArrayList<>();
                alts.add(new NotApplicableResponse.Alternative("get_file_info", "Get file type and available features"));
                NotApplicableResponse.send(ctx,
                    "Dependency analysis is only for JAR files. This is a " + fileType.toUpperCase() + " file.",
                    fileType, alts);
                return;
            }

            java.io.File jarFile = getLoadedFile();
            if (jarFile == null || !jarFile.exists()) {
                ctx.status(404).json(Map.of("error", "No JAR file loaded."));
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("type", "jar-dependencies");
            result.put("file_name", jarFile.getName());

            List<Map<String, Object>> dependencies = new ArrayList<>();
            String groupId = null, artifactId = null, version = null;

            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
                java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                List<String> nestedJars = new ArrayList<>();

                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    String name = entry.getName();

                    if (name.endsWith("pom.properties") && name.startsWith("META-INF/maven/")) {
                        try (InputStream is = jar.getInputStream(entry)) {
                            java.util.Properties props = new java.util.Properties();
                            props.load(is);
                            groupId = props.getProperty("groupId");
                            artifactId = props.getProperty("artifactId");
                            version = props.getProperty("version");
                        }
                    }

                    if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) {
                        String jarName = name.substring("BOOT-INF/lib/".length());
                        nestedJars.add(jarName);
                        Map<String, Object> dep = parseDependencyFromJarName(jarName);
                        if (dep != null) {
                            dep.put("source", "BOOT-INF/lib");
                            dependencies.add(dep);
                        }
                    }
                }

                java.util.jar.Manifest manifest = jar.getManifest();
                if (manifest != null) {
                    String classPath = manifest.getMainAttributes().getValue("Class-Path");
                    if (classPath != null && !classPath.isEmpty()) {
                        for (String path : classPath.split("\\s+")) {
                            if (path.endsWith(".jar")) {
                                Map<String, Object> dep = parseDependencyFromJarName(path);
                                if (dep != null) {
                                    dep.put("source", "MANIFEST Class-Path");
                                    dependencies.add(dep);
                                }
                            }
                        }
                    }
                }

                result.put("nested_jars_count", nestedJars.size());
            }

            if (groupId != null) result.put("group_id", groupId);
            if (artifactId != null) result.put("artifact_id", artifactId);
            if (version != null) result.put("version", version);

            result.put("dependencies", dependencies);
            result.put("total_dependencies", dependencies.size());
            result.put("status", "success");

            if (dependencies.isEmpty()) {
                result.put("note", "No embedded dependencies found. Check external build files (pom.xml, build.gradle).");
            }

            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error analyzing JAR dependencies: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleConfigStrings(Context ctx) {
        try {
            String fileType = detectFileType();
            String mode = ctx.queryParam("mode");
            if (mode == null) mode = "summary";

            Map<String, Object> result = new HashMap<>();
            result.put("type", "config-strings");

            if (isAndroidFile(fileType)) {
                result.put("source_type", "android_strings");
                result.put("source_file", "res/values/strings.xml");
                result.put("note", "For full functionality, use get_strings tool with mode parameter");
                try {
                    boolean hasStrings = !loadStringsFiles().isEmpty();
                    result.put("available", hasStrings);
                    if (hasStrings) result.put("recommended_tool", "get_strings");
                    else result.put("error", "strings.xml not found");
                } catch (Exception e) {
                    result.put("available", false);
                    result.put("error", e.getMessage());
                }
            } else if (isJarFile(fileType)) {
                result.put("source_type", "java_properties");

                java.io.File jarFile = getLoadedFile();
                if (jarFile == null) {
                    ctx.status(404).json(Map.of("error", "No JAR file loaded"));
                    return;
                }

                String query = ctx.queryParam("query");
                String key = ctx.queryParam("key");
                String targetFile = ctx.queryParam("file");
                final String finalMode = mode;

                List<Map<String, Object>> propertiesFiles = new ArrayList<>();

                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
                    java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        java.util.jar.JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.endsWith(".properties") && !entry.isDirectory()) {
                            if (targetFile != null && !name.contains(targetFile)) continue;

                            Map<String, Object> propFile = new HashMap<>();
                            propFile.put("file", name);

                            java.util.Properties props = new java.util.Properties();
                            try (InputStream is = jar.getInputStream(entry)) {
                                props.load(is);
                            }
                            propFile.put("key_count", props.size());

                            if ("summary".equals(finalMode)) {
                                List<String> sampleKeys = new ArrayList<>();
                                int count = 0;
                                for (String k2 : props.stringPropertyNames()) {
                                    if (count++ >= 5) break;
                                    sampleKeys.add(k2);
                                }
                                propFile.put("sample_keys", sampleKeys);
                            } else if ("search".equals(finalMode) && query != null) {
                                Map<String, String> matches = new HashMap<>();
                                for (String k2 : props.stringPropertyNames()) {
                                    String v2 = props.getProperty(k2);
                                    if (k2.toLowerCase().contains(query.toLowerCase()) ||
                                        (v2 != null && v2.toLowerCase().contains(query.toLowerCase()))) {
                                        matches.put(k2, v2);
                                    }
                                }
                                propFile.put("matches", matches);
                                propFile.put("match_count", matches.size());
                            } else if ("get".equals(finalMode) && key != null) {
                                String v2 = props.getProperty(key);
                                if (v2 != null) {
                                    propFile.put("key", key);
                                    propFile.put("value", v2);
                                }
                            } else {
                                Map<String, String> allProps = new HashMap<>();
                                for (String k2 : props.stringPropertyNames()) {
                                    allProps.put(k2, props.getProperty(k2));
                                }
                                propFile.put("properties", allProps);
                            }

                            propertiesFiles.add(propFile);
                            if ("summary".equals(finalMode) && propertiesFiles.size() >= 10) break;
                        }
                    }
                }

                result.put("files", propertiesFiles);
                result.put("total_files", propertiesFiles.size());
                if (propertiesFiles.isEmpty()) result.put("note", "No .properties files found in JAR");
            } else {
                result.put("source_type", "unknown");
                result.put("error", "Config strings not available for this file type");
            }

            result.put("status", "success");
            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error reading config strings: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * /list-resources-by-type — Filters the resource file list by resource path prefix.
     * Query params:
     *   type    = layout | drawable | values | xml | raw | menu | anim | color | mipmap | font
     *             (omit to return all, with a type distribution breakdown attached)
     *   offset  = starting offset (default 0)
     *   limit   = number of entries returned (default 50, max 500)
     */
    public void handleListResourcesByType(Context ctx) {
        try {
            String fileType = detectFileType();
            if (!isAndroidFile(fileType)) {
                ctx.json(Map.of(
                    "status", "NOT_APPLICABLE",
                    "reason", "Resource type listing is only available for APK/AAR files. This is a " + fileType.toUpperCase() + " file."
                ));
                return;
            }

            String typeParam = ctx.queryParam("type");
            Integer offsetVal = parseNonNegativeIntParam(ctx, "offset", 0);
            if (offsetVal == null) return;
            Integer limitVal = parseNonNegativeIntParam(ctx, "limit", 50);
            if (limitVal == null) return;
            int offset = offsetVal;
            int limit = Math.min(500, limitVal);

            // Ensure subfile names are loaded
            List<String> allNames = getAllSubFileNames();

            if (typeParam == null || typeParam.isEmpty()) {
                // Return summary with type distribution
                Map<String, Integer> typeCounts = new java.util.TreeMap<>();
                for (String name : allNames) {
                    String t = extractResourceType(name);
                    typeCounts.merge(t, 1, Integer::sum);
                }
                Map<String, Object> result = new HashMap<>();
                result.put("type", "resource/type-list");
                result.put("status", "summary");
                result.put("total_files", allNames.size());
                result.put("type_distribution", typeCounts);
                result.put("usage", "Add ?type=layout|drawable|values|xml|raw|menu|anim|color|mipmap|font to filter");
                ctx.json(result);
                return;
            }

            // Filter by type prefix: "res/<type>/" or "res/<type>-<qualifier>/"
            String typePrefix = "res/" + typeParam.toLowerCase();
            List<String> filtered = new ArrayList<>();
            for (String name : allNames) {
                // Match "res/<type>/..." and qualified variants "res/<type>-<qualifier>/...".
                // (Dropped a loose contains("/<type>/") branch that false-matched paths like
                // "assets/layout/foo" — the two startsWith checks cover all res/ variants.)
                if (name.startsWith(typePrefix + "/") || name.startsWith(typePrefix + "-")) {
                    filtered.add(name);
                }
            }

            int total = filtered.size();
            int end = Math.min(offset + limit, total);
            List<String> page = offset >= total ? List.of() : filtered.subList(offset, end);

            Map<String, Object> result = new HashMap<>();
            result.put("type", "resource/type-list");
            result.put("status", "success");
            result.put("resource_type", typeParam);
            result.put("total", total);
            result.put("offset", offset);
            result.put("limit", limit);
            result.put("files", page);
            result.put("has_more", end < total);
            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error in handleListResourcesByType: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * /get-decoded-resource — Fetches the decoded text content of a given resource.
     * Query params:
     *   file_name = resource path (e.g. res/layout/activity_main.xml)
     *   chunk     = chunk index (default 0)
     * Reuses the existing loadResourceDirect() logic, exposed as a clearer-named endpoint for the AI.
     */
    public void handleGetDecodedResource(Context ctx) {
        String fileName = ctx.queryParam("file_name");
        if (fileName == null || fileName.isEmpty()) {
            ctx.status(400).json(Map.of(
                "error", "Missing required 'file_name' parameter.",
                "usage", "?file_name=res/layout/activity_main.xml",
                "tip", "Use /list-resources-by-type?type=layout to discover file names"
            ));
            return;
        }

        int chunk = parseChunkParam(ctx);
        if (chunk < 0) return;

        try {
            // Determine resource type from file path for metadata
            String resType = extractResourceType(fileName);

            // Check cache first
            String cachedContent = resourceContentCache.get(fileName);
            if (cachedContent != null) {
                Map<String, Object> chunkedResult = SmartChunker.chunkResponse(cachedContent, chunk, "content");
                Map<String, Object> result = new HashMap<>();
                result.put("type", "resource/decoded-text");
                result.put("status", "success");
                result.put("source", "cache");
                result.put("file_name", fileName);
                result.put("resource_type", resType);
                result.putAll(chunkedResult);
                sendChunkedResponse(ctx, result);
                return;
            }

            String content = loadResourceDirect(fileName);
            if (content != null) {
                resourceContentCache.put(fileName, content);
                Map<String, Object> chunkedResult = SmartChunker.chunkResponse(content, chunk, "content");
                Map<String, Object> result = new HashMap<>();
                result.put("type", "resource/decoded-text");
                result.put("status", "success");
                result.put("source", "jadx_decoded");
                result.put("file_name", fileName);
                result.put("resource_type", resType);
                result.putAll(chunkedResult);
                sendChunkedResponse(ctx, result);
                return;
            }

            ctx.status(404).json(Map.of(
                "type", "resource/decoded-text",
                "status", "not_found",
                "file_name", fileName,
                "error", "Resource not found or not decodable as text: " + fileName,
                "tip", "Use /list-resources-by-type?type=" + resType + " to verify the file name"
            ));
        } catch (Exception e) {
            logger.error("Error in handleGetDecodedResource: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * /resolve-resource-id — Bidirectional resource ID <-> name resolution.
     * Query params (pick one):
     *   id    = hex or decimal ID (e.g. 0x7f040001 or 2131099649)
     *   name  = resource name (e.g. ic_launcher or layout/activity_main)
     * If both are omitted, returns the full ID <-> name mapping (supports offset/limit pagination).
     * Data source: ResourcesLoader.decodeTable -> ResourceStorage.getResourcesNames()
     *              and ResourceStorage.getResources() -> ResourceEntry (includes typeName/keyName)
     */
    public void handleResolveResourceId(Context ctx) {
        try {
            String fileType = detectFileType();
            if (!isAndroidFile(fileType)) {
                ctx.json(Map.of(
                    "status", "NOT_APPLICABLE",
                    "reason", "Resource ID resolution is only available for APK/AAR files. This is a " + fileType.toUpperCase() + " file."
                ));
                return;
            }

            String idParam = ctx.queryParam("id");
            String nameParam = ctx.queryParam("name");
            Integer offsetVal = parseNonNegativeIntParam(ctx, "offset", 0);
            if (offsetVal == null) return;
            Integer limitVal = parseNonNegativeIntParam(ctx, "limit", 100);
            if (limitVal == null) return;
            int offset = offsetVal;
            int limit = Math.min(1000, limitVal);

            // Load resource storage (lazy, cached)
            jadx.core.xmlgen.ResourceStorage resStorage = loadResourceStorage();

            Map<String, Object> result = new HashMap<>();
            result.put("type", "resource/id-resolution");

            if (resStorage == null) {
                result.put("status", "not_available");
                result.put("message", "Resource table (resources.arsc) not found or failed to decode");
                ctx.json(result);
                return;
            }

            if (idParam != null && !idParam.isEmpty()) {
                // ID → name lookup
                int resId;
                try {
                    String normalized = idParam.trim();
                    if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
                        resId = (int) Long.parseLong(normalized.substring(2), 16);
                    } else {
                        resId = Integer.parseInt(normalized);
                    }
                } catch (NumberFormatException e) {
                    ctx.status(400).json(Map.of("error", "Invalid 'id' parameter: " + idParam + ". Use hex (0x7f040001) or decimal."));
                    return;
                }

                String resName = resStorage.getRename(resId);
                // Also check getResourcesNames map
                if (resName == null) {
                    Map<Integer, String> namesMap = resStorage.getResourcesNames();
                    resName = namesMap != null ? namesMap.get(resId) : null;
                }

                // Try to find full entry (typeName + keyName) from getResources()
                jadx.core.xmlgen.entry.ResourceEntry found = null;
                for (jadx.core.xmlgen.entry.ResourceEntry entry : resStorage.getResources()) {
                    if (entry.getId() == resId) {
                        found = entry;
                        break;
                    }
                }

                result.put("status", "success");
                result.put("query_type", "id_to_name");
                result.put("id_decimal", resId);
                result.put("id_hex", String.format("0x%08x", resId));
                if (found != null) {
                    result.put("type_name", found.getTypeName());
                    result.put("key_name", found.getKeyName());
                    result.put("pkg_name", found.getPkgName());
                    result.put("full_name", found.getTypeName() + "/" + found.getKeyName());
                    result.put("found", true);
                } else if (resName != null) {
                    result.put("name", resName);
                    result.put("found", true);
                } else {
                    result.put("found", false);
                    result.put("message", "No resource found with ID " + String.format("0x%08x", resId));
                }

            } else if (nameParam != null && !nameParam.isEmpty()) {
                // name → ID lookup (search by typeName/keyName)
                String queryLower = nameParam.toLowerCase();
                List<Map<String, Object>> matches = new ArrayList<>();
                for (jadx.core.xmlgen.entry.ResourceEntry entry : resStorage.getResources()) {
                    String full = entry.getTypeName() + "/" + entry.getKeyName();
                    if (entry.getKeyName().toLowerCase().contains(queryLower)
                            || full.toLowerCase().contains(queryLower)) {
                        if (matches.size() >= limit) break;
                        Map<String, Object> m = new HashMap<>();
                        m.put("id_hex", String.format("0x%08x", entry.getId()));
                        m.put("id_decimal", entry.getId());
                        m.put("type_name", entry.getTypeName());
                        m.put("key_name", entry.getKeyName());
                        m.put("pkg_name", entry.getPkgName());
                        m.put("full_name", full);
                        matches.add(m);
                    }
                }
                result.put("status", "success");
                result.put("query_type", "name_to_id");
                result.put("query", nameParam);
                result.put("matches", matches);
                result.put("count", matches.size());

            } else {
                // List all with pagination
                List<jadx.core.xmlgen.entry.ResourceEntry> all = new ArrayList<>();
                for (jadx.core.xmlgen.entry.ResourceEntry entry : resStorage.getResources()) {
                    all.add(entry);
                }
                int total = all.size();
                int end = Math.min(offset + limit, total);
                List<Map<String, Object>> page = new ArrayList<>();
                for (int i = offset; i < end; i++) {
                    jadx.core.xmlgen.entry.ResourceEntry entry = all.get(i);
                    Map<String, Object> m = new HashMap<>();
                    m.put("id_hex", String.format("0x%08x", entry.getId()));
                    m.put("id_decimal", entry.getId());
                    m.put("type_name", entry.getTypeName());
                    m.put("key_name", entry.getKeyName());
                    m.put("full_name", entry.getTypeName() + "/" + entry.getKeyName());
                    page.add(m);
                }
                result.put("status", "success");
                result.put("query_type", "list_all");
                result.put("total", total);
                result.put("offset", offset);
                result.put("limit", limit);
                result.put("entries", page);
                result.put("has_more", end < total);
            }

            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error in handleResolveResourceId: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String detectFileType() {
        List<java.io.File> files = wrapper.getInputFiles();
        if (files == null || files.isEmpty()) return "unknown";
        String name = files.get(0).getName().toLowerCase();
        if (name.endsWith(".apk")) return "apk";
        if (name.endsWith(".aar")) return "aar";
        if (name.endsWith(".dex")) return "dex";
        if (name.endsWith(".jar")) return "jar";
        return "unknown";
    }

    private boolean isAndroidFile(String fileType) {
        return "apk".equals(fileType) || "aar".equals(fileType) || "dex".equals(fileType);
    }

    private boolean isJarFile(String fileType) {
        return "jar".equals(fileType);
    }

    private java.io.File getLoadedFile() {
        List<java.io.File> files = wrapper.getInputFiles();
        if (files != null && !files.isEmpty()) return files.get(0);
        return null;
    }

    private String loadResourceDirect(String targetFileName) {
        try {
            List<ResourceFile> resourceFiles = wrapper.getJadx().getResources();
            if (resourceFiles == null) return null;
            for (ResourceFile resFile : resourceFiles) {
                if (resFile == null || resFile.getDeobfName() == null) continue;
                if (resFile.getDeobfName().equals(targetFileName)) {
                    ResContainer container = resFile.loadContent();
                    if (container != null && container.getText() != null) {
                        return container.getText().getCodeStr();
                    }
                }
                if ("resources.arsc".equals(resFile.getDeobfName())) {
                    ResContainer container = resFile.loadContent();
                    if (container != null) {
                        String found = findResourceRecursively(container, targetFileName);
                        if (found != null) return found;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load resource '{}' directly: {}", targetFileName, e.getMessage());
        }
        return null;
    }

    private String findResourceRecursively(ResContainer container, String targetFileName) {
        if (container == null) return null;
        String containerFileName = container.getFileName();
        String containerName = container.getName();
        if (targetFileName.equals(containerFileName) || targetFileName.equals(containerName)) {
            if (container.getText() != null) return container.getText().getCodeStr();
        }
        if (targetFileName.contains("strings.xml") && containerFileName != null && containerFileName.contains(targetFileName)) {
            if (container.getText() != null) return container.getText().getCodeStr();
        }
        for (ResContainer sub : container.getSubFiles()) {
            String result = findResourceRecursively(sub, targetFileName);
            if (result != null) return result;
        }
        return null;
    }

    private String getResContainerContent(ResContainer container) {
        try {
            if (container.getText() != null) return container.getText().getCodeStr();
        } catch (Exception e) {
            logger.warn("Failed to get content from ResContainer: {}", e.getMessage());
        }
        return null;
    }

    private List<ResContainer> loadStringsFiles() {
        List<ResContainer> stringsFiles = new ArrayList<>();
        try {
            List<ResourceFile> resourceFiles = wrapper.getJadx().getResources();
            if (resourceFiles == null) return stringsFiles;
            for (ResourceFile resFile : resourceFiles) {
                if (resFile == null || resFile.getDeobfName() == null) continue;
                if ("resources.arsc".equals(resFile.getDeobfName())) {
                    ResContainer container = resFile.loadContent();
                    if (container != null) {
                        collectStringsContainers(container, stringsFiles);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load strings files: {}", e.getMessage());
        }
        return stringsFiles;
    }

    private void collectStringsContainers(ResContainer container, List<ResContainer> result) {
        if (container == null) return;
        String fileName = container.getFileName();
        if (fileName != null && fileName.contains("/strings.xml")) {
            result.add(container);
            return;
        }
        for (ResContainer sub : container.getSubFiles()) {
            collectStringsContainers(sub, result);
        }
    }

    private void collectSubFileNames(ResContainer container, List<String> names) {
        if (container == null) return;
        String fileName = container.getFileName();
        if (fileName != null && !fileName.isEmpty() && !names.contains(fileName)) {
            names.add(fileName);
        }
        for (ResContainer sub : container.getSubFiles()) {
            collectSubFileNames(sub, names);
        }
    }

    private Map<String, String> parseStringsXml(String xmlContent) {
        Map<String, String> strings = new HashMap<>();
        if (xmlContent == null || xmlContent.isEmpty()) return strings;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlContent)));
            var stringNodes = doc.getElementsByTagName("string");
            for (int i = 0; i < stringNodes.getLength(); i++) {
                Element elem = (Element) stringNodes.item(i);
                String name = elem.getAttribute("name");
                String value = elem.getTextContent();
                if (name != null && !name.isEmpty()) strings.put(name, value);
            }
        } catch (Exception e) {
            logger.warn("Error parsing strings.xml: {}", e.getMessage());
        }
        return strings;
    }

    private int parseChunkParam(Context ctx) {
        String chunkParam = ctx.queryParam("chunk");
        if (chunkParam == null || chunkParam.isEmpty()) return 0;
        try {
            return Integer.parseInt(chunkParam.trim());
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid chunk parameter: " + chunkParam));
            return -1;
        }
    }

    private Integer parseNonNegativeIntParam(Context ctx, String paramName, int defaultValue) {
        String rawValue = ctx.queryParam(paramName);
        if (rawValue == null || rawValue.isEmpty()) return defaultValue;
        try {
            int value = Integer.parseInt(rawValue);
            if (value < 0) {
                ctx.status(400).json(Map.of("error", "Invalid '" + paramName + "': must be non-negative"));
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            ctx.status(400).json(Map.of("error", "Invalid '" + paramName + "': must be an integer"));
            return null;
        }
    }

    private void sendChunkedResponse(Context ctx, Map<String, Object> result) {
        if (result.containsKey("error")) {
            ctx.status(400).json(result);
            return;
        }
        ctx.json(result);
    }

    private void addIfPresent(Map<String, Object> result, String key, String value) {
        if (value != null && !value.isEmpty()) result.put(key, value);
    }

    private Map<String, Object> parseDependencyFromJarName(String jarName) {
        if (jarName == null || !jarName.endsWith(".jar")) return null;
        String name = jarName.substring(0, jarName.length() - 4);
        int lastDash = name.lastIndexOf('-');
        if (lastDash > 0) {
            String possibleVersion = name.substring(lastDash + 1);
            if (!possibleVersion.isEmpty() && Character.isDigit(possibleVersion.charAt(0))) {
                Map<String, Object> dep = new HashMap<>();
                dep.put("name", name.substring(0, lastDash));
                dep.put("version", possibleVersion);
                return dep;
            }
        }
        Map<String, Object> dep = new HashMap<>();
        dep.put("name", name);
        return dep;
    }

    /** Return all expanded subfile names (uses subFileNamesCache if warm). */
    private List<String> getAllSubFileNames() {
        List<String> cached = subFileNamesCache;
        if (cached != null) return cached;
        // Double-checked locking: expanding resources.arsc is expensive; without this guard
        // concurrent callers each rebuild and overwrite the cache (see loadResourceStorage()).
        synchronized (this) {
            if (subFileNamesCache != null) return subFileNamesCache;
            List<String> names = new ArrayList<>();
            List<ResourceFile> resourceFiles = wrapper.getJadx().getResources();
            if (resourceFiles != null) {
                for (ResourceFile resFile : resourceFiles) {
                    if (resFile == null || resFile.getDeobfName() == null) continue;
                    String deobf = resFile.getDeobfName();
                    if ("resources.arsc".equals(deobf)) {
                        try {
                            ResContainer container = resFile.loadContent();
                            if (container != null) collectSubFileNames(container, names);
                        } catch (Exception e) {
                            logger.warn("Failed to expand resources.arsc for type listing: {}", e.getMessage());
                        }
                    } else {
                        if (!names.contains(deobf)) names.add(deobf);
                    }
                }
            }
            subFileNamesCache = new ArrayList<>(names);
            return subFileNamesCache;
        }
    }

    /**
     * Extract resource type from a path like "res/layout-land/foo.xml" → "layout",
     * "res/drawable-hdpi/bar.png" → "drawable", etc.
     */
    private String extractResourceType(String path) {
        if (path == null) return "unknown";
        // Strip leading "res/"
        String p = path.startsWith("res/") ? path.substring(4) : path;
        int slash = p.indexOf('/');
        String segment = slash >= 0 ? p.substring(0, slash) : p;
        // Strip qualifier suffix (e.g. "layout-land" → "layout")
        int dash = segment.indexOf('-');
        return dash >= 0 ? segment.substring(0, dash) : segment;
    }

    /** Lazy-load and cache ResourceStorage from resources.arsc via ResourcesLoader.decodeTable. */
    private volatile jadx.core.xmlgen.ResourceStorage cachedResStorage = null;
    private volatile boolean resStorageLoadAttempted = false;

    private jadx.core.xmlgen.ResourceStorage loadResourceStorage() {
        if (resStorageLoadAttempted) return cachedResStorage;
        synchronized (this) {
            if (resStorageLoadAttempted) return cachedResStorage;
            resStorageLoadAttempted = true;
            try {
                List<ResourceFile> resources = wrapper.getJadx().getResources();
                if (resources == null) return null;
                for (ResourceFile resFile : resources) {
                    if (resFile == null) continue;
                    if (resFile.getType() == ResourceType.ARSC
                            || "resources.arsc".equals(resFile.getDeobfName())) {
                        jadx.zip.IZipEntry zipEntry = resFile.getZipEntry();
                        if (zipEntry == null) continue;
                        try (java.io.InputStream is = zipEntry.getInputStream()) {
                            ResourcesLoader loader = wrapper.getJadx().getResourcesLoader();
                            IResTableParser parser = loader.decodeTable(resFile, is);
                            if (parser != null) {
                                cachedResStorage = parser.getResStorage();
                                logger.info("Resource storage loaded: {} entries",
                                    cachedResStorage != null ? cachedResStorage.size() : 0);
                                return cachedResStorage;
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to decode resource table: {}", e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to load ResourceStorage: {}", e.getMessage());
            }
            return null;
        }
    }
}
