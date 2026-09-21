package com.lv.tool.privatereader.ui.dialog;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChapterListDialogSupport 纯逻辑单元测试。
 * 覆盖:章节查找 / 选中索引决策 / 信息文案构建 / 加载动效。
 */
class ChapterListDialogSupportTest {

    private static final NovelParser.Chapter C1 = new NovelParser.Chapter("第一章", "url-1");
    private static final NovelParser.Chapter C2 = new NovelParser.Chapter("第二章", "url-2");
    private static final NovelParser.Chapter C3 = new NovelParser.Chapter("第三章", "url-3");

    // --- findChapterIndex ---

    @Test
    void findChapterIndexReturnsMatchingUrl() {
        assertEquals(1, ChapterListDialogSupport.findChapterIndex(List.of(C1, C2, C3), "url-2"));
        assertEquals(0, ChapterListDialogSupport.findChapterIndex(List.of(C1, C2, C3), "url-1"));
    }

    @Test
    void findChapterIndexReturnsMinusOneWhenMissing() {
        assertEquals(-1, ChapterListDialogSupport.findChapterIndex(List.of(C1, C2, C3), "url-404"));
    }

    @Test
    void findChapterIndexHandlesEmptyAndNullInputs() {
        assertEquals(-1, ChapterListDialogSupport.findChapterIndex(null, "url-1"));
        assertEquals(-1, ChapterListDialogSupport.findChapterIndex(List.of(), "url-1"));
        assertEquals(-1, ChapterListDialogSupport.findChapterIndex(List.of(C1), null));
        assertEquals(-1, ChapterListDialogSupport.findChapterIndex(List.of(C1), ""));
    }

    // --- resolveSelectIndex ---

    @Test
    void resolveSelectIndexPrefersLastReadChapter() {
        assertEquals(2, ChapterListDialogSupport.resolveSelectIndex(List.of(C1, C2, C3), "url-3"));
    }

    @Test
    void resolveSelectIndexFallsBackToFirstChapterWhenLastMissing() {
        assertEquals(0, ChapterListDialogSupport.resolveSelectIndex(List.of(C1, C2, C3), "url-404"));
        assertEquals(0, ChapterListDialogSupport.resolveSelectIndex(List.of(C1, C2, C3), null));
    }

    @Test
    void resolveSelectIndexReturnsMinusOneForEmptyList() {
        assertEquals(-1, ChapterListDialogSupport.resolveSelectIndex(null, "url-1"));
        assertEquals(-1, ChapterListDialogSupport.resolveSelectIndex(List.of(), "url-1"));
    }

    // --- buildInfoLabel ---

    @Test
    void buildInfoLabelContainsBookMetadata() {
        Book book = new Book("b1", "斗破苍穹", "天蚕土豆", "https://example.com/b1");
        book.setTotalChapters(100);
        book.setCurrentChapterIndex(25);

        String label = ChapterListDialogSupport.buildInfoLabel(book);
        assertTrue(label.contains("斗破苍穹"));
        assertTrue(label.contains("天蚕土豆"));
        assertTrue(label.contains("25/100 章"));
    }

    @Test
    void buildInfoLabelHandlesZeroTotalChapters() {
        Book book = new Book("b2", "书", "作者", "https://example.com/b2");
        String label = ChapterListDialogSupport.buildInfoLabel(book);
        // totalChapters=0 时进度为 0.0%,不应抛出 ArithmeticException
        assertTrue(label.contains("0/0 章"));
        assertTrue(label.contains("0.0%"));
    }

    // --- buildLoadingDots ---

    @Test
    void buildLoadingDotsCyclesThroughFourStates() {
        assertEquals("", ChapterListDialogSupport.buildLoadingDots(0));
        assertEquals(".", ChapterListDialogSupport.buildLoadingDots(1));
        assertEquals("..", ChapterListDialogSupport.buildLoadingDots(2));
        assertEquals("...", ChapterListDialogSupport.buildLoadingDots(3));
        assertEquals("", ChapterListDialogSupport.buildLoadingDots(4));
        assertEquals(".", ChapterListDialogSupport.buildLoadingDots(5));
    }

    @Test
    void buildLoadingDotsHandlesNegativeCount() {
        // floorMod 保证负数取模非负且正确周期:(-1) % 4 == 3 → "..."
        assertEquals("", ChapterListDialogSupport.buildLoadingDots(-4));
        assertEquals(".", ChapterListDialogSupport.buildLoadingDots(-3));
        assertEquals("..", ChapterListDialogSupport.buildLoadingDots(-2));
        assertEquals("...", ChapterListDialogSupport.buildLoadingDots(-1));
    }
}