package com.lv.tool.privatereader.service.impl.notification;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.lv.tool.privatereader.async.ReactiveSchedulers;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.service.ChapterService;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChapterNavigator 单元测试(V17 从 NotificationServiceImpl 拆分出的章节导航流水线)。
 * <p>
 * 覆盖:
 * - 缓存数据源导航:上一章/下一章定位正确、发布章节变更事件
 * - 首页往前翻:导航到上一章最后一页
 * - 边界保护:第一章再往前 / 最后一章再往后,弹信息而非崩溃
 * - 空章节列表短路
 * - 非缓存数据源:异步委托 {@code BookService.getChaptersSync}
 * <p>
 * 隔离方式:
 * - {@link Host} 用可断言内存实现(真实 ReaderViewState + 分页),验证导航器对宿主的调用
 * - RxJavaPlugins.setIoSchedulerHandler(trampoline):订阅同步执行,断言确定性
 */
class ChapterNavigatorTest {

    private BookService bookService;
    private NotificationReaderSettings settings;
    private ReactiveSchedulers schedulers;
    private TestHost host;
    private ChapterNavigator navigator;

    private Project project;
    private Book book;
    private NovelParser parser;
    private MockedStatic<ApplicationManager> appManagerMock;

    @BeforeEach
    void setUp() {
        bookService = mock(BookService.class);
        settings = mock(NotificationReaderSettings.class);
        when(settings.isShowReadingProgress()).thenReturn(false);
        schedulers = mock(ReactiveSchedulers.class);
        // runOnUI 同步执行
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(schedulers).runOnUI(any());
        project = mock(Project.class);
        book = new Book("b1", "测试书", "作者", "https://x.com/b1");
        book.setCachedChapters(List.of(
                new Chapter("第一章", "c1"),
                new Chapter("第二章", "c2"),
                new Chapter("第三章", "c3")));
        // stub 解析器:缓存数据源下内容从 parser 获取,避免真实网络请求
        parser = mock(NovelParser.class);
        when(parser.getChapterContent(anyString(), any(Book.class))).thenReturn("章节正文。".repeat(200));
        book.setParser(parser);
        host = new TestHost();

        // ProgressSaveHelper 通过 ApplicationManager 获取阅读进度仓库
        appManagerMock = mockStatic(ApplicationManager.class);
        Application app = mock(Application.class);
        when(app.getService(ReadingProgressRepository.class)).thenReturn(mock(ReadingProgressRepository.class));
        appManagerMock.when(ApplicationManager::getApplication).thenReturn(app);

        RxJavaPlugins.setIoSchedulerHandler(s -> Schedulers.trampoline());
        navigator = new ChapterNavigator(host, bookService, settings, schedulers);
    }

    @AfterEach
    void tearDown() {
        RxJavaPlugins.reset();
        appManagerMock.close();
    }

    @Test
    void navigateChapterReadsCachedChaptersAndAdvancesToNext() {
        startReadingAt("c1");

        navigator.navigateChapter(project, 1);

        assertEquals("c2", host.getViewState().getChapterId(), "应前进到下一章");
        assertEquals("第二章", host.getViewState().getChapterTitle());
        assertEquals("测试书 - 第二章", host.shownTitle);
        assertEquals(1, host.publishCount, "应发布一次章节变更事件");
        assertEquals("c2", host.publishedChapter.url());
        assertEquals(1, host.preloadIndex, "应触发目标章预加载");
    }

    @Test
    void navigateChapterMovesToPreviousChapter() {
        startReadingAt("c3");

        navigator.navigateChapter(project, -1);

        assertEquals("c2", host.getViewState().getChapterId());
        assertEquals(1, host.publishCount);
    }

    @Test
    void navigateChapterAtFirstChapterNotifiesInsteadOfNavigating() {
        startReadingAt("c1");

        navigator.navigateChapter(project, -1);

        assertEquals("c1", host.getViewState().getChapterId(), "第一章不应再往前");
        assertEquals("导航", host.infoTitle, "应提示已到第一章");
        assertEquals(0, host.publishCount);
    }

