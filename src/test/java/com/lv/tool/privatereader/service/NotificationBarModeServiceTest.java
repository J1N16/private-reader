package com.lv.tool.privatereader.service;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookProgressData;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationBarModeService 单元测试(补齐 V13 遗留的高收益 0% 覆盖)。
 * <p>
 * 覆盖核心流程:
 * - 激活/反激活通知栏模式(状态切换、加载通知触发、内容加载与展示协调)
 * - 翻页动作(活动会话中调 showNextPage/showPrevPage 并异步保存进度)
 * - 章节导航(调 navigateChapter 并刷新当前状态)
 * - 无活动会话时的翻页短路与「上次阅读记录」恢复
 * - 设置变更刷新(活动会话 → 刷新;空闲 → 跳过)
 * - 启动初始化(enabled 分支 / disabled 跳过)
 * <p>
 * 隔离方式(参考 NotificationServiceImplTest 既有模式):
 * - mockStatic(ApplicationManager):提供 mock Application/MessageBus 供构造器订阅通过;
 *   executeOnPooledThread / invokeLater(单参+双参)均改为同步执行,使异步加载流程可确定性验证
 * - mockStatic(ProjectManager):resolveProject 依赖
 * - mockStatic(ModalityState):invokeLater(..., ModalityState.defaultModalityState())
 * - 全部服务/仓库为 mock,章节加载链默认 stub(Single.just),避免 LOG.error 分支触发 AssertionError
 */
class NotificationBarModeServiceTest {

    private ReaderModeSettings readerModeSettings;
    private NotificationService notificationService;
    private ChapterService chapterService;
    private BookService bookService;
    private ReadingProgressRepository readingProgressRepository;
    private NotificationReaderSettings notificationReaderSettings;
    private NotificationBarModeService service;

    private MockedStatic<ApplicationManager> appManagerMock;
    private MockedStatic<ProjectManager> projectManagerMock;
    private MockedStatic<ModalityState> modalityStateMock;

    private Project project;
    private Book sampleBook;

    @BeforeEach
    void setUp() {
        readerModeSettings = mock(ReaderModeSettings.class);
        notificationService = mock(NotificationService.class);
        chapterService = mock(ChapterService.class);
        bookService = mock(BookService.class);
        readingProgressRepository = mock(ReadingProgressRepository.class);
        notificationReaderSettings = mock(NotificationReaderSettings.class);
        project = mock(Project.class);
        when(project.isDisposed()).thenReturn(false);
        sampleBook = new Book("b1", "书", "作者", "https://x.com/b1");

        // 默认 stub 章节加载链:所有触发加载的路径都能成功,避免走进 LOG.error 分支
        when(bookService.getBookById(anyString())).thenReturn(Single.just(sampleBook));
        when(chapterService.getChapterContent(any(), anyString())).thenReturn(Single.just("章节内容"));
        when(chapterService.getChapterTitle(anyString(), anyString())).thenReturn(Single.just("章节标题"));

        // 拦截 ApplicationManager:构造器需要 getMessageBus().connect(this).subscribe(...);
        // executeOnPooledThread / invokeLater 均改为同步执行
        appManagerMock = mockStatic(ApplicationManager.class);
        Application app = mock(Application.class);
        MessageBus messageBus = mock(MessageBus.class);
        MessageBusConnection connection = mock(MessageBusConnection.class);
        when(messageBus.connect(any(com.intellij.openapi.Disposable.class))).thenReturn(connection);
        when(app.getMessageBus()).thenReturn(messageBus);
        Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(app).executeOnPooledThread(any(Runnable.class));
        Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(app).invokeLater(any(Runnable.class));
        Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(app).invokeLater(any(Runnable.class), any(ModalityState.class));
        appManagerMock.when(ApplicationManager::getApplication).thenReturn(app);

        // 拦截 ProjectManager:resolveProject 依赖
        projectManagerMock = mockStatic(ProjectManager.class);
        ProjectManager pm = mock(ProjectManager.class);
        when(ProjectManager.getInstance()).thenReturn(pm);
        when(pm.getOpenProjects()).thenReturn(new Project[]{project});

        // 拦截 ModalityState:invokeLater(..., ModalityState.defaultModalityState())
        modalityStateMock = mockStatic(ModalityState.class);
        when(ModalityState.defaultModalityState()).thenReturn(mock(ModalityState.class));

        service = new NotificationBarModeService(
                readerModeSettings, notificationService, chapterService, bookService,
                readingProgressRepository, notificationReaderSettings);
        service.setProject(project);
    }

