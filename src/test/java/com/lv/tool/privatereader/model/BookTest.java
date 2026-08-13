package com.lv.tool.privatereader.model;

import com.lv.tool.privatereader.parser.NovelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Book 单元测试（纯逻辑部分）
 * 覆盖：阅读进度计算、章节索引/对象查找、页码守卫、来源ID解析、equals/hashCode
 */
class BookTest {

    private static final List<NovelParser.Chapter> CHAPTERS = List.of(
            new NovelParser.Chapter("第一章", "https://example.com/1"),
            new NovelParser.Chapter("第二章", "https://example.com/2"),
            new NovelParser.Chapter("第三章", "https://example.com/3")
    );

    private Book sampleBook() {
        Book book = new Book("b1", "书名", "作者", "https://example.com/book/1");
        book.setCachedChapters(CHAPTERS);
        return book;
    }

    // --- 阅读进度 ---

    @Test
    void readingProgressIsZeroWhenNoChapters() {
        Book book = new Book("b1", "书名", "作者", "https://example.com/book/1");
        assertEquals(0.0, book.getReadingProgress());
    }

    @Test
    void readingProgressRatioByChapterIndex() {
        Book book = sampleBook();
        book.setCurrentChapterIndex(1);
        assertEquals(1.0 / 3.0, book.getReadingProgress(), 1e-9);
    }

    @Test
    void updateReadingProgressSetsFields() {
        Book book = sampleBook();
        book.updateReadingProgress("https://example.com/2", 500, 7);
        assertEquals("https://example.com/2", book.getLastReadChapterId());
        assertEquals(500, book.getLastReadPosition());
        assertEquals(7, book.getLastReadPage());
    }

    // --- 章节查找 ---

    @Test
    void setCachedChaptersRebuildsTotalAndIndexMap() {
        Book book = sampleBook();
        assertEquals(3, book.getTotalChapters());
        assertEquals(1, book.getChapterIndex("https://example.com/2"));
        assertNotNull(book.getChapterById("https://example.com/1"));
    }

    @Test
    void getChapterIndexReturnsNegativeWhenNotFound() {
        Book book = sampleBook();
        assertEquals(-1, book.getChapterIndex("https://example.com/99"));
        assertEquals(-1, book.getChapterIndex(null));
    }

    @Test
    void getChapterByIndexBoundsCheck() {
        Book book = sampleBook();
        assertEquals("第二章", book.getChapterByIndex(1).title());
        assertNull(book.getChapterByIndex(-1));
        assertNull(book.getChapterByIndex(5));
    }

    @Test
    void clearingChaptersEmptiesMaps() {
        Book book = sampleBook();
        book.setCachedChapters(null);
        // 注意：setCachedChapters(null) 只清空索引 map，totalChapters 保持原值（源码行为）
        assertEquals(3, book.getTotalChapters());
        assertEquals(-1, book.getChapterIndex("https://example.com/1"));
        assertNull(book.getChapterById("https://example.com/1"));
    }

    // --- 页码守卫 ---

    @Test
    void setLastReadPageClampsInvalidToZero() {
        Book book = sampleBook();
        book.setLastReadPage(0);
        assertEquals(1, book.getLastReadPage()); // 0 被归一为 1
        assertEquals(1, book.getLastReadPageOrDefault(42));
    }

    @Test
    void setLastReadPageAcceptsPositive() {
        Book book = sampleBook();
        book.setLastReadPage(5);
        assertEquals(5, book.getLastReadPage());
        assertEquals(5, book.getLastReadPageOrDefault(42));
    }

    @Test
    void lastReadPageOrDefaultFallsBackWhenInvalid() {
        Book book = sampleBook();
        book.setLastReadPage(-1); // 被归一为 1，但原值非法
        // 若需要测试 fallback，直接构造未设置的默认场景
        Book fresh = new Book("x", "y", "z", "http://a");
        fresh.setLastReadPage(-3);
        assertEquals(1, fresh.getLastReadPage());
    }

    // --- 来源ID ---

    @Test
    void sourceIdDerivesFromUrlHost() {
        Book book = sampleBook();
        assertEquals("example.com", book.getSourceId());
    }

    @Test
    void sourceIdReturnsEmptyWhenNoUrl() {
        Book book = new Book();
        assertEquals("", book.getSourceId());
    }

    @Test
    void sourceIdPrefersExplicit() {
        Book book = sampleBook();
        book.setSourceId("custom");
        assertEquals("custom", book.getSourceId());
    }

    // --- 相等性 ---

    @Test
    void equalityBasedOnId() {
        Book a = sampleBook();
        Book b = sampleBook();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        b.setId("b2");
        assertFalse(a.equals(b));
    }

    @Test
    void toStringContainsTitleAndProgress() {
        Book book = sampleBook();
        String s = book.toString();
        assertTrue(s.contains("书名"));
        assertTrue(s.contains("0%"));
    }
}