    @Test
    void navigateChapterAtLastChapterNotifiesInsteadOfNavigating() {
        startReadingAt("c3");

        navigator.navigateChapter(project, 1);

        assertEquals("c3", host.getViewState().getChapterId(), "最后一章不应再往后");
        assertEquals("导航", host.infoTitle);
        assertEquals(0, host.publishCount);
    }

    @Test
    void navigateToLastPagePositionsAtTargetChapterLastPage() {
        startReadingAt("c2");

        navigator.navigateToLastPage(project, -1);

        assertEquals("c1", host.getViewState().getChapterId(), "应回到上一章");
        assertTrue(host.getViewState().getPageCount() > 0);
        assertEquals(host.getViewState().getPageCount() - 1, host.getViewState().getPageIndex(),
                "应定位到目标章节最后一页");
        assertEquals(1, host.publishCount);
    }

    @Test
    void navigateToLastPageWhenNothingReadingShowsInfo() {
        // host 保持空状态
        navigator.navigateToLastPage(project, -1);

        assertEquals("导航", host.infoTitle);
        assertEquals(0, host.publishCount);
    }

    @Test
    void navigateChapterFallsBackToBookServiceWhenNoCachedChapters() {
        startReadingAt("c1");
        book.setCachedChapters(List.of()); // 清空缓存
        when(bookService.getChaptersSync("b1")).thenReturn(List.of(
                new ChapterService.EnhancedChapter("第一章", "c1", "内容一"),
                new ChapterService.EnhancedChapter("第二章", "c2", "内容二")));

        navigator.navigateChapter(project, 1);

        verify(bookService).getChaptersSync("b1");
        assertEquals("c2", host.getViewState().getChapterId(), "异步数据源应导航到下一章");
        assertEquals(1, host.publishCount);
    }

    @Test
    void navigateChapterWithEmptyChapterListShowsInfo() {
        startReadingAt("c1");
        book.setCachedChapters(List.of()); // 清空缓存以走异步数据源
        when(bookService.getChaptersSync("b1")).thenReturn(List.of());

        navigator.navigateChapter(project, 1);

        assertEquals("导航", host.infoTitle, "空章节列表应提示");
        assertEquals(0, host.publishCount);
    }

    // --- 辅助 ---

    private void startReadingAt(String chapterId) {
        Chapter chapter = book.getCachedChapters().stream()
                .filter(c -> c.url().equals(chapterId)).findFirst().orElseThrow();
        List<String> pages = List.of("内容一。".repeat(60), "内容二。".repeat(60), "内容三。".repeat(60));
        host.getViewStateRef().set(ReaderViewState.empty()
                .withChapter(book, chapterId, chapter.title())
                .withPages(pages, true));
    }

    /** 可断言的内存 Host:复用真实 ReaderViewState + 分页逻辑 */
    private static final class TestHost implements ChapterNavigator.Host {
        private final AtomicReference<ReaderViewState> ref = new AtomicReference<>(ReaderViewState.empty());
        private final ChapterPaginationCache cache = new ChapterPaginationCache();
        String shownTitle;
        String infoTitle;
        String errorTitle;
        int preloadIndex = -1;
        int publishCount;
        NovelParser.Chapter publishedChapter;

        AtomicReference<ReaderViewState> getViewStateRef() {
            return ref;
        }

        @Override
        public ReaderViewState getViewState() {
            return ref.get();
        }

        @Override
        public void updateViewState(UnaryOperator<ReaderViewState> updater) {
            ref.updateAndGet(updater);
        }

        @Override
        public void setCurrentChapterContent(String content) {
            ref.updateAndGet(s -> s.withPages(cache.paginate(content, 70), true));
        }

        @Override
        public void showCurrentPageInternal(Project project, String title, String content) {
            this.shownTitle = title;
        }

        @Override
        public void showError(String title, String message) {
            this.errorTitle = title;
        }

        @Override
        public void showInfo(String title, String message) {
            this.infoTitle = title;
        }

        @Override
        public void showLoadingNotification(Project project, String message) {
            // no-op
        }

        @Override
        public void triggerChapterPreload(Book book, int chapterIndex) {
            this.preloadIndex = chapterIndex;
        }

        @Override
        public void publishChapterChanged(Book book, NovelParser.Chapter chapter) {
            this.publishCount++;
            this.publishedChapter = chapter;
        }
    }
}