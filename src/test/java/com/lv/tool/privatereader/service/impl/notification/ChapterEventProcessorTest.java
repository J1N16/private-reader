package com.lv.tool.privatereader.service.impl.notification;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.lv.tool.privatereader.events.ChapterChangeEventSource;
import com.lv.tool.privatereader.events.ChapterChangeManager;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import com.lv.tool.privatereader.service.ChapterService;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * ChapterEventProcessor 单元测试(V16 从 NotificationServiceImpl 拆分出的章节变更事件处理)。
 * <p>
 * 覆盖:
 * - 事件源门控:非 READER_PANEL 事件直接忽略
 * - 模式门控:非通知栏模式直接忽略
 * - 正常链路:拉取内容/标题 → 分页 → 定位首页 → 展示通知 → 保存进度
 * - 页码恢复:仓库存在匹配章节的进度时定位到该页码
 * <p>
 * 隔离方式:
 * - {@link Host} 用可断言的内存实现(真实 ReaderViewState + 分页),验证处理器对宿主的调用
 * - mockStatic(ApplicationManager):ReaderModeSettings 查询 + invokeLater 同步执行
 * - mockStatic(ProjectManager)/mockStatic(ModalityState):项目与模态获取
 * - RxJavaPlugins.setIoSchedulerHandler(trampoline):让 subscribeOn/observeOn(io) 同步执行,
 *   避免 mockStatic 的线程本地性陷阱(见 docs/solutions/README 踩坑 #5)
 */
class ChapterEventProcessorTest {

    private ChapterService chapterService;
    private NotificationReaderSettings notificationSettings;
    private ChapterChangeManager chapterChangeManager;
    private ReadingProgressRepository readingProgressRepository;
    private ReaderModeSettings readerModeSettings;
    private TestHost host;
    private ChapterEventProcessor processor;

    private MockedStatic<ApplicationManager> appManagerMock;
    private MockedStatic<ProjectManager> projectManagerMock;
    private MockedStatic<ModalityState> modalityStateMock;

    private Project project;
    private Book book;

    @BeforeEach
    void setUp() {
        chapterService = mock(ChapterService.class);
        notificationSettings = mock(NotificationReaderSettings.class);
        chapterChangeManager = mock(ChapterChangeManager.class);
        readingProgressRepository = mock(ReadingProgressRepository.class);
        readerModeSettings = mock(ReaderModeSettings.class);
        project = mock(Project.class);
        book = new Book("b1", "测试书", "作者", "https://x.com/b1");
        host = new TestHost();

        when(notificationSettings.getPageSize()).thenReturn(70);
        when(notificationSettings.isShowReadingProgress()).thenReturn(false);
        when(chapterChangeManager.getLastEventSource()).thenReturn(ChapterChangeEventSource.READER_PANEL);
        when(readingProgressRepository.getProgress(anyString())).thenReturn(Optional.empty());
        when(chapterService.getChapterContent(any(Book.class), anyString())).thenReturn(Single.just("第一章内容。".repeat(80)));
        when(chapterService.getChapterTitle(anyString(), anyString())).thenReturn(Single.just("第一章 起始"));

        // ApplicationManager:ReaderModeSettings 查询 + invokeLater 同步执行
        appManagerMock = mockStatic(ApplicationManager.class);
        Application app = mock(Application.class);
        when(app.getService(ReaderModeSettings.class)).thenReturn(readerModeSettings);
        Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(app).invokeLater(any(Runnable.class), any(ModalityState.class));
        appManagerMock.when(ApplicationManager::getApplication).thenReturn(app);

        // ProjectManager:返回单个打开的项目
        projectManagerMock = mockStatic(ProjectManager.class);
        ProjectManager pm = mock(ProjectManager.class);
        when(ProjectManager.getInstance()).thenReturn(pm);
        when(pm.getOpenProjects()).thenReturn(new Project[]{project});

        modalityStateMock = mockStatic(ModalityState.class);
        when(ModalityState.defaultModalityState()).thenReturn(mock(ModalityState.class));

        // 让 io 调度同步执行,保证断言确定性
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());

        processor = new ChapterEventProcessor(host, chapterService, notificationSettings,
                chapterChangeManager, readingProgressRepository);
    }

    @AfterEach
    void tearDown() {
        RxJavaPlugins.reset();
        modalityStateMock.close();
        projectManagerMock.close();
        appManagerMock.close();
    }

    @Test
    void ignoresEventWhenSourceIsNotReaderPanel() {
        when(chapterChangeManager.getLastEventSource()).thenReturn(ChapterChangeEventSource.NOTIFICATION_SERVICE);
        when(readerModeSettings.isNotificationMode()).thenReturn(true);

        processor.handleChapterChangedEvent(book, new Chapter("第一章", "c1"));

        assertEquals(0, host.saveCount, "非阅读器面板事件不应处理");
        assertNull(host.shownTitle);
    }

    @Test
    void ignoresEventWhenNotInNotificationMode() {
        when(readerModeSettings.isNotificationMode()).thenReturn(false);

        processor.handleChapterChangedEvent(book, new Chapter("第一章", "c1"));

        assertEquals(0, host.saveCount, "阅读器模式下不应同步通知栏");
        assertNull(host.shownTitle);
    }

    @Test
    void syncsChapterIntoNotificationOnReaderPanelChange() {
        when(readerModeSettings.isNotificationMode()).thenReturn(true);

        processor.handleChapterChangedEvent(book, new Chapter("第一章 起始", "c1"));

        assertEquals(book, host.getViewState().getBook());
        assertEquals("c1", host.getViewState().getChapterId());
        assertEquals("第一章 起始", host.getViewState().getChapterTitle());
        assertEquals("第一章 起始", host.shownTitle, "应以解析出的章节标题展示");
        assertEquals(project, host.shownProject);
        assertEquals(1, host.saveCount, "同步后应保存一次进度");
        assertTrue(host.getViewState().getPageCount() > 0);
    }

    @Test
    void restoresSavedPageForMatchingChapter() {
        when(readerModeSettings.isNotificationMode()).thenReturn(true);
        com.lv.tool.privatereader.model.BookProgressData progress =
                new com.lv.tool.privatereader.model.BookProgressData(
                        "b1", "测试书", "c1", "第一章 起始", 0, 3, false, "2026-01-01 00:00:00.000");
        when(readingProgressRepository.getProgress(anyString())).thenReturn(Optional.of(progress));

        processor.handleChapterChangedEvent(book, new Chapter("第一章 起始", "c1"));

        assertEquals(2, host.getViewState().getPageIndex(), "应恢复到保存的页码(3 → 索引 2)");
    }

    /** 可断言的内存 Host 实现:复用真实 ReaderViewState + 分页逻辑 */
    private static final class TestHost implements ChapterEventProcessor.Host {
        private final AtomicReference<ReaderViewState> ref = new AtomicReference<>(ReaderViewState.empty());
        private final ChapterPaginationCache cache = new ChapterPaginationCache();
        Project shownProject;
        String shownTitle;
        String shownContent;
        String errorTitle;
        int saveCount;

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
            List<String> pages = cache.paginate(content, 70);
            ref.updateAndGet(s -> s.withPages(pages, true));
        }

        @Override
        public void showCurrentPageInternal(Project project, String title, String content) {
            this.shownProject = project;
            this.shownTitle = title;
            this.shownContent = content;
        }

        @Override
        public void showError(String title, String message) {
            this.errorTitle = title;
        }

        @Override
        public void saveNotificationModeProgress() {
            this.saveCount++;
        }
    }
}