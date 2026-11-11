package com.lv.tool.privatereader.service;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import com.lv.tool.privatereader.ui.ReaderPanel;
import com.lv.tool.privatereader.ui.ReaderToolWindowFactory;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReaderModeSwitcher 单元测试(V23)。
 *
 * <p>此前 0 覆盖。该类为 APP 级服务,构造器依赖 IntelliJ 消息总线与
 * ProjectManager。测试用 mockStatic 拦截 ApplicationManager / ProjectManager,
 * 以 mock 的 MessageBus/Project/ToolWindow 驱动初始化、模式切换与释放路径。
 * 只走非 LOG.error 分支(测试环境 LOG.error 会断言失败)。</p>
 */
class ReaderModeSwitcherTest {

    private MockedStatic<ApplicationManager> appMgr;
    private MockedStatic<ProjectManager> projectMgr;

    private NotificationService notificationService;
    private ReaderModeSettings readerModeSettings;
    private NotificationReaderSettings notificationReaderSettings;
    private MessageBusConnection connection;
    private ProjectManager projectManager;
    private Application application;
    private NotificationBarModeService notificationBarModeService;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        readerModeSettings = mock(ReaderModeSettings.class);
        notificationReaderSettings = mock(NotificationReaderSettings.class);
        connection = mock(MessageBusConnection.class);

        application = mock(Application.class, RETURNS_DEEP_STUBS);
        MessageBus messageBus = mock(MessageBus.class);
        when(application.getMessageBus()).thenReturn(messageBus);
        when(messageBus.connect(any(com.intellij.openapi.Disposable.class))).thenReturn(connection);
        notificationBarModeService = mock(NotificationBarModeService.class);
        when(application.getService(NotificationBarModeService.class)).thenReturn(notificationBarModeService);
        // V32:让 invokeLater 同步执行,以便覆盖隐藏/显示工具窗口的 lambda 体
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(application).invokeLater(any(Runnable.class));

        appMgr = mockStatic(ApplicationManager.class);
        appMgr.when(ApplicationManager::getApplication).thenReturn(application);

