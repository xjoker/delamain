package com.zin.delamain.server.routes;

import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.WarmupManager;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ManifestInfoService;

import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.ResourceFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APK/JAR Info and warmup-status API Routes.
 *
 * Endpoints (additive — /health and basic /apk-info are already in DelamainServer):
 *   GET /warmup-status  — warmup progress snapshot
 *   POST /start-warmup  — start background cache warmup
 *   GET  /file-info     — detailed file info (richer than /apk-info)
 */
public class ApkInfoRoutes {
    private static final Logger logger = LoggerFactory.getLogger(ApkInfoRoutes.class);
    private final HeadlessJadxWrapper wrapper;
    private final ManifestInfoService manifestInfoService = ManifestInfoService.getInstance();

    public ApkInfoRoutes(HeadlessJadxWrapper wrapper) {
        this.wrapper = wrapper;
    }

    public void register(Javalin app, AuthConfig auth) {
        app.get("/warmup-status", this::handleWarmupStatus);
        app.post("/start-warmup", this::handleStartWarmup);
        app.get("/file-info", this::handleFileInfo);
    }

    // -------------------------------------------------------------------------
    // GET /warmup-status
    // -------------------------------------------------------------------------

    public void handleWarmupStatus(Context ctx) {
        ctx.json(WarmupManager.getStatus());
    }

    // -------------------------------------------------------------------------
    // POST /start-warmup
    // -------------------------------------------------------------------------

    public void handleStartWarmup(Context ctx) {
        if (wrapper == null || !wrapper.isLoaded()) {
            ctx.status(503).json(Map.of("error", "No file loaded"));
            return;
        }
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            boolean skipLibraries = true;
            if (body != null && body.containsKey("skip_libraries")) {
                Object v = body.get("skip_libraries");
                if (v instanceof Boolean) {
                    skipLibraries = (Boolean) v;
                } else if (v instanceof String) {
                    skipLibraries = Boolean.parseBoolean((String) v);
                }
            }
            Map<String, Object> result = WarmupManager.start(wrapper, skipLibraries);
            ctx.json(result);
        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to start warmup: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // GET /file-info
    // -------------------------------------------------------------------------

    /**
     * GET /file-info
     *
     * Unified file information API that works for both APK and JAR files.
     * Returns file_type, class_count, android_features, smali_available,
     * and type-specific information.
     */
    public void handleFileInfo(Context ctx) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("type", "file-info");

            if (wrapper == null || !wrapper.isLoaded()) {
                result.put("loaded", false);
                result.put("error", "No file loaded");
                ctx.json(result);
                return;
            }

            List<java.io.File> inputFiles = wrapper.getInputFiles();
            if (inputFiles.isEmpty()) {
                result.put("loaded", false);
                result.put("error", "No input files");
                ctx.json(result);
                return;
            }

            java.io.File primaryFile = inputFiles.get(0);
            String fileName = primaryFile.getName().toLowerCase();

            // Determine file type
            String fileType = detectFileType(fileName);
            boolean hasAndroidFeatures = fileType.equals("apk") || fileType.equals("aar")
                    || fileType.equals("xapk") || fileType.equals("apkm") || fileType.equals("apks")
                    || fileType.equals("aab");
            boolean smaliAvailable = hasAndroidFeatures || fileType.equals("dex");

            result.put("file_type", fileType);
            result.put("android_features", hasAndroidFeatures);
            result.put("smali_available", smaliAvailable);
            result.put("file_name", primaryFile.getName());

            // Class count
            int classCount = wrapper.getTotalClassCount();
            if (classCount == 0) {
                result.put("loaded", false);
                result.put("error", "No classes available");
                ctx.json(result);
                return;
            }
            result.put("loaded", true);
            result.put("class_count", classCount);

            // Type-specific info
            if (hasAndroidFeatures) {
                result.put("file_category", "android");
                parseAndroidInfo(result);
                result.put("recommended_tools", List.of(
                    "get_android_manifest",
                    "get_main_activity_class",
                    "get_strings",
                    "get_smali_of_class"
                ));
            } else if ("jar".equals(fileType)) {
                result.put("file_category", "java");
                parseJarManifest(primaryFile, result);
                result.put("recommended_tools", List.of(
                    "jar_get_manifest",
                    "jar_get_entry_points",
                    "jar_get_services",
                    "get_class_source"
                ));
            } else if ("dex".equals(fileType)) {
                result.put("file_category", "android");
                result.put("note", "DEX file without resources. Limited to bytecode analysis.");
                result.put("recommended_tools", List.of(
                    "get_class_source",
                    "get_smali_of_class",
                    "search_classes_by_keyword"
                ));
            } else {
                result.put("file_category", "unknown");
            }

            // Feature summary
            Map<String, Boolean> features = new HashMap<>();
            features.put("manifest", hasAndroidFeatures);
            features.put("smali", smaliAvailable);
            features.put("strings", hasAndroidFeatures);
            features.put("jar_manifest", "jar".equals(fileType));
            features.put("spi_services", "jar".equals(fileType));
            result.put("features", features);

            result.put("apk_identity", com.zin.delamain.core.ApkIdentity.build(wrapper));

            result.put("status", "success");
            ctx.json(result);

        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to get file info: " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String detectFileType(String fileName) {
        if (fileName.endsWith(".apk")) return "apk";
        if (fileName.endsWith(".aar")) return "aar";
        if (fileName.endsWith(".xapk")) return "xapk";
        if (fileName.endsWith(".apkm")) return "apkm";
        if (fileName.endsWith(".apks")) return "apks";
        if (fileName.endsWith(".aab")) return "aab";
        if (fileName.endsWith(".dex")) return "dex";
        if (fileName.endsWith(".jar")) return "jar";
        if (fileName.endsWith(".class")) return "class";
        return "unknown";
    }

