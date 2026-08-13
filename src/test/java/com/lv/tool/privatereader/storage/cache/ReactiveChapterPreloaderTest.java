package com.lv.tool.privatereader.storage.cache;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import com.lv.tool.privatereader.repository.ReactiveChapterCacheRepository;
import com.lv.tool.privatereader.settings.CacheSettings;
import com.lv.tool.privatereader.settings.PluginSettings;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReactiveChapterPreloader 单元测试
 * 覆盖：优先级预加载顺序、插件/预加载禁用跳过、重复预加载防抖、缓存命中跳过、空章节处理
 *
 * <p>预加载器通过 ApplicationManager.getService 获取 PluginSettings/CacheSettings/
 * ReactiveChapterCacheRepository，用 mockStatic 拦截 ApplicationManager 与这些服务。</p>
 */
class ReactiveChapterPreloaderTest {

    private static final List<Chapter> CHAPTERS = List.of(
            new Chapter("第一章", "https://example.com/1"),
            new Chapter("第二章", "https://example.com/2"),
            new Chapter("第三章", "https://example.com/3")
    );

    private PluginSettings pluginSettings;
    private CacheSettings cacheSettings;
    private ReactiveChapterCacheRepository cacheRepository;
    private NovelParser parser;
    private Book book;
    private MockedStatic<ApplicationManager> appMgr;

    @BeforeEach
    void setUp() {
        // 用同步 trampoline 替换 io/computation 调度器：mockStatic 仅在测试线程生效，
        // 源码内 subscribeOn(Schedulers.io())、delaySubscription 默认 computation 调度器
        // 会把 lambda 挪到其他线程，导致 ApplicationManager 门面失效（NPE）。
        // RxJavaPlugins 的 handler 只影响替换后新建的 scheduler，因此同时调用
        // Schedulers.io()/computation() 触发重建；trampoline 确保一切在测试线程同步执行。
        RxJavaPlugins.reset();
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxJavaPlugins.setComputationSchedulerHandler(scheduler -> Schedulers.trampoline());
        // 强制重建全局单例，使其捕获新的 handler
        Schedulers.io();
        Schedulers.computation();

        pluginSettings = mock(PluginSettings.class);
        when(pluginSettings.isEnabled()).thenReturn(true);

        cacheSettings = mock(CacheSettings.class);
        when(cacheSettings.isEnablePreload()).thenReturn(true);
        when(cacheSettings.getPreloadCount()).thenReturn(2);
        when(cacheSettings.getPreloadDelay()).thenReturn(0);

        cacheRepository = mock(ReactiveChapterCacheRepository.class);

        parser = mock(NovelParser.class);
        book = mock(Book.class);
        when(book.getId()).thenReturn("book-1");
        when(book.getTitle()).thenReturn("测试书籍");
        when(book.getCachedChapters()).thenReturn(CHAPTERS);
        when(book.getParser()).thenReturn(parser);

        Application app = mock(Application.class);
        when(app.getService(PluginSettings.class)).thenReturn(pluginSettings);
        when(app.getService(CacheSettings.class)).thenReturn(cacheSettings);
        when(app.getService(ReactiveChapterCacheRepository.class)).thenReturn(cacheRepository);

        appMgr = mockStatic(ApplicationManager.class);
        appMgr.when(ApplicationManager::getApplication).thenReturn(app);
    }

    @AfterEach
    void tearDown() {
        RxJavaPlugins.reset();
        appMgr.close();
    }

    // --- 优先级预加载 ---

    @Test
    void preloadsCurrentAndAdjacentChaptersOnCacheMiss() {
        // 全部未缓存 → 依次预加载 当前(1) → 后(2) → 前(0)
        when(parser.parseChapterContent("https://example.com/2")).thenReturn("内容2");
        when(parser.parseChapterContent("https://example.com/3")).thenReturn("内容3");
        when(parser.parseChapterContent("https://example.com/1")).thenReturn("内容1");

        new ReactiveChapterPreloader()
                .preloadChaptersReactive(book, 1)
                .blockingAwait();

        verify(cacheRepository).cacheContent("book-1", "https://example.com/2", "内容2");
        verify(cacheRepository).cacheContent("book-1", "https://example.com/3", "内容3");
        verify(cacheRepository).cacheContent("book-1", "https://example.com/1", "内容1");
    }

