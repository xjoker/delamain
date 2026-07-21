package com.zin.delamain.server.routes;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.JadxApiAdapter;
import com.zin.delamain.utils.PaginationUtils;
import com.zin.delamain.utils.SmartChunker;

import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.core.dex.info.AccessInfo;
import jadx.core.dex.instructions.args.ArgType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lean navigation and class-metadata MCP endpoints (headless port).
 *
 * <p>Covers: {@code /current-class}, {@code /all-classes}, {@code /selected-text},
 * {@code /class-info}, {@code /methods-of-class}, {@code /fields-of-class},
 * {@code /package-classes}, {@code /package-tree}, {@code /jar-bytecode},
 * {@code /jar-entry-points}.</p>
 */
public class ClassRoutes {
    private static final Logger logger = LoggerFactory.getLogger(ClassRoutes.class);

    private final HeadlessJadxWrapper wrapper;
    private final PaginationUtils paginationUtils;

    // Known library package prefixes for is_likely_library heuristic
    private static final String[] LIBRARY_PREFIXES = {
            "androidx.", "android.support.", "com.google.", "com.android.",
            "kotlin.", "kotlinx.", "okhttp3.", "okio.", "retrofit2.",
            "com.squareup.", "io.reactivex.", "rx.", "dagger.",
            "com.facebook.", "com.amazonaws.", "org.apache.", "org.json.",
            "com.fasterxml.", "org.slf4j.", "javax.", "junit.",
            "io.netty.", "com.bumptech.glide.", "org.greenrobot.",
            "com.airbnb.", "io.realm.", "bolts.", "butterknife."
    };

    public ClassRoutes(HeadlessJadxWrapper wrapper, PaginationUtils paginationUtils) {
        this.wrapper = wrapper;
        this.paginationUtils = paginationUtils;
    }

    public void register(Javalin app, AuthConfig authConfig) {
        app.get("/current-class", this::handleCurrentClass);
        app.get("/all-classes", this::handleAllClasses);
        app.get("/selected-text", this::handleSelectedText);
        app.get("/class-info", this::handleClassInfo);
        app.get("/methods-of-class", this::handleMethodsOfClass);
        app.get("/fields-of-class", this::handleFieldsOfClass);
        app.get("/package-classes", this::handlePackageClasses);
        app.get("/package-tree", this::handleGetPackageTree);
        app.get("/jar-bytecode", this::handleJarBytecode);
        app.get("/jar-entry-points", this::handleJarEntryPoints);
    }

    // ------------------------------- Request Handlers --------------------------

    /**
     * Handles {@code /current-class}.
     * Not available in headless mode — returns 501.
     */
    public void handleCurrentClass(Context ctx) {
        ctx.status(501).json(Map.of(
            "error", "not_available_in_headless_mode",
            "headless", true,
            "message", "current-class requires JADX GUI. Use get_class_source with a class name instead."
        ));
    }

    /**
     * Handles {@code /selected-text}.
     * Not available in headless mode — returns 501.
     */
    public void handleSelectedText(Context ctx) {
        ctx.status(501).json(Map.of(
            "error", "not_available_in_headless_mode",
            "headless", true,
            "message", "selected-text requires JADX GUI."
        ));
    }

