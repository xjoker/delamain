package com.zin.delamain.server.routes;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 缺口 A：/batch-class-source 类粒度分页（{@link BatchRoutes#buildPagedBatchResponse}，package-private）。
 *
 * <p>直接对已解析好的 class 结果对象列表（模拟 handleBatchClassSource 内部构建的 `results`）做分页切片
 * 单元测试，不依赖真实 JADX/ClassCacheManager —— 待测的新契约是"字节预算下按完整类切页"这一纯函数逻辑，
 * 与解码/缓存无关（后者已由集成测试 tests/test_java_endpoints.py::TestBatch 覆盖）。</p>
 */
class BatchRoutesClassSourcePagingTest {

    private static Map<String, Object> classResult(String name, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("found", true);
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> notFoundResult(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("found", false);
        m.put("error", "Class not found");
        return m;
    }

    @Test
    void multiPageCoversAllRequestedClassesWithoutDuplicationOrGaps() {
        // 5 个类，单个约 40 字节，预算 100 字节 -> 每页 2 个类左右，需要多页取完
        List<Map<String, Object>> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(classResult("com.example.Class" + i, "content-".repeat(4) + i));
        }

        int budget = 100;
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> allPagesFlattened = new ArrayList<>();
        int offset = 0;
        int pageCount = 0;
        while (true) {
            Map<String, Object> resp = BatchRoutes.buildPagedBatchResponse(all, offset, budget);
            pageCount++;
            assertEquals("success", resp.get("status"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> page = (List<Map<String, Object>>) resp.get("classes");
            assertFalse(page.isEmpty(), "page must never be empty while items remain");

            for (Map<String, Object> cls : page) {
                String name = (String) cls.get("name");
                assertTrue(seen.add(name), "class " + name + " returned more than once across pages");
                // 每个对象必须是结构完整的 class 结果，而不是 JSON 字符串碎片
                assertNotNull(cls.get("found"));
                assertTrue(cls.containsKey("content") || cls.containsKey("error"));
            }
            allPagesFlattened.addAll(page);

            assertEquals(5, resp.get("total"));
            assertEquals(offset, resp.get("offset"));
            assertEquals(page.size(), resp.get("returned"));

            boolean hasMore = (boolean) resp.get("has_more");
            if (!hasMore) {
                assertNull(resp.get("next_offset"));
                break;
            }
            Object nextOffset = resp.get("next_offset");
            assertNotNull(nextOffset);
            offset = (int) nextOffset;
            assertTrue(pageCount < 20, "pagination did not converge - possible infinite loop");
        }

        assertTrue(pageCount > 1, "expected multiple pages given the byte budget");
        assertEquals(5, seen.size());
        assertEquals(5, allPagesFlattened.size());
    }

    @Test
    void oversizeSingleClassStillReturnsOnePageNotEmpty() {
        String hugeContent = "x".repeat(500);
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(classResult("com.example.Huge", hugeContent));
        all.add(classResult("com.example.Small", "y"));

        int budget = 50; // smaller than the first class alone
        Map<String, Object> resp = BatchRoutes.buildPagedBatchResponse(all, 0, budget);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page = (List<Map<String, Object>>) resp.get("classes");
        assertEquals(1, page.size(), "oversize single class must still be returned alone, not dropped");
        assertEquals("com.example.Huge", page.get(0).get("name"));
        assertEquals(Boolean.TRUE, page.get(0).get("oversize_single"));
        assertTrue((boolean) resp.get("has_more"));
        assertEquals(1, resp.get("next_offset"));

        // Follow-up page must pick up the remaining class and terminate (no infinite loop).
        Map<String, Object> resp2 = BatchRoutes.buildPagedBatchResponse(all, 1, budget);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page2 = (List<Map<String, Object>>) resp2.get("classes");
        assertEquals(1, page2.size());
        assertEquals("com.example.Small", page2.get(0).get("name"));
        assertFalse((boolean) resp2.get("has_more"));
    }

    @Test
    void smallBatchFitsInOnePageWithHasMoreFalse() {
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(classResult("com.example.A", "short"));
        all.add(notFoundResult("com.example.Missing"));

        Map<String, Object> resp = BatchRoutes.buildPagedBatchResponse(all, 0, 32768);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page = (List<Map<String, Object>>) resp.get("classes");
        assertEquals(2, page.size());
        assertFalse((boolean) resp.get("has_more"));
        assertNull(resp.get("next_offset"));
        // Regression: tests/test_java_endpoints.py TestBatch.test_batch_class_source expects
        // classes[0].found is True for a simple single/small request.
        assertEquals(Boolean.TRUE, page.get(0).get("found"));
        assertEquals(1, resp.get("found"));
    }

    @Test
    void offsetPastEndReturnsEmptyPageWithHasMoreFalse() {
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(classResult("com.example.A", "short"));

        Map<String, Object> resp = BatchRoutes.buildPagedBatchResponse(all, 5, 32768);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page = (List<Map<String, Object>>) resp.get("classes");
        assertTrue(page.isEmpty());
        assertFalse((boolean) resp.get("has_more"));
        assertEquals(0, resp.get("returned"));
    }
}
