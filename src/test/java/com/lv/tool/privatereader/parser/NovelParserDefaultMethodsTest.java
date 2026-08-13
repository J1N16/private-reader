package com.lv.tool.privatereader.parser;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import com.lv.tool.privatereader.repository.ReactiveChapterCacheRepository;
import com.lv.tool.privatereader.repository.RepositoryModule;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * NovelParser 接口 default 方法单元测试
 * 覆盖：getChapterList 缓存优先/解析刷新/失败降级，
 * getChapterContent 缓存链路/网络回写/失败回退
 */
class NovelParserDefaultMethodsTest {

    private static final List<Chapter> CACHED = List.of(
            new Chapter("第一章", "https://example.com/1"),
            new Chapter("第二章", "https://example.com/2")
    );
    private static final List<Chapter> FRESH = List.of(
            new Chapter("第一章", "https://example.com/1"),
            new Chapter("第二章", "https://example.com/2"),
            new Chapter("第三章", "https://example.com/3")
    );

    // --- getChapterList ---

    @Test
    void getChapterListReturnsCacheWhenAvailable() {
        Book book = new Book("b1", "书名", "作者", "https://example.com/book");
        book.setCachedChapters(CACHED);
        // CALLS_REAL_METHODS：接口 default 方法执行真实逻辑，抽象方法由 stub 提供
        NovelParser parser = mock(NovelParser.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        // 缓存非空时不应解析
        when(parser.parseChapterList()).thenReturn(FRESH);

        List<Chapter> result = parser.getChapterList(book);

        assertSame(CACHED, result);
        verify(parser, never()).parseChapterList();
    }

    @Test
    void getChapterListParsesAndCachesWhenCacheEmpty() {
        Book book = new Book("b1", "书名", "作者", "https://example.com/book");
        // CALLS_REAL_METHODS：接口 default 方法执行真实逻辑，抽象方法由 stub 提供
        NovelParser parser = mock(NovelParser.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        when(parser.parseChapterList()).thenReturn(FRESH);

        List<Chapter> result = parser.getChapterList(book);

        assertEquals(3, result.size());
        assertEquals(FRESH, result);
        assertEquals(3, book.getTotalChapters(), "解析结果应写回书籍缓存");
        assertEquals(FRESH, book.getCachedChapters());
    }

    @Test
    void getChapterListFallsBackToEmptyCacheWhenParseFails() {
        Book book = new Book("b1", "书名", "作者", "https://example.com/book");
        // 空列表（非 null）→ 缓存不可用，走到解析；解析失败后回退返回缓存引用
        book.setCachedChapters(List.of());
        // CALLS_REAL_METHODS：接口 default 方法执行真实逻辑，抽象方法由 stub 提供
        NovelParser parser = mock(NovelParser.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        when(parser.parseChapterList()).thenThrow(new RuntimeException("网络错误"));

        List<Chapter> result = parser.getChapterList(book);

        assertTrue(result.isEmpty(), "解析失败时应回退到空缓存而非抛出");
    }

    @Test
    void getChapterListThrowsWhenParseFailsAndNoCache() {
        Book book = new Book("b1", "书名", "作者", "https://example.com/book");
        // CALLS_REAL_METHODS：接口 default 方法执行真实逻辑，抽象方法由 stub 提供
        NovelParser parser = mock(NovelParser.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        when(parser.parseChapterList()).thenThrow(new RuntimeException("网络错误"));

        assertThrows(RuntimeException.class, () -> parser.getChapterList(book));
    }

    // --- getChapterContent ---

    @Test
    void getChapterContentReturnsCacheHit() {
        ReactiveChapterCacheRepository cache = mock(ReactiveChapterCacheRepository.class);
        when(cache.getCachedContent("b1", "ch1")).thenReturn("缓存内容");
        try (MockedStatic<RepositoryModule> repo = mockStatic(RepositoryModule.class)) {
            RepositoryModule module = mock(RepositoryModule.class);
            repo.when(RepositoryModule::getInstance).thenReturn(module);
            when(module.getChapterCacheRepository()).thenReturn(cache);

            // CALLS_REAL_METHODS：接口 default 方法执行真实逻辑，抽象方法由 stub 提供
        NovelParser parser = mock(NovelParser.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
            Book book = new Book("b1", "书名", "作者", "https://example.com/book");
            String result = parser.getChapterContent("ch1", book);

            assertEquals("缓存内容", result);
            verify(parser, never()).parseChapterContent("ch1");
        }
    }

    @Test
    void getChapterContentFetchesAndWritesBackOnCacheMiss() {
        ReactiveChapterCacheRepository cache = mock(ReactiveChapterCacheRepository.class);
        when(cache.getCachedContent("b1", "ch1")).thenReturn(null);
        try (MockedStatic<RepositoryModule> repo = mockStatic(RepositoryModule.class)) {
            RepositoryModule module = mock(RepositoryModule.class);
            repo.when(RepositoryModule::getInstance).thenReturn(module);
            when(module.getChapterCacheRepository()).thenReturn(cache);

            // CALLS_REAL_METHODS：接口 default 方法执行真实逻辑，抽象方法由 stub 提供
        NovelParser parser = mock(NovelParser.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
            when(parser.parseChapterContent("ch1")).thenReturn("新内容");
            Book book = new Book("b1", "书名", "作者", "https://example.com/book");

            String result = parser.getChapterContent("ch1", book);

            assertEquals("新内容", result);
            verify(cache).cacheContent("b1", "ch1", "新内容");
        }
    }

    @Test
    void getChapterContentFallsBackToStaleCacheOnNetworkFailure() {
        ReactiveChapterCacheRepository cache = mock(ReactiveChapterCacheRepository.class);
        when(cache.getCachedContent("b1", "ch1")).thenReturn(null);
        when(cache.getFallbackCachedContent("b1", "ch1")).thenReturn("过期内容");
        try (MockedStatic<RepositoryModule> repo = mockStatic(RepositoryModule.class)) {
            RepositoryModule module = mock(RepositoryModule.class);
            repo.when(RepositoryModule::getInstance).thenReturn(module);
            when(module.getChapterCacheRepository()).thenReturn(cache);

            // CALLS_REAL_METHODS：接口 default 方法执行真实逻辑，抽象方法由 stub 提供
        NovelParser parser = mock(NovelParser.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
            when(parser.parseChapterContent("ch1")).thenThrow(new RuntimeException("网络错误"));
            Book book = new Book("b1", "书名", "作者", "https://example.com/book");

            String result = parser.getChapterContent("ch1", book);

            assertEquals("过期内容", result);
        }
    }

    @Test
    void getChapterContentReturnsErrorMessageOnTotalFailure() {
        ReactiveChapterCacheRepository cache = mock(ReactiveChapterCacheRepository.class);
        when(cache.getCachedContent("b1", "ch1")).thenReturn(null);
        when(cache.getFallbackCachedContent("b1", "ch1")).thenReturn(null);
        try (MockedStatic<RepositoryModule> repo = mockStatic(RepositoryModule.class)) {
            RepositoryModule module = mock(RepositoryModule.class);
            repo.when(RepositoryModule::getInstance).thenReturn(module);
            when(module.getChapterCacheRepository()).thenReturn(cache);

            // CALLS_REAL_METHODS：接口 default 方法执行真实逻辑，抽象方法由 stub 提供
        NovelParser parser = mock(NovelParser.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
            when(parser.parseChapterContent("ch1")).thenThrow(new RuntimeException("boom"));
            Book book = new Book("b1", "书名", "作者", "https://example.com/book");

            String result = parser.getChapterContent("ch1", book);

            assertEquals("章节内容暂时无法访问：boom", result);
        }
    }

    @Test
    void getChapterContentParsesDirectlyWhenNoCacheRepository() {
        try (MockedStatic<RepositoryModule> repo = mockStatic(RepositoryModule.class)) {
            RepositoryModule module = mock(RepositoryModule.class);
            repo.when(RepositoryModule::getInstance).thenReturn(module);
            when(module.getChapterCacheRepository()).thenReturn(null); // 缓存仓库不可用

            // CALLS_REAL_METHODS：接口 default 方法执行真实逻辑，抽象方法由 stub 提供
        NovelParser parser = mock(NovelParser.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
            when(parser.parseChapterContent("ch1")).thenReturn("直连内容");
            Book book = new Book("b1", "书名", "作者", "https://example.com/book");

            String result = parser.getChapterContent("ch1", book);

            assertEquals("直连内容", result);
        }
    }
}
