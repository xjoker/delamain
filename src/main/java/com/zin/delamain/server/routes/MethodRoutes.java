package com.zin.delamain.server.routes;

import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.ICodeInfo;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.utils.CodeUtils;
import jadx.api.plugins.input.data.annotations.IAnnotation;
import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.api.plugins.input.data.attributes.types.AnnotationsAttr;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.MethodOverrideAttr;
import jadx.core.dex.info.AccessInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.BaseInvokeNode;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.InsnWrapArg;
import jadx.core.dex.nodes.IMethodDetails;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.FridaTypeConverter;
import com.zin.delamain.utils.JadxApiAdapter;
import com.zin.delamain.utils.JadxSearchLock;
import com.zin.delamain.utils.SmartChunker;

public class MethodRoutes {
    private static final Logger logger = LoggerFactory.getLogger(MethodRoutes.class);

    private static final java.lang.reflect.Method JAVA_METHOD_GET_USED = findOptionalMethod(JavaMethod.class, "getUsed");
    private static final java.lang.reflect.Method JAVA_METHOD_GET_UNRESOLVED_USED =
        findOptionalMethod(JavaMethod.class, "getUnresolvedUsed");
    private static final java.lang.reflect.Method METHOD_NODE_GET_USED = findOptionalMethod(MethodNode.class, "getUsed");
    private static final java.lang.reflect.Method METHOD_NODE_GET_UNRESOLVED_USED =
        findOptionalMethod(MethodNode.class, "getUnresolvedUsed");

    static {
        logger.info("[delamain] MethodNode.getUsed() available via reflection: {}", METHOD_NODE_GET_USED != null);
    }

    private final HeadlessJadxWrapper wrapper;

    public MethodRoutes(HeadlessJadxWrapper wrapper) {
        this.wrapper = wrapper;
    }

    public void register(Javalin app, AuthConfig authConfig) {
        app.get("/method-by-name",      this::handleMethodByName);
        app.get("/batch-method-by-name", this::handleBatchMethodByName);
        app.get("/method-signature",    this::handleMethodSignature);
        app.get("/method-callees",      this::handleMethodCallees);
        app.get("/search-native-methods", this::handleSearchNativeMethods);
        app.get("/method-symbol-view",  this::handleMethodSymbolView);
        app.get("/method-source",       this::handleMethodSource);
    }

    // -------------------------------------------------------------------------
    // Route handlers
    // -------------------------------------------------------------------------

