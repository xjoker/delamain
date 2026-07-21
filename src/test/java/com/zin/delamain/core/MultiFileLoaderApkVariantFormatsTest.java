package com.zin.delamain.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G1 regression: jadx-all 1.5.5 bundles input plugins for the APK-distribution container
 * formats (XApkInputPlugin, ApksCustomCodeInput, ApkmInputPlugin, AabInputPlugin — see
 * {@code jadx.plugins.input.*} inside jadx-all-1.5.5.jar), but {@link MultiFileLoader}'s own
 * extension whitelist rejected them before jadx ever got a chance to look at the bytes.
 *
 * <p>These tests build real zip-container fixtures for all four formats (.apks/.xapk/.apkm/.aab),
 * whose on-disk layout was reverse-engineered from the plugin bytecode (javap on
 * jadx-all-1.5.5.jar, no source available for these plugin modules) and drive them through the
 * real {@link MultiFileLoader} + {@link HeadlessJadxWrapper} pipeline, asserting that jadx
 * actually unpacked and decompiled classes out of them — not just that the extension was
 * accepted.</p>
 */
class MultiFileLoaderApkVariantFormatsTest {

    private static final File SAMPLE_APK =
            new File("test-harness/real/UnCrackable-Level1.apk");

    @Test
    void apksContainer_isRejectedByExtensionWhitelist_beforeFix() {
        // Documents the exact pre-fix behavior this task fixes: .apks was rejected by
        // MultiFileLoader's own gate even though FileManagementRoutes already allowed it and
        // jadx-all 1.5.5 can load it. Kept as a standing assertion of the *current* (fixed)
        // whitelist contents; the actual pre-fix red run is captured in the task report.
        assertTrue(MultiFileLoader.VALID_EXTENSIONS.contains(".apks"),
                "post-fix: .apks must be in the canonical whitelist");
        assertTrue(MultiFileLoader.VALID_EXTENSIONS.contains(".xapk"),
                "post-fix: .xapk must be in the canonical whitelist");
        assertTrue(MultiFileLoader.VALID_EXTENSIONS.contains(".apkm"),
                "post-fix: .apkm must be in the canonical whitelist");
        assertTrue(MultiFileLoader.VALID_EXTENSIONS.contains(".aab"),
                "post-fix: .aab must be in the canonical whitelist");
    }

