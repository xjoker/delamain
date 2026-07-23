package com.zin.delamain.server.routes;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.CodeStore;
import com.zin.delamain.index.WarmupManager;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.JadxApiAdapter;
import com.zin.delamain.utils.JadxSearchLock;
import com.zin.delamain.utils.ManifestInfoService;

import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.core.dex.instructions.BaseInvokeNode;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.InsnWrapArg;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analysis Routes: attack surface, string literal search, call graph export.
 */
public class AnalysisRoutes {
    private static final Logger logger = LoggerFactory.getLogger(AnalysisRoutes.class);
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    static final int DEFAULT_LITERAL_SCAN_MAX_CLASSES = Integer.parseInt(
        System.getenv().getOrDefault("DELAMAIN_LITERAL_SCAN_MAX_CLASSES", "20000")
    );
    static final int DEFAULT_LITERAL_SCAN_TIMEOUT_SECONDS = Integer.parseInt(
        System.getenv().getOrDefault("DELAMAIN_LITERAL_SCAN_TIMEOUT_SECONDS", "10")
    );
    static int LITERAL_SCAN_MAX_CLASSES = DEFAULT_LITERAL_SCAN_MAX_CLASSES;
    static int LITERAL_SCAN_TIMEOUT_SECONDS = DEFAULT_LITERAL_SCAN_TIMEOUT_SECONDS;

    /** Known DANGEROUS-level Android permissions. */
    private static final Set<String> DANGEROUS_PERMISSIONS = new HashSet<>(Arrays.asList(
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.GET_ACCOUNTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS",
        "android.permission.CALL_PHONE",
        "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.ADD_VOICEMAIL",
        "android.permission.USE_SIP",
        "android.permission.BODY_SENSORS",
        "android.permission.BODY_SENSORS_BACKGROUND",
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_WAP_PUSH",
        "android.permission.RECEIVE_MMS",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.UWB_RANGING",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.USE_EXACT_ALARM",
        "com.android.voicemail.permission.ADD_VOICEMAIL"
    ));

    private final HeadlessJadxWrapper wrapper;
    private final ManifestInfoService manifestInfoService = ManifestInfoService.getInstance();

    public AnalysisRoutes(HeadlessJadxWrapper wrapper) {
        this.wrapper = wrapper;
    }

    public void register(Javalin app, AuthConfig auth) {
        app.get("/attack-surface", this::handleAttackSurface);
        app.get("/search-string-literals", this::handleSearchStringLiterals);
        app.get("/export-callgraph", this::handleExportCallgraph);
    }

    // -------------------------------------------------------------------------
    // GET /attack-surface
    // -------------------------------------------------------------------------