    public void handleMethodByName(Context ctx) {
        String className = ctx.queryParam("class_name");
        String methodSignature = ctx.queryParam("method_signature");
        if (methodSignature != null && methodSignature.isBlank()) methodSignature = null;

        String methodName = validateMethodParam(ctx);
        if (methodName == null) return;

        try {
            if (wrapper == null || !wrapper.isLoaded()) {
                ctx.status(500).json(Map.of("error", "Wrapper not initialized"));
                return;
            }

            if (!JadxSearchLock.tryAcquireRead()) {
                ctx.status(503).json(Map.of("error", "Decompilation operation in progress",
                    "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS, "busy", true));
                return;
            }
            JavaClass foundCls = null;
            JavaMethod foundMethod = null;
            Map<String, Object> earlyError = null;
            try {
                if (className == null || className.isEmpty()) {
                    outer1:
                    for (JavaClass cls : wrapper.getClassesWithInners()) {
                        for (JadxApiAdapter.MethodInfoSnapshot methodInfo : JadxApiAdapter.getDeclaredMethodInfos(cls)) {
                            if (matchesMethodName(methodInfo, methodName)) {
                                JavaMethod method = findMethodByNameAndDescriptor(cls, methodName, methodSignature);
                                if (method != null) {
                                    foundCls = cls;
                                    foundMethod = method;
                                    break outer1;
                                }
                            }
                        }
                    }
                } else {
                    for (JavaClass cls : wrapper.getClassesWithInners()) {
                        if (JadxApiAdapter.matchesClassName(cls, className)) {
                            List<JavaMethod> candidates = findMethodsByName(cls, methodName);
                            if (candidates.isEmpty()) break;

                            if (methodSignature != null) {
                                for (JavaMethod candidate : candidates) {
                                    if (JadxApiAdapter.matchesMethodDescriptor(candidate, methodSignature)) {
                                        foundCls = cls;
                                        foundMethod = candidate;
                                        break;
                                    }
                                }
                                if (foundMethod == null) {
                                    List<String> available = collectDescriptors(candidates);
                                    Map<String, Object> err = new HashMap<>();
                                    err.put("error", "No overload of " + methodName + " matches descriptor '"
                                        + methodSignature + "' in class " + cls.getFullName());
                                    err.put("available_descriptors", available);
                                    earlyError = err;
                                    earlyError.put("__status", 404);
                                }
                                break;
                            }

                            if (candidates.size() == 1) {
                                foundCls = cls;
                                foundMethod = candidates.get(0);
                                break;
                            }

                            List<String> available = collectDescriptors(candidates);
                            Map<String, Object> err = new HashMap<>();
                            err.put("error", "Method " + methodName + " in class " + cls.getFullName()
                                + " has " + candidates.size()
                                + " overloads. Provide 'method_signature' to select one.");
                            err.put("available_descriptors", available);
                            earlyError = err;
                            earlyError.put("__status", 300);
                            break;
                        }
                    }
                }
            } finally {
                JadxSearchLock.releaseRead();
            }

            if (earlyError != null) {
                int errStatus = ((Number) earlyError.remove("__status")).intValue();
                ctx.status(errStatus).json(earlyError);
                return;
            }

            if (foundCls != null && foundMethod != null) {
                if (!tryAcquireDecompileLock(ctx)) return;
                try {
                    returnMethodResult(ctx, foundCls, foundMethod);
                } finally {
                    JadxSearchLock.release();
                }
                return;
            }

            ctx.status(404).json(Map.of("error", "Requested method " + methodName + " not found."));
        } catch (Exception e) {
            logger.error("Internal error retrieving method: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleBatchMethodByName(Context ctx) {
        String methodsParam = ctx.queryParam("methods");
        if (methodsParam == null || methodsParam.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing 'methods' parameter. Provide comma-separated class_name:method_name pairs."));
            return;
        }

        String chunkParam = ctx.queryParam("chunk");
        int chunk = 0;
        if (chunkParam != null) {
            try {
                chunk = Integer.parseInt(chunkParam);
            } catch (NumberFormatException e) {
                ctx.status(400).json(Map.of("error", "Invalid 'chunk' parameter: must be an integer"));
                return;
            }
        }

        String[] methodPairs = methodsParam.split(",");
        final int MAX_BATCH_SIZE = 20;
        if (methodPairs.length > MAX_BATCH_SIZE) {
            ctx.status(400).json(Map.of("error", "Too many methods requested. Maximum " + MAX_BATCH_SIZE + " methods per request."));
            return;
        }

        try {
            if (wrapper == null || !wrapper.isLoaded()) {
                ctx.status(500).json(Map.of("error", "Wrapper not initialized"));
                return;
            }

            if (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
                ClassCacheManager.initCache(wrapper);
            }

            ClassCacheManager.CacheStatus status = ClassCacheManager.getStatus();
            if (status == ClassCacheManager.CacheStatus.LOADING) {
                Map<String, Object> health = ClassCacheManager.getHealthInfo();
                Map<String, Object> response = new HashMap<>();
                response.put("status", "loading");
                response.put("type", "batch-method-by-name");
                response.put("message", "Class cache is being loaded in background.");
                response.put("retry_after", 10);
                response.put("health", health);
                ctx.json(response);
                return;
            }

            if (!JadxSearchLock.tryAcquireRead()) {
                ctx.status(503).json(Map.of("error", "Decompilation operation in progress",
                    "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS, "busy", true));
                return;
            }

            List<Map<String, Object>> results = new ArrayList<>();
            List<JavaMethod> methodsToDecompile = new ArrayList<>();

            try {
                Map<String, JavaClass> classMap = ClassCacheManager.getCache();

                for (String pair : methodPairs) {
                    String trimmedPair = pair.trim();
                    Map<String, Object> methodResult = new HashMap<>();

                    int colonIndex = trimmedPair.lastIndexOf(':');
                    if (colonIndex == -1) {
                        methodResult.put("input", trimmedPair);
                        methodResult.put("found", false);
                        methodResult.put("error", "Invalid format. Use class_name:method_name");
                        results.add(methodResult);
                        methodsToDecompile.add(null);
                        continue;
                    }

                    String clsName = trimmedPair.substring(0, colonIndex);
                    String methodName = trimmedPair.substring(colonIndex + 1);

                    methodResult.put("class_name", clsName);
                    methodResult.put("method_name", methodName);

                    JavaClass cls = ClassCacheManager.findClass(classMap, clsName);
                    if (cls == null) {
                        methodResult.put("found", false);
                        methodResult.put("error", "Class not found");
                        results.add(methodResult);
                        methodsToDecompile.add(null);
                        continue;
                    }

                    JavaMethod method = findMethodByName(cls, methodName);
                    if (method != null) {
                        methodResult.put("class_name", cls.getFullName());
                        methodResult.put("raw_class_name", cls.getRawName());
                        methodResult.put("method_name", method.getName());
                        methodResult.put("raw_method_name", JadxApiAdapter.getMethodRawName(method));
                        methodResult.put("raw_method_full_id", JadxApiAdapter.getMethodRawFullId(method));
                        methodResult.put("found", true);
                        methodResult.put("decl", String.valueOf(method.getCodeNodeRef()));
                        results.add(methodResult);
                        methodsToDecompile.add(method);
                    } else {
                        methodResult.put("found", false);
                        methodResult.put("error", "Method not found in class");
                        results.add(methodResult);
                        methodsToDecompile.add(null);
                    }
                }
            } finally {
                JadxSearchLock.releaseRead();
            }

            int foundCount = 0;
            boolean needsDecompile = methodsToDecompile.stream().anyMatch(m -> m != null);
            if (needsDecompile) {
                if (!tryAcquireDecompileLock(ctx)) return;
                try {
                    for (int i = 0; i < methodsToDecompile.size(); i++) {
                        JavaMethod method = methodsToDecompile.get(i);
                        if (method == null) continue;
                        Map<String, Object> methodResult = results.get(i);
                        try {
                            methodResult.put("code", method.getCodeStr());
                            foundCount++;
                        } catch (Exception e) {
                            methodResult.put("error", "Failed to get code: " + e.getMessage());
                        }
                    }
                } finally {
                    JadxSearchLock.release();
                }
            } else {
                for (Map<String, Object> r : results) {
                    if (Boolean.TRUE.equals(r.get("found"))) foundCount++;
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("methods", results);
            response.put("total", methodPairs.length);
            response.put("found", foundCount);

            com.google.gson.Gson gson = new com.google.gson.Gson();
            String responseJson = gson.toJson(response);

            Map<String, Object> chunkedResponse = SmartChunker.chunkResponse(responseJson, chunk, "batch_result");

            if (chunkedResponse.containsKey("error")) {
                ctx.status(400).json(chunkedResponse);
                return;
            }

            ctx.json(chunkedResponse);
        } catch (Exception e) {
            logger.error("Internal error retrieving batch methods: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleMethodSignature(Context ctx) {
        String className = ctx.queryParam("class_name");
        String methodName = validateMethodParam(ctx);
        if (methodName == null) return;

        if (className == null || className.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter 'class_name'"));
            return;
        }

        try {
            for (JavaClass cls : wrapper.getClassesWithInners()) {
                if (JadxApiAdapter.matchesClassName(cls, className)) {
                    List<Map<String, Object>> signatures = new ArrayList<>();

                    for (JavaMethod method : cls.getMethods()) {
                        if (JadxApiAdapter.matchesMethodName(method, methodName)) {
                            Map<String, Object> sig = new HashMap<>();
                            sig.put("method_name", method.getName());
                            sig.put("raw_method_name", JadxApiAdapter.getMethodRawName(method));
                            sig.put("return_type", method.getReturnType() != null ?
                                method.getReturnType().toString() : "void");
                            sig.put("access_flags", method.getAccessFlags().toString());
                            sig.put("is_constructor", method.isConstructor());

                            List<Map<String, String>> params = new ArrayList<>();
                            List<jadx.core.dex.instructions.args.ArgType> argTypes = new ArrayList<>();

                            try {
                                argTypes = new ArrayList<>(method.getArguments());
                                int idx = 0;
                                for (jadx.core.dex.instructions.args.ArgType argType : argTypes) {
                                    Map<String, String> param = new HashMap<>();
                                    param.put("name", "arg" + idx);
                                    param.put("type", argType.toString());
                                    param.put("type_frida", FridaTypeConverter.toFridaType(argType));
                                    params.add(param);
                                    idx++;
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to get arguments for {}: {}", methodName, e.getMessage());
                            }
                            sig.put("parameters", params);

                            String fridaOverload = FridaTypeConverter.toFridaOverloadString(argTypes);
                            sig.put("frida_overload", fridaOverload);
                            sig.put("declaration", String.valueOf(method.getCodeNodeRef()));

                            signatures.add(sig);
                        }
                    }

                    if (signatures.isEmpty()) {
                        ctx.status(404).json(Map.of("error", "Method " + methodName + " not found in class " + className));
                        return;
                    }

                    Map<String, Object> response = new HashMap<>();
                    response.put("class_name", cls.getFullName());
                    response.put("raw_class_name", cls.getRawName());
                    response.put("method_name", methodName);
                    response.put("overloads", signatures.size());
                    response.put("signatures", signatures);
                    ctx.json(response);
                    return;
                }
            }
            ctx.status(404).json(Map.of("error", "Class " + className + " not found."));
        } catch (Exception e) {
            logger.error("Internal error retrieving method signature: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleMethodCallees(Context ctx) {
        String className = ctx.queryParam("class_name");
        String methodName = validateMethodParam(ctx);
        if (methodName == null) return;

        if (className == null || className.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter 'class_name'"));
            return;
        }

        try {
            if (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
                ClassCacheManager.initCache(wrapper);
            }

            Map<String, JavaClass> classMap = ClassCacheManager.getCache();
            JavaClass cls = ClassCacheManager.findClass(classMap, className);

            if (cls != null) {
                if (!tryAcquireDecompileLock(ctx)) return;
                try {
                    JavaMethod method = findMethodByName(cls, methodName);
                    if (method != null) {
                        CalleeAnalysisResult analysis = analyzeMethodCallees(method);

                        Map<String, Object> response = new HashMap<>();
                        response.put("class_name", cls.getFullName());
                        response.put("raw_class_name", cls.getRawName());
                        response.put("method_name", method.getName());
                        response.put("raw_method_name", JadxApiAdapter.getMethodRawName(method));
                        response.put("callees_count", analysis.getAllCallees().size());
                        response.put("callees", analysis.getAllCallees());
                        response.put("resolved_callees_count", analysis.getResolvedCallees().size());
                        response.put("resolved_callees", analysis.getResolvedCallees());
                        response.put("unresolved_callees_count", analysis.getUnresolvedCallees().size());
                        response.put("unresolved_callees", analysis.getUnresolvedCallees());
                        response.put("analysis_mode", analysis.getAnalysisMode());
                        response.put("note", analysis.getNote());

                        Map<String, Object> apiCompat = new HashMap<>();
                        apiCompat.put("get_used_via", METHOD_NODE_GET_USED != null ? "reflection" : "unavailable");
                        response.put("api_compatibility", apiCompat);

                        ctx.json(response);
                        return;
                    }
                } finally {
                    JadxSearchLock.release();
                }

                ctx.status(404).json(Map.of("error", "Method " + methodName + " not found in class " + className));
                return;
            }

            ctx.status(404).json(Map.of("error", "Class " + className + " not found."));
        } catch (Exception e) {
            logger.error("Internal error retrieving method callees: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleSearchNativeMethods(Context ctx) {
        String packageFilter = ctx.queryParam("package");
        int offset = 0;
        int count = 50;

        try {
            String offsetStr = ctx.queryParam("offset");
            if (offsetStr != null) offset = Integer.parseInt(offsetStr);
        } catch (NumberFormatException e) { /* use default */ }

        try {
            String countStr = ctx.queryParam("count");
            if (countStr != null) count = Math.min(Integer.parseInt(countStr), 200);
        } catch (NumberFormatException e) { /* use default */ }

        try {
            if (wrapper == null || !wrapper.isLoaded()) {
                ctx.status(500).json(Map.of("error", "Wrapper not initialized"));
                return;
            }

            List<Map<String, Object>> nativeMethods = new ArrayList<>();
            int totalFound = 0;
            int skipped = 0;

            if (!JadxSearchLock.tryAcquireRead()) {
                ctx.status(503).json(Map.of("error", "Decompilation operation in progress",
                    "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS, "busy", true));
                return;
            }
            try {
                for (JavaClass cls : wrapper.getClassesWithInners()) {
                    String clsName = cls.getFullName();
                    if (packageFilter != null && !packageFilter.isEmpty()) {
                        if (!clsName.startsWith(packageFilter)) continue;
                    }

                    for (JadxApiAdapter.MethodInfoSnapshot methodSnapshot : JadxApiAdapter.getDeclaredMethodInfos(cls)) {
                        if (methodSnapshot.getAccessFlags() != null && methodSnapshot.getAccessFlags().isNative()) {
                            totalFound++;

                            if (skipped < offset) {
                                skipped++;
                                continue;
                            }
                            if (nativeMethods.size() >= count) continue;

                            Map<String, Object> nativeMethodInfo = new HashMap<>();
                            nativeMethodInfo.put("class_name", clsName);
                            nativeMethodInfo.put("raw_class_name", JadxApiAdapter.getClassRawName(cls));
                            String methodAliasName = methodSnapshot.getAliasName() != null
                                ? methodSnapshot.getAliasName()
                                : methodSnapshot.getName();
                            nativeMethodInfo.put("method_name", methodAliasName);
                            nativeMethodInfo.put("raw_method_name", methodSnapshot.getName());
                            nativeMethodInfo.put("short_id", methodSnapshot.getShortId());

                            List<String> paramTypes = new ArrayList<>();
                            for (jadx.core.dex.instructions.args.ArgType argType : methodSnapshot.getArgumentTypes()) {
                                paramTypes.add(FridaTypeConverter.toFridaType(argType));
                            }
                            nativeMethodInfo.put("param_types_frida", paramTypes);

                            nativeMethods.add(nativeMethodInfo);
                        }
                    }
                }
            } finally {
                JadxSearchLock.releaseRead();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("native_methods", nativeMethods);
            response.put("count", nativeMethods.size());
            response.put("total_found", totalFound);
            response.put("offset", offset);
            response.put("has_more", totalFound > offset + nativeMethods.size());
            if (packageFilter != null && !packageFilter.isEmpty()) {
                response.put("package_filter", packageFilter);
            }

            ctx.json(response);
        } catch (Exception e) {
            logger.error("Internal error searching native methods: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Method symbol view
    // -------------------------------------------------------------------------

    public void handleMethodSymbolView(Context ctx) {
        String className = ctx.queryParam("class_name");
        String methodName = validateMethodParam(ctx);
        if (methodName == null) return;

        // method_signature is optional; used to disambiguate overloads
        String methodSignature = ctx.queryParam("method_signature");
        if (methodSignature != null && methodSignature.isBlank()) methodSignature = null;

        if (className == null || className.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter 'class_name'"));
            return;
        }

        try {
            if (wrapper == null || !wrapper.isLoaded()) {
                ctx.status(500).json(Map.of("error", "Wrapper not initialized"));
                return;
            }

            if (!JadxSearchLock.tryAcquireRead()) {
                ctx.status(503).json(Map.of("error", "Decompilation operation in progress",
                    "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS, "busy", true));
                return;
            }

            JavaClass foundCls = null;
            List<JavaMethod> candidates = null;
            try {
                for (JavaClass cls : wrapper.getClassesWithInners()) {
                    if (JadxApiAdapter.matchesClassName(cls, className)) {
                        candidates = findMethodsByName(cls, methodName);
                        foundCls = cls;
                        break;
                    }
                }
            } finally {
                JadxSearchLock.releaseRead();
            }

            if (foundCls == null) {
                ctx.status(404).json(Map.of("error", "Class " + className + " not found."));
                return;
            }
            if (candidates == null || candidates.isEmpty()) {
                ctx.status(404).json(Map.of("error", "Method " + methodName + " not found in class " + className));
                return;
            }

            // Disambiguate overloads
            JavaMethod targetMethod = null;
            if (methodSignature != null) {
                for (JavaMethod m : candidates) {
                    if (JadxApiAdapter.matchesMethodDescriptor(m, methodSignature)) {
                        targetMethod = m;
                        break;
                    }
                }
                if (targetMethod == null) {
                    List<String> available = collectDescriptors(candidates);
                    Map<String, Object> err = new HashMap<>();
                    err.put("error", "No overload of " + methodName + " matches descriptor '"
                        + methodSignature + "' in class " + foundCls.getFullName());
                    err.put("available_descriptors", available);
                    ctx.status(404).json(err);
                    return;
                }
            } else if (candidates.size() == 1) {
                targetMethod = candidates.get(0);
            } else {
                List<String> available = collectDescriptors(candidates);
                Map<String, Object> err = new HashMap<>();
                err.put("error", "Method " + methodName + " in class " + foundCls.getFullName()
                    + " has " + candidates.size()
                    + " overloads. Provide 'method_signature' to select one.");
                err.put("available_descriptors", available);
                // 400, not 300 (Multiple Choices): clients that don't special-case 3xx would
                // treat this as success. This is a "need a disambiguating param" client error.
                ctx.status(400).json(err);
                return;
            }

            ctx.json(buildMethodSymbolView(foundCls, targetMethod));
        } catch (Exception e) {
            logger.error("Internal error retrieving method symbol view: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    public void handleMethodSource(Context ctx) {
        String className = ctx.queryParam("class_name");
        String methodSignature = ctx.queryParam("method_signature");
        if (methodSignature != null && methodSignature.isBlank()) methodSignature = null;

        String methodName = validateMethodParam(ctx);
        if (methodName == null) return;

        if (className == null || className.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter 'class_name'"));
            return;
        }

        try {
            if (wrapper == null || !wrapper.isLoaded()) {
                ctx.status(500).json(Map.of("error", "Wrapper not initialized"));
                return;
            }

            if (!JadxSearchLock.tryAcquireRead()) {
                ctx.status(503).json(Map.of("error", "Decompilation operation in progress",
                    "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS, "busy", true));
                return;
            }

            JavaClass foundCls = null;
            JavaMethod foundMethod = null;
            Map<String, Object> earlyError = null;
            try {
                for (JavaClass cls : wrapper.getClassesWithInners()) {
                    if (JadxApiAdapter.matchesClassName(cls, className)) {
                        List<JavaMethod> candidates = findMethodsByName(cls, methodName);
                        if (candidates.isEmpty()) break;

                        if (methodSignature != null) {
                            for (JavaMethod candidate : candidates) {
                                if (JadxApiAdapter.matchesMethodDescriptor(candidate, methodSignature)) {
                                    foundCls = cls;
                                    foundMethod = candidate;
                                    break;
                                }
                            }
                            if (foundMethod == null) {
                                List<String> available = collectDescriptors(candidates);
                                Map<String, Object> err = new HashMap<>();
                                err.put("error", "No overload of " + methodName + " matches descriptor '"
                                    + methodSignature + "' in class " + cls.getFullName());
                                err.put("available_descriptors", available);
                                earlyError = err;
                                earlyError.put("__status", 404);
                            }
                            break;
                        }

                        if (candidates.size() == 1) {
                            foundCls = cls;
                            foundMethod = candidates.get(0);
                            break;
                        }

                        List<String> available = collectDescriptors(candidates);
                        Map<String, Object> err = new HashMap<>();
                        err.put("error", "Method " + methodName + " in class " + cls.getFullName()
                            + " has " + candidates.size()
                            + " overloads. Provide 'method_signature' to select one.");
                        err.put("available_descriptors", available);
                        earlyError = err;
                        earlyError.put("__status", 300);
                        break;
                    }
                }
            } finally {
                JadxSearchLock.releaseRead();
            }

            if (earlyError != null) {
                int errStatus = ((Number) earlyError.remove("__status")).intValue();
                ctx.status(errStatus).json(earlyError);
                return;
            }

            if (foundCls == null) {
                ctx.status(404).json(Map.of("error", "Class " + className + " not found."));
                return;
            }
            if (foundMethod == null) {
                ctx.status(404).json(Map.of("error", "Method " + methodName + " not found in class " + className));
                return;
            }

            // Decompile lock required for getCodeStr() and getCodeInfo()
            if (!tryAcquireDecompileLock(ctx)) return;
            try {
                ctx.json(buildMethodSourceResult(foundCls, foundMethod));
            } finally {
                JadxSearchLock.release();
            }

        } catch (Exception e) {
            logger.error("Internal error retrieving method source: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    private Map<String, Object> buildMethodSourceResult(JavaClass cls, JavaMethod method) {
        // --- Source code ---
        String methodCode;
        try {
            methodCode = method.getCodeStr();
        } catch (Exception e) {
            logger.error("Error retrieving method code: {}", e.getMessage());
            methodCode = "// Error retrieving code: " + e.getMessage();
        }

        // --- Frida overload string ---
        List<ArgType> argTypes = new ArrayList<>();
        try {
            argTypes = new ArrayList<>(method.getArguments());
        } catch (Exception e) {
            logger.warn("Failed to get arguments for {}: {}", method.getName(), e.getMessage());
        }
        String fridaOverload = FridaTypeConverter.toFridaOverloadString(argTypes);

        // --- Line numbers (best-effort) ---
        int startLine = -1;
        int endLine = -1;
        try {
            ICodeInfo codeInfo = cls.getCodeInfo();
            if (codeInfo != null) {
                int defPos = method.getDefPos();
                if (defPos > 0) {
                    int decompiledLine = CodeUtils.getLineNumForPos(
                        codeInfo.getCodeStr(),
                        defPos,
                        wrapper.getArgs().getCodeNewLineStr()
                    );
                    if (decompiledLine > 0) {
                        startLine = decompiledLine;
                        if (methodCode != null && !methodCode.isEmpty()) {
                            int lineCount = methodCode.split(
                                java.util.regex.Pattern.quote(wrapper.getArgs().getCodeNewLineStr()), -1
                            ).length;
                            endLine = startLine + lineCount - 1;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve line numbers for {}.{}: {}", cls.getFullName(), method.getName(), e.getMessage());
        }

        // --- Import list (from class source header) ---
        List<String> imports = extractImports(cls);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class_name", cls.getFullName());
        result.put("method_name", method.getName());
        result.put("raw_method_name", JadxApiAdapter.getMethodRawName(method));
        result.put("frida_overload", fridaOverload);
        result.put("start_line", startLine);
        result.put("end_line", endLine);
        result.put("imports", imports);
        result.put("source", methodCode);
        return result;
    }

    private List<String> extractImports(JavaClass cls) {
        try {
            String classCode = ClassCacheManager.getCachedCodeDirect(cls);
            if (classCode == null) return Collections.emptyList();
            List<String> imports = new ArrayList<>();
            String newLine = wrapper.getArgs().getCodeNewLineStr();
            for (String line : classCode.split(java.util.regex.Pattern.quote(newLine), -1)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
                    imports.add(trimmed.substring(7, trimmed.length() - 1));
                } else if (!trimmed.isEmpty() && !trimmed.startsWith("//")
                           && !trimmed.startsWith("/*")
                           && !trimmed.startsWith("*")
                           && !trimmed.startsWith("package ")) {
                    break; // past the import section
                }
            }
            return imports;
        } catch (Exception e) {
            logger.debug("Failed to extract imports for {}: {}", cls.getFullName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    private Map<String, Object> buildMethodSymbolView(JavaClass cls, JavaMethod method) {
        Map<String, Object> result = new LinkedHashMap<>();

        // --- Identity (raw + display dual-track) ---
        result.put("class_name", cls.getFullName());
        result.put("raw_class_name", cls.getRawName());
        result.put("method_name", method.getName());
        result.put("raw_method_name", JadxApiAdapter.getMethodRawName(method));
        result.put("method_full_id", JadxApiAdapter.getMethodFullId(method));
        result.put("raw_method_full_id", JadxApiAdapter.getMethodRawFullId(method));

        // --- Return type ---
        ArgType returnType = method.getReturnType();
        result.put("return_type", returnType != null ? returnType.toString() : "void");
        result.put("return_type_frida", returnType != null ? FridaTypeConverter.toFridaType(returnType) : "void");

        // --- Parameters ---
        List<Map<String, String>> params = new ArrayList<>();
        List<ArgType> argTypes = new ArrayList<>();
        try {
            argTypes = new ArrayList<>(method.getArguments());
            int idx = 0;
            for (ArgType argType : argTypes) {
                Map<String, String> param = new HashMap<>();
                param.put("name", "arg" + idx);
                param.put("type", argType.toString());
                param.put("type_frida", FridaTypeConverter.toFridaType(argType));
                params.add(param);
                idx++;
            }
        } catch (Exception e) {
            logger.warn("Failed to get arguments for {}: {}", method.getName(), e.getMessage());
        }
        result.put("parameters", params);
        result.put("frida_overload", FridaTypeConverter.toFridaOverloadString(argTypes));
        result.put("is_constructor", method.isConstructor());
        result.put("is_class_init", method.isClassInit());

        // --- Declared throws (from MethodNode) ---
        List<String> throwsList = new ArrayList<>();
        MethodNode methodNode = JadxApiAdapter.getInternalMethodNode(method);
        if (methodNode != null) {
            try {
                List<ArgType> throws_ = methodNode.getThrows();
                if (throws_ != null) {
                    for (ArgType t : throws_) {
                        throwsList.add(t.toString());
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to get throws for {}: {}", method.getName(), e.getMessage());
            }
        }
        result.put("throws", throwsList);

        // --- Access flags ---
        AccessInfo accessInfo = method.getAccessFlags();
        Map<String, Object> accessMap = new LinkedHashMap<>();
        if (accessInfo != null) {
            accessMap.put("raw_string", accessInfo.toString());
            accessMap.put("is_public", accessInfo.isPublic());
            accessMap.put("is_protected", accessInfo.isProtected());
            accessMap.put("is_private", accessInfo.isPrivate());
            accessMap.put("is_package_private", accessInfo.isPackagePrivate());
            accessMap.put("is_static", accessInfo.isStatic());
            accessMap.put("is_final", accessInfo.isFinal());
            accessMap.put("is_abstract", accessInfo.isAbstract());
            accessMap.put("is_synchronized", accessInfo.isSynchronized());
            accessMap.put("is_native", accessInfo.isNative());
            accessMap.put("is_bridge", accessInfo.isBridge());
            accessMap.put("is_synthetic", accessInfo.isSynthetic());
            accessMap.put("is_var_args", accessInfo.isVarArgs());
            accessMap.put("raw_value", accessInfo.rawValue());
        }
        result.put("access_flags", accessMap);

        // --- Java annotations on this method ---
        List<Map<String, Object>> annotations = new ArrayList<>();
        if (methodNode != null) {
            try {
                AnnotationsAttr annAttr = methodNode.get(JadxAttrType.ANNOTATION_LIST);
                if (annAttr != null && !annAttr.isEmpty()) {
                    for (IAnnotation ann : annAttr.getList()) {
                        Map<String, Object> annMap = new LinkedHashMap<>();
                        annMap.put("annotation_class", ann.getAnnotationClass());
                        annMap.put("visibility", ann.getVisibility() != null ? ann.getVisibility().toString() : null);
                        Map<String, String> valuesStr = new LinkedHashMap<>();
                        if (ann.getValues() != null) {
                            for (Map.Entry<String, jadx.api.plugins.input.data.annotations.EncodedValue> entry : ann.getValues().entrySet()) {
                                valuesStr.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : null);
                            }
                        }
                        annMap.put("values", valuesStr);
                        annotations.add(annMap);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to get annotations for {}: {}", method.getName(), e.getMessage());
            }
        }
        result.put("annotations", annotations);

        // --- Owning class inheritance chain ---
        Map<String, Object> classHierarchy = new LinkedHashMap<>();
        classHierarchy.put("super_class", JadxApiAdapter.getSuperClass(cls));
        classHierarchy.put("interfaces", JadxApiAdapter.getInterfaces(cls));
        result.put("class_hierarchy", classHierarchy);

        // --- Override relations via JavaMethod.getOverrideRelatedMethods() ---
        List<Map<String, Object>> overrideRelated = new ArrayList<>();
        try {
            List<JavaMethod> related = method.getOverrideRelatedMethods();
            if (related != null) {
                for (JavaMethod rel : related) {
                    if (rel == method) continue; // skip self
                    Map<String, Object> relMap = new LinkedHashMap<>();
                    relMap.put("class_name", rel.getDeclaringClass() != null ? rel.getDeclaringClass().getFullName() : "");
                    relMap.put("raw_class_name", rel.getDeclaringClass() != null ? rel.getDeclaringClass().getRawName() : "");
                    relMap.put("method_name", rel.getName());
                    relMap.put("raw_method_name", JadxApiAdapter.getMethodRawName(rel));
                    relMap.put("raw_method_full_id", JadxApiAdapter.getMethodRawFullId(rel));
                    overrideRelated.add(relMap);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to get override related methods for {}: {}", method.getName(), e.getMessage());
        }
        result.put("override_related_methods", overrideRelated);

        // --- Base methods via MethodOverrideAttr (more precise: which interface/super defines this) ---
        List<Map<String, Object>> baseMethods = new ArrayList<>();
        if (methodNode != null) {
            try {
                MethodOverrideAttr overrideAttr = methodNode.get(AType.METHOD_OVERRIDE);
                if (overrideAttr != null) {
                    for (IMethodDetails base : overrideAttr.getBaseMethods()) {
                        MethodInfo baseInfo = base.getMethodInfo();
                        if (baseInfo == null) continue;
                        Map<String, Object> baseMap = new LinkedHashMap<>();
                        baseMap.put("class_name", baseInfo.getDeclClass().getFullName());
                        baseMap.put("raw_class_name", baseInfo.getDeclClass().getRawName());
                        // raw = getName() (original/runtime name — use this for Frida/Xposed hooks);
                        // display = getAlias() (deobf/renamed name). Keep the two distinct.
                        baseMap.put("method_name", baseInfo.getAlias() != null ? baseInfo.getAlias() : baseInfo.getName());
                        baseMap.put("raw_method_name", baseInfo.getName());
                        baseMap.put("raw_method_full_id", baseInfo.getRawFullId());
                        baseMap.put("short_id", baseInfo.getShortId());
                        baseMethods.add(baseMap);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to get base methods for {}: {}", method.getName(), e.getMessage());
            }
        }
        result.put("base_methods", baseMethods);

        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String validateMethodParam(Context ctx) {
        String methodName = ctx.queryParam("method_name");
        if (methodName == null || methodName.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter 'method_name'"));
            return null;
        }
        return methodName;
    }

    private JavaMethod findMethodByName(JavaClass cls, String methodName) {
        for (JavaMethod method : cls.getMethods()) {
            if (JadxApiAdapter.matchesMethodName(method, methodName)) return method;
        }
        return null;
    }

    private List<JavaMethod> findMethodsByName(JavaClass cls, String methodName) {
        List<JavaMethod> result = new ArrayList<>();
        for (JavaMethod method : cls.getMethods()) {
            if (JadxApiAdapter.matchesMethodName(method, methodName)) result.add(method);
        }
        return result;
    }

    private JavaMethod findMethodByNameAndDescriptor(JavaClass cls, String methodName, String descriptor) {
        for (JavaMethod method : cls.getMethods()) {
            if (JadxApiAdapter.matchesMethodName(method, methodName)
                    && JadxApiAdapter.matchesMethodDescriptor(method, descriptor)) {
                return method;
            }
        }
        return null;
    }

    private List<String> collectDescriptors(List<JavaMethod> methods) {
        List<String> descriptors = new ArrayList<>(methods.size());
        for (JavaMethod m : methods) {
            String shortId = JadxApiAdapter.getMethodInfo(m) != null
                ? JadxApiAdapter.getMethodInfo(m).getShortId()
                : null;
            if (shortId != null) descriptors.add(shortId);
        }
        return descriptors;
    }

    private boolean matchesMethodName(JadxApiAdapter.MethodInfoSnapshot methodInfo, String methodName) {
        if (methodInfo == null || methodName == null || methodName.isEmpty()) return false;
        return methodName.equalsIgnoreCase(methodInfo.getRawName())
            || (methodInfo.getAliasName() != null && methodName.equalsIgnoreCase(methodInfo.getAliasName()))
            || (methodInfo.getFullId() != null && methodName.equalsIgnoreCase(methodInfo.getFullId()))
            || (methodInfo.getRawFullId() != null && methodName.equalsIgnoreCase(methodInfo.getRawFullId()));
    }

    private void returnMethodResult(Context ctx, JavaClass cls, JavaMethod method) {
        String codeStr;
        try {
            codeStr = method.getCodeStr();
        } catch (Exception e) {
            logger.error("Error retrieving code: {}", e.getMessage());
            codeStr = "Error retrieving code: " + e.getMessage();
        }

        Map<String, String> result = new HashMap<>();
        result.put("class_name", cls.getFullName());
        result.put("raw_class_name", cls.getRawName());
        result.put("method_name", method.getName());
        result.put("raw_method_name", JadxApiAdapter.getMethodRawName(method));
        result.put("method_full_id", JadxApiAdapter.getMethodFullId(method));
        result.put("raw_method_full_id", JadxApiAdapter.getMethodRawFullId(method));
        result.put("decl", String.valueOf(method.getCodeNodeRef()));
        result.put("code", codeStr);
        ctx.json(result);
    }

    // -------------------------------------------------------------------------
    // Callee analysis
    // -------------------------------------------------------------------------

    private CalleeAnalysisResult analyzeMethodCallees(JavaMethod method) {
        CalleeAnalysisResult publicApiResult = analyzeMethodCalleesWithJavaMethodApi(method);
        if (publicApiResult != null) return publicApiResult;

        CalleeAnalysisResult internalApiResult = analyzeMethodCalleesWithMethodNodeApi(method);
        if (internalApiResult != null) return internalApiResult;

        return analyzeMethodCalleesFromInstructions(method);
    }

    private CalleeAnalysisResult analyzeMethodCalleesWithJavaMethodApi(JavaMethod method) {
        if (JAVA_METHOD_GET_USED == null || JAVA_METHOD_GET_UNRESOLVED_USED == null) return null;
        try {
            CalleeAnalysisResult result = new CalleeAnalysisResult(
                "semantic-public-api",
                "Collected callees via JavaMethod.getUsed()/getUnresolvedUsed()."
            );
            addResolvedCallees(result, invokeNodeCollection(JAVA_METHOD_GET_USED, method));
            addUnresolvedCallees(result, invokeMethodRefCollection(JAVA_METHOD_GET_UNRESOLVED_USED, method));
            return result;
        } catch (ReflectiveOperationException e) {
            logger.warn("Failed to use JavaMethod semantic callee API: {}", e.getMessage());
            return null;
        }
    }

    private CalleeAnalysisResult analyzeMethodCalleesWithMethodNodeApi(JavaMethod method) {
        if (METHOD_NODE_GET_USED == null || METHOD_NODE_GET_UNRESOLVED_USED == null) return null;
        MethodNode methodNode = JadxApiAdapter.getInternalMethodNode(method);
        if (methodNode == null) return null;
        try {
            CalleeAnalysisResult result = new CalleeAnalysisResult(
                "semantic-internal-api",
                "Collected callees via internal MethodNode usage API."
            );
            addResolvedCallees(result, invokeNodeCollection(METHOD_NODE_GET_USED, methodNode));
            addUnresolvedCallees(result, invokeMethodRefCollection(METHOD_NODE_GET_UNRESOLVED_USED, methodNode));
            return result;
        } catch (ReflectiveOperationException e) {
            logger.warn("Failed to use MethodNode semantic callee API: {}", e.getMessage());
            return null;
        }
    }

    private CalleeAnalysisResult analyzeMethodCalleesFromInstructions(JavaMethod method) {
        CalleeAnalysisResult result = new CalleeAnalysisResult(
            "semantic-instruction-fallback",
            "JavaMethod.getUsed()/getUnresolvedUsed() unavailable; using MethodNode instruction analysis."
        );

        MethodNode methodNode = JadxApiAdapter.getInternalMethodNode(method);
        if (methodNode == null) return result;

        InsnNode[] instructions = methodNode.getInstructions();
        if (instructions == null) return result;

        for (InsnNode instruction : instructions) {
            collectInvokeCallees(methodNode, instruction, result);
        }
        return result;
    }

    private void collectInvokeCallees(MethodNode callerMethod, InsnNode instruction, CalleeAnalysisResult result) {
        if (instruction == null) return;

        if (instruction instanceof BaseInvokeNode) {
            MethodInfo calledMethod = ((BaseInvokeNode) instruction).getCallMth();
            if (calledMethod != null) {
                MethodNode resolvedMethod = callerMethod.root().resolveMethod(calledMethod);
                if (resolvedMethod != null) {
                    result.addResolved(buildResolvedCalleeInfo(resolvedMethod));
                } else {
                    result.addUnresolved(buildUnresolvedCalleeInfo(calledMethod));
                }
            }
        }

        for (InsnArg argument : instruction.getArguments()) {
            if (argument.isInsnWrap()) {
                collectInvokeCallees(callerMethod, ((InsnWrapArg) argument).getWrapInsn(), result);
            }
        }
    }

    private void addResolvedCallees(CalleeAnalysisResult result, Collection<?> usedNodes) {
        for (Object usedNode : usedNodes) {
            if (usedNode instanceof JavaMethod) {
                result.addResolved(buildResolvedCalleeInfo((JavaMethod) usedNode));
            } else if (usedNode instanceof MethodNode) {
                result.addResolved(buildResolvedCalleeInfo((MethodNode) usedNode));
            }
        }
    }

    private void addUnresolvedCallees(CalleeAnalysisResult result, Collection<IMethodRef> unresolvedRefs) {
        for (IMethodRef unresolvedRef : unresolvedRefs) {
            result.addUnresolved(buildUnresolvedCalleeInfo(unresolvedRef));
        }
    }

    private Collection<?> invokeNodeCollection(java.lang.reflect.Method apiMethod, Object target)
            throws ReflectiveOperationException {
        Object value = apiMethod.invoke(target);
        if (value instanceof Collection<?>) return (Collection<?>) value;
        return Collections.emptyList();
    }

    private Collection<IMethodRef> invokeMethodRefCollection(java.lang.reflect.Method apiMethod, Object target)
            throws ReflectiveOperationException {
        Object value = apiMethod.invoke(target);
        if (!(value instanceof Collection<?>)) return Collections.emptyList();

        List<IMethodRef> methodRefs = new ArrayList<>();
        for (Object item : (Collection<?>) value) {
            if (item instanceof IMethodRef) methodRefs.add((IMethodRef) item);
        }
        return methodRefs;
    }

    private Map<String, Object> buildResolvedCalleeInfo(JavaMethod calleeMethod) {
        return buildResolvedCalleeInfo(JadxApiAdapter.getMethodInfo(calleeMethod));
    }

    private Map<String, Object> buildResolvedCalleeInfo(MethodNode calleeMethod) {
        return buildResolvedCalleeInfo(JadxApiAdapter.getMethodInfo(calleeMethod));
    }

    private Map<String, Object> buildResolvedCalleeInfo(JadxApiAdapter.MethodInfoSnapshot calleeMethodInfo) {
        Map<String, Object> info = new LinkedHashMap<>();
        if (calleeMethodInfo == null) {
            info.put("class_name", "");
            info.put("method_name", "");
            info.put("raw_method_name", "");
            info.put("full_name", "");
            info.put("raw_full_id", "");
            info.put("short_id", "");
            info.put("display_name", "");
            return info;
        }
        String fullName = calleeMethodInfo.getFullName();
        String aliasFullName = calleeMethodInfo.getAliasFullName();
        String clsName = calleeMethodInfo.getDeclaringClassName();
        info.put("class_name", clsName);
        info.put("method_name", calleeMethodInfo.getAliasName() != null
            ? calleeMethodInfo.getAliasName()
            : calleeMethodInfo.getRawName());
        info.put("raw_method_name", calleeMethodInfo.getRawName());
        info.put("full_name", fullName);
        info.put("alias_full_name", aliasFullName);
        info.put("raw_full_id", calleeMethodInfo.getRawFullId());
        info.put("short_id", calleeMethodInfo.getShortId());
        info.put("display_name", aliasFullName != null ? aliasFullName : fullName);
        return info;
    }

    private Map<String, Object> buildUnresolvedCalleeInfo(MethodInfo unresolvedMethod) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("class_name", unresolvedMethod.getDeclClass().getFullName());
        info.put("method_name", unresolvedMethod.getName());
        info.put("raw_method_name", unresolvedMethod.getName());
        info.put("full_name", unresolvedMethod.getFullName());
        info.put("raw_full_id", unresolvedMethod.getRawFullId());
        info.put("short_id", unresolvedMethod.getShortId());
        info.put("display_name", unresolvedMethod.getFullName());
        return info;
    }

    private Map<String, Object> buildUnresolvedCalleeInfo(IMethodRef unresolvedMethod) {
        Map<String, Object> info = new LinkedHashMap<>();
        String clsName = normalizeTypeName(unresolvedMethod.getParentClassType());
        info.put("class_name", clsName);
        info.put("method_name", unresolvedMethod.getName());
        info.put("full_name", clsName + "." + unresolvedMethod.getName());
        info.put("arg_types", new ArrayList<>(unresolvedMethod.getArgTypes()));
        info.put("return_type", unresolvedMethod.getReturnType());
        info.put("display_name", clsName + "." + unresolvedMethod.getName());
        return info;
    }

    private static java.lang.reflect.Method findOptionalMethod(Class<?> type, String methodName) {
        try {
            return type.getMethod(methodName);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private String normalizeTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) return "";
        if (typeName.startsWith("L") && typeName.endsWith(";")) {
            return typeName.substring(1, typeName.length() - 1).replace('/', '.');
        }
        return typeName.replace('/', '.');
    }

    private boolean tryAcquireDecompileLock(Context ctx) {
        if (JadxSearchLock.tryAcquire()) return true;
        ctx.status(503).json(Map.of("error", "Decompilation operation in progress",
            "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS, "busy", true));
        return false;
    }

    // -------------------------------------------------------------------------
    // Callee result model
    // -------------------------------------------------------------------------

    private static final class CalleeAnalysisResult {
        private final LinkedHashMap<String, Map<String, Object>> resolved = new LinkedHashMap<>();
        private final LinkedHashMap<String, Map<String, Object>> unresolved = new LinkedHashMap<>();
        private final String analysisMode;
        private final String note;

        private CalleeAnalysisResult(String analysisMode, String note) {
            this.analysisMode = analysisMode;
            this.note = note;
        }

        private void addResolved(Map<String, Object> callee) {
            resolved.putIfAbsent(String.valueOf(callee.get("full_name")) + "#" + String.valueOf(callee.get("short_id")), callee);
        }

        private void addUnresolved(Map<String, Object> callee) {
            String key = String.valueOf(callee.get("full_name")) + "#"
                + String.valueOf(callee.getOrDefault("short_id", callee.getOrDefault("arg_types", "")));
            unresolved.putIfAbsent(key, callee);
        }

        private List<String> getAllCallees() {
            Set<String> combined = new LinkedHashSet<>();
            for (Map<String, Object> callee : resolved.values()) combined.add(String.valueOf(callee.get("display_name")));
            for (Map<String, Object> callee : unresolved.values()) combined.add(String.valueOf(callee.get("display_name")));
            return new ArrayList<>(combined);
        }

        private List<Map<String, Object>> getResolvedCallees() { return new ArrayList<>(resolved.values()); }
        private List<Map<String, Object>> getUnresolvedCallees() { return new ArrayList<>(unresolved.values()); }
        private String getAnalysisMode() { return analysisMode; }
        private String getNote() { return note; }
    }
}
