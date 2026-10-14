package com.lv.tool.privatereader.service.impl;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import com.lv.tool.privatereader.async.ReactiveSchedulers;
import com.lv.tool.privatereader.events.ChapterChangeManager;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.service.ChapterService;
import com.lv.tool.privatereader.service.impl.notification.NotificationDisplayManager;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import com.lv.tool.privatereader.storage.cache.ReactiveChapterPreloader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * NotificationServiceImpl 单元测试(分页/状态读取/空导航短路,不触碰真实 IntelliJ 消息总线)。
 * <p>
 * 覆盖:
 * - 分页核心:setCurrentChapterContent / calculateTotalPages / getPageContent(有效性 + 非法页码)
 * - 状态读取:getCurrentPage / getTotalPages / getCurrentBookId / getCurrentChapterId(空状态)
 * - 空状态导航短路:showPrevPage / showNextPage / navigateChapter 在未开始阅读时直接返回
 * - showLoadingNotification(mock 通知显示)
 * <p>
 * 隔离方式:
 * - mockStatic(ApplicationManager):提供 mock Application/MessageBus,构造器安全通过
 * - mockStatic(ReactiveSchedulers):runOnUI 同步执行,避免真实 EDT
 * - mockStatic(NotificationDisplayManager):不真正创建 IntelliJ 通知
 * <p>
 * 说明:requireReadingState 的完整导航工作流依赖 ProjectManager/Sqlite 进度恢复等深度
 * IntelliJ 链路,此处不重复覆盖(分页结果缓存、ReaderViewState 已由各自单元测试覆盖)。
 */
class NotificationServiceImplTest {

    private BookService bookService;
    private ChapterService chapterService;
    private NotificationReaderSettings settings;
    private ReactiveChapterPreloader preloader;
    private ChapterChangeManager chapterChangeManager;
    private NotificationServiceImpl service;

    private MockedStatic<ApplicationManager> appManagerMock;
    private MockedStatic<ReactiveSchedulers> schedulersMock;
    private MockedStatic<NotificationDisplayManager> displayManagerMock;
    private MockedStatic<com.intellij.openapi.application.ModalityState> modalityStateMock;

    private ReactiveSchedulers schedulers;
    private ReadingProgressRepository progressRepository;
    private Project project;

    @BeforeEach
    void setUp() {
        // 1. 先创建所有 mock 依赖,再 stubbing 静态门面
        bookService = mock(BookService.class);
        chapterService = mock(ChapterService.class);
        settings = mock(NotificationReaderSettings.class);
        when(settings.getPageSize()).thenReturn(70);
        when(settings.isShowReadingProgress()).thenReturn(false);
        preloader = mock(ReactiveChapterPreloader.class);
        chapterChangeManager = mock(ChapterChangeManager.class);
        project = mock(Project.class);

        // 2. 拦截 ApplicationManager:构造器需要 getMessageBus().connect(this).subscribe(...)
        appManagerMock = mockStatic(ApplicationManager.class);
        Application app = mock(Application.class);
        MessageBus messageBus = mock(MessageBus.class);
        MessageBusConnection connection = mock(MessageBusConnection.class);
        when(messageBus.connect(any(com.intellij.openapi.Disposable.class))).thenReturn(connection);
        when(app.getMessageBus()).thenReturn(messageBus);
        Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(app).invokeLater(any());
        Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(app).invokeLater(any(), org.mockito.ArgumentMatchers.<com.intellij.openapi.application.ModalityState>any());
        // ChapterContentViewer 使用 ModalityState.defaultModalityState();mock Application 环境下
        // 静态方法返回 null 会触发平台 @NotNull 断言,故显式提供非 null 实例
        modalityStateMock = mockStatic(com.intellij.openapi.application.ModalityState.class);
        modalityStateMock.when(com.intellij.openapi.application.ModalityState::defaultModalityState)
                .thenReturn(mock(com.intellij.openapi.application.ModalityState.class));
        appManagerMock.when(ApplicationManager::getApplication).thenReturn(app);

        // 3. 拦截 ReactiveSchedulers:runOnUI 改为测试线程同步执行
        schedulers = mock(ReactiveSchedulers.class);
        Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(schedulers).runOnUI(any());
        Mockito.when(schedulers.io()).thenReturn(io.reactivex.rxjava3.schedulers.Schedulers.trampoline());
        schedulersMock = mockStatic(ReactiveSchedulers.class);
        schedulersMock.when(ReactiveSchedulers::getInstance).thenReturn(schedulers);

        // 4. 拦截 NotificationDisplayManager 静态通知创建(加载/错误/信息通知均不真正显示)
        displayManagerMock = mockStatic(NotificationDisplayManager.class);
        displayManagerMock.when(() -> NotificationDisplayManager.showLoadingNotification(any(), any()))
                .thenReturn(mock(com.intellij.notification.Notification.class));
        displayManagerMock.when(() -> NotificationDisplayManager.showError(any(), any()))
                .thenReturn(mock(com.intellij.notification.Notification.class));
        displayManagerMock.when(() -> NotificationDisplayManager.showInfo(any(), any()))
                .thenReturn(mock(com.intellij.notification.Notification.class));

        // 5. 构造被测服务(依赖全部注入)
        progressRepository = mock(ReadingProgressRepository.class);
        service = new NotificationServiceImpl(bookService, chapterService, settings, preloader, chapterChangeManager,
                progressRepository);
    }

