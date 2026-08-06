package com.zin.delamain.index;

import com.zin.delamain.index.shard.ContentShardBuilder;
import com.zin.delamain.index.shard.ContentShardIndex;
import com.zin.delamain.index.shard.ShardCatalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code WarmupManager.getStatus()}'s {@code code_search} capability must report "ready" once the
 * mmap shard layer is built and serving search — the AI must not be told to avoid code search when
 * the shard layer already covers it.
 */
class WarmupManagerCodeSearchReadinessTest {

    @BeforeEach
    void setUp() {
        ContentShardIndex.clear();
    }

    @AfterEach
    void tearDown() {
        ContentShardIndex.clear();
    }

    @Test
    void codeSearchReadyWhenShardLayerBuilt(@TempDir Path indexDir) throws Exception {
        String hash = "deadbeefcafebabe";
        ContentShardBuilder builder = new ContentShardBuilder(indexDir, hash, 128L * 1024 * 1024);
        builder.addClass(0, "hello world this is some searchable source code");
        builder.close();

        List<ShardCatalog.ShardEntry> written = builder.writtenShards();
        ShardCatalog.write(indexDir, hash, written);
        ContentShardIndex.loadCatalog(indexDir, hash);

        Map<String, Object> status = WarmupManager.getStatus();
        @SuppressWarnings("unchecked")
        Map<String, String> caps = (Map<String, String>) status.get("capabilities");
        assertEquals("ready", caps.get("code_search"),
                "code_search must report ready once the shard layer is built: " + status);
    }
}