    /**
     * GET /attack-surface
     *
     * Parses AndroidManifest.xml and returns a structured view of the APK's
     * exported components, intent filters, permissions, and deep-links.
     */
    public void handleAttackSurface(Context ctx) {
        try {
            if (wrapper == null) {
                ctx.status(503).json(Map.of("error", "JADX wrapper not initialized", "total_exported", 0));
                return;
            }

            String manifestContent = manifestInfoService.getManifestContent(wrapper.getJadx());
            if (manifestContent == null || manifestContent.isBlank()) {
                ctx.json(Map.of(
                    "error", "manifest not available",
                    "total_exported", 0
                ));
                return;
            }

            Document doc = parseXml(manifestContent);
            if (doc == null) {
                ctx.json(Map.of("error", "failed to parse manifest", "total_exported", 0));
                return;
            }

            Element manifestEl = getFirstElement(doc, "manifest");
            String pkgName = manifestEl != null ? emptyToNull(manifestEl.getAttribute("package")) : null;
            Element appEl = getFirstElement(doc, "application");

            List<Map<String, Object>> activities = new ArrayList<>();
            List<Map<String, Object>> services = new ArrayList<>();
            List<Map<String, Object>> receivers = new ArrayList<>();
            List<Map<String, Object>> providers = new ArrayList<>();
            List<Map<String, Object>> customPermissions = new ArrayList<>();
            List<String> dangerousPermissionsUsed = new ArrayList<>();
            Set<String> deeplinkSummary = new LinkedHashSet<>();

            // Parse <uses-permission> for dangerous ones
            NodeList usesPermNodes = doc.getElementsByTagName("uses-permission");
            for (int i = 0; i < usesPermNodes.getLength(); i++) {
                Node n = usesPermNodes.item(i);
                if (!(n instanceof Element)) continue;
                String permName = getAndroidAttr((Element) n, "name");
                if (permName != null && DANGEROUS_PERMISSIONS.contains(permName)) {
                    dangerousPermissionsUsed.add(permName);
                }
            }

            // Parse <permission> for custom permissions
            NodeList permNodes = doc.getElementsByTagName("permission");
            for (int i = 0; i < permNodes.getLength(); i++) {
                Node n = permNodes.item(i);
                if (!(n instanceof Element)) continue;
                Element el = (Element) n;
                String permName = getAndroidAttr(el, "name");
                String protectionLevel = getAndroidAttr(el, "protectionLevel");
                if (permName != null) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", permName);
                    entry.put("protection_level", resolveProtectionLevel(protectionLevel));
                    customPermissions.add(entry);
                }
            }

            // targetSdk for implicit-export detection: components with intent-filter and targetSdk<31
            // are implicitly exported (Android 12 / API 31 changed the default).
            int targetSdk = 0;
            try {
                String sdkStr = manifestInfoService.getTargetSdkVersion(wrapper.getJadx());
                if (sdkStr != null) targetSdk = Integer.parseInt(sdkStr.trim());
            } catch (Exception ignored) {}

            // Parse application components
            if (appEl != null) {
                NodeList children = appEl.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node node = children.item(i);
                    if (!(node instanceof Element)) continue;
                    Element el = (Element) node;
                    String tag = el.getTagName();
                    String exportedAttr = getAndroidAttr(el, "exported");
                    boolean explicitExport = "true".equalsIgnoreCase(exportedAttr);
                    boolean explicitNotExport = "false".equalsIgnoreCase(exportedAttr);
                    boolean hasIntentFilter = !parseIntentFilters(el, null).isEmpty();
                    // Implicit export: no explicit attribute, has intent-filter, targetSdk < 31
                    boolean implicitExport = !explicitExport && !explicitNotExport
                            && hasIntentFilter && targetSdk > 0 && targetSdk < 31;
                    if (!explicitExport && !implicitExport) continue;

                    String compName = normalizeComponentName(pkgName, getAndroidAttr(el, "name"));

                    switch (tag) {
                        case "activity":
                        case "activity-alias": {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name", compName);
                            entry.put("exported", explicitExport);
                            entry.put("implicit_export", implicitExport);
                            if (implicitExport) entry.put("implicit_export_reason",
                                "intent-filter present, targetSdk=" + targetSdk + " < 31 → implicit export");
                            List<Map<String, Object>> filters = parseIntentFilters(el, deeplinkSummary);
                            entry.put("intent_filters", filters);
                            activities.add(entry);
                            break;
                        }
                        case "service": {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name", compName);
                            entry.put("exported", explicitExport);
                            entry.put("implicit_export", implicitExport);
                            if (implicitExport) entry.put("implicit_export_reason",
                                "intent-filter present, targetSdk=" + targetSdk + " < 31 → implicit export");
                            List<Map<String, Object>> filters = parseIntentFilters(el, null);
                            entry.put("intent_filters", filters);
                            services.add(entry);
                            break;
                        }
                        case "receiver": {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name", compName);
                            entry.put("exported", explicitExport);
                            entry.put("implicit_export", implicitExport);
                            if (implicitExport) entry.put("implicit_export_reason",
                                "intent-filter present, targetSdk=" + targetSdk + " < 31 → implicit export");
                            List<String> actions = parseReceiverActions(el);
                            entry.put("actions", actions);
                            receivers.add(entry);
                            break;
                        }
                        case "provider": {
                            // Provider export semantics differ; only include explicitly exported ones
                            if (!explicitExport) continue;
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name", compName);
                            entry.put("exported", true);
                            entry.put("implicit_export", false);
                            entry.put("authority", emptyToNull(getAndroidAttr(el, "authorities")));
                            String grantUri = getAndroidAttr(el, "grantUriPermissions");
                            entry.put("grant_uri_permissions", "true".equalsIgnoreCase(grantUri));
                            providers.add(entry);
                            break;
                        }
                        default:
                            break;
                    }
                }
            }

