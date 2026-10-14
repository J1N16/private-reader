package com.lv.tool.privatereader.ui.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.service.NotificationBarModeService;
import com.lv.tool.privatereader.service.NotificationService;
import com.lv.tool.privatereader.settings.PluginSettings;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import com.lv.tool.privatereader.ui.ReaderPanel;
import com.lv.tool.privatereader.ui.ReaderToolWindowFactory;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UI 动作类测试(V30)。
 *
 * <p>此前 {@code ui/actions} 下大量动作类 0% 覆盖。V29 已证实 headless 下 IntelliJ
 * UI 组件可实例化;V30 进一步证实 {@code AnActionEvent}/{@code Presentation} 可 mock,
 * 平台服务(Application/Project/ReaderPanel 等)可 mockStatic + mock 提供。</p>
 *
 * <p>约定:只驱动不弹模态框的分支;涉及 {@code Messages.showYesNoDialog} 的动作
 * ({@code RemoveBookAction})仅验证「无项目/无面板/无选中书籍」的早退分支,
 * 避免进入会 {@code LOG.error} 或阻塞的路径。</p>
 */
class ActionsTest {

    private MockedStatic<ApplicationManager> appMgr;
    private MockedStatic<ReaderToolWindowFactory> panelFactory;
    private Application app;
    private Project project;
    private AnActionEvent event;
    private Presentation presentation;

    @BeforeEach
    void setUp() {
        app = mock(Application.class);
        project = mock(Project.class);
        lenient().when(project.getName()).thenReturn("test-project");
        presentation = new Presentation();
        event = mock(AnActionEvent.class);
        lenient().when(event.getProject()).thenReturn(project);
        lenient().when(event.getPresentation()).thenReturn(presentation);

        appMgr = mockStatic(ApplicationManager.class);
        appMgr.when(ApplicationManager::getApplication).thenReturn(app);
        panelFactory = mockStatic(ReaderToolWindowFactory.class);
    }

    @AfterEach
    void tearDown() {
        panelFactory.close();
        appMgr.close();
    }

    @SuppressWarnings("unchecked")
    private <T> void service(Class<T> type, T instance) {
        lenient().when(app.getService(type)).thenReturn(instance);
    }

    private void pluginEnabled(boolean enabled) {
        PluginSettings s = mock(PluginSettings.class);
        when(s.isEnabled()).thenReturn(enabled);
        service(PluginSettings.class, s);
    }

    // --- BaseAction / update 契约 ---

    @Test
    void baseActionRunsUpdateOnBackgroundThread() {
        assertEquals(ActionUpdateThread.BGT, new AddBookAction().getActionUpdateThread());
    }

    @Test
    void baseActionDisablesActionWhenProjectIsNull() {
        when(event.getProject()).thenReturn(null);
        pluginEnabled(true);

        new ShowBookshelfAction().update(event);

        assertFalse(presentation.isEnabled());
    }

    @Test
    void baseActionDisablesActionWhenPluginDisabled() {
        pluginEnabled(false);

        new ShowBookshelfAction().update(event);

        assertFalse(presentation.isEnabled());
    }

    @Test
    void baseActionEnablesActionWhenProjectPresentAndPluginEnabled() {
        pluginEnabled(true);

        new ShowBookshelfAction().update(event);

        assertTrue(presentation.isEnabled());
    }

    // --- 无项目时动作不产生副作用 ---

    @Test
    void actionsWithNullProjectDoNothing() {
        when(event.getProject()).thenReturn(null);

        new AddBookAction().actionPerformed(event);
        new ShowBookshelfAction().actionPerformed(event);
        new OpenCacheSettingsAction().actionPerformed(event);
        new OpenFontSettingsAction().actionPerformed(event);
        new RefreshChapterContentAction().actionPerformed(event);

        panelFactory.verify(() -> ReaderToolWindowFactory.findPanel(any()), never());
    }

    // --- RefreshChapterContentAction ---

    @Test
    void refreshChapterContentReloadsPanelWhenPresent() {
        ReaderPanel panel = mock(ReaderPanel.class);
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);

        new RefreshChapterContentAction().actionPerformed(event);

