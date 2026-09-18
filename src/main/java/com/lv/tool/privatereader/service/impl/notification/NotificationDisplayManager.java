package com.lv.tool.privatereader.service.impl.notification;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.lv.tool.privatereader.config.PrivateReaderConfig;
import com.lv.tool.privatereader.service.NotificationService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 通知展示管理器(纯展示职责,不持有阅读状态)。
 * <p>
 * 从 {@code NotificationServiceImpl} 中拆分出的"创建/显示通知"逻辑:
 * <ul>
 *   <li>创建阅读通知(含页码标题、导航/返回阅读器操作按钮)</li>
 *   <li>加载中通知</li>
 *   <li>错误/信息通知</li>
 *   <li>HTML 标签清理(展示前脱敏)</li>
 * </ul>
 * 本类为纯工具:所有方法均为静态,不维护任何跨调用状态,便于单元测试与职责单一。
 */
public final class NotificationDisplayManager {
    private NotificationDisplayManager() {}

    /**
     * 创建阅读内容通知并显示。
     *
     * @param project      当前项目(可为 null)
     * @param title        通知标题
     * @param content      页面内容(未清理 HTML)
     * @param pageIndex    当前页索引(0 基,用于按钮与页码显示)
     * @param pages       当前分页结果(用于按钮可用性判断)
     * @param showButtons 是否显示导航操作按钮
     * @param showPageNumbers 是否在标题显示页码
     * @return 已显示的通知
     */
    @NotNull
    public static Notification showReadingNotification(@NotNull Project project,
                                                       @NotNull String title,
                                                       @NotNull String content,
                                                       int pageIndex,
                                                       @NotNull java.util.List<String> pages,
                                                       boolean showButtons,
                                                       boolean showPageNumbers) {
        String displayTitle = title;
        if (pageIndex == 0) {
            displayTitle = title;
        } else {
            displayTitle = "阅读中";
        }
        if (showPageNumbers) {
            displayTitle += String.format(" (第%d页/共%d页)", pageIndex + 1, pages.size());
        }

        String cleanContent = cleanHtmlTags(content);
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID_READER)
                .createNotification(cleanContent, NotificationType.INFORMATION)
                .setTitle(displayTitle);

        if (showButtons) {
            addReadingActions(project, notification, pageIndex, pages.size());
        }

        com.intellij.notification.Notifications.Bus.notify(notification, project);
        return notification;
    }

    /**
     * 添加阅读通知的操作按钮:上一页/下一页(按可用性),上一章/下一章,返回阅读器。
     */
    private static void addReadingActions(@NotNull Project project,
                                          @NotNull Notification notification,
                                          int pageIndex,
                                          int pageCount) {
        if (pageIndex > 0) {
            notification.addAction(NotificationAction.createSimple("上一页", () -> showPrevPage(project)));
        }
        if (pageIndex < pageCount - 1) {
            notification.addAction(NotificationAction.createSimple("下一页", () -> showNextPage(project)));
        }
        notification.addAction(NotificationAction.createSimple("上一章", () -> navigateChapter(project, -1)));
        notification.addAction(NotificationAction.createSimple("下一章", () -> navigateChapter(project, 1)));
        notification.addAction(NotificationAction.createSimple("返回阅读器", () -> switchBackToReader()));
    }

    private static void showPrevPage(@NotNull Project project) {
        NotificationService service = ApplicationManager.getApplication().getService(NotificationService.class);
        if (service != null) {
            service.showPrevPage(project);
        }
    }

    private static void showNextPage(@NotNull Project project) {
        NotificationService service = ApplicationManager.getApplication().getService(NotificationService.class);
        if (service != null) {
            service.showNextPage(project);
        }
    }

    private static void navigateChapter(@NotNull Project project, int direction) {
        NotificationService service = ApplicationManager.getApplication().getService(NotificationService.class);
        if (service != null) {
            service.navigateChapter(project, direction);
        }
    }

    private static void switchBackToReader() {
        ApplicationManager.getApplication().invokeLater(() -> {
            com.lv.tool.privatereader.settings.ReaderModeSettings settings =
                    ApplicationManager.getApplication()
                            .getService(com.lv.tool.privatereader.settings.ReaderModeSettings.class);
            if (settings != null) {
                settings.setNotificationMode(false);
            }
        });
    }

    /**
     * 显示加载状态通知。
     *
     * @param project 当前项目
     * @param message 加载消息
     * @return 已显示的加载通知
     */
    @NotNull
    public static Notification showLoadingNotification(@NotNull Project project, @NotNull String message) {
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID_READER)
                .createNotification(message, NotificationType.INFORMATION);
        notification.setTitle("加载中");
        com.intellij.notification.Notifications.Bus.notify(notification, project);
        return notification;
    }

    /**
     * 显示错误通知。
     *
     * @param title   通知标题
     * @param message 错误消息
     * @return 已显示的错误通知
     */
    @NotNull
    public static Notification showError(@NotNull String title, @NotNull String message) {
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID_READER)
                .createNotification(message, NotificationType.ERROR);
        notification.setTitle(title);
        notification.notify(null);
        return notification;
    }

    /**
     * 显示信息通知。
     *
     * @param title   通知标题
     * @param message 消息内容
     * @return 已显示的信息通知
     */
    @NotNull
    public static Notification showInfo(@NotNull String title, @NotNull String message) {
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID_READER)
                .createNotification(message, NotificationType.INFORMATION);
        notification.setTitle(title);
        notification.notify(null);
        return notification;
    }

    /**
     * 清理 HTML 标签,保留换行符和基本格式。
     *
     * @param content 包含 HTML 标签的内容;null 视为空
     * @return 清理后的纯文本内容
     */
    @NotNull
    public static String cleanHtmlTags(@Nullable String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return cleanHtmlTagsNonNull(content);
    }

    private static String cleanHtmlTagsNonNull(@NotNull String content) {
        String result = content
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");

        result = result.replaceAll("<[^>]*>", "");
        result = result.replaceAll("\\n{3,}", "\n\n");
        return result;
    }
}