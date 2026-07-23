package com.zin.delamain.utils;

import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaginationUtilsTest {

    @Test
    void limitZeroCapsLargeRemainingPageAndAdvertisesNextPage() throws Exception {
        PaginationUtils paginationUtils = new PaginationUtils();
        List<Integer> items = java.util.stream.IntStream.range(0, paginationUtils.MAX_PAGE_SIZE + 1)
            .boxed()
            .toList();

        Map<String, Object> response = paginationUtils.handlePagination(
            contextWithQuery("limit", "0"), items, "numbers", "items");

        Map<?, ?> pagination = (Map<?, ?>) response.get("pagination");
        assertEquals(paginationUtils.MAX_PAGE_SIZE, ((List<?>) response.get("items")).size());
        assertEquals(paginationUtils.MAX_PAGE_SIZE, pagination.get("limit"));
        assertEquals(true, pagination.get("has_more"));
        assertEquals(paginationUtils.MAX_PAGE_SIZE, pagination.get("next_offset"));
    }

    @Test
    void limitZeroReturnsAllRemainingItemsWhenTheyFitInOnePage() throws Exception {
        PaginationUtils paginationUtils = new PaginationUtils();
        List<Integer> items = java.util.stream.IntStream.range(0, 10).boxed().toList();

        Map<String, Object> response = paginationUtils.handlePagination(
            contextWithQuery("offset", "7", "limit", "0"), items, "numbers", "items");

        Map<?, ?> pagination = (Map<?, ?>) response.get("pagination");
        assertEquals(3, ((List<?>) response.get("items")).size());
        assertEquals(3, pagination.get("limit"));
        assertEquals(false, pagination.get("has_more"));
        assertTrue(!pagination.containsKey("next_offset"));
    }

    private static Context contextWithQuery(String... entries) {
        Map<String, String> query = new java.util.HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            query.put(entries[i], entries[i + 1]);
        }
        return (Context) Proxy.newProxyInstance(
            PaginationUtilsTest.class.getClassLoader(),
            new Class<?>[] {Context.class},
            (proxy, method, args) -> "queryParam".equals(method.getName()) ? query.get(args[0]) : null);
    }
}
