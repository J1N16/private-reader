package com.lv.tool.privatereader.service.impl.notification;

import com.lv.tool.privatereader.model.Book;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReaderViewState 单元测试。
 * 覆盖:空状态、不可变拷贝构造(with* 方法)、页码重置语义、阅读会话有效性判断。
 */
class ReaderViewStateTest {

    private static Book createBook(String id, String title) {
        Book book = new Book();
        book.setId(id);
        book.setTitle(title);
        return book;
    }

    @Test
    void emptyState() {
        ReaderViewState state = ReaderViewState.empty();
        assertNull(state.getBook());
        assertNull(state.getChapterId());
        assertNull(state.getChapterTitle());
        assertEquals(0, state.getPageCount());
        assertEquals(0, state.getPageIndex());
        assertFalse(state.isReadingActive());
    }

    @Test
    void withChapterUpdatesChapterFields() {
        Book book = createBook("b1", "测试书");
        ReaderViewState state = ReaderViewState.empty()
                .withChapter(book, "c1", "第一章");

        assertSame(book, state.getBook());
        assertEquals("c1", state.getChapterId());
        assertEquals("第一章", state.getChapterTitle());
        assertTrue(state.isReadingActive());
        // 其他字段保留空状态
        assertEquals(0, state.getPageCount());
    }

    @Test
    void withChapterResetsPageIndexWhenChapterChanges() {
        Book book = createBook("b1", "测试书");
        ReaderViewState state = ReaderViewState.empty()
                .withChapter(book, "c1", "第一章")
                .withPages(List.of("页1", "页2", "页3"), true)
                .withPageIndex(2)
                .withChapter(book, "c2", "第二章");

        assertEquals(0, state.getPageIndex(), "切换到新章节时应重置页码");
        assertEquals("c2", state.getChapterId());
        assertEquals("第二章", state.getChapterTitle());
    }

    @Test
    void withChapterKeepsPageIndexWhenChapterUnchanged() {
        Book book = createBook("b1", "测试书");
        ReaderViewState state = ReaderViewState.empty()
                .withChapter(book, "c1", "第一章")
                .withPages(List.of("页1", "页2", "页3"), true)
                .withPageIndex(2)
                .withChapter(book, "c1", "第一章");

        assertEquals(2, state.getPageIndex(), "章节未变时应保留页码");
    }

    @Test
    void withPagesPresentsExistingPageCount() {
        Book book = createBook("b1", "测试书");
        List<String> pages = List.of("页1", "页2", "页3");
        ReaderViewState state = ReaderViewState.empty()
                .withChapter(book, "c1", "第一章")
                .withPages(pages, true);

        assertSame(pages, state.getPages());
        assertEquals(3, state.getPageCount());
        // resetPageIndex=true 时页码归零
        assertEquals(0, state.getPageIndex());
    }

    @Test
    void withPagesWithoutResetKeepsPageIndex() {
        Book book = createBook("b1", "测试书");
        ReaderViewState state = ReaderViewState.empty()
                .withChapter(book, "c1", "第一章")
                .withPages(List.of("页1", "页2"), true)
                .withPageIndex(1)
                .withPages(List.of("页1", "页2"), false);

        assertEquals(1, state.getPageIndex(), "resetPageIndex=false 时应保留页码");
    }

    @Test
    void withPageIndexUpdatesOnlyIndex() {
        Book book = createBook("b1", "测试书");
        ReaderViewState state = ReaderViewState.empty()
                .withChapter(book, "c1", "第一章")
                .withPages(List.of("页1", "页2", "页3"), true)
                .withPageIndex(2);

        assertEquals(2, state.getPageIndex());
        assertEquals(3, state.getPageCount());
        assertSame(book, state.getBook());
    }

    @Test
    void withChaptersIsImmutableNoSharedMutation() {
        Book book = createBook("b1", "测试书");
        ReaderViewState original = ReaderViewState.empty()
                .withChapter(book, "c1", "第一章")
                .withPages(List.of("页1"), true)
                .withPageIndex(0);

        // 派生新状态不影响原状态
        ReaderViewState derived = original.withPageIndex(5);
        assertEquals(0, original.getPageIndex());
        assertEquals(5, derived.getPageIndex());
    }

    @Test
    void pageIndexSanityBound() {
        Book book = createBook("b1", "测试书");
        ReaderViewState state = ReaderViewState.empty()
                .withChapter(book, "c1", "第一章")
                .withPages(List.of("页1"), true);

        // 空状态页码溢出测试
        assertEquals(0, state.getPageIndex());
        assertEquals(1, state.getPageCount());
    }
}