package com.lv.tool.privatereader.service.impl.notification;

import com.lv.tool.privatereader.model.Book;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 通知栏阅读视图状态(不可变快照)。
 * <p>
 * 将原本多个松散 volatile 字段(currentBook / currentChapterId / currentChapterTitle /
 * currentPages / currentPageIndex)封装为单个不可变对象,消除"跨字段不一致"隐患:
 * 任何更新都通过 with* 拷贝构造生成新快照,读取方通过一次引用读取获得一致的整组状态,
 * 不再可能出现"书籍已更新但章节/页码仍是旧值"的混合视图。
 */
public final class ReaderViewState {
    private static final ReaderViewState EMPTY =
            new ReaderViewState(null, null, null, List.of(), 0);

    private final Book book;
    private final String chapterId;
    private final String chapterTitle;
    private final List<String> pages;
    private final int pageIndex;

    private ReaderViewState(@Nullable Book book,
                            @Nullable String chapterId,
                            @Nullable String chapterTitle,
                            @NotNull List<String> pages,
                            int pageIndex) {
        this.book = book;
        this.chapterId = chapterId;
        this.chapterTitle = chapterTitle;
        this.pages = pages;
        this.pageIndex = pageIndex;
    }

    /** 空状态:未开始阅读,无分页 */
    @NotNull
    public static ReaderViewState empty() {
        return EMPTY;
    }

    @Nullable
    public Book getBook() {
        return book;
    }

    @Nullable
    public String getChapterId() {
        return chapterId;
    }

    @Nullable
    public String getChapterTitle() {
        return chapterTitle;
    }

    /** 分页结果(不可变语义,调用方不得修改) */
    @NotNull
    public List<String> getPages() {
        return pages;
    }

    /** 当前页索引(0 基) */
    public int getPageIndex() {
        return pageIndex;
    }

    public int getPageCount() {
        return pages.size();
    }

    /** 是否处于有效阅读会话(书籍 + 章节ID + 章节标题均非空) */
    public boolean isReadingActive() {
        return book != null && chapterId != null && chapterTitle != null;
    }

    /** 仅更新书籍(保留章节与页码) */
    @NotNull
    public ReaderViewState withBook(@NotNull Book newBook) {
        return new ReaderViewState(newBook, chapterId, chapterTitle, pages, pageIndex);
    }

    /** 同时更新书籍、章节ID、章节标题(保留分页与页码) */
    @NotNull
    public ReaderViewState withChapter(@NotNull Book newBook,
                                       @NotNull String newChapterId,
                                       @NotNull String newChapterTitle) {
        return new ReaderViewState(newBook, newChapterId, newChapterTitle, pages, pageIndex);
    }

    /**
     * 更新分页结果。
     *
     * @param newPages       新的分页结果(不可变语义,调用方不得修改)
     * @param resetPageIndex 是否将页码重置为 0(分页内容真正变化时重置)
     */
    @NotNull
    public ReaderViewState withPages(@NotNull List<String> newPages, boolean resetPageIndex) {
        return new ReaderViewState(book, chapterId, chapterTitle, newPages,
                resetPageIndex ? 0 : pageIndex);
    }

    /** 仅更新页码索引(0 基) */
    @NotNull
    public ReaderViewState withPageIndex(int newPageIndex) {
        return new ReaderViewState(book, chapterId, chapterTitle, pages, newPageIndex);
    }

    @Override
    public String toString() {
        return "ReaderViewState{book=" + (book != null ? book.getTitle() : "null")
                + ", chapterId=" + chapterId
                + ", pageIndex=" + pageIndex + "/" + pages.size() + "}";
    }
}