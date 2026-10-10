package com.lv.tool.privatereader.service.impl.notification;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.lv.tool.privatereader.async.ReactiveSchedulers;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookProgressData;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChapterContentViewer 单元测试(V20 从 NotificationServiceImpl 拆分出的章节内容展示流水线)。
 * <p>
 * 覆盖:
 * - 正常展示:书/章节写入状态、按页定位、展示通知标题、触发预加载
 * - 页码恢复:传入页码为 1 时从仓库恢复保存页码
 * - 空内容短路:不触发取书、直接错误提示
 * - 书籍不存在:错误提示
 * <p>
 * 隔离方式:
 * - 窄接口 {@link ChapterContentViewer.Host} 用可断言内存实现(真实 {@link ReaderViewState})
 * - mockStatic(ApplicationManager):invokeLater 同步执行,避免真实 EDT
 * - RxJavaPlugins.setIoSchedulerHandler(trampoline):subscribeOn(io) 同步执行
 */
class ChapterContentViewerTest {

    private BookService bookService;
    private NotificationReaderSettings settings;
    private ReactiveSchedulers schedulers;
    private ReadingProgressRepository repository;
    private TestHost host;
    private ChapterContentViewer viewer;

    private Project project;
    private Book book;
    private MockedStatic<ApplicationManager> appManagerMock;
    private MockedStatic<ModalityState> modalityStateMock;

    @BeforeEach
    void setUp() {
        bookService = mock(BookService.class);
        settings = mock(NotificationReaderSettings.class);
        when(settings.isShowReadingProgress()).thenReturn(false);
        schedulers = mock(ReactiveSchedulers.class);
        when(schedulers.io()).thenReturn(Schedulers.trampoline());
        repository = mock(ReadingProgressRepository.class);
        project = mock(Project.class);

        book = new Book("b1", "测试书", "作者", "https://x.com/b1");
        book.setCachedChapters(List.of(
                new NovelParser.Chapter("第一章", "c1"),
                new NovelParser.Chapter("第二章", "c2")));
        host = new TestHost();

        // ApplicationManager.invokeLater 同步执行
        appManagerMock = mockStatic(ApplicationManager.class);
        Application app = mock(Application.class);
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(app).invokeLater(any(Runnable.class), any(ModalityState.class));
        appManagerMock.when(ApplicationManager::getApplication).thenReturn(app);

        // ModalityState.defaultModalityState() 返回 mock,使上面的匹配生效
        modalityStateMock = mockStatic(ModalityState.class);
        when(ModalityState.defaultModalityState()).thenReturn(mock(ModalityState.class));

        RxJavaPlugins.setIoSchedulerHandler(s -> Schedulers.trampoline());
        viewer = new ChapterContentViewer(host, bookService, settings, schedulers,
                new NotificationProgressManager(host, repository));
    }

    @AfterEach
    void tearDown() {
        RxJavaPlugins.reset();
        modalityStateMock.close();
        appManagerMock.close();
    }

    @Test
    void showsChapterContentAtFirstPage() {
        when(bookService.getBookById("b1")).thenReturn(Single.just(book));

        viewer.showChapterContent(project, "b1", "c2", 1, "第二章", "章节正文。".repeat(300));

        assertEquals("b1", host.getViewState().getBook().getId());
        assertEquals("c2", host.getViewState().getChapterId());
        assertEquals(0, host.getViewState().getPageIndex(), "页码为 1 时应定位到首页");
        assertEquals("测试书 - 第二章", host.shownTitle);
        assertTrue(host.getViewState().getPageCount() > 0);
        assertEquals(1, host.preloadIndex, "应触发当前章节预加载");
        assertNotNull(host.shownContent);
    }

    @Test
    void restoresSavedPageWhenIncomingPageIsDefault() {
        when(bookService.getBookById("b1")).thenReturn(Single.just(book));
        when(repository.getProgress("b1")).thenReturn(Optional.of(
                new BookProgressData("b1", "测试书", "c2", "第二章", 0, 3, false, null)));

        viewer.showChapterContent(project, "b1", "c2", 1, "第二章", "章节正文。".repeat(600));

        assertTrue(host.getViewState().getPageCount() >= 3, "内容应至少分 3 页");
        assertEquals(2, host.getViewState().getPageIndex(), "应恢复到已保存的第 3 页(索引 2)");
        verify(repository).getProgress("b1");
    }

    @Test
    void emptyContentShowsErrorWithoutFetchingBook() {
        viewer.showChapterContent(project, "b1", "c2", 1, "第二章", "");

        assertEquals("显示章节失败", host.errorTitle);
        verify(bookService, never()).getBookById(anyString());
    }

    // --- 辅助 ---

    /** 可断言的内存 Host:复用真实 ReaderViewState + 分页逻辑 */
    private static final class TestHost implements ChapterContentViewer.Host,
            NotificationProgressManager.Host {
        private final AtomicReference<ReaderViewState> ref = new AtomicReference<>(ReaderViewState.empty());
        private final ChapterPaginationCache cache = new ChapterPaginationCache();
        String shownTitle;
        String shownContent;
        String errorTitle;
        int shownCount;
        int preloadIndex = -1;

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
            this.shownContent = content;
            this.shownCount++;
        }

        @Override
        public void showError(String title, String message) {
            this.errorTitle = title;
        }

        @Override
        public void closeCurrentNotificationInternal() {
            // no-op
        }

        @Override
        public void triggerChapterPreload(Book book, int chapterIndex) {
            this.preloadIndex = chapterIndex;
        }

        // NotificationProgressManager.Host
        @Override
        public boolean isReadingActive(boolean notifyWhenInactive) {
            return ref.get().isReadingActive();
        }
    }
}