    @AfterEach
    void tearDown() {
        appManagerMock.close();
        projectManagerMock.close();
        modalityStateMock.close();
    }

    // --- 激活/反激活 ---

    @Test
    void activateSwitchesToNotificationModeAndShowsContent() {
        service.activateNotificationBarMode(project, "b1", "c1", 1);

        verify(readerModeSettings).setCurrentMode(ReaderModeSettings.Mode.NOTIFICATION_BAR);
        verify(notificationService).showLoadingNotification(any(Project.class), anyString());
        // 异步加载链同步执行后应展示章节内容
        verify(notificationService).showChapterContent(any(Project.class), eq("b1"), eq("c1"), anyInt(),
                anyString(), anyString());
    }

    @Test
    void deactivateClosesNotificationsAndClearsState() {
        service.deactivateNotificationBarMode();
        verify(notificationService).closeAllNotifications();
        verify(readerModeSettings).setCurrentMode(ReaderModeSettings.Mode.DEFAULT);
    }

    // --- 翻页 ---

    @Test
    void handleNextPageActionCallsShowNextPageAndSavesProgress() {
        when(notificationService.getCurrentBookId()).thenReturn("b1");
        when(notificationService.getCurrentChapterId()).thenReturn("c1");
        when(notificationService.getCurrentPage()).thenReturn(3);

        service.handleNextPageAction(project);

        verify(notificationService).showNextPage(project);
        // 进度异步保存:getBookById(io 线程) → updateProgress(book, chapterId, null, 0, page)
        verify(readingProgressRepository, timeout(1000).times(1))
                .updateProgress(any(Book.class), eq("c1"), isNull(), eq(0), eq(3));
    }

    @Test
    void handlePrevPageActionCallsShowPrevPageAndSavesProgress() {
        when(notificationService.getCurrentBookId()).thenReturn("b1");
        when(notificationService.getCurrentChapterId()).thenReturn("c1");
        when(notificationService.getCurrentPage()).thenReturn(2);

        service.handlePrevPageAction(project);

        verify(notificationService).showPrevPage(project);
        verify(readingProgressRepository, timeout(1000).times(1))
                .updateProgress(any(Book.class), eq("c1"), isNull(), eq(0), eq(2));
    }

