package com.lv.tool.privatereader.service.impl.notification;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationDisplayManager 通知创建路径测试(V25)。
 *
 * <p>补齐此前未覆盖的展示方法:{@code showReadingNotification}/{@code showLoadingNotification}/
 * {@code showError}/{@code showInfo}。通过 mockStatic 拦截
 * {@code NotificationGroupManager} 与 {@code Notifications.Bus},以 mock 的
 * {@link Notification} 验证标题、类型与操作按钮装配,不依赖真实 IDE 通知系统。</p>
 */
class NotificationDisplayManagerDisplayTest {

    /** 构建一个返回给定 Notification 的通知组 mock(在 mockStatic 作用域外调用)。 */
    private static NotificationGroup groupReturning(Notification notification) {
        NotificationGroup group = mock(NotificationGroup.class);
        when(group.createNotification(any(String.class), any(NotificationType.class))).thenReturn(notification);
        return group;
    }

    @Test
    void showReadingNotificationDisplaysWithCleanedContentAndTitle() {
        Notification notification = mock(Notification.class, org.mockito.Mockito.RETURNS_SELF);
        NotificationGroup group = groupReturning(notification);
        NotificationGroupManager manager = mock(NotificationGroupManager.class);
        when(manager.getNotificationGroup(any(String.class))).thenReturn(group);
        Project project = mock(Project.class);

        try (MockedStatic<NotificationGroupManager> ngm = mockStatic(NotificationGroupManager.class);
             MockedStatic<Notifications.Bus> bus = mockStatic(Notifications.Bus.class)) {
            ngm.when(NotificationGroupManager::getInstance).thenReturn(manager);

            Notification result = NotificationDisplayManager.showReadingNotification(
                    project, "书名 - 章节", "<p>你好&nbsp;世界</p>", 0, List.of("a", "b"),
                    false, true, null, null, null, null, null);

            assertEquals(notification, result);
            verify(group).createNotification(eq("你好 世界"), eq(NotificationType.INFORMATION));
            verify(notification).setTitle(eq("书名 - 章节 (第1页/共2页)"));
            bus.verify(() -> Notifications.Bus.notify(notification, project));
        }
    }

    @Test
    void showReadingNotificationAddsEnabledActionsWhenButtonsShown() {
        Notification notification = mock(Notification.class, org.mockito.Mockito.RETURNS_SELF);
        NotificationGroup group = groupReturning(notification);
        NotificationGroupManager manager = mock(NotificationGroupManager.class);
        when(manager.getNotificationGroup(any(String.class))).thenReturn(group);
        Project project = mock(Project.class);
        Runnable prevPage = mock(Runnable.class);
        Runnable nextPage = mock(Runnable.class);
        Runnable prevChapter = mock(Runnable.class);
        Runnable nextChapter = mock(Runnable.class);
        Runnable switchBack = mock(Runnable.class);

        try (MockedStatic<NotificationGroupManager> ngm = mockStatic(NotificationGroupManager.class);
             MockedStatic<Notifications.Bus> bus = mockStatic(Notifications.Bus.class)) {
            ngm.when(NotificationGroupManager::getInstance).thenReturn(manager);

            NotificationDisplayManager.showReadingNotification(
                    project, "标题", "内容", 1, List.of("a", "b", "c"),
                    true, false, prevPage, nextPage, prevChapter, nextChapter, switchBack);

            // 中间页:上一页/下一页均可用 + 上一章/下一章/返回阅读器 = 5 个按钮
            verify(notification, times(5)).addAction(any(NotificationAction.class));
        }
    }

    @Test
    void showReadingNotificationHidesActionsOnSinglePageWithoutButtons() {
        Notification notification = mock(Notification.class, org.mockito.Mockito.RETURNS_SELF);
        NotificationGroup group = groupReturning(notification);
        NotificationGroupManager manager = mock(NotificationGroupManager.class);
        when(manager.getNotificationGroup(any(String.class))).thenReturn(group);
        Project project = mock(Project.class);

        try (MockedStatic<NotificationGroupManager> ngm = mockStatic(NotificationGroupManager.class);
             MockedStatic<Notifications.Bus> bus = mockStatic(Notifications.Bus.class)) {
            ngm.when(NotificationGroupManager::getInstance).thenReturn(manager);

            NotificationDisplayManager.showReadingNotification(
                    project, "标题", "内容", 0, List.of("a"),
                    false, false, null, null, null, null, null);

            verify(notification, times(0)).addAction(any(NotificationAction.class));
        }
    }

    @Test
    void showLoadingNotificationSetsLoadingTitle() {
        Notification notification = mock(Notification.class, org.mockito.Mockito.RETURNS_SELF);
        NotificationGroup group = groupReturning(notification);
        NotificationGroupManager manager = mock(NotificationGroupManager.class);
        when(manager.getNotificationGroup(any(String.class))).thenReturn(group);
        Project project = mock(Project.class);

        try (MockedStatic<NotificationGroupManager> ngm = mockStatic(NotificationGroupManager.class);
             MockedStatic<Notifications.Bus> bus = mockStatic(Notifications.Bus.class)) {
            ngm.when(NotificationGroupManager::getInstance).thenReturn(manager);

            Notification result = NotificationDisplayManager.showLoadingNotification(project, "正在加载章节");

            assertEquals(notification, result);
            verify(group).createNotification(eq("正在加载章节"), eq(NotificationType.INFORMATION));
            verify(notification).setTitle(eq("加载中"));
            bus.verify(() -> Notifications.Bus.notify(notification, project));
        }
    }

    @Test
    void showErrorCreatesErrorNotification() {
        Notification notification = mock(Notification.class, org.mockito.Mockito.RETURNS_SELF);
        NotificationGroup group = groupReturning(notification);
        NotificationGroupManager manager = mock(NotificationGroupManager.class);
        when(manager.getNotificationGroup(any(String.class))).thenReturn(group);

        try (MockedStatic<NotificationGroupManager> ngm = mockStatic(NotificationGroupManager.class)) {
            ngm.when(NotificationGroupManager::getInstance).thenReturn(manager);

            Notification result = NotificationDisplayManager.showError("网络错误", "连接失败");

            assertEquals(notification, result);
            verify(group).createNotification(eq("连接失败"), eq(NotificationType.ERROR));
            verify(notification).setTitle(eq("网络错误"));
            verify(notification).notify(null);
        }
    }

    @Test
    void showInfoCreatesInformationNotification() {
        Notification notification = mock(Notification.class, org.mockito.Mockito.RETURNS_SELF);
        NotificationGroup group = groupReturning(notification);
        NotificationGroupManager manager = mock(NotificationGroupManager.class);
        when(manager.getNotificationGroup(any(String.class))).thenReturn(group);

        try (MockedStatic<NotificationGroupManager> ngm = mockStatic(NotificationGroupManager.class)) {
            ngm.when(NotificationGroupManager::getInstance).thenReturn(manager);

            Notification result = NotificationDisplayManager.showInfo("阅读模式", "已切换");

            assertEquals(notification, result);
            verify(group).createNotification(eq("已切换"), eq(NotificationType.INFORMATION));
            verify(notification).setTitle(eq("阅读模式"));
            verify(notification).notify(null);
        }
    }
}