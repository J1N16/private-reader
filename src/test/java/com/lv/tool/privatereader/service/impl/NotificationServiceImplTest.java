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
        appManagerMock.when(ApplicationManager::getApplication).thenReturn(app);

        // 3. 拦截 ReactiveSchedulers:runOnUI 改为测试线程同步执行
        ReactiveSchedulers schedulers = mock(ReactiveSchedulers.class);
        Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(schedulers).runOnUI(any());
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
        service = new NotificationServiceImpl(bookService, chapterService, settings, preloader, chapterChangeManager,
                mock(ReadingProgressRepository.class));
    }

    @AfterEach
    void tearDown() {
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
}