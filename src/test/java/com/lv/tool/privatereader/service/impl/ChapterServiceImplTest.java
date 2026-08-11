package com.lv.tool.privatereader.service.impl;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import com.lv.tool.privatereader.repository.BookRepository;
import com.lv.tool.privatereader.repository.ReactiveChapterCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChapterServiceImpl 单元测试
 * 覆盖：章节内容缓存链路（缓存命中/未命中回写/网络失败回退/无回退抛错）、
 * 章节列表缓存优先、缓存清理
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChapterServiceImplTest {

    @Mock
    private ReactiveChapterCacheRepository chapterCacheRepository;

    @Mock
    private BookRepository bookRepository;

    private ChapterServiceImpl service;
    private Book book;

    @BeforeEach
    void setUp() {
        service = new ChapterServiceImpl(chapterCacheRepository, bookRepository);
        book = new Book("book-1", "测试书籍", "作者", "https://example.com/book");
    }

    private Book bookWithParser(NovelParser parser) {
        book.setParser(parser);
        return book;
    }

    // --- getChapterContent：缓存命中 ---

    @Test
    void getChapterContentReturnsCachedContentWithoutHittingNetwork() {
        when(chapterCacheRepository.getCachedContent("book-1", "ch-1")).thenReturn("缓存内容");

        String result = service.getChapterContent(book, "ch-1").blockingGet();

        assertEquals("缓存内容", result);
    }

    // --- getChapterContent：缓存未命中 → 网络获取 → 回写缓存 ---

    @Test
    void getChapterContentFetchesFromNetworkAndWritesBackToCache() {
        NovelParser parser = mock(NovelParser.class);
        when(chapterCacheRepository.getCachedContent("book-1", "ch-1")).thenReturn(null);
        when(parser.parseChapterContent("ch-1")).thenReturn("网络内容");

        String result = service.getChapterContent(bookWithParser(parser), "ch-1").blockingGet();

        assertEquals("网络内容", result);
        verify(chapterCacheRepository).cacheContent("book-1", "ch-1", "网络内容");
    }

    // --- getChapterContent：网络失败 → 回退缓存 ---

    @Test
    void getChapterContentFallsBackToCachedContentWhenNetworkFails() {
        NovelParser parser = mock(NovelParser.class);
        when(chapterCacheRepository.getCachedContent("book-1", "ch-1")).thenReturn(null);
        when(parser.parseChapterContent("ch-1")).thenThrow(new RuntimeException("网络错误"));
        when(chapterCacheRepository.getFallbackCachedContent("book-1", "ch-1"))
                .thenReturn("回退内容");

        String result = service.getChapterContent(bookWithParser(parser), "ch-1").blockingGet();

        assertEquals("回退内容", result);
    }

    // --- getChapterContent：网络失败且无回退缓存 → 抛错 ---

    @Test
    void getChapterContentPropagatesErrorWhenNetworkFailsAndNoFallback() {
        NovelParser parser = mock(NovelParser.class);
        when(chapterCacheRepository.getCachedContent("book-1", "ch-1")).thenReturn(null);
        when(parser.parseChapterContent("ch-1")).thenThrow(new RuntimeException("网络错误"));
        when(chapterCacheRepository.getFallbackCachedContent("book-1", "ch-1")).thenReturn(null);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.getChapterContent(bookWithParser(parser), "ch-1").blockingGet());

        assertEquals("网络错误", error.getMessage());
    }

    // --- getChapterContent：parser 不可用 ---

    @Test
    void getChapterContentThrowsWhenParserNotAvailable() {
        when(chapterCacheRepository.getCachedContent("book-1", "ch-1")).thenReturn(null);
        // book 无 parser
        assertThrows(RuntimeException.class,
                () -> service.getChapterContent(book, "ch-1").blockingGet());
    }

    // --- getChapterContentSync：找不到书籍 ---

    @Test
    void getChapterContentSyncReturnsErrorWhenBookNotFound() {
        when(bookRepository.getBook("missing-book")).thenReturn(null);

        String result = service.getChapterContentSync("missing-book", "ch-1");

        assertEquals("错误: 未找到书籍。", result);
    }

    // --- getChapterList：缓存优先 ---

    @Test
    void getChapterListReturnsCachedChaptersImmediatelyWhenAvailable() {
        List<Chapter> cachedChapters = List.of(
                new Chapter("第一章", "https://example.com/1"),
                new Chapter("第二章", "https://example.com/2")
        );
        book.setCachedChapters(cachedChapters);
        // 后台刷新失败时静默，不影响缓存优先返回
        NovelParser parser = mock(NovelParser.class);
        when(parser.parseChapterList()).thenThrow(new RuntimeException("网络错误"));

        List<Chapter> result = service.getChapterList(bookWithParser(parser)).blockingGet();

        assertEquals(cachedChapters, result);
    }

    // --- clearBookCache / clearAllCache ---

    @Test
    void clearBookCacheInvalidatesAllCachesForBook() {
        service.clearBookCache(book).blockingAwait();

        verify(chapterCacheRepository).clearCache("book-1");
    }

    @Test
    void clearAllCacheClearsEverything() {
        service.clearAllCache().blockingAwait();

        verify(chapterCacheRepository).clearAllCache();
    }
}