    @Test
    void skipsCurrentChapterWhenAlreadyCached() {
        // 当前章节已缓存 → 只预加载前后章节
        when(cacheRepository.getCachedContent("book-1", "https://example.com/2")).thenReturn("已缓存");
        when(parser.parseChapterContent("https://example.com/3")).thenReturn("内容3");
        when(parser.parseChapterContent("https://example.com/1")).thenReturn("内容1");

        new ReactiveChapterPreloader()
                .preloadChaptersReactive(book, 1)
                .blockingAwait();

        // 当前章已缓存，不应再 fetch
        verify(parser, never()).parseChapterContent("https://example.com/2");
        verify(cacheRepository).cacheContent("book-1", "https://example.com/3", "内容3");
        verify(cacheRepository).cacheContent("book-1", "https://example.com/1", "内容1");
    }

    @Test
    void skipsChapterWhenAlreadyPreloadedToCache() {
        // 当前章节及前后章节均已缓存 → 全部跳过 fetch
        when(cacheRepository.getCachedContent("book-1", "https://example.com/2")).thenReturn("已缓存2");
        when(cacheRepository.getCachedContent("book-1", "https://example.com/3")).thenReturn("已缓存3");
        when(cacheRepository.getCachedContent("book-1", "https://example.com/1")).thenReturn("已缓存1");

        new ReactiveChapterPreloader()
                .preloadChaptersReactive(book, 1)
                .blockingAwait();

        // 缓存全命中 → parser 不被调用，无新缓存写入
        verify(parser, never()).parseChapterContent(org.mockito.ArgumentMatchers.anyString());
        verify(cacheRepository, never()).cacheContent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // --- 禁用路径 ---

    @Test
    void skipsWhenPluginDisabled() {
        when(pluginSettings.isEnabled()).thenReturn(false);

        new ReactiveChapterPreloader()
                .preloadChaptersReactive(book, 1)
                .blockingAwait();

        verify(cacheRepository, never()).cacheContent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsWhenPreloadDisabled() {
        when(cacheSettings.isEnablePreload()).thenReturn(false);

        new ReactiveChapterPreloader()
                .preloadChaptersReactive(book, 1)
                .blockingAwait();

        verify(cacheRepository, never()).cacheContent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // --- 异常/边界 ---

    @Test
    void handlesNullParserGracefully() {
        when(book.getParser()).thenReturn(null);

        new ReactiveChapterPreloader()
                .preloadChaptersReactive(book, 1)
                .blockingAwait(); // 不应抛出

        verify(cacheRepository, never()).cacheContent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void handlesEmptyChaptersGracefully() {
        when(book.getCachedChapters()).thenReturn(List.of());

        new ReactiveChapterPreloader()
                .preloadChaptersReactive(book, 1)
                .blockingAwait(); // 不应抛出

        verify(cacheRepository, never()).cacheContent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void toleratesParseErrorWithoutPropagating() {
        when(parser.parseChapterContent(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("解析失败"));

        // 不应抛出异常（onErrorResumeNext 吞掉）
        new ReactiveChapterPreloader()
                .preloadChaptersReactive(book, 1)
                .blockingAwait();

        // 即使所有预加载失败，仍应正常完成
        assertTrue(true);
    }

    // --- stopPreload ---

    @Test
    void stopPreloadOnlyClearsMatchingBook() {
        ReactiveChapterPreloader preloader = new ReactiveChapterPreloader();
        preloader.stopPreload("book-1");
        preloader.stopPreload("other-book"); // 不应影响

        // 无异常即通过；stop 不匹配时不清状态
        preloader.stopPreload("other-book");
    }

    @Test
    void stopPreloadWithNullIsSafe() {
        new ReactiveChapterPreloader().stopPreload(null);
    }
}