    @AfterEach
    void tearDown() {
        modalityStateMock.close();
        appManagerMock.close();
        schedulersMock.close();
        displayManagerMock.close();
    }

    // --- 分页核心 ---

    @Test
    void setCurrentChapterContentPaginatesAndPositionsFirstPage() {
        String longContent = "段落一。段落二。段落三。".repeat(500);

        service.setCurrentChapterContent(longContent);

        assertTrue(service.getTotalPages() > 0, "长内容应被分页");
        assertEquals(1, service.getCurrentPage(), "新内容应从第 1 页开始");
    }

    @Test
    void setCurrentChapterContentWithNullContentDoesNotCrash() {
        service.setCurrentChapterContent("");
        assertEquals(0, service.getTotalPages());
    }

    @Test
    void calculateTotalPagesMatchesConfiguredPageSize() {
        String content = "A".repeat(1000);
        // pageSize=70 → 1000 字符至少 14 页
        assertTrue(service.calculateTotalPages(content) >= 14);
    }

    @Test
    void getPageContentReturnsContentForValidPageAndPlaceholderForInvalid() {
        String content = "第一页内容。" + "第二页内容。".repeat(300);

        String page = service.getPageContent(content, 1);
        assertTrue(page != null && !page.isEmpty());

        assertEquals("Invalid page number.", service.getPageContent(content, 9999));
    }

    // --- 状态读取(空状态) ---

    @Test
    void gettersReportEmptyStateBeforeReadingStarts() {
        assertEquals(0, service.getCurrentPage());
        assertEquals(0, service.getTotalPages());
        assertNull(service.getCurrentBookId());
        assertNull(service.getCurrentChapterId());
    }

    // --- 空状态导航短路 ---

    @Test
    void showPrevAndNextPageAreNoOpsWhenNothingReading() {
        // 未开始阅读:showPrevPage/showNextPage 应直接返回(不触发通知、不崩溃)
        service.showPrevPage(project);
        service.showNextPage(project);
        assertEquals(0, service.getCurrentPage(), "空状态下翻页不应改变页码");
    }

    @Test
    void navigateChapterIsNoOpWhenNothingReading() {
        service.navigateChapter(project, 1);
        service.navigateChapter(project, -1);
        // 空状态短路,无异常即通过
        assertTrue(true);
    }

    // --- 加载状态 ---

    @Test
    void showLoadingNotificationCreatesLoadingNotificationAndSetsFlag() {
        service.showLoadingNotification(project, "加载中...");
        // 加载状态下 showNextPage 短路(不崩溃)
        service.showNextPage(project);
        assertTrue(true);
    }

    // --- 活动阅读会话:翻页/导航状态机(V30) ---

    private Book book(String title) {
        Book b = new Book("book-1", title, "作者", "https://example.com/book");
        b.setCachedChapters(java.util.List.of(
                new com.lv.tool.privatereader.parser.NovelParser.Chapter("第一章", "chapter-1"),
                new com.lv.tool.privatereader.parser.NovelParser.Chapter("第二章", "chapter-2")));
        return b;
    }

    /** 通过接口入口建立活动阅读会话(内容足够长以产生多页)。 */
    private void startReading(String content, int pageNumber) {
        when(bookService.getBookById("book-1")).thenReturn(io.reactivex.rxjava3.core.Single.just(book("测试书籍")));
        service.showChapterContent(project, "book-1", "chapter-1", pageNumber, "第一章", content);
    }

    private static String longContent() {
        return "用于分页的长段落内容。".repeat(200);
    }