            int totalExported = activities.size() + services.size() + receivers.size() + providers.size();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("activities", activities);
            result.put("services", services);
            result.put("receivers", receivers);
            result.put("providers", providers);
            result.put("custom_permissions", customPermissions);
            result.put("dangerous_permissions_used", dangerousPermissionsUsed);
            result.put("deeplink_summary", new ArrayList<>(deeplinkSummary));
            result.put("total_exported", totalExported);
            result.put("detected_target_sdk", targetSdk > 0 ? targetSdk : "unknown");
            result.put("note", targetSdk > 0 && targetSdk < 31
                ? "Includes explicit exported=true and implicit exports (targetSdk=" + targetSdk + "<31 + intent-filter); implicit_export=true marks implicit ones"
                : "Includes explicit android:exported='true'; implicit exports (targetSdk<31+intent-filter) not applicable for targetSdk=" + targetSdk);

            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to analyze attack surface: " + e.getMessage()));
        }
    }

    private List<Map<String, Object>> parseIntentFilters(Element componentEl, Set<String> deeplinkCollector) {
        List<Map<String, Object>> filters = new ArrayList<>();
        NodeList children = componentEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            if (!"intent-filter".equals(el.getTagName())) continue;

            String action = null;
            String category = null;
            String dataScheme = null;
            String dataHost = null;
            String dataPath = null;

            NodeList filterChildren = el.getChildNodes();
            for (int j = 0; j < filterChildren.getLength(); j++) {
                Node fn = filterChildren.item(j);
                if (!(fn instanceof Element)) continue;
                Element fe = (Element) fn;
                switch (fe.getTagName()) {
                    case "action":
                        if (action == null) action = getAndroidAttr(fe, "name");
                        break;
                    case "category":
                        if (category == null) category = getAndroidAttr(fe, "name");
                        break;
                    case "data":
                        if (dataScheme == null) dataScheme = getAndroidAttr(fe, "scheme");
                        if (dataHost == null) dataHost = getAndroidAttr(fe, "host");
                        if (dataPath == null) {
                            dataPath = getAndroidAttr(fe, "path");
                            if (dataPath == null) dataPath = getAndroidAttr(fe, "pathPattern");
                            if (dataPath == null) dataPath = getAndroidAttr(fe, "pathPrefix");
                        }
                        break;
                    default:
                        break;
                }
            }

            Map<String, Object> filterEntry = new LinkedHashMap<>();
            filterEntry.put("action", action);
            filterEntry.put("category", category);
            filterEntry.put("data_scheme", dataScheme);

            String deeplink = null;
            if (dataScheme != null) {
                StringBuilder sb = new StringBuilder(dataScheme).append("://");
                if (dataHost != null) sb.append(dataHost);
                if (dataPath != null) sb.append(dataPath);
                deeplink = sb.toString();
                if (deeplinkCollector != null) deeplinkCollector.add(deeplink);
            }
            filterEntry.put("deeplink", deeplink);
            filters.add(filterEntry);
        }
        return filters;
    }

    private List<String> parseReceiverActions(Element componentEl) {
        List<String> actions = new ArrayList<>();
        NodeList children = componentEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            if (!"intent-filter".equals(el.getTagName())) continue;
            NodeList filterChildren = el.getChildNodes();
            for (int j = 0; j < filterChildren.getLength(); j++) {
                Node fn = filterChildren.item(j);
                if (!(fn instanceof Element)) continue;
                Element fe = (Element) fn;
                if ("action".equals(fe.getTagName())) {
                    String actionName = getAndroidAttr(fe, "name");
                    if (actionName != null) actions.add(actionName);
                }
            }
        }
        return actions;
    }

    private String resolveProtectionLevel(String raw) {
        if (raw == null) return "normal";
        try {
            int val = Integer.decode(raw) & 0xF;
            switch (val) {
                case 0: return "normal";
                case 1: return "dangerous";
                case 2: return "signature";
                case 3: return "signatureOrSystem";
                default: return raw;
            }
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    // -------------------------------------------------------------------------
    // GET /search-string-literals
    // -------------------------------------------------------------------------

    /**
     * GET /search-string-literals
     *
     * Scans already-cached decompiled class code for string literals matching
     * the given pattern. Does NOT trigger new decompilation.
     *
     * Query params:
     *   pattern   (required) — substring or regex to match
     *   regex     (optional, default false) — treat pattern as regex
     *   min_length (optional, default 8)
     *   class     (optional) — filter by class name prefix/contains
     *   limit     (optional, default 200)
     */
    public void handleSearchStringLiterals(Context ctx) {
        String pattern = ctx.queryParam("pattern");
        if (pattern == null || pattern.isEmpty()) {
            logger.warn("Missing required parameter 'pattern'");
            ctx.status(400).json(Map.of("error", "Missing required parameter 'pattern'"));
            return;
        }

        boolean useRegex = "true".equalsIgnoreCase(ctx.queryParam("regex"));
        int minLength;
        try {
            String ml = ctx.queryParam("min_length");
            minLength = (ml != null && !ml.isEmpty()) ? Integer.parseInt(ml) : 8;
        } catch (NumberFormatException e) {
            minLength = 8;
        }

        int limit;
        try {
            String lm = ctx.queryParam("limit");
            limit = (lm != null && !lm.isEmpty()) ? Integer.parseInt(lm) : 200;
        } catch (NumberFormatException e) {
            limit = 200;
        }

        String classFilter = ctx.queryParam("class");
        boolean forceDecompile = "true".equalsIgnoreCase(ctx.queryParam("force_decompile"));

        // force_decompile must be used together with class_filter to prevent a full forced decompile
        if (forceDecompile && (classFilter == null || classFilter.trim().isEmpty())) {
            ctx.status(400).json(Map.of("error", "force_decompile requires class_filter to limit scope"));
            return;
        }

        try {
            if (wrapper == null) {
                logger.warn("JADX wrapper not initialized");
                ctx.status(503).json(Map.of("error", "JADX wrapper not initialized"));
                return;
            }

            Pattern literalPattern = Pattern.compile("\"([^\"\n]{" + minLength + ",})\"");

            Pattern userRegex = null;
            String userSubstring = null;
            if (useRegex) {
                try {
                    userRegex = Pattern.compile(pattern);
                } catch (Exception e) {
                    logger.warn("Invalid regex pattern: {}", e.getMessage());
                    ctx.status(400).json(Map.of("error", "Invalid regex pattern: " + e.getMessage()));
                    return;
                }
            } else {
                userSubstring = pattern.toLowerCase(Locale.ROOT);
            }

            List<JavaClass> allClasses = wrapper.getClassesWithInners();
            int totalClasses = allClasses.size();
            boolean hasClassFilter = classFilter != null && !classFilter.isEmpty();
            int candidateClasses = 0;
            for (JavaClass cls : allClasses) {
                if (!hasClassFilter || cls.getFullName().contains(classFilter)) {
                    candidateClasses++;
                }
            }
            int scannedCount = 0;
            int cachedCount = 0;
            int unreadableCount = 0;
            int examinedCount = 0;
            boolean scanLimited = false;
            boolean timedOut = false;
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(LITERAL_SCAN_TIMEOUT_SECONDS);

            List<Map<String, Object>> results = new ArrayList<>();
            boolean truncated = false;

            for (JavaClass cls : allClasses) {
                if (results.size() >= limit) {
                    truncated = true;
                    break;
                }
                if (System.nanoTime() >= deadlineNanos) {
                    timedOut = true;
                    break;
                }

                String fullName = cls.getFullName();
                if (hasClassFilter) {
                    if (!fullName.contains(classFilter)) continue;
                }

                if (examinedCount >= LITERAL_SCAN_MAX_CLASSES) {
                    scanLimited = true;
                    break;
                }
                examinedCount++;
                try {
                    if (cls.getClassNode() != null && cls.getClassNode().getState().isProcessComplete()) {
                        cachedCount++;
                    }
                } catch (Exception ignored) {
                }

                // A literal search is a bulk operation. force_decompile remains accepted for
                // compatibility, but never permits per-class live JADX work on this path.
                String code = readBulkLiteralSource(cls, forceDecompile);
                if (code == null) {
                    unreadableCount++;
                    continue;
                }

                scannedCount++;

                if (code == null || code.isEmpty()) continue;

                Matcher litMatcher = literalPattern.matcher(code);
                String[] lines = null;

                while (litMatcher.find()) {
                    if (results.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    String literal = litMatcher.group(1);
                    boolean matches;
                    if (useRegex) {
                        matches = userRegex.matcher(literal).find();
                    } else {
                        matches = literal.toLowerCase(Locale.ROOT).contains(userSubstring);
                    }
                    if (!matches) continue;

                    if (lines == null) lines = code.split("\n", -1);
                    int lineNum = findLineNumber(code, litMatcher.start());

                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("class_name", fullName);
                    entry.put("literal", literal);
                    entry.put("line_number", lineNum);
                    results.add(entry);
                }
            }

            Map<String, Object> metadata = literalScanMetadata(
                totalClasses, candidateClasses, scannedCount, cachedCount, unreadableCount,
                scanLimited, timedOut, truncated, hasClassFilter);
            boolean partialResults = (Boolean) metadata.get("partial_results");

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("results", results);
            response.put("total", results.size());
            response.put("truncated", truncated);
            response.put("limit", limit);
            response.put("scanned_classes", scannedCount);
            response.put("total_classes", totalClasses);
            response.putAll(metadata);
            response.put("force_decompile_ignored", forceDecompile);
            response.put("source_unreadable_classes", unreadableCount);
            response.put("scan_examined_classes", examinedCount);
            response.put("partial_results", partialResults);
            response.put("timed_out", timedOut);
            response.put("scan_limited", scanLimited);
            if (partialResults) {
                response.put("hint", "Bulk literal search reads only cached or persisted source; "
                    + "run start_warmup to materialise skipped classes.");
            }
            ctx.json(response);

        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to search string literals: " + e.getMessage()));
        }
    }

    /**
     * Resolves already materialised source for a bulk literal scan. The force flag is deliberately
     * ignored: calling {@link JavaClass#getCode()} here would make a request unbounded.
     */
    static String readBulkLiteralSource(JavaClass cls, boolean forceDecompile) {
        String code = ClassCacheManager.getCachedCodeDirect(cls);
        if (code != null) return code;
        try {
            CodeStore codeStore = WarmupManager.codeStore();
            if (codeStore == null) return null;
            String rawName = cls.getRawName();
            return rawName == null || rawName.isEmpty() ? null : codeStore.get(rawName);
        } catch (Exception ignored) {
            return null;
        }
    }

    static Map<String, Object> literalScanMetadata(
        int totalClasses, int candidateClasses, int scannedCount, int cachedCount,
        int unreadableCount, boolean scanLimited, boolean timedOut, boolean truncated,
        boolean hasClassFilter
    ) {
        int denominator = hasClassFilter ? candidateClasses : totalClasses;
        int cachedPct = denominator > 0 ? cachedCount * 100 / denominator : 0;
        int coveragePct = denominator > 0 ? scannedCount * 100 / denominator : 0;
        boolean partialResults = unreadableCount > 0 || scanLimited || timedOut || truncated;
        String candidateLabel = hasClassFilter ? " candidate" : "";
        String coverageNote = partialResults
            ? "PARTIAL SCAN: only " + scannedCount + "/" + denominator + candidateLabel
                + " classes scanned from already materialised source. "
                + "Empty result does NOT mean the string is absent. "
                + "Run warmup to materialise more source."
            : "FULL SCAN: all " + denominator + candidateLabel + " classes scanned.";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("candidate_classes", candidateClasses);
        metadata.put("coverage_pct", coveragePct);
        metadata.put("coverage_note", coverageNote);
        metadata.put("cached_percentage_at_scan", cachedPct);
        metadata.put("partial_results", partialResults);
        return metadata;
    }

    private int findLineNumber(String code, int charPos) {
        int line = 1;
        for (int i = 0; i < charPos && i < code.length(); i++) {
            if (code.charAt(i) == '\n') line++;
        }
        return line;
    }

    // -------------------------------------------------------------------------
    // GET /export-callgraph
    // -------------------------------------------------------------------------

    /**
     * GET /export-callgraph
     *
     * BFS call-graph export starting from a specified method.
     *
     * Query params:
     *   class   (required)
     *   method  (required)
     *   depth   (optional, default 3, max 6)
     *   format  (optional, "json" or "dot", default "json")
     */
    public void handleExportCallgraph(Context ctx) {
        String className = ctx.queryParam("class");
        String methodName = ctx.queryParam("method");

        if (className == null || className.isEmpty()) {
            logger.warn("Missing required parameter 'class'");
            ctx.status(400).json(Map.of("error", "Missing required parameter 'class'"));
            return;
        }
        if (methodName == null || methodName.isEmpty()) {
            logger.warn("Missing required parameter 'method'");
            ctx.status(400).json(Map.of("error", "Missing required parameter 'method'"));
            return;
        }

        int maxDepth;
        try {
            String dp = ctx.queryParam("depth");
            maxDepth = (dp != null && !dp.isEmpty()) ? Integer.parseInt(dp) : 3;
        } catch (NumberFormatException e) {
            maxDepth = 3;
        }
        if (maxDepth < 1) maxDepth = 1;
        if (maxDepth > 6) maxDepth = 6;

        String format = ctx.queryParam("format");
        if (format == null || format.isEmpty()) format = "json";
        format = format.toLowerCase(Locale.ROOT);

        try {
            if (wrapper == null) {
                logger.warn("JADX wrapper not initialized");
                ctx.status(503).json(Map.of("error", "JADX wrapper not initialized"));
                return;
            }

            if (ClassCacheManager.getStatus() == ClassCacheManager.CacheStatus.NOT_INITIALIZED) {
                ClassCacheManager.initCache(wrapper);
            }

            Map<String, JavaClass> classMap = ClassCacheManager.getCache();
            JavaClass cls = ClassCacheManager.findClass(classMap, className);
            if (cls == null) {
                logger.warn("Class not found: {}", className);
                ctx.status(404).json(Map.of("error", "Class not found: " + className));
                return;
            }

            JavaMethod rootMethod = null;
            String strippedName = methodName.contains("(") ? methodName.substring(0, methodName.indexOf('(')) : methodName;
            for (JavaMethod m : cls.getMethods()) {
                if (JadxApiAdapter.matchesMethodName(m, strippedName)) {
                    rootMethod = m;
                    break;
                }
            }
            if (rootMethod == null) {
                logger.warn("Method '{}' not found in class {}", methodName, className);
                ctx.status(404).json(Map.of("error",
                    "Method '" + methodName + "' not found in class " + className));
                return;
            }

            if (!JadxSearchLock.tryAcquire(30)) {
                ctx.status(503).json(Map.of(
                    "error", "Decompilation operation in progress, retry later",
                    "retry_after", JadxSearchLock.RETRY_AFTER_SECONDS
                ));
                return;
            }

            Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
            List<Map<String, String>> edges = new ArrayList<>();
            boolean truncated = false;

            final String rootId = buildMethodId(cls.getFullName(), rootMethod);

            try {
                Map<String, Object> rootNode = buildNodeEntry(cls.getFullName(), rootMethod);
                nodes.put(rootId, rootNode);

                Queue<Object[]> queue = new ArrayDeque<>();
                queue.add(new Object[]{rootId, rootMethod, 0});
                Set<String> visited = new LinkedHashSet<>();
                visited.add(rootId);

                final int MAX_NODES = 500;

                while (!queue.isEmpty()) {
                    Object[] entry = queue.poll();
                    String fromId = (String) entry[0];
                    JavaMethod fromMethod = (JavaMethod) entry[1];
                    int depth = (int) entry[2];

                    if (depth >= maxDepth) continue;

                    List<Map<String, Object>> callees = collectCalleesForMethod(fromMethod);
                    for (Map<String, Object> callee : callees) {
                        String toClass = (String) callee.get("class_name");
                        String toMethod = (String) callee.get("method_name");
                        String toId = toClass + "#" + toMethod;

                        Map<String, String> edge = new LinkedHashMap<>();
                        edge.put("from", fromId);
                        edge.put("to", toId);
                        edges.add(edge);

                        if (!visited.contains(toId)) {
                            visited.add(toId);
                            Map<String, Object> nodeEntry = new LinkedHashMap<>();
                            nodeEntry.put("id", toId);
                            nodeEntry.put("class", toClass);
                            nodeEntry.put("method", toMethod);
                            nodeEntry.put("signature", callee.getOrDefault("short_id", ""));
                            nodes.put(toId, nodeEntry);

                            if (nodes.size() >= MAX_NODES) {
                                truncated = true;
                                break;
                            }

                            JavaClass toJavaCls = ClassCacheManager.findClass(classMap, toClass);
                            if (toJavaCls != null && depth + 1 < maxDepth) {
                                JavaMethod toJavaMethod = null;
                                String calleeMethodSimple = toMethod.contains("(")
                                    ? toMethod.substring(0, toMethod.indexOf('(')) : toMethod;
                                for (JavaMethod m : toJavaCls.getMethods()) {
                                    if (JadxApiAdapter.matchesMethodName(m, calleeMethodSimple)) {
                                        toJavaMethod = m;
                                        break;
                                    }
                                }
                                if (toJavaMethod != null) {
                                    queue.add(new Object[]{toId, toJavaMethod, depth + 1});
                                }
                            }
                        }
                    }

                    if (truncated) break;
                }
            } finally {
                JadxSearchLock.release();
            }

            String finalFormat = format;
            if ("dot".equals(finalFormat)) {
                String dot = buildDotGraph(rootId, nodes, edges);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("format", "dot");
                response.put("dot", dot);
                ctx.json(response);
            } else {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("format", "json");
                response.put("root", rootId);
                response.put("depth", maxDepth);
                response.put("nodes", new ArrayList<>(nodes.values()));
                response.put("edges", edges);
                response.put("truncated", truncated);
                ctx.json(response);
            }

        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to export call graph: " + e.getMessage()));
        }
    }

    private String buildMethodId(String clsName, JavaMethod method) {
        String name = method.getName();
        JadxApiAdapter.MethodInfoSnapshot info = JadxApiAdapter.getMethodInfo(method);
        String shortId = info != null ? info.getShortId() : null;
        return clsName + "#" + (shortId != null ? shortId : name + "()");
    }

    private Map<String, Object> buildNodeEntry(String clsName, JavaMethod method) {
        String name = method.getName();
        JadxApiAdapter.MethodInfoSnapshot info = JadxApiAdapter.getMethodInfo(method);
        String shortId = info != null ? info.getShortId() : null;
        String id = clsName + "#" + (shortId != null ? shortId : name + "()");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("class", clsName);
        node.put("method", name);
        node.put("signature", shortId != null ? shortId : "");
        return node;
    }

    /**
     * Collect callee method info maps from a JavaMethod using instruction walking.
     */
    private List<Map<String, Object>> collectCalleesForMethod(JavaMethod method) {
        List<Map<String, Object>> callees = new ArrayList<>();
        MethodNode methodNode = JadxApiAdapter.getInternalMethodNode(method);
        if (methodNode == null) return callees;

        InsnNode[] instructions = methodNode.getInstructions();
        if (instructions == null) return callees;

        Set<String> seen = new HashSet<>();
        for (InsnNode insn : instructions) {
            collectInvokeCalleesFromInsn(methodNode, insn, callees, seen);
        }
        return callees;
    }

    private void collectInvokeCalleesFromInsn(
            MethodNode callerNode,
            InsnNode insn,
            List<Map<String, Object>> out,
            Set<String> seen) {
        if (insn == null) return;
        if (insn instanceof BaseInvokeNode) {
            jadx.core.dex.info.MethodInfo calledMethodInfo = ((BaseInvokeNode) insn).getCallMth();
            if (calledMethodInfo != null) {
                String calleeClass = calledMethodInfo.getDeclClass().getFullName();
                String calleeMethod = calledMethodInfo.getName();
                String key = calleeClass + "#" + calledMethodInfo.getShortId();
                if (seen.add(key)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("class_name", calleeClass);
                    entry.put("method_name", calleeMethod);
                    entry.put("short_id", calledMethodInfo.getShortId());
                    out.add(entry);
                }
            }
        }
        for (InsnArg arg : insn.getArguments()) {
            if (arg.isInsnWrap()) {
                collectInvokeCalleesFromInsn(callerNode, ((InsnWrapArg) arg).getWrapInsn(), out, seen);
            }
        }
    }

    private String buildDotGraph(String rootId,
                                  Map<String, Map<String, Object>> nodes,
                                  List<Map<String, String>> edges) {
        StringBuilder sb = new StringBuilder("digraph callgraph {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  node [shape=box];\n");
        for (Map.Entry<String, Map<String, Object>> e : nodes.entrySet()) {
            String id = e.getKey();
            String label = id.equals(rootId) ? id + " [ROOT]" : id;
            sb.append("  \"").append(escapeForDot(id)).append("\" [label=\"")
              .append(escapeForDot(label)).append("\"];\n");
        }
        for (Map<String, String> edge : edges) {
            sb.append("  \"").append(escapeForDot(edge.get("from")))
              .append("\" -> \"").append(escapeForDot(edge.get("to")))
              .append("\";\n");
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeForDot(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // -------------------------------------------------------------------------
    // XML helpers
    // -------------------------------------------------------------------------

    private Document parseXml(String xmlContent) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            try (StringReader reader = new StringReader(xmlContent)) {
                return builder.parse(new InputSource(reader));
            }
        } catch (Exception e) {
            logger.debug("parseXml failed: {}", e.getMessage());
            return null;
        }
    }

    private Element getFirstElement(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return null;
        Node n = nodes.item(0);
        return n instanceof Element ? (Element) n : null;
    }

    private String getAndroidAttr(Element el, String localName) {
        if (el == null) return null;
        String val = emptyToNull(el.getAttributeNS(ANDROID_NS, localName));
        if (val != null) return val;
        return emptyToNull(el.getAttribute("android:" + localName));
    }

    private String normalizeComponentName(String pkgName, String componentName) {
        String val = emptyToNull(componentName);
        if (val == null) return null;
        if (val.startsWith(".")) return pkgName == null ? val : pkgName + val;
        if (val.contains(".")) return val;
        return pkgName == null ? val : pkgName + "." + val;
    }

    private String emptyToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