        projectManager = mock(ProjectManager.class);
        projectMgr = mockStatic(ProjectManager.class);
        projectMgr.when(ProjectManager::getInstance).thenReturn(projectManager);
    }

    @AfterEach
    void tearDown() {
        appMgr.close();
        projectMgr.close();
    }

    private ReaderModeSwitcher newSwitcher() {
        return new ReaderModeSwitcher(notificationService, readerModeSettings, notificationReaderSettings);
    }

    @Test
    void constructorSubscribesToTopicAndFinalizesWhenNoProjectsOpen() {
        when(readerModeSettings.isNotificationMode()).thenReturn(false);
        when(projectManager.getOpenProjects()).thenReturn(new Project[0]);

        ReaderModeSwitcher switcher = newSwitcher();

        verify(connection).subscribe(eq(ReaderModeSettings.TOPIC), any());
        switcher.dispose();
    }

    @Test
    void modeChangedAfterInitDelegatesToApplyWithNoProjects() {
        when(readerModeSettings.isNotificationMode()).thenReturn(false);
        when(projectManager.getOpenProjects()).thenReturn(new Project[0]);

        ReaderModeSwitcher switcher = newSwitcher();
        switcher.modeChanged(true);

        // 无项目时安全跳过,不抛异常
        switcher.dispose();
    }

    @Test
    void modeChangedBeforeInitIsIgnored() {
        when(readerModeSettings.isNotificationMode()).thenReturn(false);
        when(projectManager.getOpenProjects()).thenReturn(new Project[0]);

        ReaderModeSwitcher switcher = newSwitcher();
        switcher.dispose(); // initialized = false
        org.mockito.Mockito.clearInvocations(notificationService);
        switcher.modeChanged(true); // 应 warn 并跳过,不应触发任何模式应用

        verify(notificationService, never()).closeAllNotifications();
    }

    @Test
    void applyInitialModeForProjectSkipsWhenNotInitialized() {
        when(readerModeSettings.isNotificationMode()).thenReturn(false);
        when(projectManager.getOpenProjects()).thenReturn(new Project[0]);

        ReaderModeSwitcher switcher = newSwitcher();
        switcher.dispose();

        Project project = mock(Project.class);
        when(project.getName()).thenReturn("proj");
        org.mockito.Mockito.clearInvocations(notificationService);
        switcher.applyInitialModeForProject(project);

        // 未初始化时不得应用任何模式(不关闭通知)
        verify(notificationService, never()).closeAllNotifications();
    }

    @Test
    void panelModeClosesNotificationsAndShowsToolWindowForOpenProject() {
        when(readerModeSettings.isNotificationMode()).thenReturn(false);

        Project project = mock(Project.class);
        when(project.isDisposed()).thenReturn(false);
        when(project.getName()).thenReturn("proj");
        when(projectManager.getOpenProjects()).thenReturn(new Project[]{project});

        ToolWindowManager twm = mock(ToolWindowManager.class);
        ToolWindow toolWindow = mock(ToolWindow.class);
        when(toolWindow.isAvailable()).thenReturn(true);
        when(toolWindow.isVisible()).thenReturn(false);
        when(twm.getToolWindow("PrivateReader")).thenReturn(toolWindow);
        try (MockedStatic<ToolWindowManager> twmStatic = mockStatic(ToolWindowManager.class)) {
            twmStatic.when(() -> ToolWindowManager.getInstance(project)).thenReturn(twm);

            ReaderModeSwitcher switcher = newSwitcher();

            // 构造时 applyCurrentMode 即触发一次;关闭通知(面板模式)
            verify(notificationService, times(1)).closeAllNotifications();
            switcher.dispose();
        }
    }

    @Test
    void notificationModeShowsInfoAndHidesToolWindow() {
        when(readerModeSettings.isNotificationMode()).thenReturn(true);
        when(notificationService.showInfo(any(), any())).thenReturn(Single.just(mock(com.intellij.notification.Notification.class)));

        Project project = mock(Project.class);
        when(project.isDisposed()).thenReturn(false);
        when(project.getName()).thenReturn("proj");
        when(projectManager.getOpenProjects()).thenReturn(new Project[]{project});

        ToolWindowManager twm = mock(ToolWindowManager.class);
        ToolWindow toolWindow = mock(ToolWindow.class);
        when(toolWindow.isVisible()).thenReturn(true);
        when(twm.getToolWindow("PrivateReader")).thenReturn(toolWindow);
        try (MockedStatic<ToolWindowManager> twmStatic = mockStatic(ToolWindowManager.class)) {
            twmStatic.when(() -> ToolWindowManager.getInstance(project)).thenReturn(twm);

            ReaderModeSwitcher switcher = newSwitcher();

            verify(notificationService, times(1)).showInfo(eq("阅读模式"), eq("已切换到通知栏模式"));
            switcher.dispose();
        }
    }

    @Test
    void disposeIsIdempotent() {
        when(readerModeSettings.isNotificationMode()).thenReturn(false);
        when(projectManager.getOpenProjects()).thenReturn(new Project[0]);

        ReaderModeSwitcher switcher = newSwitcher();
        switcher.dispose();
        switcher.dispose();
        // 无异常即通过(connection 已随父 Disposable 释放)
    }

    // --- V32 补充:invokeLater 同步化后的窗口隐藏/显示与初始模式应用 ---

    @Test
    void notificationModeHidesVisibleToolWindowOnUiThread() {
        when(readerModeSettings.isNotificationMode()).thenReturn(true);
        when(notificationService.showInfo(any(), any()))
                .thenReturn(Single.just(mock(com.intellij.notification.Notification.class)));

        Project project = mock(Project.class);
        when(project.isDisposed()).thenReturn(false);
        when(project.getName()).thenReturn("proj");
        when(projectManager.getOpenProjects()).thenReturn(new Project[]{project});

        ToolWindowManager twm = mock(ToolWindowManager.class);
        ToolWindow toolWindow = mock(ToolWindow.class);
        when(toolWindow.isVisible()).thenReturn(true);
        when(twm.getToolWindow("PrivateReader")).thenReturn(toolWindow);
        try (MockedStatic<ToolWindowManager> twmStatic = mockStatic(ToolWindowManager.class)) {
            twmStatic.when(() -> ToolWindowManager.getInstance(project)).thenReturn(twm);

            ReaderModeSwitcher switcher = newSwitcher();

            // invokeLater 同步执行 → 确实隐藏了可见的工具窗口
            verify(toolWindow).hide(null);
            switcher.dispose();
        }
    }

    @Test
    void panelModeShowsHiddenToolWindowOnUiThread() {
        when(readerModeSettings.isNotificationMode()).thenReturn(false);

        Project project = mock(Project.class);
        when(project.isDisposed()).thenReturn(false);
        when(project.getName()).thenReturn("proj");
        when(projectManager.getOpenProjects()).thenReturn(new Project[]{project});

        ToolWindowManager twm = mock(ToolWindowManager.class);
        ToolWindow toolWindow = mock(ToolWindow.class);
        when(toolWindow.isAvailable()).thenReturn(true);
        when(toolWindow.isVisible()).thenReturn(false);
        when(twm.getToolWindow("PrivateReader")).thenReturn(toolWindow);
        try (MockedStatic<ToolWindowManager> twmStatic = mockStatic(ToolWindowManager.class)) {
            twmStatic.when(() -> ToolWindowManager.getInstance(project)).thenReturn(twm);

            ReaderModeSwitcher switcher = newSwitcher();

            verify(toolWindow).show(null);
            switcher.dispose();
        }
    }

    @Test
    void panelModeWithoutToolWindowDoesNotThrow() {
        when(readerModeSettings.isNotificationMode()).thenReturn(false);

        Project project = mock(Project.class);
        when(project.isDisposed()).thenReturn(false);
        when(project.getName()).thenReturn("proj");
        when(projectManager.getOpenProjects()).thenReturn(new Project[]{project});

        ToolWindowManager twm = mock(ToolWindowManager.class);
        when(twm.getToolWindow("PrivateReader")).thenReturn(null);
        try (MockedStatic<ToolWindowManager> twmStatic = mockStatic(ToolWindowManager.class)) {
            twmStatic.when(() -> ToolWindowManager.getInstance(project)).thenReturn(twm);

            // 关闭通知仍然发生;工具窗口缺失只走 warn 分支
            ReaderModeSwitcher switcher = newSwitcher();
            verify(notificationService).closeAllNotifications();
            switcher.dispose();
        }
    }

    @Test
    void applyInitialModeForProjectInPanelModeReadsSettingsAndApplies() {
        when(readerModeSettings.isNotificationMode()).thenReturn(false);

        Project project = mock(Project.class);
        when(project.isDisposed()).thenReturn(false);
        when(project.getName()).thenReturn("proj");
        when(projectManager.getOpenProjects()).thenReturn(new Project[]{project});

        ToolWindowManager twm = mock(ToolWindowManager.class);
        ToolWindow toolWindow = mock(ToolWindow.class);
        when(toolWindow.isAvailable()).thenReturn(true);
        when(toolWindow.isVisible()).thenReturn(true);
        when(twm.getToolWindow("PrivateReader")).thenReturn(toolWindow);
        try (MockedStatic<ToolWindowManager> twmStatic = mockStatic(ToolWindowManager.class)) {
            twmStatic.when(() -> ToolWindowManager.getInstance(project)).thenReturn(twm);

            ReaderModeSwitcher switcher = newSwitcher();
            org.mockito.Mockito.clearInvocations(notificationService);

            switcher.applyInitialModeForProject(project);

            // 初始化态下会重新读取设置并应用面板模式
            verify(readerModeSettings, org.mockito.Mockito.atLeastOnce()).isNotificationMode();
            verify(notificationService).closeAllNotifications();
            switcher.dispose();
        }
    }

    @Test
    void applyInitialModeForProjectInNotificationModeBindsBarService() {
        when(readerModeSettings.isNotificationMode()).thenReturn(true);
        when(notificationService.showInfo(any(), any()))
                .thenReturn(Single.just(mock(com.intellij.notification.Notification.class)));
        NotificationBarModeService barService = mock(NotificationBarModeService.class);
        when(application.getService(NotificationBarModeService.class)).thenReturn(barService);

        Project project = mock(Project.class);
        when(project.isDisposed()).thenReturn(false);
        when(project.getName()).thenReturn("proj");
        when(projectManager.getOpenProjects()).thenReturn(new Project[]{project});

        ToolWindowManager twm = mock(ToolWindowManager.class);
        ToolWindow toolWindow = mock(ToolWindow.class);
        when(toolWindow.isVisible()).thenReturn(false);
        when(twm.getToolWindow("PrivateReader")).thenReturn(toolWindow);
        try (MockedStatic<ToolWindowManager> twmStatic = mockStatic(ToolWindowManager.class)) {
            twmStatic.when(() -> ToolWindowManager.getInstance(project)).thenReturn(twm);

            ReaderModeSwitcher switcher = newSwitcher();
            org.mockito.Mockito.clearInvocations(barService);

            switcher.applyInitialModeForProject(project);

            // applyModeForProject 与 applyInitialModeForProject 都会绑定一次
            verify(barService, org.mockito.Mockito.atLeastOnce()).setProject(project);
            switcher.dispose();
        }
    }

    @Test
    void defaultConstructorResolvesServicesFromApplication() {
        when(application.getService(NotificationService.class)).thenReturn(notificationService);
        when(application.getService(ReaderModeSettings.class)).thenReturn(readerModeSettings);
        when(application.getService(NotificationReaderSettings.class)).thenReturn(notificationReaderSettings);
        when(readerModeSettings.isNotificationMode()).thenReturn(false);
        when(projectManager.getOpenProjects()).thenReturn(new Project[0]);

        ReaderModeSwitcher switcher = new ReaderModeSwitcher();

        verify(connection).subscribe(eq(ReaderModeSettings.TOPIC), any());
        switcher.dispose();
    }

    @Test
    void nullNotificationReaderSettingsDisablesAutoReadWithoutFailing() {
        when(readerModeSettings.isNotificationMode()).thenReturn(false);
        when(projectManager.getOpenProjects()).thenReturn(new Project[0]);

        ReaderModeSwitcher switcher = new ReaderModeSwitcher(notificationService, readerModeSettings, null);

        verify(connection).subscribe(eq(ReaderModeSettings.TOPIC), any());
        switcher.dispose();
    }

    // --- 切换到通知栏模式时立即展示当前/最近阅读内容 ---

    @Test
    void modeChangedToNotificationModeActivatesCurrentChapterFromPanel() {
        when(readerModeSettings.isNotificationMode()).thenReturn(false);
        when(notificationService.showInfo(any(), any()))
                .thenReturn(Single.just(mock(com.intellij.notification.Notification.class)));

        Project project = mock(Project.class);
        when(project.isDisposed()).thenReturn(false);
        when(project.getName()).thenReturn("proj");
        when(projectManager.getOpenProjects()).thenReturn(new Project[]{project});

        ToolWindowManager twm = mock(ToolWindowManager.class);
        ToolWindow toolWindow = mock(ToolWindow.class);
        when(twm.getToolWindow("PrivateReader")).thenReturn(toolWindow);
        try (MockedStatic<ToolWindowManager> twmStatic = mockStatic(ToolWindowManager.class)) {
            twmStatic.when(() -> ToolWindowManager.getInstance(project)).thenReturn(twm);

            ReaderModeSwitcher switcher = newSwitcher();

            Book book = new Book("b1", "书", "作者", "https://x.com/b1");
            NovelParser.Chapter chapter = new NovelParser.Chapter("第一章", "c1");
            ReaderPanel panel = mock(ReaderPanel.class);
            when(panel.getSelectedBook()).thenReturn(book);
            when(panel.getSelectedChapter()).thenReturn(chapter);
            ReaderToolWindowFactory.PROJECT_PANELS.put(project, panel);
            try {
                switcher.modeChanged(true);

                verify(notificationBarModeService).activateNotificationBarMode(project, "b1", "c1", 1);
                verify(notificationBarModeService, never()).activateLastReadChapter(project);
            } finally {
                ReaderToolWindowFactory.PROJECT_PANELS.remove(project);
            }
            switcher.dispose();
        }
    }

    @Test
    void modeChangedToNotificationModeFallsBackToLastReadWhenPanelHasNoChapter() {
        when(readerModeSettings.isNotificationMode()).thenReturn(false);
        when(notificationService.showInfo(any(), any()))
                .thenReturn(Single.just(mock(com.intellij.notification.Notification.class)));

        Project project = mock(Project.class);
        when(project.isDisposed()).thenReturn(false);
        when(project.getName()).thenReturn("proj");
        when(projectManager.getOpenProjects()).thenReturn(new Project[]{project});

        ToolWindowManager twm = mock(ToolWindowManager.class);
        ToolWindow toolWindow = mock(ToolWindow.class);
        when(twm.getToolWindow("PrivateReader")).thenReturn(toolWindow);
        try (MockedStatic<ToolWindowManager> twmStatic = mockStatic(ToolWindowManager.class)) {
            twmStatic.when(() -> ToolWindowManager.getInstance(project)).thenReturn(twm);

            ReaderModeSwitcher switcher = newSwitcher();

            ReaderToolWindowFactory.PROJECT_PANELS.put(project, mock(ReaderPanel.class));
            try {
                switcher.modeChanged(true);

                verify(notificationBarModeService).activateLastReadChapter(project);
                verify(notificationBarModeService, never()).activateNotificationBarMode(
                        any(Project.class), any(), any(), anyInt());
            } finally {
                ReaderToolWindowFactory.PROJECT_PANELS.remove(project);
            }
            switcher.dispose();
        }
    }
}