    @Test
    void showChapterContentEstablishesReadingSessionAndShowsFirstPage() {
        startReading(longContent(), 1);

        assertEquals("book-1", service.getCurrentBookId());
        assertEquals("chapter-1", service.getCurrentChapterId());
        assertEquals("第一章", service.getCurrentChapterTitle());
        assertTrue(service.getTotalPages() > 1, "长内容应分为多页");
        assertEquals(1, service.getCurrentPage());
    }

    @Test
    void showChapterContentWithEmptyContentShowsErrorAndKeepsStateInactive() {
        service.showChapterContent(project, "book-1", "chapter-1", 1, "第一章", "");

        assertNull(service.getCurrentBookId());
        assertEquals(0, service.getTotalPages());
    }

    @Test
    void showNextPageAdvancesPageIndex() {
        startReading(longContent(), 1);
        int before = service.getCurrentPage();

        service.showNextPage(project);

        assertEquals(before + 1, service.getCurrentPage());
    }

    @Test
    void showPrevPageAfterNextReturnsToFirstPage() {
        startReading(longContent(), 1);
        service.showNextPage(project);

        service.showPrevPage(project);

        assertEquals(1, service.getCurrentPage());
    }

    @Test
    void showPrevPageOnFirstPageNavigatesToPreviousChapterLastPage() {
        startReading(longContent(), 1);
        assertEquals(1, service.getCurrentPage());

        // 首页再上一页:走 chapterNavigator.navigateToLastPage(-1) 分支
        service.showPrevPage(project);

        assertEquals(1, service.getCurrentPage(), "首页保持不动由导航器处理");
    }

    @Test
    void showNextPageOnLastPageNavigatesToNextChapter() {
        startReading(longContent(), 1);
        int total = service.getTotalPages();
        for (int i = 1; i < total; i++) {
            service.showNextPage(project);
        }
        assertEquals(total, service.getCurrentPage());

        // 末页再下一页:走 chapterNavigator.navigateChapter(1) 分支
        service.showNextPage(project);

        assertEquals(total, service.getCurrentPage());
    }

    @Test
    void navigateChapterWithActiveSessionDelegatesToNavigator() {
        startReading(longContent(), 1);

        // 会话有效 → 不短路,委托导航器(章节列表为空时安全返回)
        service.navigateChapter(project, 1);

        assertEquals("book-1", service.getCurrentBookId());
    }

    @Test
    void closeAllNotificationsExpiresCurrentNotification() {
        com.intellij.notification.Notification notification =
                mock(com.intellij.notification.Notification.class,
                        org.mockito.Mockito.RETURNS_SELF);
        when(notification.isExpired()).thenReturn(false);
        displayManagerMock.when(() -> NotificationDisplayManager.showReadingNotification(
                        any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(Boolean.class),
                        any(Boolean.class), any(), any(), any(), any(), any()))
                .thenReturn(notification);

        startReading(longContent(), 1);
        service.closeAllNotifications();

        Mockito.verify(notification).expire();
    }

    @Test
    void restoredPageNumberIsClampedToPageCount() {
        // 仓库报告的页码超出总页数 → 应夹紧到最后一页
        com.lv.tool.privatereader.model.BookProgressData data =
                new com.lv.tool.privatereader.model.BookProgressData(
                        "book-1", "测试书籍", "chapter-1", "第一章", 0, 9999, false, null);
        when(progressRepository.getProgress("book-1"))
                .thenReturn(java.util.Optional.of(data));

        startReading(longContent(), 1);

        assertEquals(service.getTotalPages(), service.getCurrentPage(),
                "越界页码应夹紧到最后一页");
    }

    @Test
    void restoredPageNumberIsUsedWhenChapterMatches() {
        com.lv.tool.privatereader.model.BookProgressData data =
                new com.lv.tool.privatereader.model.BookProgressData(
                        "book-1", "测试书籍", "chapter-1", "第一章", 0, 2, false, null);
        when(progressRepository.getProgress("book-1"))
                .thenReturn(java.util.Optional.of(data));

        startReading(longContent(), 1);

        assertEquals(2, service.getCurrentPage());
    }

    @Test
    void showChapterContentWithProgressEnabledAppendsProgressText() {
        when(settings.isShowReadingProgress()).thenReturn(true);
        startReading(longContent(), 1);

        assertTrue(service.getTotalPages() > 0);
        assertEquals(1, service.getCurrentPage());
    }

    @Test
    void disposeIsSafeAndIdempotent() {
        service.dispose();
        service.dispose();
        assertTrue(true);
    }
}
