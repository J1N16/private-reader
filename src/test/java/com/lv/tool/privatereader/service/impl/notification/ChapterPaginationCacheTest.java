package com.lv.tool.privatereader.service.impl.notification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChapterPaginationCache 单元测试。
 * 覆盖：缓存命中/未命中、pageSize 变化触发重分页、内容变化触发重分页、空内容、清空缓存。
 */
class ChapterPaginationCacheTest {

    @Test
    void firstCallPaginatesAndCaches() {
        ChapterPaginationCache cache = new ChapterPaginationCache();
        String content = "字".repeat(100);

        List<String> pages = cache.paginate(content, 30);

        assertEquals(4, pages.size());
        assertEquals(content, cache.cachedContent());
        assertEquals(30, cache.cachedPageSize());
    }

    @Test
    void sameContentReusesCachedResult() {
        ChapterPaginationCache cache = new ChapterPaginationCache();
        String content = "字".repeat(100);

        List<String> first = cache.paginate(content, 30);
        List<String> second = cache.paginate(content, 30);

        // 命中缓存：返回同一引用，避免重复分页
        assertSame(first, second, "相同内容与 pageSize 应复用缓存结果");
    }

    @Test
    void changedContentTriggersRepagination() {
        ChapterPaginationCache cache = new ChapterPaginationCache();

        List<String> first = cache.paginate("字".repeat(100), 30);
        List<String> second = cache.paginate("字".repeat(200), 30);

        assertNotSame(first, second);
        assertEquals(7, second.size(), "200 字符 / 30 每页 = 7 页");
        assertEquals("字".repeat(200), cache.cachedContent());
    }

    @Test
    void changedPageSizeTriggersRepagination() {
        ChapterPaginationCache cache = new ChapterPaginationCache();
        String content = "字".repeat(100);

        List<String> first = cache.paginate(content, 30);
        List<String> second = cache.paginate(content, 50);

        assertNotSame(first, second);
        assertEquals(2, second.size(), "100 字符 / 50 每页 = 2 页");
        assertEquals(50, cache.cachedPageSize());
    }

    @Test
    void paginateAfterChangedPageSizeReusesWhenSizeReverts() {
        ChapterPaginationCache cache = new ChapterPaginationCache();
        String content = "字".repeat(100);

        cache.paginate(content, 30);
        cache.paginate(content, 50);   // pageSize 变化 → 重分页
        List<String> again = cache.paginate(content, 50); // 回到 50 → 命中

        assertEquals(2, again.size());
        assertSame(cache.paginate(content, 50), again);
    }

    @Test
    void clearResetsCacheState() {
        ChapterPaginationCache cache = new ChapterPaginationCache();
        cache.paginate("字".repeat(100), 30);

        cache.clear();

        assertNull(cache.cachedContent());
        assertEquals(-1, cache.cachedPageSize());
        // 清空后再次分页应重新计算（新引用）
        List<String> afterClear = cache.paginate("字".repeat(100), 30);
        assertEquals(4, afterClear.size());
    }

    @Test
    void emptyContentIsCachedAsEmptyList() {
        ChapterPaginationCache cache = new ChapterPaginationCache();

        List<String> pages = cache.paginate("", 30);

        assertTrue(pages.isEmpty());
        assertEquals("", cache.cachedContent());
        assertEquals(30, cache.cachedPageSize());
        // 空内容命中缓存
        assertSame(pages, cache.paginate("", 30));
    }

    @Test
    void nullContentTreatedAsEmpty() {
        ChapterPaginationCache cache = new ChapterPaginationCache();

        List<String> pages = cache.paginate(null, 30);

        assertTrue(pages.isEmpty());
        assertEquals("", cache.cachedContent());
    }
}