    @Test
    void pageActionsAreNoOpsWhenNoActiveReadingAndNoLastProgress() {
        when(notificationService.getCurrentBookId()).thenReturn(null);
        when(notificationService.getCurrentChapterId()).thenReturn(null);
        when(readingProgressRepository.getLastReadProgressData()).thenReturn(Optional.empty());

        service.handleNextPageAction(project);

        verify(notificationService, never()).showNextPage(any(Project.class));
        verify(notificationService, never()).showPrevPage(any(Project.class));
        verify(readingProgressRepository, never())
                .updateProgress(any(Book.class), anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void pageActionRestoresFromLastReadProgressWhenNoActiveSession() {
        when(notificationService.getCurrentBookId()).thenReturn(null);
        when(notificationService.getCurrentChapterId()).thenReturn(null);
        BookProgressData lastRead = new BookProgressData(
                "b1", "书", "c1", "第一章", 0, 5, false, "2026-01-01 12:30:45.123");
        when(readingProgressRepository.getLastReadProgressData()).thenReturn(Optional.of(lastRead));

        service.handleNextPageAction(project);

        // 应通过 activateNotificationBarMode 恢复(加载链默认 stub 成功)
        verify(readerModeSettings).setCurrentMode(ReaderModeSettings.Mode.NOTIFICATION_BAR);
        verify(notificationService).showChapterContent(any(Project.class), eq("b1"), eq("c1"), anyInt(),
                anyString(), anyString());
    }

    // --- 章节导航 ---

    @Test
    void handleNextChapterActionCallsNavigateAndRefreshesState() {
        when(notificationService.getCurrentBookId()).thenReturn("b1");
        when(notificationService.getCurrentChapterId()).thenReturn("c2");
        when(notificationService.getCurrentPage()).thenReturn(1);

        service.handleNextChapterAction(project);

        verify(notificationService).navigateChapter(project, 1);
        assertEquals("c2", service.getCurrentChapterIdForTest());
        assertEquals(1, service.getCurrentPageNumberForTest());
    }

    @Test
    void handlePrevChapterActionCallsNavigateWithMinusOne() {
        when(notificationService.getCurrentBookId()).thenReturn("b1");
        when(notificationService.getCurrentChapterId()).thenReturn("c1");
        when(notificationService.getCurrentPage()).thenReturn(1);

        service.handlePrevChapterAction(project);

        verify(notificationService).navigateChapter(project, -1);
        assertEquals("c1", service.getCurrentChapterIdForTest());
    }

    // --- 设置变更刷新 ---

    @Test
    void settingsChangedRefreshesWhenNotificationModeActive() {
        // 先通过章节导航建立活动会话(currentBookId/currentChapterId 字段非空)
        when(notificationService.getCurrentBookId()).thenReturn("b1");
        when(notificationService.getCurrentChapterId()).thenReturn("c1");
        when(notificationService.getCurrentPage()).thenReturn(2);
        service.handleNextChapterAction(project);

        when(readerModeSettings.getCurrentMode()).thenReturn(ReaderModeSettings.Mode.NOTIFICATION_BAR);
        service.settingsChanged();

        // 刷新应重新加载并展示章节内容
        verify(notificationService, atLeastOnce()).showChapterContent(any(Project.class), eq("b1"), eq("c1"),
                anyInt(), anyString(), anyString());
    }

    @Test
    void settingsChangedSkipsWhenNotNotificationMode() {
        when(notificationService.getCurrentBookId()).thenReturn("b1");
        when(notificationService.getCurrentChapterId()).thenReturn("c1");
        service.handleNextChapterAction(project);

        when(readerModeSettings.getCurrentMode()).thenReturn(ReaderModeSettings.Mode.DEFAULT);
        service.settingsChanged();

        // 不触发刷新(无 showChapterContent 调用,即使活动会话存在)
        verify(notificationService, never())
                .showChapterContent(any(Project.class), anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    // --- 启动初始化 ---

    @Test
    void initializeLogsWhenEnabledWithExistingProgress() {
        when(notificationReaderSettings.isEnabled()).thenReturn(true);
        BookProgressData lastRead = new BookProgressData(
                "b1", "书", "c1", "第一章", 0, 3, false, "2026-01-01 12:30:45.123");
        when(readingProgressRepository.getLastReadProgressData()).thenReturn(Optional.of(lastRead));

        service.initializeNotificationBarModeSettings();
    }

    @Test
    void initializeSkipsWhenDisabled() {
        when(notificationReaderSettings.isEnabled()).thenReturn(false);
        service.initializeNotificationBarModeSettings();
        verify(readingProgressRepository, never()).getLastReadProgressData();
    }

    // --- 项目解析回退 ---

    @Test
    void resolveProjectFallsBackToOpenProjectsWhenCurrentDisposed() {
        Project otherProject = mock(Project.class);
        when(otherProject.isDisposed()).thenReturn(false);
        when(project.isDisposed()).thenReturn(true);
        when(ProjectManager.getInstance().getOpenProjects()).thenReturn(new Project[]{otherProject});

        when(notificationService.getCurrentBookId()).thenReturn("b1");
        when(notificationService.getCurrentChapterId()).thenReturn("c1");
        when(notificationService.getCurrentPage()).thenReturn(1);

        service.handleNextPageAction(project);

        // 翻页应作用于回退解析出的项目
        verify(notificationService).showNextPage(otherProject);
    }
}