    /**
     * Handles {@code /all-classes}.
     *
     * <p>Returns all classes decompiled from the loaded APK/JAR, with pagination.</p>
     */
    public void handleAllClasses(Context ctx) {
        try {
            List<JavaClass> classes = wrapper.getClassesWithInners();

            Map<String, Object> result = paginationUtils.handlePagination(
                    ctx,
                    classes,
                    "class-list",
                    "classes",
                    cls -> {
                        Map<String, Object> classEntry = new HashMap<>();
                        classEntry.put("name", JadxApiAdapter.getClassAliasName(cls));
                        classEntry.put("raw_name", JadxApiAdapter.getClassRawName(cls));
                        return classEntry;
                    });
            ctx.json(result);
        } catch (PaginationUtils.PaginationException e) {
            logger.error("Pagination Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Pagination Error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to load class list: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to load class list: " + e.getMessage()));
        }
    }

    /**
     * Handles {@code /class-info}.
     *
     * <p>Returns structured metadata about a class.</p>
     */
    public void handleClassInfo(Context ctx) {
        String className = checkClassParam(ctx);
        if (className == null) return;

        try {
            JavaClass cls = findClassByName(className);
            if (cls != null) {
                Map<String, Object> info = new HashMap<>();
                info.put("class_name", cls.getFullName());
                info.put("raw_class_name", cls.getRawName());
                info.put("simple_name", cls.getName());
                info.put("raw_simple_name", JadxApiAdapter.getClassRawSimpleName(cls));
                info.put("package", cls.getPackage());

                try {
                    jadx.core.dex.info.AccessInfo accessInfo = JadxApiAdapter.getAccessFlags(cls);
                    if (accessInfo != null) {
                        info.put("access_flags", accessInfo.toString());
                        info.put("is_interface", accessInfo.isInterface());
                        info.put("is_enum", accessInfo.isEnum());
                        info.put("is_abstract", accessInfo.isAbstract());
                        info.put("is_final", accessInfo.isFinal());
                    } else {
                        info.put("access_flags", "unknown");
                        info.put("is_interface", false);
                        info.put("is_enum", false);
                        info.put("is_abstract", false);
                        info.put("is_final", false);
                    }
                } catch (Exception e) {
                    info.put("access_flags", "unknown");
                    info.put("is_interface", false);
                    info.put("is_enum", false);
                    info.put("is_abstract", false);
                    info.put("is_final", false);
                }
                info.put("is_inner", cls.isInner());

                try {
                    String superClass = JadxApiAdapter.getSuperClass(cls);
                    info.put("super_class", superClass != null ? superClass : "java.lang.Object");
                } catch (Exception e) {
                    info.put("super_class", "java.lang.Object");
                }

                List<String> interfaces = new ArrayList<>();
                try {
                    interfaces.addAll(JadxApiAdapter.getInterfaces(cls));
                } catch (Exception e) {
                    logger.warn("Failed to get interfaces for {}: {}", className, e.getMessage());
                }
                info.put("interfaces", interfaces);

                List<Map<String, Object>> innerClasses = new ArrayList<>();
                try {
                    for (JavaClass inner : cls.getInnerClasses()) {
                        Map<String, Object> innerEntry = new HashMap<>();
                        innerEntry.put("name", JadxApiAdapter.getClassAliasName(inner));
                        innerEntry.put("raw_name", JadxApiAdapter.getClassRawName(inner));
                        innerClasses.add(innerEntry);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to get inner classes for {}: {}", className, e.getMessage());
                }
                info.put("inner_classes", innerClasses);

                List<JadxApiAdapter.MethodInfoSnapshot> declaredMethods =
                    JadxApiAdapter.getDeclaredMethodInfos(cls);
                List<JadxApiAdapter.FieldInfoSnapshot> declaredFields =
                    JadxApiAdapter.getDeclaredFieldInfos(cls);

                info.put("methods_count", declaredMethods.size());
                info.put("fields_count", declaredFields.size());

                List<String> methodNames = new ArrayList<>();
                List<String> rawMethodNames = new ArrayList<>();
                List<String> nativeMethodNames = new ArrayList<>();
                for (JadxApiAdapter.MethodInfoSnapshot methodSnapshot : declaredMethods) {
                    String rawName = methodSnapshot.getRawName();
                    String aliasName = methodSnapshot.getAliasName() != null
                        ? methodSnapshot.getAliasName()
                        : rawName;
                    methodNames.add(aliasName);
                    rawMethodNames.add(rawName);
                    if (methodSnapshot.getAccessFlags() != null && methodSnapshot.getAccessFlags().isNative()) {
                        nativeMethodNames.add(rawName);
                    }
                }
                info.put("method_names", methodNames);
                info.put("raw_method_names", rawMethodNames);
                info.put("native_method_names", nativeMethodNames);
                info.put("native_count", nativeMethodNames.size());

                List<String> fieldNames = new ArrayList<>();
                List<String> rawFieldNames = new ArrayList<>();
                for (JadxApiAdapter.FieldInfoSnapshot fieldSnapshot : declaredFields) {
                    fieldNames.add(fieldSnapshot.getAliasName() != null
                        ? fieldSnapshot.getAliasName()
                        : fieldSnapshot.getRawName());
                    rawFieldNames.add(fieldSnapshot.getRawName());
                }
                info.put("field_names", fieldNames);
                info.put("raw_field_names", rawFieldNames);

                ctx.json(info);
                return;
            }
            ctx.status(404).json(Map.of("error", "Class " + className + " not found."));
        } catch (Exception e) {
            logger.error("Internal error retrieving class info: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error retrieving class info: " + e.getMessage()));
        }
    }

    /**
     * Handles {@code /methods-of-class}.
     */
    public void handleMethodsOfClass(Context ctx) {
        String className = checkClassParam(ctx);
        if (className == null) return;

        try {
            JavaClass cls = findClassByName(className);
            if (cls != null) {
                Map<String, Integer> overloadCounts = new HashMap<>();
                for (JavaMethod method : cls.getMethods()) {
                    String name = method.getName();
                    overloadCounts.put(name, overloadCounts.getOrDefault(name, 0) + 1);
                }

                List<Map<String, Object>> methodsList = new ArrayList<>();
                for (JavaMethod method : cls.getMethods()) {
                    Map<String, Object> methodInfo = new HashMap<>();
                    methodInfo.put("name", method.getName());
                    methodInfo.put("raw_name", JadxApiAdapter.getMethodRawName(method));
                    methodInfo.put("full_id", JadxApiAdapter.getMethodFullId(method));
                    methodInfo.put("raw_full_id", JadxApiAdapter.getMethodRawFullId(method));

                    AccessInfo accessFlags = method.getAccessFlags();
                    if (accessFlags != null) {
                        methodInfo.put("is_static", accessFlags.isStatic());
                        methodInfo.put("is_native", accessFlags.isNative());
                        methodInfo.put("is_abstract", accessFlags.isAbstract());
                        methodInfo.put("is_synchronized", accessFlags.isSynchronized());

                        List<String> modifiers = new ArrayList<>();
                        if (accessFlags.isPublic()) modifiers.add("public");
                        if (accessFlags.isPrivate()) modifiers.add("private");
                        if (accessFlags.isProtected()) modifiers.add("protected");
                        if (accessFlags.isStatic()) modifiers.add("static");
                        if (accessFlags.isNative()) modifiers.add("native");
                        if (accessFlags.isAbstract()) modifiers.add("abstract");
                        if (accessFlags.isSynchronized()) modifiers.add("synchronized");
                        if (accessFlags.isFinal()) modifiers.add("final");
                        methodInfo.put("modifiers", modifiers);
                    } else {
                        methodInfo.put("is_static", false);
                        methodInfo.put("is_native", false);
                        methodInfo.put("is_abstract", false);
                        methodInfo.put("is_synchronized", false);
                        methodInfo.put("modifiers", new ArrayList<>());
                    }

                    methodInfo.put("is_constructor", method.isConstructor());
                    methodInfo.put("overload_count", overloadCounts.get(method.getName()));

                    String returnType = method.getReturnType() != null ?
                        method.getReturnType().toString() : "void";
                    methodInfo.put("return_type", returnType);

                    methodsList.add(methodInfo);
                }

                Map<String, Object> response = new HashMap<>();
                response.put("class_name", cls.getFullName());
                response.put("raw_class_name", cls.getRawName());
                response.put("methods", methodsList);
                response.put("count", methodsList.size());

                ctx.json(response);
                return;
            }
            ctx.status(404).json(Map.of("error", "Class " + className + " not found."));
        } catch (Exception e) {
            logger.error("Internal error retrieving methods: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error retrieving methods: " + e.getMessage()));
        }
    }

    /**
     * Handles {@code /fields-of-class}.
     */
    public void handleFieldsOfClass(Context ctx) {
        String className = checkClassParam(ctx);
        if (className == null) return;

        try {
            JavaClass cls = findClassByName(className);
            if (cls != null) {
                List<Map<String, Object>> fieldsList = new ArrayList<>();

                for (JavaField field : cls.getFields()) {
                    Map<String, Object> fieldInfo = new HashMap<>();
                    fieldInfo.put("name", field.getName());
                    fieldInfo.put("raw_name", field.getRawName());
                    fieldInfo.put("raw_full_id", JadxApiAdapter.getFieldRawFullId(field));

                    String typeStr = field.getType() != null ? field.getType().toString() : "unknown";
                    fieldInfo.put("type", typeStr);

                    if (field.getType() != null) {
                        fieldInfo.put("type_frida",
                            com.zin.delamain.utils.FridaTypeConverter.toFridaType(field.getType()));
                    } else {
                        fieldInfo.put("type_frida", typeStr);
                    }

                    AccessInfo accessFlags = field.getAccessFlags();
                    List<String> modifiers = new ArrayList<>();
                    if (accessFlags.isPublic()) modifiers.add("public");
                    if (accessFlags.isPrivate()) modifiers.add("private");
                    if (accessFlags.isProtected()) modifiers.add("protected");
                    if (accessFlags.isStatic()) modifiers.add("static");
                    if (accessFlags.isFinal()) modifiers.add("final");
                    if (accessFlags.isVolatile()) modifiers.add("volatile");
                    if (accessFlags.isTransient()) modifiers.add("transient");

                    fieldInfo.put("modifiers", modifiers);
                    fieldInfo.put("is_static", accessFlags.isStatic());
                    fieldInfo.put("is_final", accessFlags.isFinal());

                    fieldsList.add(fieldInfo);
                }

                Map<String, Object> response = new HashMap<>();
                response.put("class_name", cls.getFullName());
                response.put("raw_class_name", cls.getRawName());
                response.put("fields", fieldsList);
                response.put("count", fieldsList.size());

                ctx.json(response);
                return;
            }
            ctx.status(404).json(Map.of("error", "Class " + className + " not found."));
        } catch (Exception e) {
            logger.error("Internal error retrieving fields: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error retrieving fields: " + e.getMessage()));
        }
    }

    /**
     * Handles {@code /package-classes}.
     */
    public void handlePackageClasses(Context ctx) {
        try {
            String packagePrefix = ctx.queryParam("package");
            boolean autoDetect = "true".equalsIgnoreCase(ctx.queryParam("auto"));
            boolean includeInner = !"false".equalsIgnoreCase(ctx.queryParam("include_inner"));

            // Detect file type inline
            String fileTypeName = detectFileType();

            // Auto-detect package from manifest or JAR
            if (autoDetect || packagePrefix == null || packagePrefix.isEmpty()) {
                // Try manifest (APK)
                if (isAndroidFile()) {
                    try {
                        List<jadx.api.ResourceFile> resources = wrapper.getJadx().getResources();
                        jadx.api.ResourceFile manifestRes =
                            jadx.core.utils.android.AndroidManifestParser.getAndroidManifest(resources);
                        if (manifestRes != null) {
                            String manifestXml = manifestRes.loadContent().getText().getCodeStr();
                            try (java.io.InputStream xmlStream = new java.io.ByteArrayInputStream(
                                    manifestXml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                                org.w3c.dom.Document manifestDoc =
                                    wrapper.getArgs().getSecurity().parseXml(xmlStream);
                                manifestDoc.getDocumentElement().normalize();
                                org.w3c.dom.Element manifestElement =
                                    (org.w3c.dom.Element) manifestDoc.getElementsByTagName("manifest").item(0);
                                packagePrefix = manifestElement.getAttribute("package");
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to auto-detect package from manifest: {}", e.getMessage());
                    }
                } else if (isJarFile()) {
                    // Try JAR manifest
                    try {
                        File jarFile = wrapper.getInputFiles().get(0);
                        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
                            java.util.jar.Manifest manifest = jar.getManifest();
                            if (manifest != null) {
                                String startClass = manifest.getMainAttributes().getValue("Start-Class");
                                if (startClass == null) {
                                    startClass = manifest.getMainAttributes().getValue("Main-Class");
                                }
                                if (startClass != null && startClass.contains(".")) {
                                    int lastDot = startClass.lastIndexOf('.');
                                    packagePrefix = startClass.substring(0, lastDot);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to auto-detect package from JAR manifest: {}", e.getMessage());
                    }
                }
            }

            if (packagePrefix == null || packagePrefix.isEmpty()) {
                ctx.status(400).json(Map.of(
                    "error", "Package prefix not specified and could not be auto-detected",
                    "hint", "Use ?package=com.example or ?auto=true"
                ));
                return;
            }

            final String pkgPrefix = packagePrefix;
            List<JavaClass> allClasses = includeInner
                ? wrapper.getClassesWithInners()
                : wrapper.getClasses();

            List<JavaClass> matchedClasses = allClasses.stream()
                .filter(cls -> cls.getFullName().startsWith(pkgPrefix + ".") ||
                               cls.getFullName().equals(pkgPrefix))
                .collect(Collectors.toList());

            int offset = paginationUtils.getIntParam(ctx, "offset", 0);
            int count = paginationUtils.getIntParam(ctx, "count", 100);
            count = Math.min(count, 500);

            List<Map<String, Object>> classesInfo = new ArrayList<>();
            for (int i = offset; i < Math.min(offset + count, matchedClasses.size()); i++) {
                JavaClass cls = matchedClasses.get(i);
                Map<String, Object> classInfo = new HashMap<>();
                classInfo.put("name", cls.getFullName());
                classInfo.put("is_inner", cls.isInner());
                classesInfo.add(classInfo);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("type", "package-classes");
            result.put("file_type", fileTypeName);
            result.put("package", packagePrefix);
            result.put("classes", classesInfo);
            result.put("offset", offset);
            result.put("count", classesInfo.size());
            result.put("total_matched", matchedClasses.size());
            result.put("has_more", matchedClasses.size() > offset + classesInfo.size());
            result.put("status", "success");

            ctx.json(result);

        } catch (Exception e) {
            logger.error("Error getting package classes: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Error getting package classes: " + e.getMessage()));
        }
    }

    /**
     * Handles {@code /package-tree}.
     */
    public void handleGetPackageTree(Context ctx) {
        try {
            List<JavaClass> allClasses = wrapper.getClassesWithInners();

            Map<String, Integer> packageCounts = new HashMap<>();
            for (JavaClass cls : allClasses) {
                String fullName = cls.getFullName();
                int lastDot = fullName.lastIndexOf('.');
                String pkg = lastDot > 0 ? fullName.substring(0, lastDot) : "(default)";
                packageCounts.merge(pkg, 1, Integer::sum);
            }

            List<Map<String, Object>> packages = packageCounts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .map(entry -> {
                        Map<String, Object> pkg = new HashMap<>();
                        pkg.put("name", entry.getKey());
                        pkg.put("class_count", entry.getValue());
                        pkg.put("is_likely_library", isLikelyLibrary(entry.getKey()));
                        return pkg;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("total_classes", allClasses.size());
            result.put("total_packages", packages.size());
            result.put("packages", packages);
            ctx.json(result);
        } catch (Exception e) {
            logger.error("Internal error building package tree: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error building package tree: " + e.getMessage()));
        }
    }

    /**
     * Handles {@code /jar-entry-points}.
     */
    public void handleJarEntryPoints(Context ctx) {
        try {
            if (!isJarFile()) {
                String fileTypeName = detectFileType();
                Map<String, Object> response = new HashMap<>();
                response.put("status", "NOT_APPLICABLE");
                response.put("reason", "JAR entry points detection is only for JAR files. This is a " +
                    fileTypeName.toUpperCase() + " file.");
                response.put("file_type", fileTypeName);
                response.put("alternatives", List.of(
                    Map.of("tool", "get_main_activity_class", "description", "Get MainActivity for APK files")
                ));
                ctx.json(response);
                return;
            }

            List<File> inputFiles = wrapper.getInputFiles();
            if (inputFiles == null || inputFiles.isEmpty()) {
                ctx.status(404).json(Map.of("error", "No JAR file loaded"));
                return;
            }
            File jarFile = inputFiles.get(0);

            Map<String, Object> result = new HashMap<>();
            result.put("type", "jar-entry-points");
            result.put("file_name", jarFile.getName());

            List<Map<String, Object>> entryPoints = new ArrayList<>();
            String primaryEntry = null;

            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
                java.util.jar.Manifest manifest = jar.getManifest();
                if (manifest != null) {
                    java.util.jar.Attributes attrs = manifest.getMainAttributes();

                    String startClass = attrs.getValue("Start-Class");
                    if (startClass != null && !startClass.isEmpty()) {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("type", "spring_boot_start_class");
                        entry.put("class", startClass);
                        entry.put("source", "MANIFEST.MF Start-Class");
                        entry.put("priority", 1);
                        entryPoints.add(entry);
                        primaryEntry = startClass;
                    }

                    String mainClass = attrs.getValue("Main-Class");
                    if (mainClass != null && !mainClass.isEmpty()) {
                        Map<String, Object> entry = new HashMap<>();
                        boolean isSpringBootLauncher = mainClass.contains("springframework.boot.loader");
                        entry.put("type", isSpringBootLauncher ? "spring_boot_launcher" : "main_class");
                        entry.put("class", mainClass);
                        entry.put("source", "MANIFEST.MF Main-Class");
                        entry.put("priority", isSpringBootLauncher ? 3 : 1);
                        if (isSpringBootLauncher) {
                            entry.put("note", "This is Spring Boot launcher. Use Start-Class for actual app.");
                        }
                        entryPoints.add(entry);
                        if (primaryEntry == null && !isSpringBootLauncher) {
                            primaryEntry = mainClass;
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to read manifest for entry points: {}", e.getMessage());
            }

            List<JavaClass> allClasses = wrapper.getClassesWithInners();
            for (JavaClass cls : allClasses) {
                try {
                    String className = cls.getFullName();
                    if (className.endsWith("Application") ||
                        className.contains(".Application$") ||
                        className.endsWith("App") ||
                        className.contains(".bootstrap.")) {
                        for (JadxApiAdapter.MethodInfoSnapshot methodSnapshot : JadxApiAdapter.getDeclaredMethodInfos(cls)) {
                            if (methodSnapshot.getName().equals("main")
                                && methodSnapshot.getAccessFlags() != null
                                && methodSnapshot.getAccessFlags().isStatic()
                                && methodSnapshot.getAccessFlags().isPublic()) {
                                Map<String, Object> entry = new HashMap<>();
                                entry.put("type", "application_class");
                                entry.put("class", className);
                                entry.put("source", "naming_pattern");
                                entry.put("priority", 2);
                                entryPoints.add(entry);
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip classes that can't be analyzed
                }
            }

            int mainMethodCount = 0;
            for (JavaClass cls : allClasses) {
                if (mainMethodCount >= 10) break;
                try {
                    for (JadxApiAdapter.MethodInfoSnapshot methodSnapshot : JadxApiAdapter.getDeclaredMethodInfos(cls)) {
                        if (methodSnapshot.getName().equals("main")
                            && methodSnapshot.getAccessFlags() != null
                            && methodSnapshot.getAccessFlags().isStatic()
                            && methodSnapshot.getAccessFlags().isPublic()) {
                            if ("void".equals(String.valueOf(methodSnapshot.getReturnType()))) {
                                List<ArgType> args = methodSnapshot.getArgumentTypes();
                                if (args.size() == 1 && args.get(0).toString().contains("String[]")) {
                                    Map<String, Object> entry = new HashMap<>();
                                    entry.put("type", "main_method");
                                    entry.put("class", cls.getFullName());
                                    entry.put("method", "main(String[])");
                                    entry.put("source", "method_signature");
                                    entry.put("priority", 4);
                                    entryPoints.add(entry);
                                    mainMethodCount++;
                                    break;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip
                }
            }

            result.put("status", "success");
            result.put("entry_points", entryPoints);
            result.put("total_found", entryPoints.size());
            if (primaryEntry != null) {
                result.put("primary_entry", primaryEntry);
            }

            if (entryPoints.isEmpty()) {
                result.put("note", "No entry points found. This may be a library JAR without executable entry.");
            }

            ctx.json(result);

        } catch (Exception e) {
            logger.error("Error finding JAR entry points: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Error finding JAR entry points: " + e.getMessage()));
        }
    }

    /**
     * Handles {@code /jar-bytecode}.
     */
    public void handleJarBytecode(Context ctx) {
        try {
            String className = ctx.queryParam("class_name");
            if (className == null || className.isEmpty()) {
                ctx.status(400).json(Map.of("error", "class_name parameter is required"));
                return;
            }

            String fileTypeName = detectFileType();
            JavaClass targetClass = findClassByName(className);

            if (targetClass == null) {
                ctx.status(404).json(Map.of(
                    "error", "Class not found: " + className,
                    "suggestion", "Use search_classes_by_keyword to find the correct class name"
                ));
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("type", "bytecode");
            result.put("class_name", targetClass.getFullName());
            result.put("raw_class_name", targetClass.getRawName());
            result.put("file_type", fileTypeName);

            AccessInfo classAccessFlags = JadxApiAdapter.getAccessFlags(targetClass);
            List<JadxApiAdapter.FieldInfoSnapshot> fieldSnapshots = JadxApiAdapter.getDeclaredFieldInfos(targetClass);
            List<JadxApiAdapter.MethodInfoSnapshot> methodSnapshots = JadxApiAdapter.getDeclaredMethodInfos(targetClass);
            if (classAccessFlags == null) {
                result.put("error", "Cannot access bytecode - class node not available");
                ctx.json(result);
                return;
            }

            StringBuilder bytecode = new StringBuilder();
            bytecode.append("// Class: ").append(targetClass.getFullName()).append("\n");
            bytecode.append("// File type: ").append(fileTypeName.toUpperCase()).append("\n\n");
            bytecode.append("// Access: ").append(classAccessFlags.rawValue())
                    .append(" (").append(classAccessFlags.toString()).append(")\n");

            String superClass = JadxApiAdapter.getSuperClass(targetClass);
            if (superClass != null) {
                bytecode.append("// Extends: ").append(superClass).append("\n");
            }

            List<String> interfaces = JadxApiAdapter.getInterfaces(targetClass);
            if (!interfaces.isEmpty()) {
                bytecode.append("// Implements: ");
                for (int i = 0; i < interfaces.size(); i++) {
                    if (i > 0) bytecode.append(", ");
                    bytecode.append(interfaces.get(i));
                }
                bytecode.append("\n");
            }
            bytecode.append("\n");

            bytecode.append("// Fields:\n");
            for (JadxApiAdapter.FieldInfoSnapshot fieldSnapshot : fieldSnapshots) {
                bytecode.append("  ").append(fieldSnapshot.getAccessFlags().toString())
                        .append(" ").append(fieldSnapshot.getType())
                        .append(" ").append(fieldSnapshot.getName()).append("\n");
            }
            bytecode.append("\n");

            bytecode.append("// Methods:\n");
            for (JadxApiAdapter.MethodInfoSnapshot methodSnapshot : methodSnapshots) {
                bytecode.append("  ").append(methodSnapshot.getAccessFlags().toString())
                        .append(" ").append(methodSnapshot.getReturnType())
                        .append(" ").append(methodSnapshot.getName())
                        .append("(");

                var args = methodSnapshot.getArgumentTypes();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) bytecode.append(", ");
                    bytecode.append(args.get(i).toString());
                }
                bytecode.append(")\n");

                if (methodSnapshot.getBasicBlockCount() != null) {
                    bytecode.append("    // Basic blocks: ").append(methodSnapshot.getBasicBlockCount()).append("\n");
                }
            }

            result.put("bytecode", bytecode.toString());
            result.put("field_count", fieldSnapshots.size());
            result.put("method_count", methodSnapshots.size());
            result.put("status", "success");

            boolean smaliAvailable = isAndroidFile();
            result.put("smali_available", smaliAvailable);
            if (smaliAvailable) {
                result.put("note", "Use get_smali_of_class for full Dalvik bytecode (APK/DEX files)");
            } else {
                result.put("note", "JAR files use JVM bytecode. This shows class structure similar to javap.");
            }

            ctx.json(result);

        } catch (Exception e) {
            logger.error("Error getting bytecode: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Error getting bytecode: " + e.getMessage()));
        }
    }

    // ------------------------------- Helpers -----------------------------------

    private String checkClassParam(Context ctx) {
        String className = ctx.queryParam("class_name");
        if (className == null || className.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter 'class_name'"));
            return null;
        }
        return className;
    }

    private JavaClass findClassByName(String className) {
        if (className == null || className.isEmpty()) {
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

    private String detectFileType() {
        List<File> inputFiles = wrapper.getInputFiles();
        if (inputFiles == null || inputFiles.isEmpty()) {
            return "unknown";
        }
        String name = inputFiles.get(0).getName().toLowerCase();
        if (name.endsWith(".apk")) return "apk";
        if (name.endsWith(".aar")) return "aar";
        if (name.endsWith(".dex")) return "dex";
        if (name.endsWith(".jar")) return "jar";
        return "unknown";
    }

    private boolean isAndroidFile() {
        String type = detectFileType();
        return "apk".equals(type) || "aar".equals(type) || "dex".equals(type);
    }

    private boolean isJarFile() {
        return "jar".equals(detectFileType());
    }

    private boolean isLikelyLibrary(String packageName) {
        if (packageName == null) return false;
        for (String prefix : LIBRARY_PREFIXES) {
            if (packageName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
