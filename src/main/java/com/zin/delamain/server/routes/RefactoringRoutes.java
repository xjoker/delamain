package com.zin.delamain.server.routes;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.core.RenameStorage;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.JadxApiAdapter;

/**
 * Headless refactoring routes.
 *
 * Rename operations use {@link RenameStorage} (direct ClassNode/MethodNode/FieldNode rename + JSON persistence)
 * instead of the GUI {@code NodeRenamedByUser} event bus that is unavailable in headless mode.
 *
 * Variable rename ({@code /rename-variable}) is not implemented in headless mode because
 * SSA variable tracking requires Swing EDT processing; it returns a 501 NOT_IMPLEMENTED response.
 */
public class RefactoringRoutes {
    private static final Logger logger = LoggerFactory.getLogger(RefactoringRoutes.class);
    private static final Gson GSON = new Gson();

    private final HeadlessJadxWrapper wrapper;
    private final RenameStorage renameStorage;

    public RefactoringRoutes(HeadlessJadxWrapper wrapper, RenameStorage renameStorage) {
        this.wrapper = wrapper;
        this.renameStorage = renameStorage;
    }

    public void register(Javalin app, AuthConfig authConfig) {
        app.post("/rename-class",             this::handleRenameClass);
        app.post("/rename-method",            this::handleRenameMethod);
        app.post("/rename-field",             this::handleRenameField);
        app.post("/rename-package",           this::handleRenamePackage);
        app.get("/export-rename-mappings",    this::handleExportRenameMappings);
        app.post("/import-rename-mappings",   this::handleImportRenameMappings);
        app.post("/apply-proguard-mapping",   this::handleApplyProguardMapping);
        app.post("/rename-variable",          this::handleRenameVariable);
    }

    // -------------------------------------------------------------------------
    // Route handlers
    // -------------------------------------------------------------------------