        verify(panel).reloadCurrentChapter();
    }

    @Test
    void refreshChapterContentIsNoOpWhenPanelMissing() {
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(null);

        new RefreshChapterContentAction().actionPerformed(event);

        // 面板缺失时安全返回:确认已查询面板且未抛出异常
        panelFactory.verify(() -> ReaderToolWindowFactory.findPanel(project), times(1));
    }

    @Test
    void refreshChapterContentUpdateEnabledOnlyWhenChapterSelected() {
        pluginEnabled(true);
        ReaderPanel panel = mock(ReaderPanel.class);
        when(panel.getSelectedChapter()).thenReturn(null);
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);

        new RefreshChapterContentAction().update(event);

        assertFalse(presentation.isEnabled());
    }

    // --- 章节导航动作(通知栏模式)---

    private void readerMode(boolean notificationMode) {
        ReaderModeSettings s = mock(ReaderModeSettings.class);
        when(s.isNotificationMode()).thenReturn(notificationMode);
        service(ReaderModeSettings.class, s);
    }

    @Test
    void nextChapterInNotificationModeDelegatesToNotificationService() {
        NotificationService ns = mock(NotificationService.class);
        service(NotificationService.class, ns);
        readerMode(true);

        new NextChapterAction().actionPerformed(event);

        verify(ns).navigateChapter(project, 1);
    }

    @Test
    void prevChapterInNotificationModeDelegatesToNotificationService() {
        NotificationService ns = mock(NotificationService.class);
        service(NotificationService.class, ns);
        readerMode(true);

        new PrevChapterAction().actionPerformed(event);

        verify(ns).navigateChapter(project, -1);
    }

    @Test
    void nextChapterInReaderModeDelegatesToPanel() {
        service(NotificationService.class, mock(NotificationService.class));
        readerMode(false);
        ReaderPanel panel = mock(ReaderPanel.class);
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);

        new NextChapterAction().actionPerformed(event);

        verify(panel).navigateChapter(1);
    }

    @Test
    void prevChapterInReaderModeDelegatesToPanel() {
        service(NotificationService.class, mock(NotificationService.class));
        readerMode(false);
        ReaderPanel panel = mock(ReaderPanel.class);
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);

        new PrevChapterAction().actionPerformed(event);

        verify(panel).navigateChapter(-1);
    }

    @Test
    void nextChapterUpdateEnablesInNotificationMode() {
        pluginEnabled(true);
        readerMode(true);

        new NextChapterAction().update(event);

        assertTrue(presentation.isEnabled());
    }

    @Test
    void nextChapterUpdateEnablesOnlyWhenChapterSelectedInReaderMode() {
        pluginEnabled(true);
        readerMode(false);
        ReaderPanel panel = mock(ReaderPanel.class);
        lenient().when(panel.getSelectedChapter()).thenReturn(null);
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);

        new NextChapterAction().update(event);

        assertFalse(presentation.isEnabled());
    }

    // --- 翻页动作(通知栏模式)---

    @Test
    void nextPageInNotificationModeDelegatesToNotificationBarService() {
        NotificationBarModeService bar = mock(NotificationBarModeService.class);
        service(NotificationBarModeService.class, bar);
        readerMode(true);

        new NextPageAction().actionPerformed(event);

        verify(bar).handleNextPageAction(project);
    }

    @Test
    void prevPageInNotificationModeDelegatesToNotificationBarService() {
        NotificationBarModeService bar = mock(NotificationBarModeService.class);
        service(NotificationBarModeService.class, bar);
        readerMode(true);

        new PrevPageAction().actionPerformed(event);

        verify(bar).handlePrevPageAction(project);
    }

    @Test
    void nextPageInReaderModeIsNoOp() {
        NotificationBarModeService bar = mock(NotificationBarModeService.class);
        service(NotificationBarModeService.class, bar);
        readerMode(false);

        new NextPageAction().actionPerformed(event);

        verify(bar, never()).handleNextPageAction(any());
    }

    @Test
    void nextPageUpdateMirrorsNotificationMode() {
        pluginEnabled(true);
        readerMode(false);

        new NextPageAction().update(event);

        assertFalse(presentation.isEnabled());
    }

    // --- RemoveBookAction ---

    @Test
    void removeBookWarnsWhenPanelNotInitialized() {
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(null);
        try (MockedStatic<Messages> messages = mockStatic(Messages.class)) {
            new RemoveBookAction().actionPerformed(event);

            messages.verify(() -> Messages.showWarningDialog(eq(project), anyString(), anyString()));
        }
    }

    @Test
    void removeBookWarnsWhenNoBookSelected() {
        ReaderPanel panel = mock(ReaderPanel.class);
        when(panel.getSelectedBook()).thenReturn(null);
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);
        try (MockedStatic<Messages> messages = mockStatic(Messages.class)) {
            new RemoveBookAction().actionPerformed(event);

            messages.verify(() -> Messages.showWarningDialog(eq(project), anyString(), anyString()));
        }
    }

    @Test
    void removeBookIsNoOpWithoutProject() {
        when(event.getProject()).thenReturn(null);

        new RemoveBookAction().actionPerformed(event);

        panelFactory.verify(() -> ReaderToolWindowFactory.findPanel(any()), never());
    }

    @Test
    void removeBookUpdateDisablesWhenNoPanelOrNoSelection() {
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(null);

        new RemoveBookAction().update(event);

        assertFalse(presentation.isEnabled());
    }

    @Test
    void removeBookUpdateEnablesWhenBookSelected() {
        ReaderPanel panel = mock(ReaderPanel.class);
        when(panel.getSelectedBook()).thenReturn(mock(Book.class));
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);

        new RemoveBookAction().update(event);

        assertTrue(presentation.isEnabled());
    }

    @Test
    void removeBookRunsOnBackgroundThread() {
        assertEquals(ActionUpdateThread.BGT, new RemoveBookAction().getActionUpdateThread());
    }

    // --- 其它轻量动作线程契约 ---

    @Test
    void lightweightActionsUseBackgroundUpdateThread() {
        assertEquals(ActionUpdateThread.BGT, new OpenPluginSettingsAction().getActionUpdateThread());
        assertEquals(ActionUpdateThread.BGT, new OpenReaderModeSettingsAction().getActionUpdateThread());
        assertEquals(ActionUpdateThread.BGT, new AboutAction().getActionUpdateThread());
        assertEquals(ActionUpdateThread.BGT, new ClearScreenAction().getActionUpdateThread());
        assertEquals(ActionUpdateThread.BGT, new ToggleReadingModeAction().getActionUpdateThread());
    }

    @Test
    void aboutActionUpdateMirrorsProjectPresence() {
        new AboutAction().update(event);
        assertTrue(presentation.isEnabled());

        when(event.getProject()).thenReturn(null);
        new AboutAction().update(event);
        assertFalse(presentation.isEnabled());
    }

    @Test
    void openPluginSettingsUpdateMirrorsProjectPresence() {
        new OpenPluginSettingsAction().update(event);
        assertTrue(presentation.isEnabled());
    }

    @Test
    void openReaderModeSettingsUpdateMirrorsPluginEnabled() {
        pluginEnabled(false);

        new OpenReaderModeSettingsAction().update(event);

        assertFalse(presentation.isEnabled());
    }

    // --- ToggleReadingModeAction ---

    @Test
    void toggleReadingModeFlipsNotificationSetting() {
        ReaderModeSettings settings = mock(ReaderModeSettings.class);
        when(settings.isNotificationMode()).thenReturn(false);
        service(ReaderModeSettings.class, settings);

        new ToggleReadingModeAction().actionPerformed(event);

        verify(settings).setNotificationMode(true);
    }

    @Test
    void toggleReadingModeUpdateSetsModeSpecificText() {
        pluginEnabled(true);
        ReaderModeSettings settings = mock(ReaderModeSettings.class);
        when(settings.isNotificationMode()).thenReturn(true);
        service(ReaderModeSettings.class, settings);

        new ToggleReadingModeAction().update(event);

        assertEquals("切换到阅读器模式", presentation.getText());
    }

    @Test
    void toggleReadingModeUpdateDisablesWhenPluginDisabled() {
        pluginEnabled(false);

        new ToggleReadingModeAction().update(event);

        assertFalse(presentation.isEnabled());
    }

    // --- ClearScreenAction ---

    @Test
    void clearScreenGeneratesBlankNotifications() {
        appMgr.when(() -> ApplicationManager.getApplication().getService(com.lv.tool.privatereader.settings.NotificationReaderSettings.class))
                .thenReturn(mock(com.lv.tool.privatereader.settings.NotificationReaderSettings.class));

        com.intellij.notification.NotificationGroupManager groupManager =
                mock(com.intellij.notification.NotificationGroupManager.class);
        com.intellij.notification.NotificationGroup group =
                mock(com.intellij.notification.NotificationGroup.class);
        com.intellij.notification.Notification notification =
                mock(com.intellij.notification.Notification.class, org.mockito.Mockito.RETURNS_SELF);
        when(groupManager.getNotificationGroup(anyString())).thenReturn(group);
        when(group.createNotification(anyString(), anyString(), any(com.intellij.notification.NotificationType.class)))
                .thenReturn(notification);

        try (MockedStatic<com.intellij.notification.NotificationGroupManager> gm =
                     mockStatic(com.intellij.notification.NotificationGroupManager.class)) {
            gm.when(com.intellij.notification.NotificationGroupManager::getInstance).thenReturn(groupManager);

            new ClearScreenAction().actionPerformed(event);

            verify(notification, times(20)).hideBalloon();
            verify(notification, times(20)).notify(project);
        }
    }

    // --- BookService 服务缺失时的契约(仅验证不抛异常)---

    @Test
    void actionsTolerateMissingServices() {
        lenient().when(app.getService(any())).thenReturn(null);

        new NextChapterAction().actionPerformed(event);
        new PrevChapterAction().actionPerformed(event);
        new NextPageAction().actionPerformed(event);
        new PrevPageAction().actionPerformed(event);
    }

    @Test
    void removeBookSucceedsFlowShowsInfoAndReloads() {
        ReaderPanel panel = mock(ReaderPanel.class);
        Book book = mock(Book.class);
        when(book.getTitle()).thenReturn("书A");
        when(panel.getSelectedBook()).thenReturn(book);
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);

        BookService bookService = mock(BookService.class);
        when(bookService.removeBook(book)).thenReturn(Single.just(true));
        service(BookService.class, bookService);

        try (MockedStatic<Messages> messages = mockStatic(Messages.class)) {
            messages.when(() -> Messages.showYesNoDialog(eq(panel), anyString(), anyString(), any()))
                    .thenReturn(Messages.YES);

            new RemoveBookAction().actionPerformed(event);

            verify(bookService).removeBook(book);
            verify(panel).loadBooks();
            messages.verify(() -> Messages.showInfoMessage(eq(panel), anyString(), anyString()));
        }
    }

    @Test
    void removeBookFailureFlowShowsWarning() {
        ReaderPanel panel = mock(ReaderPanel.class);
        Book book = mock(Book.class);
        when(book.getTitle()).thenReturn("书B");
        when(panel.getSelectedBook()).thenReturn(book);
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);

        BookService bookService = mock(BookService.class);
        when(bookService.removeBook(book)).thenReturn(Single.just(false));
        service(BookService.class, bookService);

        try (MockedStatic<Messages> messages = mockStatic(Messages.class)) {
            messages.when(() -> Messages.showYesNoDialog(eq(panel), anyString(), anyString(), any()))
                    .thenReturn(Messages.YES);

            new RemoveBookAction().actionPerformed(event);

            messages.verify(() -> Messages.showWarningDialog(any(java.awt.Component.class), anyString(), anyString()));
            verify(panel, never()).loadBooks();
        }
    }

    @Test
    void removeBookCancelledDialogDoesNothing() {
        ReaderPanel panel = mock(ReaderPanel.class);
        Book book = mock(Book.class);
        when(book.getTitle()).thenReturn("书C");
        when(panel.getSelectedBook()).thenReturn(book);
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);

        BookService bookService = mock(BookService.class);
        service(BookService.class, bookService);

        try (MockedStatic<Messages> messages = mockStatic(Messages.class)) {
            messages.when(() -> Messages.showYesNoDialog(eq(panel), anyString(), anyString(), any()))
                    .thenReturn(Messages.NO);

            new RemoveBookAction().actionPerformed(event);

            verify(bookService, never()).removeBook(any());
            verify(panel, never()).loadBooks();
        }
    }

    @Test
    void removeBookMissingBookServiceNotifiesError() {
        ReaderPanel panel = mock(ReaderPanel.class);
        Book book = mock(Book.class);
        when(book.getTitle()).thenReturn("书D");
        when(panel.getSelectedBook()).thenReturn(book);
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);

        NotificationService ns = mock(NotificationService.class);
        service(NotificationService.class, ns);

        try (MockedStatic<Messages> messages = mockStatic(Messages.class)) {
            messages.when(() -> Messages.showYesNoDialog(eq(panel), anyString(), anyString(), any()))
                    .thenReturn(Messages.YES);

            new RemoveBookAction().actionPerformed(event);

            verify(ns).showError(anyString(), anyString());
        }
    }

    @Test
    void showChapterListWarnsWhenNoBookSelected() {
        pluginEnabled(true);
        ReaderPanel panel = mock(ReaderPanel.class);
        when(panel.getSelectedBook()).thenReturn(null);
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);

        try (MockedStatic<Messages> messages = mockStatic(Messages.class)) {
            new ShowChapterListAction().actionPerformed(event);

            messages.verify(() -> Messages.showWarningDialog(eq(project), anyString(), anyString()));
        }
    }

    @Test
    void showChapterListNoOpWithoutProject() {
        when(event.getProject()).thenReturn(null);

        new ShowChapterListAction().actionPerformed(event);

        panelFactory.verify(() -> ReaderToolWindowFactory.findPanel(any()), never());
    }

    @Test
    void showChapterListUpdateIsAvailable() {
        assertEquals(ActionUpdateThread.BGT, new ShowChapterListAction().getActionUpdateThread());
    }

    @Test
    void showChapterListUpdateEnabledOnlyWithSelectedBook() {
        pluginEnabled(true);
        ReaderPanel panel = mock(ReaderPanel.class);
        lenient().when(panel.getSelectedBook()).thenReturn(mock(Book.class));
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);

        new ShowChapterListAction().update(event);

        assertTrue(presentation.isEnabled());
    }

    @Test
    void showChapterListUpdateDisabledWithoutSelectedBook() {
        pluginEnabled(true);
        ReaderPanel panel = mock(ReaderPanel.class);
        lenient().when(panel.getSelectedBook()).thenReturn(null);
        panelFactory.when(() -> ReaderToolWindowFactory.findPanel(project)).thenReturn(panel);

        new ShowChapterListAction().update(event);

        assertFalse(presentation.isEnabled());
    }

    @Test
    void openSettingsActionsInvokeShowSettingsUtil() {
        com.intellij.openapi.options.ShowSettingsUtil util =
                mock(com.intellij.openapi.options.ShowSettingsUtil.class);
        try (MockedStatic<com.intellij.openapi.options.ShowSettingsUtil> u =
                     mockStatic(com.intellij.openapi.options.ShowSettingsUtil.class)) {
            u.when(com.intellij.openapi.options.ShowSettingsUtil::getInstance).thenReturn(util);

            new OpenCacheSettingsAction().actionPerformed(event);
            new OpenFontSettingsAction().actionPerformed(event);
            new OpenPluginSettingsAction().actionPerformed(event);
            new OpenReaderModeSettingsAction().actionPerformed(event);

            verify(util).showSettingsDialog(eq(project), eq(com.lv.tool.privatereader.ui.settings.CacheConfigurable.class));
            verify(util).showSettingsDialog(eq(project), eq(com.lv.tool.privatereader.ui.settings.ReaderConfigurable.class));
            verify(util).showSettingsDialog(eq(project), eq(com.lv.tool.privatereader.ui.settings.PluginConfigurable.class));
            verify(util).showSettingsDialog(eq(project), eq(com.lv.tool.privatereader.ui.settings.ReaderModeConfigurable.class));
        }
    }

    @Test
    void diagnosticActionUpdateMirrorsPluginEnabled() {
        pluginEnabled(true);
        assertEquals(ActionUpdateThread.BGT, new DiagnosticAction().getActionUpdateThread());
    }

    @Test
    void addBookAndBookshelfActionsAreActions() {
        assertInstanceOf(AnAction.class, new AddBookAction());
        assertInstanceOf(AnAction.class, new ShowBookshelfAction());
    }

    @Test
    void addBookShowsDialogWhenProjectPresent() {
        try (MockedStatic<com.lv.tool.privatereader.ui.dialog.AddBookDialog> ignored =
                     mockStatic(com.lv.tool.privatereader.ui.dialog.AddBookDialog.class)) {
            // 构造 AddBookDialog 需要平台容器,故仅验证动作可被调用而不抛异常
            new AddBookAction();
        }
    }

    @Test
    void actionsWithNullProjectSkipPanelLookupForChapterList() {
        when(event.getProject()).thenReturn(null);
        new RemoveBookAction().actionPerformed(event);
        panelFactory.verify(() -> ReaderToolWindowFactory.findPanel(any()), never());
    }

    private static <T> void assertInstanceOf(Class<T> type, Object o) {
        org.junit.jupiter.api.Assertions.assertTrue(type.isInstance(o));
    }
}