    /**
     * bundletool {@code .apks}: {@code ApksCustomCodeInput.loadFiles} (decompiled via javap,
     * jadx-plugins-input-apks source not present in the jar) walks every zip entry whose name
     * ends in {@code .apk} (case-insensitive) and feeds the extracted files straight to
     * {@code DexInputPlugin} — no manifest/toc.pb required for that code path. A zip containing
     * just {@code base.apk} is therefore a faithful minimal fixture.
     */
    @Test
    void apksFixture_realLoad_producesDecompiledClasses(@TempDir Path tempDir) throws Exception {
        assertTrue(SAMPLE_APK.isFile(), "fixture sample APK missing: " + SAMPLE_APK.getAbsolutePath());

        File apksFile = tempDir.resolve("sample.apks").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(apksFile))) {
            zos.putNextEntry(new ZipEntry("base.apk"));
            Files.copy(SAMPLE_APK.toPath(), zos);
            zos.closeEntry();
        }

        assertRealDecompile(apksFile, tempDir.resolve("out-apks").toFile());
    }

    /**
     * APKPure {@code .xapk}: {@code XApkLoader.checkAndLoad} (decompiled via javap) requires a
     * {@code manifest.json} zip entry deserializing to {@code XApkManifest} with
     * {@code @SerializedName("xapk_version")==2} and a non-empty
     * {@code @SerializedName("split_apks")} list of {@code {file, id}} objects; every path in
     * {@code split_apks[].file} must exist as a sibling zip entry. Those entries are then handed
     * to {@code DexInputPlugin} the same way as the {@code .apks} path.
     */
    @Test
    void xapkFixture_realLoad_producesDecompiledClasses(@TempDir Path tempDir) throws Exception {
        assertTrue(SAMPLE_APK.isFile(), "fixture sample APK missing: " + SAMPLE_APK.getAbsolutePath());

        File xapkFile = tempDir.resolve("sample.xapk").toFile();
        String manifestJson = "{\"xapk_version\":2,\"split_apks\":"
                + "[{\"file\":\"base.apk\",\"id\":\"base\"}]}";
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(xapkFile))) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("base.apk"));
            Files.copy(SAMPLE_APK.toPath(), zos);
            zos.closeEntry();
        }

        assertRealDecompile(xapkFile, tempDir.resolve("out-xapk").toFile());
    }

    /**
     * APKMirror {@code .apkm}: {@code ApkmUtils.getManifest} (decompiled via javap; no source in
     * the jar) requires an {@code info.json} zip entry deserializing to {@code ApkmManifest}
     * with {@code @SerializedName("apkm_version")} present (any value other than the Kotlin
     * default-arg sentinel {@code -1} passes {@code ApkmUtils.isSupported}); every other zip
     * entry ending in {@code .apk} is then fed to {@code DexInputPlugin}, same as {@code .apks}.
     */
    @Test
    void apkmFixture_realLoad_producesDecompiledClasses(@TempDir Path tempDir) throws Exception {
        assertTrue(SAMPLE_APK.isFile(), "fixture sample APK missing: " + SAMPLE_APK.getAbsolutePath());

        File apkmFile = tempDir.resolve("sample.apkm").toFile();
        String infoJson = "{\"apkm_version\":1}";
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(apkmFile))) {
            zos.putNextEntry(new ZipEntry("info.json"));
            zos.write(infoJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("base.apk"));
            Files.copy(SAMPLE_APK.toPath(), zos);
            zos.closeEntry();
        }

        assertRealDecompile(apkmFile, tempDir.resolve("out-apkm").toFile());
    }

    /**
     * Android App Bundle {@code .aab}: unlike xapk/apks/apkm there is no dedicated
     * {@code AabCodeInput} class in the jar — {@code AabInputPlugin} only registers proto
     * resource-container factories (AndroidManifest.xml/resources.pb in protobuf form). Real dex
     * loading comes for free from {@code DexInputPlugin}'s generic zip scan (verified empirically
     * here: it does not filter by top-level file extension, just walks every zip entry looking
     * for {@code classes*.dex}), so a minimal bundle layout ({@code base/dex/classes.dex}) is
     * enough to prove classes really decompile out of a {@code .aab} container.
     */
    @Test
    void aabFixture_realLoad_producesDecompiledClasses(@TempDir Path tempDir) throws Exception {
        assertTrue(SAMPLE_APK.isFile(), "fixture sample APK missing: " + SAMPLE_APK.getAbsolutePath());

        File aabFile = tempDir.resolve("sample.aab").toFile();
        try (java.util.zip.ZipFile srcZip = new java.util.zip.ZipFile(SAMPLE_APK);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(aabFile))) {
            ZipEntry dexEntry = srcZip.getEntry("classes.dex");
            zos.putNextEntry(new ZipEntry("base/dex/classes.dex"));
            try (InputStream is = srcZip.getInputStream(dexEntry)) {
                is.transferTo(zos);
            }
            zos.closeEntry();
        }

        assertRealDecompile(aabFile, tempDir.resolve("out-aab").toFile());
    }

    /**
     * Loads {@code inputFile} through the real {@link MultiFileLoader} gate (the actual bug
     * location: {@code isValidExtension} on top of {@link HeadlessJadxWrapper#reload}) — the same
     * code path the {@code /load-file} HTTP route uses — and asserts jadx actually unpacked and
     * parsed classes out of it, proof the container was really decoded and not just that the
     * extension passed the whitelist.
     */
    private static void assertRealDecompile(File inputFile, File outputDir) throws IOException {
        // Pre-create the output dir: jadx's deobf-map save otherwise logs a (non-fatal, but
        // noisy) NoSuchFileException on the first reload of a @TempDir subdirectory that doesn't
        // exist yet.
        Files.createDirectories(outputDir.toPath());
        // A minimal placeholder wrapper; MultiFileLoader.loadFiles replaces its decompiler
        // instance entirely via HeadlessJadxWrapper.reload, so the initial input list is
        // irrelevant — only the extension gate and the reload call matter for this test.
        HeadlessJadxWrapper wrapper = new HeadlessJadxWrapper(List.of(), outputDir, 1);
        MultiFileLoader loader = new MultiFileLoader(wrapper, outputDir, 1);

        MultiFileLoader.LoadResult result = loader.loadFiles(List.of(inputFile), "replace");
        try {
            assertTrue(result.success, "load-file rejected " + inputFile.getName()
                    + ": " + result.message + " errors=" + result.errors);

            List<jadx.api.JavaClass> classes = wrapper.getClasses();
            assertFalse(classes.isEmpty(),
                    "expected jadx to have parsed at least one class out of " + inputFile.getName());

            // Force one real decompile to prove this isn't just a class-name listing: pick the
            // first class with a body and confirm jadx actually produced Java source for it.
            boolean sawNonEmptySource = false;
            for (jadx.api.JavaClass jc : classes) {
                String code = jc.getCode();
                if (code != null && !code.isBlank()) {
                    sawNonEmptySource = true;
                    break;
                }
            }
            assertTrue(sawNonEmptySource,
                    "expected at least one class to decompile to non-empty source from " + inputFile.getName());
        } finally {
            wrapper.getJadx().close();
        }
    }
}
