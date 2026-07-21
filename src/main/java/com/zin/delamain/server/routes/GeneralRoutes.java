package com.zin.delamain.server.routes;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.zin.delamain.core.HeadlessJadxWrapper;
import com.zin.delamain.index.CodeContentIndex;
import com.zin.delamain.index.WarmupManager;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.server.AuthConfig;
import com.zin.delamain.utils.ClassCacheManager;
import com.zin.delamain.utils.JadxApiAdapter;

import io.javalin.Javalin;
import io.javalin.http.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * General utility routes.
 *
 * Endpoints:
 *   GET /index-stats — unified snapshot of all internal indices
 *
 * Note: /health, /apk-info, /decompile-status are registered directly in DelamainServer.
 * GUI-only endpoints (handleHealth with plugin-specific info, handleGetCurrentClass,
 * handleGetSelectedText) are intentionally omitted in the headless version.
 */
public class GeneralRoutes {
    private static final Logger logger = LoggerFactory.getLogger(GeneralRoutes.class);
    private static final Gson GSON = new Gson();
    private final HeadlessJadxWrapper wrapper;

    public GeneralRoutes(HeadlessJadxWrapper wrapper) {
        this.wrapper = wrapper;
    }

    public void register(Javalin app, AuthConfig auth) {
        app.get("/index-stats", this::handleIndexStats);
    }

    /**
     * GET /index-stats
     *
     * Returns a unified health snapshot of all internal plugin indices:
     * name indices (class/method/field), trigram content index, snapshot cache,
     * and a summary of the upstream code cache delegation.
     *
     * Useful for AI clients to decide whether trigram-based code search is warm
     * enough to use efficiently, or whether to fall back to metadata searches.
     */
    public void handleIndexStats(Context ctx) {
        try {
            Map<String, Object> response = new HashMap<>();

            // --- Name indices (ClassCacheManager) ---
            response.put("name_indices", ClassCacheManager.getNameIndexStats());

            // --- Trigram content index (CodeContentIndex) ---
            response.put("trigram_index", CodeContentIndex.getStats());

            // --- Mmap shard content index (ContentShardIndex) — dual-build coverage, not yet
            // consulted by queries (Wave B). Exposed for real-machine coverage verification. ---
            response.put("shard_index", ContentShardIndex.getStats());

            // --- Prebaked-index manifest (see docs/prebaked-index.md) — tells the AI/operator
            // whether this index volume was fully built (shard index complete + manifest matches
            // the currently loaded APK's input hash), so a copied volume can be trusted for
            // FAST_RESTORE without re-running warmup. Read-only: never written from here. ---
            response.put("index_prebaked", readPrebakedManifest());

            // --- Snapshot cache (JadxApiAdapter) ---
            response.put("snapshot_cache", JadxApiAdapter.getSnapshotCacheStats());

            // --- Code cache (ClassCacheManager facade over JADX ICodeCache) ---
            Map<String, Object> codeCacheRaw = ClassCacheManager.getCodeCacheStats();
            Map<String, Object> codeCache = new HashMap<>();
            codeCache.put("class_index_size", codeCacheRaw.getOrDefault("class_index_size", 0));
            codeCache.put("delegates_to_jadx_icodecache",
                    codeCacheRaw.getOrDefault("delegates_to_jadx_icodecache", true));
            response.put("code_cache", codeCache);

            ctx.json(response);
        } catch (Exception e) {
            logger.error("Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal Error while handling index-stats request: " + e.getMessage()));
        }
    }

    /**
     * Reads the {@code <inputHash>.manifest.json} written by
     * {@code WarmupManager#writePrebakedManifest} for the currently loaded APK, if present.
     * Returns {@code complete=false} when no warmup has run yet, the manifest file is missing
     * (e.g. shard build still in progress or an older index volume without this feature), or its
     * {@code input_hash} does not match the currently loaded APK — any of which mean the caller
     * should not assume this index volume is a trustworthy prebaked copy.
     */
    // Public (not private) so WarmupManagerColdShardBuildTest can exercise it directly without
    // standing up a Javalin HTTP context.
    public Map<String, Object> readPrebakedManifest() {
        String currentHash = WarmupManager.getCurrentInputHash();
        if (currentHash == null) {
            return Map.of("complete", false, "reason", "no warmup has run yet for this APK");
        }
        Path manifestPath = WarmupManager.getIndexDir().resolve(currentHash + ".manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            return Map.of("complete", false, "reason", "manifest not found (shard build not finished yet, or older index volume)");
        }
        try {
            String json = Files.readString(manifestPath);
            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> manifest = GSON.fromJson(json, mapType);
            if (manifest == null || !currentHash.equals(manifest.get("input_hash"))) {
                return Map.of("complete", false, "reason", "manifest input_hash does not match currently loaded APK");
            }
            Map<String, Object> result = new HashMap<>(manifest);
            result.put("complete", true);
            return result;
        } catch (JsonSyntaxException | java.io.IOException e) {
            logger.warn("Failed to read prebaked-index manifest {}: {}", manifestPath, e.getMessage());
            return Map.of("complete", false, "reason", "manifest unreadable: " + e.getMessage());
        }
    }
}