    private void parseAndroidInfo(Map<String, Object> result) {
        try {
            if (wrapper == null) return;
            java.util.List<ResourceFile> resources = wrapper.getJadx().getResources();
            if (resources != null && !resources.isEmpty()) {
                // Parse package name, version info via ManifestInfoService
                String packageName = manifestInfoService.getPackageName(wrapper.getJadx());
                if (packageName != null) result.put("apk_package", packageName);

                String versionName = manifestInfoService.getVersionName(wrapper.getJadx());
                if (versionName != null) result.put("version_name", versionName);

                String versionCode = manifestInfoService.getVersionCode(wrapper.getJadx());
                if (versionCode != null) {
                    try {
                        result.put("version_code", Integer.parseInt(versionCode));
                    } catch (NumberFormatException e) {
                        result.put("version_code", versionCode);
                    }
                }

                String appLabel = manifestInfoService.getApplicationLabel(wrapper.getJadx());
                if (appLabel != null) {
                    if (appLabel.startsWith("@string/")) {
                        result.put("app_name_ref", appLabel);
                    } else {
                        result.put("app_name", appLabel);
                    }
                }
            }

            // APK-specific enrichment
            List<java.io.File> inputFiles = wrapper.getInputFiles();
            if (!inputFiles.isEmpty()) {
                enrichApkMetadata(inputFiles.get(0), result);
            }
        } catch (Exception e) {
            logger.debug("Failed to parse Android info: {}", e.getMessage());
        }
    }

