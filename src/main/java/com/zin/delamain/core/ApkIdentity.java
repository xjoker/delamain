package com.zin.delamain.core;

import com.zin.delamain.index.WarmupManager;
import com.zin.delamain.utils.ManifestInfoService;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for "which APK/JAR is currently loaded" across every endpoint that
 * exposes it (/apk-info, /decompile-status, /file-info, search responses). Before this existed,
 * /apk-info and /file-info disagreed on field names and content (no file_name/apk_package/version
 * on /apk-info), which made a gateway split-brain about whether a file was loaded at all — see
 * the RCA this class fixes.
 *
 * <p>All fields are null-safe: manifest parsing failures degrade to null rather than throwing,
 * and {@code input_hash} is legitimately null until {@link WarmupManager} has computed it.</p>
 */
public final class ApkIdentity {

    private ApkIdentity() {
    }

    /**
     * Builds the identity map for the given wrapper's current state.
     *
     * @return {@code {"loaded": false}} when nothing is loaded, otherwise the full identity map.
     */
    public static Map<String, Object> build(HeadlessJadxWrapper wrapper) {
        Map<String, Object> identity = new HashMap<>();
        if (wrapper == null || !wrapper.isLoaded()) {
            identity.put("loaded", false);
            return identity;
        }

        identity.put("loaded", true);
        identity.put("file_name", firstFileName(wrapper));
        identity.put("class_count", wrapper.getTotalClassCount());
        identity.put("input_hash", WarmupManager.getCurrentInputHash());

        String apkPackage = null;
        String versionName = null;
        Integer versionCode = null;
        try {
            ManifestInfoService manifestInfoService = ManifestInfoService.getInstance();
            apkPackage = manifestInfoService.getPackageName(wrapper.getJadx());
            versionName = manifestInfoService.getVersionName(wrapper.getJadx());
            String versionCodeStr = manifestInfoService.getVersionCode(wrapper.getJadx());
            if (versionCodeStr != null) {
                try {
                    versionCode = Integer.parseInt(versionCodeStr);
                } catch (NumberFormatException ignored) {
                    // Leave versionCode null rather than surface a non-numeric string under an
                    // int-typed field.
                }
            }
        } catch (Exception ignored) {
            // Manifest parsing is best-effort; a failure here must not break identity reporting.
        }

        identity.put("apk_package", apkPackage);
        identity.put("version_name", versionName);
        identity.put("version_code", versionCode);

        return identity;
    }

    private static String firstFileName(HeadlessJadxWrapper wrapper) {
        List<File> inputFiles = wrapper.getInputFiles();
        if (inputFiles == null || inputFiles.isEmpty()) {
            return null;
        }
        return inputFiles.get(0).getName();
    }
}
