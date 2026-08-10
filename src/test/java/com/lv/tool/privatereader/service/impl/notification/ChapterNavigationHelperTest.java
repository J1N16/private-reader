package com.lv.tool.privatereader.service.impl.notification;

import com.lv.tool.privatereader.model.Book;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChapterNavigationHelper 单元测试
 * 覆盖：章节索引查找、导航方向验证、目标索引计算
 */
class ChapterNavigationHelperTest {

    private static final List<String> CHAPTER_URLS =
            List.of("https://example.com/1", "https://example.com/2", "https://example.com/3");

    // --- findChapterIndex ---

    @Test
    void findChapterIndexReturnsNegativeForNullChapterId() {
        assertEquals(-1, ChapterNavigationHelper.findChapterIndex(null, null, CHAPTER_URLS));
    }

    @Test
    void findChapterIndexReturnsNegativeForEmptyUrls() {
        assertEquals(-1, ChapterNavigationHelper.findChapterIndex(null, "x", List.of()));
    }

    @Test
    void findChapterIndexFindsByLinearSearchWhenBookNull() {
        assertEquals(1, ChapterNavigationHelper.findChapterIndex(null, "https://example.com/2", CHAPTER_URLS));
    }

    @Test
    void findChapterIndexReturnsNegativeWhenNotFound() {
        assertEquals(-1, ChapterNavigationHelper.findChapterIndex(null, "https://example.com/99", CHAPTER_URLS));
    }

    @Test
    void findChapterIndexPrefersBookIndexMapOverLinearSearch() {
        // 构造一个章节索引Map与实际URL顺序不一致的Book，验证优先使用Map
        Book book = new Book();
        book.setCachedChapters(List.of(
                new com.lv.tool.privatereader.parser.NovelParser.Chapter("章节3", "https://example.com/3"),
                new com.lv.tool.privatereader.parser.NovelParser.Chapter("章节1", "https://example.com/1")
        ));
        // book 的索引Map按 setCachedChapters 顺序建立：example.com/3 -> 0, example.com/1 -> 1
        int index = ChapterNavigationHelper.findChapterIndex(book, "https://example.com/1", CHAPTER_URLS);
        // Map结果是1，验证返回的是Map结果
        assertTrue(index >= 0);
        assertEquals(1, index);
    }

    // --- validateNavigation ---

    @Test
    void validateNavigationReturnsNullForValidMove() {
        assertNull(ChapterNavigationHelper.validateNavigation(1, 1, CHAPTER_URLS.size()));
        assertNull(ChapterNavigationHelper.validateNavigation(1, -1, CHAPTER_URLS.size()));
    }

    @Test
    void validateNavigationRejectsFirstChapterGoingBack() {
        assertNotNull(ChapterNavigationHelper.validateNavigation(0, -1, CHAPTER_URLS.size()));
        assertEquals("已经是第一章了", ChapterNavigationHelper.validateNavigation(0, -1, CHAPTER_URLS.size()));
    }

    @Test
    void validateNavigationRejectsLastChapterGoingForward() {
        assertNotNull(ChapterNavigationHelper.validateNavigation(2, 1, CHAPTER_URLS.size()));
        assertEquals("已经是最后一章了", ChapterNavigationHelper.validateNavigation(2, 1, CHAPTER_URLS.size()));
    }

    @Test
    void validateNavigationRejectsWhenCurrentNotFound() {
        assertNotNull(ChapterNavigationHelper.validateNavigation(-1, 1, CHAPTER_URLS.size()));
        assertEquals("当前章节在列表中未找到", ChapterNavigationHelper.validateNavigation(-1, 1, CHAPTER_URLS.size()));
    }

    // --- calculateTargetIndex ---

    @Test
    void calculateTargetIndexAdvancesByDirection() {
        assertEquals(2, ChapterNavigationHelper.calculateTargetIndex(1, 1));
        assertEquals(0, ChapterNavigationHelper.calculateTargetIndex(1, -1));
        assertEquals(3, ChapterNavigationHelper.calculateTargetIndex(0, 3));
    }

    @Test
    void navigationResultRecordPreservesFields() {
        ChapterNavigationHelper.NavigationResult result =
                new ChapterNavigationHelper.NavigationResult(2, "https://example.com/3", "第三章");
        assertEquals(2, result.targetIndex());
        assertEquals("https://example.com/3", result.targetChapterId());
        assertEquals("第三章", result.targetChapterTitle());
    }
}