    /**
     * Enriches the response with signing certificate, native libraries, and DEX count.
     */
    private void enrichApkMetadata(java.io.File apkFile, Map<String, Object> result) {
        if (apkFile == null || !apkFile.exists()) {
            result.put("signing_certificate", null);
            result.put("native_libraries", null);
            result.put("dex_count", null);
            return;
        }

        try (ZipFile zip = new ZipFile(apkFile)) {
            // Signature certificate (v1: META-INF/*.RSA / *.DSA / *.EC)
            Map<String, Object> certInfo = null;
            try {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith("META-INF/") && (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                        try (InputStream is = zip.getInputStream(entry)) {
                            CertificateFactory cf = CertificateFactory.getInstance("X.509");
                            Certificate cert = cf.generateCertificate(is);
                            if (cert instanceof X509Certificate) {
                                X509Certificate x509 = (X509Certificate) cert;
                                certInfo = new LinkedHashMap<>();
                                certInfo.put("subject", x509.getSubjectDN().getName());
                                certInfo.put("algorithm", x509.getSigAlgName());

                                byte[] encoded = x509.getEncoded();
                                MessageDigest md = MessageDigest.getInstance("SHA-256");
                                byte[] digest = md.digest(encoded);
                                StringBuilder hexSb = new StringBuilder();
                                for (byte b : digest) {
                                    hexSb.append(String.format("%02X", b));
                                }
                                certInfo.put("sha256", hexSb.toString().toLowerCase());

                                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                                certInfo.put("valid_from", sdf.format(x509.getNotBefore()));
                                certInfo.put("valid_until", sdf.format(x509.getNotAfter()));
                            }
                        } catch (Exception ex) {
                            logger.debug("Failed to parse signing cert entry {}: {}", name, ex.getMessage());
                        }
                        if (certInfo != null) break;
                    }
                }
            } catch (Exception ex) {
                logger.debug("Failed to enumerate APK entries for cert: {}", ex.getMessage());
            }
            result.put("signing_certificate", certInfo);

            // Native libraries and DEX count
            List<Map<String, Object>> nativeLibs = new ArrayList<>();
            int dexCount = 0;
            try {
                Enumeration<? extends ZipEntry> allEntries = zip.entries();
                while (allEntries.hasMoreElements()) {
                    ZipEntry entry = allEntries.nextElement();
                    String name = entry.getName();

                    if (name.endsWith(".dex")) {
                        dexCount++;
                    }

                    if (name.startsWith("lib/") && name.endsWith(".so") && !entry.isDirectory()) {
                        String[] parts = name.split("/");
                        if (parts.length >= 3) {
                            String abi = parts[1];
                            String soName = parts[parts.length - 1];
                            Map<String, Object> libEntry = new LinkedHashMap<>();
                            libEntry.put("path", name);
                            libEntry.put("abi", abi);
                            libEntry.put("name", soName);
                            nativeLibs.add(libEntry);
                        }
                    }
                }
            } catch (Exception ex) {
                logger.debug("Failed to enumerate APK entries for native libs/dex: {}", ex.getMessage());
            }
            result.put("native_libraries", nativeLibs);
            result.put("dex_count", dexCount);

        } catch (Exception e) {
            logger.debug("Failed to open APK as ZIP for metadata enrichment: {}", e.getMessage());
            result.put("signing_certificate", null);
            result.put("native_libraries", null);
            result.put("dex_count", null);
        }
    }

    private void parseJarManifest(java.io.File jarFile, Map<String, Object> result) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
            java.util.jar.Manifest manifest = jar.getManifest();
            if (manifest != null) {
                java.util.jar.Attributes attrs = manifest.getMainAttributes();

                String mainClass = attrs.getValue("Main-Class");
                if (mainClass != null) result.put("main_class", mainClass);

                String implTitle = attrs.getValue("Implementation-Title");
                if (implTitle != null) result.put("implementation_title", implTitle);

                String implVersion = attrs.getValue("Implementation-Version");
                if (implVersion != null) result.put("implementation_version", implVersion);

                String startClass = attrs.getValue("Start-Class");
                if (startClass != null) result.put("start_class", startClass);

                String springVersion = attrs.getValue("Spring-Boot-Version");
                if (springVersion != null) result.put("spring_boot_version", springVersion);

                result.put("entry_points_available", mainClass != null || startClass != null);
            }
        } catch (Exception e) {
            logger.debug("Failed to parse JAR manifest: {}", e.getMessage());
        }
    }
}
