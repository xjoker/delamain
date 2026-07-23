package com.zin.delamain.server.routes;

import com.zin.delamain.utils.PaginationUtils;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchRoutesPaginationTest {

    @Test
    void limitZeroCapsLargeRemainingWindowAndAdvertisesNextPage() throws Exception {
        PaginationUtils paginationUtils = new PaginationUtils();
        BatchRoutes routes = new BatchRoutes(null, paginationUtils, null);

        BatchRoutes.PaginationWindow window = routes.resolvePaginationWindow(
            contextWithQuery("limit", "0"), paginationUtils.MAX_PAGE_SIZE + 1);

        assertEquals(paginationUtils.MAX_PAGE_SIZE, window.getLimit());
        assertEquals(0, window.getStartIndex());
        assertEquals(paginationUtils.MAX_PAGE_SIZE, window.getEndIndex());
        assertTrue(window.hasMore());
        assertEquals(paginationUtils.MAX_PAGE_SIZE, window.getNextOffset());
    }

    @Test
    void limitZeroNearEndReturnsRemainingItemsWithoutNextPage() throws Exception {
        BatchRoutes routes = new BatchRoutes(null, new PaginationUtils(), null);

        BatchRoutes.PaginationWindow window = routes.resolvePaginationWindow(
            contextWithQuery("offset", "9998", "limit", "0"), 10_000);

        assertEquals(2, window.getLimit());
        assertEquals(9998, window.getStartIndex());
        assertEquals(10_000, window.getEndIndex());
        assertFalse(window.hasMore());
    }

    private static Context contextWithQuery(String... entries) {
        Map<String, String> query = new java.util.HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            query.put(entries[i], entries[i + 1]);
        }
        return (Context) Proxy.newProxyInstance(
            BatchRoutesPaginationTest.class.getClassLoader(),
            new Class<?>[] {Context.class},
            (proxy, method, args) -> "queryParam".equals(method.getName()) ? query.get(args[0]) : null);
    }
}
