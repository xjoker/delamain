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
 * A1 follow-up: with the heap trigram index OFF ({@code TRIGRAM_HEAP == false}), {@code
 * WarmupManager.getStatus()}'s {@code code_search} capability must NOT stay pinned at "warming"
 * forever just because the heap-resident trigram BitSet is (by design) never built. Once the mmap
 * shard layer is built and serving search, code_search must report "ready" — the AI must not be
 * told to avoid code search when the shard layer already covers it.
 */
class WarmupManagerCodeSearchReadinessTest {

    private boolean originalTrigramHeap;

    @BeforeEach
    void setUp() {
        originalTrigramHeap = CodeContentIndex.TRIGRAM_HEAP;
        ContentShardIndex.clear();
    }

    @AfterEach
    void tearDown() {
        ContentShardIndex.clear();
        CodeContentIndex.TRIGRAM_HEAP = originalTrigramHeap;
    }

    @Test
    void codeSearchReadyWhenShardLayerBuiltEvenWithTrigramHeapOff(@TempDir Path indexDir) throws Exception {
        CodeContentIndex.TRIGRAM_HEAP = false;

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
                "code_search must report ready from the shard layer when TRIGRAM_HEAP is off: " + status);
    }
}
