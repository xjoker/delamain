package com.zin.delamain.utils;

import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for generating NOT_APPLICABLE responses.
 */
public class NotApplicableResponse {

    public static class Alternative {
        private final String tool;
        private final String description;

        public Alternative(String tool, String description) {
            this.tool = tool;
            this.description = description;
        }

        public Map<String, String> toMap() {
            Map<String, String> map = new HashMap<>();
            map.put("tool", tool);
            map.put("description", description);
            return map;
        }
    }

    public static void send(Context ctx, String reason, String fileType, List<Alternative> alternatives) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "NOT_APPLICABLE");
        response.put("reason", reason);
        response.put("file_type", fileType);

        if (alternatives != null && !alternatives.isEmpty()) {
            List<Map<String, String>> altList = new ArrayList<>();
            for (Alternative alt : alternatives) {
                altList.add(alt.toMap());
            }
            response.put("alternatives", altList);
        }

        ctx.status(200);
        ctx.json(response);
    }

    public static void sendSmaliNotAvailable(Context ctx, String fileType) {
        List<Alternative> alts = new ArrayList<>();
        alts.add(new Alternative("get_class_source", "Get decompiled Java source code"));
        send(ctx,
            "Smali generation is only available for APK/DEX files. JAR files use JVM bytecode, not Dalvik bytecode.",
            fileType,
            alts);
    }

    public static void sendManifestNotAvailable(Context ctx, String fileType) {
        List<Alternative> alts = new ArrayList<>();
        alts.add(new Alternative("get_all_classes", "Browse all classes in the package"));
        alts.add(new Alternative("search_classes_by_keyword", "Search for specific classes"));
        send(ctx,
            "AndroidManifest.xml is only available in APK/AAR files. This is a " + fileType.toUpperCase() + " file.",
            fileType,
            alts);
    }

    public static void sendStringsNotAvailable(Context ctx, String fileType) {
        List<Alternative> alts = new ArrayList<>();
        alts.add(new Alternative("search_classes_by_keyword", "Search for strings in code"));
        alts.add(new Alternative("get_class_source", "View class source for hardcoded strings"));
        send(ctx,
            "Android string resources (res/values/strings.xml) are only available in APK/AAR files.",
            fileType,
            alts);
    }

    public static void sendMainActivityNotAvailable(Context ctx, String fileType) {
        List<Alternative> alts = new ArrayList<>();
        alts.add(new Alternative("search_classes_by_keyword", "Search for 'Main' or 'Application' classes"));
        alts.add(new Alternative("get_all_classes", "Browse package structure to find entry points"));
        send(ctx,
            "Main Activity detection requires AndroidManifest.xml, which is not available in " + fileType.toUpperCase() + " files.",
            fileType,
            alts);
    }
}