    public void handleRenameClass(Context ctx) {
        JsonObject requestBody = parseJsonBody(ctx);
        if (requestBody == null) return;

        String className = getBodyString(requestBody, "class_name");
        String newName = getBodyString(requestBody, "new_name");
        if (validateParams(ctx, className, newName)) return;

        try {
            if (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
                ClassCacheManager.initCache(wrapper);
            }
            Map<String, JavaClass> classMap = ClassCacheManager.getCache();
            JavaClass cls = ClassCacheManager.findClass(classMap, className);

            if (cls != null) {
                String oldName = cls.getName();
                renameStorage.renameClass(cls, newName);
                ClassCacheManager.invalidateCode(className);
                ClassCacheManager.invalidateCode(cls.getFullName());
                logger.info("Renamed class '{}' to '{}'", oldName, newName);
                ctx.json(Map.of("result", "Renamed Class " + oldName + " to " + newName));
                return;
            }

            ctx.status(404).json(Map.of("error", "Class " + className + " not found."));
        } catch (Exception e) {
            logger.error("Internal error renaming class: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleRenameMethod(Context ctx) {
        JsonObject requestBody = parseJsonBody(ctx);
        if (requestBody == null) return;

        String methodName = getBodyString(requestBody, "method_name");
        String newName = getBodyString(requestBody, "new_name");
        String className = getBodyString(requestBody, "class_name");
        String methodSignature = getBodyString(requestBody, "method_signature");
        if (methodSignature != null && methodSignature.isBlank()) methodSignature = null;

        if (validateParams(ctx, methodName, newName)) return;

        if (methodName.contains("(")) {
            methodName = methodName.substring(0, methodName.indexOf('('));
        }

        try {
            if (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
                ClassCacheManager.initCache(wrapper);
            }

            String simpleMethodName = methodName;
            if ((className == null || className.isEmpty()) && methodName.contains(":")) {
                int colonIdx = methodName.lastIndexOf(':');
                className = methodName.substring(0, colonIdx);
                simpleMethodName = methodName.substring(colonIdx + 1);
            } else if ((className == null || className.isEmpty()) && methodName.contains(".")) {
                int lastDot = methodName.lastIndexOf('.');
                className = methodName.substring(0, lastDot);
                simpleMethodName = methodName.substring(lastDot + 1);
            }

            if (className == null || className.isEmpty()) {
                ctx.status(400).json(Map.of("error", "Missing required parameter 'class_name'"));
                return;
            }

            Map<String, JavaClass> classMap = ClassCacheManager.getCache();
            JavaClass cls = ClassCacheManager.findClass(classMap, className);

            if (cls != null) {
                List<JavaMethod> candidates = new ArrayList<>();
                for (JavaMethod method : cls.getMethods()) {
                    if (JadxApiAdapter.matchesMethodName(method, simpleMethodName)) candidates.add(method);
                }

                if (candidates.isEmpty()) {
                    ctx.status(404).json(Map.of("error", "Method " + simpleMethodName + " not found in class " + className));
                    return;
                }

                if (methodSignature != null) {
                    for (JavaMethod method : candidates) {
                        if (JadxApiAdapter.matchesMethodDescriptor(method, methodSignature)) {
                            doRenameMethod(ctx, cls, method, newName, className);
                            return;
                        }
                    }
                    List<String> available = collectMethodDescriptors(candidates);
                    Map<String, Object> err = new HashMap<>();
                    err.put("error", "No overload of " + simpleMethodName + " matches descriptor '"
                        + methodSignature + "' in class " + className);
                    err.put("available_descriptors", available);
                    ctx.status(404).json(err);
                    return;
                }

                if (candidates.size() == 1) {
                    doRenameMethod(ctx, cls, candidates.get(0), newName, className);
                    return;
                }

                List<String> available = collectMethodDescriptors(candidates);
                Map<String, Object> err = new HashMap<>();
                err.put("error", "Method " + simpleMethodName + " in class " + className
                    + " has " + candidates.size()
                    + " overloads. Provide 'method_signature' to select one.");
                err.put("available_descriptors", available);
                ctx.status(300).json(err);
                return;
            }

            ctx.status(404).json(Map.of("error", "Class " + className + " not found."));
        } catch (Exception e) {
            logger.error("Internal error renaming method: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleRenameField(Context ctx) {
        JsonObject requestBody = parseJsonBody(ctx);
        if (requestBody == null) return;

        String className = getBodyString(requestBody, "class_name");
        String oldFieldName = getBodyString(requestBody, "field_name");
        String newFieldName = getBodyString(requestBody, "new_field_name");

        if (validateParams(ctx, className, oldFieldName, newFieldName)) return;

        try {
            if (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
                ClassCacheManager.initCache(wrapper);
            }
            Map<String, JavaClass> classMap = ClassCacheManager.getCache();
            JavaClass cls = ClassCacheManager.findClass(classMap, className);

            if (cls != null) {
                for (JavaField field : cls.getFields()) {
                    if (JadxApiAdapter.matchesFieldName(field, oldFieldName)) {
                        String oldName = field.getName();
                        renameStorage.renameField(field, newFieldName);
                        ClassCacheManager.invalidateCode(className);
                        ClassCacheManager.invalidateCode(cls.getFullName());
                        logger.info("Renamed field '{}' to '{}'", oldName, newFieldName);
                        ctx.json(Map.of("result", "Renamed field " + oldName + " to " + newFieldName));
                        return;
                    }
                }
                ctx.status(404).json(Map.of("error", "Field " + oldFieldName + " not found in class " + className));
                return;
            }

            ctx.status(404).json(Map.of("error", "Class " + className + " not found."));
        } catch (Exception e) {
            logger.error("Internal error renaming field: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleRenamePackage(Context ctx) {
        JsonObject requestBody = parseJsonBody(ctx);
        if (requestBody == null) return;

        String oldPackage = getBodyString(requestBody, "old_package_name");
        String newPackage = getBodyString(requestBody, "new_package_name");
        if (validateParams(ctx, oldPackage, newPackage)) return;

        try {
            List<String> errors = new ArrayList<>();
            int count = 0;
            int total = 0;

            for (JavaClass cls : wrapper.getClassesWithInners()) {
                String fullName = cls.getFullName();
                if (fullName.startsWith(oldPackage + ".") || fullName.equals(oldPackage)) {
                    total++;
                    try {
                        String relativePath = fullName.substring(oldPackage.length());
                        String newFullName = newPackage + relativePath;
                        renameStorage.renameClass(cls, newFullName);
                        count++;
                    } catch (Exception e) {
                        errors.add("Failed to rename " + fullName + ": " + e.getMessage());
                    }
                }
            }

            if (count > 0) {
                ClassCacheManager.clearCodeCache();
            }

            Map<String, Object> result = new HashMap<>();
            result.put("renamed", count);
            result.put("total", total);
            result.put("errors", errors);
            ctx.json(result);
        } catch (Exception e) {
            logger.error("Internal error renaming package: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleExportRenameMappings(Context ctx) {
        try {
            if (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
                ClassCacheManager.initCache(wrapper);
            }

            List<Map<String, String>> mappings = new ArrayList<>();

            for (JavaClass cls : wrapper.getClassesWithInners()) {
                String rawName = cls.getRawName();
                String aliasName = cls.getFullName();

                if (rawName != null && aliasName != null && !rawName.equals(aliasName)) {
                    Map<String, String> entry = new HashMap<>();
                    entry.put("type", "class");
                    entry.put("original_name", rawName);
                    entry.put("new_name", aliasName);
                    entry.put("class_context", "");
                    mappings.add(entry);
                }

                for (JavaField field : cls.getFields()) {
                    String rawFieldName = field.getRawName();
                    String aliasFieldName = field.getName();
                    if (rawFieldName != null && aliasFieldName != null && !rawFieldName.equals(aliasFieldName)) {
                        Map<String, String> fEntry = new HashMap<>();
                        fEntry.put("type", "field");
                        fEntry.put("original_name", rawFieldName);
                        fEntry.put("new_name", aliasFieldName);
                        fEntry.put("class_context", cls.getFullName());
                        mappings.add(fEntry);
                    }
                }

                for (JavaMethod method : cls.getMethods()) {
                    String rawMethodName = JadxApiAdapter.getMethodRawName(method);
                    String aliasMethodName = JadxApiAdapter.getMethodAliasName(method);
                    if (rawMethodName != null && aliasMethodName != null && !rawMethodName.equals(aliasMethodName)) {
                        Map<String, String> mEntry = new HashMap<>();
                        mEntry.put("type", "method");
                        mEntry.put("original_name", rawMethodName);
                        mEntry.put("new_name", aliasMethodName);
                        mEntry.put("class_context", cls.getFullName());
                        mappings.add(mEntry);
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("mappings", mappings);
            result.put("total", mappings.size());
            logger.info("Exported {} rename mappings", mappings.size());
            ctx.json(result);
        } catch (Exception e) {
            logger.error("Failed to export rename mappings: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleImportRenameMappings(Context ctx) {
        JsonObject requestBody = parseJsonBody(ctx);
        if (requestBody == null) return;

        if (!requestBody.has("mappings") || !requestBody.get("mappings").isJsonArray()) {
            ctx.status(400).json(Map.of("error", "Missing or invalid 'mappings' array in request body"));
            return;
        }

        JsonArray mappingsArray = requestBody.getAsJsonArray("mappings");

        try {
            if (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
                ClassCacheManager.initCache(wrapper);
            }

            Map<String, JavaClass> classMap = ClassCacheManager.getCache();
            int successCount = 0;
            int failCount = 0;
            List<String> errors = new ArrayList<>();

            for (JsonElement element : mappingsArray) {
                if (!element.isJsonObject()) {
                    failCount++;
                    errors.add("Skipped non-object element");
                    continue;
                }

                JsonObject entry = element.getAsJsonObject();
                String type = getStringFromJson(entry, "type");
                String originalName = getStringFromJson(entry, "original_name");
                String newName = getStringFromJson(entry, "new_name");
                String classContext = getStringFromJson(entry, "class_context");

                if (type == null || originalName == null || newName == null) {
                    failCount++;
                    errors.add("Missing required fields (type/original_name/new_name) in entry: " + entry);
                    continue;
                }

                try {
                    switch (type.toLowerCase()) {
                        case "class": {
                            JavaClass cls = ClassCacheManager.findClass(classMap, originalName);
                            if (cls == null) {
                                failCount++;
                                errors.add("Class not found: " + originalName);
                                continue;
                            }
                            renameStorage.renameClass(cls, newName);
                            ClassCacheManager.invalidateCode(originalName);
                            successCount++;
                            break;
                        }
                        case "method": {
                            if (classContext == null || classContext.isEmpty()) {
                                failCount++;
                                errors.add("class_context required for method: " + originalName);
                                continue;
                            }
                            JavaClass cls = ClassCacheManager.findClass(classMap, classContext);
                            if (cls == null) {
                                failCount++;
                                errors.add("Class not found for method: " + classContext);
                                continue;
                            }
                            boolean found = false;
                            for (JavaMethod method : cls.getMethods()) {
                                if (JadxApiAdapter.matchesMethodName(method, originalName)) {
                                    renameStorage.renameMethod(method, newName);
                                    ClassCacheManager.invalidateCode(classContext);
                                    found = true;
                                    successCount++;
                                    break;
                                }
                            }
                            if (!found) {
                                failCount++;
                                errors.add("Method not found: " + originalName + " in " + classContext);
                            }
                            break;
                        }
                        case "field": {
                            if (classContext == null || classContext.isEmpty()) {
                                failCount++;
                                errors.add("class_context required for field: " + originalName);
                                continue;
                            }
                            JavaClass cls = ClassCacheManager.findClass(classMap, classContext);
                            if (cls == null) {
                                failCount++;
                                errors.add("Class not found for field: " + classContext);
                                continue;
                            }
                            boolean found = false;
                            for (JavaField field : cls.getFields()) {
                                if (JadxApiAdapter.matchesFieldName(field, originalName)) {
                                    renameStorage.renameField(field, newName);
                                    ClassCacheManager.invalidateCode(classContext);
                                    found = true;
                                    successCount++;
                                    break;
                                }
                            }
                            if (!found) {
                                failCount++;
                                errors.add("Field not found: " + originalName + " in " + classContext);
                            }
                            break;
                        }
                        default:
                            failCount++;
                            errors.add("Unknown type '" + type + "' for entry: " + originalName);
                    }
                } catch (Exception e) {
                    failCount++;
                    errors.add("Error processing " + type + " '" + originalName + "': " + e.getMessage());
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", failCount == 0);
            result.put("total", mappingsArray.size());
            result.put("applied", successCount);
            result.put("failed", failCount);
            result.put("errors", errors);
            logger.info("Import rename mappings: applied={}, failed={}", successCount, failCount);
            ctx.json(result);
        } catch (Exception e) {
            logger.error("Failed to import rename mappings: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleApplyProguardMapping(Context ctx) {
        JsonObject requestBody = parseJsonBody(ctx);
        if (requestBody == null) return;

        String mappingContent = getBodyString(requestBody, "mapping_content");
        if (mappingContent == null || mappingContent.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter 'mapping_content'"));
            return;
        }

        java.util.regex.Pattern classLinePattern =
            java.util.regex.Pattern.compile("^([\\w.$]+)\\s*->\\s*([\\w.$]+)\\s*:.*$");

        JsonArray mappingsArray = new JsonArray();
        List<String> parseErrors = new ArrayList<>();

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.StringReader(mappingContent))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (line.startsWith(" ") || line.startsWith("\t")) continue;

                java.util.regex.Matcher m = classLinePattern.matcher(trimmed);
                if (!m.matches()) continue;

                String originalName = m.group(1).trim();
                String obfuscatedName = m.group(2).trim();

                JsonObject entry = new JsonObject();
                entry.addProperty("type", "class");
                entry.addProperty("original_name", obfuscatedName);
                entry.addProperty("new_name", originalName);
                entry.addProperty("class_context", "");
                mappingsArray.add(entry);
            }
        } catch (Exception e) {
            logger.error("Failed to parse ProGuard mapping: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to parse ProGuard mapping: " + e.getMessage()));
            return;
        }

        if (mappingsArray.size() == 0) {
            ctx.json(Map.of("applied", 0, "failed", 0, "errors", parseErrors, "total", 0, "format", "proguard"));
            return;
        }

        try {
            if (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
                ClassCacheManager.initCache(wrapper);
            }

            Map<String, JavaClass> classMap = ClassCacheManager.getCache();
            int successCount = 0;
            int failCount = 0;
            List<String> errors = new ArrayList<>(parseErrors);

            for (JsonElement element : mappingsArray) {
                if (!element.isJsonObject()) {
                    failCount++;
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                String originalName = getStringFromJson(entry, "original_name");
                String newName = getStringFromJson(entry, "new_name");
                if (originalName == null || newName == null) {
                    failCount++;
                    continue;
                }

                try {
                    JavaClass cls = ClassCacheManager.findClass(classMap, originalName);
                    if (cls == null) {
                        failCount++;
                        errors.add("Class not found: " + originalName);
                        continue;
                    }
                    renameStorage.renameClass(cls, newName);
                    ClassCacheManager.invalidateCode(originalName);
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    errors.add("Error renaming '" + originalName + "': " + e.getMessage());
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("applied", successCount);
            result.put("failed", failCount);
            result.put("errors", errors);
            result.put("total", mappingsArray.size());
            result.put("format", "proguard");
            logger.info("Apply ProGuard mapping: applied={}, failed={}", successCount, failCount);
            ctx.json(result);
        } catch (Exception e) {
            logger.error("Failed to apply ProGuard mapping: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    /**
     * Variable rename is not implemented in headless mode.
     * SSA variable tracking requires JADX GUI's Swing EDT processing pipeline
     * which is unavailable in headless operation.
     */
    public void handleRenameVariable(Context ctx) {
        ctx.status(501).json(Map.of(
            "status", "not_implemented_headless",
            "message", "Variable rename is not available in headless mode. " +
                "SSA variable tracking requires JADX GUI's EDT processing pipeline.",
            "alternatives", List.of(
                Map.of("tool", "rename_method", "description", "Rename the method containing the variable"),
                Map.of("tool", "get_class_source", "description", "View the class source to understand variable usage")
            )
        ));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void doRenameMethod(Context ctx, JavaClass cls, JavaMethod method, String newName, String className) {
        try {
            String oldName = method.getName();
            renameStorage.renameMethod(method, newName);
            ClassCacheManager.invalidateCode(className);
            ClassCacheManager.invalidateCode(cls.getFullName());
            logger.info("Renamed method '{}' to '{}'", oldName, newName);
            ctx.json(Map.of("result", "Rename method " + oldName + " to " + newName));
        } catch (Exception e) {
            logger.error("Failed to rename method: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to rename method: " + e.getMessage()));
        }
    }

    private List<String> collectMethodDescriptors(List<JavaMethod> methods) {
        List<String> descriptors = new ArrayList<>(methods.size());
        for (JavaMethod m : methods) {
            JadxApiAdapter.MethodInfoSnapshot info = JadxApiAdapter.getMethodInfo(m);
            if (info != null && info.getShortId() != null) descriptors.add(info.getShortId());
        }
        return descriptors;
    }

    private JsonObject parseJsonBody(Context ctx) {
        try {
            String rawBody = ctx.body();
            if (rawBody == null || rawBody.isBlank()) {
                ctx.status(400).json(Map.of("error", "Missing JSON request body"));
                return null;
            }
            JsonObject body = GSON.fromJson(rawBody, JsonObject.class);
            if (body == null) {
                ctx.status(400).json(Map.of("error", "Missing JSON request body"));
                return null;
            }
            return body;
        } catch (JsonParseException e) {
            ctx.status(400).json(Map.of("error", "Invalid JSON request body"));
            return null;
        }
    }

    private String getBodyString(JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull() || !body.get(key).isJsonPrimitive()) return null;
        String value = body.get(key).getAsString();
        return value == null ? null : value.trim();
    }

    private String getStringFromJson(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString().trim() : null;
    }

    private boolean validateParams(Context ctx, String p1, String p2) {
        if (p1 == null || p1.isEmpty() || p2 == null || p2.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameters"));
            return true;
        }
        return false;
    }

    private boolean validateParams(Context ctx, String p1, String p2, String p3) {
        if (p1 == null || p1.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter 'class_name'"));
            return true;
        }
        if (p2 == null || p2.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter 'field_name'"));
            return true;
        }
        if (p3 == null || p3.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter 'new_field_name'"));
            return true;
        }
        return false;
    }
}
