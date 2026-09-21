package com.lv.tool.privatereader.service.impl.notification;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.lv.tool.privatereader.config.PrivateReaderConfig;
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
     * @param pageIndex    当前页索引(0 基,用于按钮与页码显示)
     * @param pages       当前分页结果(用于按钮可用性判断)
     * @param showButtons 是否显示导航操作按钮
     * @param showPageNumbers 是否在标题显示页码
     * @param prevPageAction 上一页回调(可为 null)
     * @param nextPageAction 下一页回调(可为 null)
     * @param prevChapterAction 上一章回调(可为 null)
     * @param nextChapterAction 下一章回调(可为 null)
     * @param switchBackAction  返回阅读器回调(可为 null)
     * @return 已显示的通知
     */
    @NotNull
    public static Notification showReadingNotification(@NotNull Project project,
                                                       @NotNull String title,
                                                       @NotNull String content,
                                                       int pageIndex,
                                                       @NotNull java.util.List<String> pages,
                                                       boolean showButtons,
                                                       boolean showPageNumbers,
                                                       @Nullable Runnable prevPageAction,
                                                       @Nullable Runnable nextPageAction,
                                                       @Nullable Runnable prevChapterAction,
                                                       @Nullable Runnable nextChapterAction,
                                                       @Nullable Runnable switchBackAction) {
        String displayTitle = buildDisplayTitle(title, pageIndex, pages.size(), showPageNumbers);

        String cleanContent = cleanHtmlTags(content);
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID_READER)
                .createNotification(cleanContent, NotificationType.INFORMATION)
                .setTitle(displayTitle);

        if (showButtons) {
            addReadingActions(notification, pageIndex, pages.size(),
                    prevPageAction, nextPageAction, prevChapterAction, nextChapterAction, switchBackAction);
        }

        com.intellij.notification.Notifications.Bus.notify(notification, project);
        return notification;
    }

    /**
     * 构建通知展示标题(纯函数,便于单元测试)。
     * <p>
     * 规则:首页显示原标题,后续页显示"阅读中";开启页码显示时追加 "(第X页/共Y页)"。
     *
     * @param title          原始标题(如 "书名 - 章节名")
     * @param pageIndex      当前页索引(0 基)
     * @param totalPages     总页数
     * @param showPageNumbers 是否显示页码
     * @return 展示标题
     */
    @NotNull
    public static String buildDisplayTitle(@NotNull String title, int pageIndex, int totalPages, boolean showPageNumbers) {
        String displayTitle = (pageIndex == 0) ? title : "阅读中";
        if (showPageNumbers) {
            displayTitle += String.format(" (第%d页/共%d页)", pageIndex + 1, totalPages);
        }
        return displayTitle;
    }

    /**
     * 判断上一页按钮是否可用(纯函数)。
     *
     * @param pageIndex 当前页索引(0 基)
     * @return 非第一页时可用
     */
    public static boolean isPrevPageActionEnabled(int pageIndex) {
        return pageIndex > 0;
    }

    /**
     * 判断下一页按钮是否可用(纯函数)。
     *
     * @param pageIndex  当前页索引(0 基)
     * @param totalPages 总页数
     * @return 非最后一页时可用
     */
    public static boolean isNextPageActionEnabled(int pageIndex, int totalPages) {
        return pageIndex < totalPages - 1;
    }

    /**
     * 添加阅读通知的操作按钮:上一页/下一页(按可用性),上一章/下一章,返回阅读器。
     * 动作回调由调用方提供,本类不再直接依赖 IntelliJ 服务容器(消除 getService 硬编码)。
     */
    private static void addReadingActions(@NotNull Notification notification,
                                          int pageIndex,
                                          int pageCount,
                                          @Nullable Runnable prevPageAction,
                                          @Nullable Runnable nextPageAction,
                                          @Nullable Runnable prevChapterAction,
                                          @Nullable Runnable nextChapterAction,
                                          @Nullable Runnable switchBackAction) {
        if (isPrevPageActionEnabled(pageIndex) && prevPageAction != null) {
            notification.addAction(NotificationAction.createSimple("上一页", () -> prevPageAction.run()));
        }
        if (isNextPageActionEnabled(pageIndex, pageCount) && nextPageAction != null) {
            notification.addAction(NotificationAction.createSimple("下一页", () -> nextPageAction.run()));
        }
        if (prevChapterAction != null) {
            notification.addAction(NotificationAction.createSimple("上一章", () -> prevChapterAction.run()));
        }
        if (nextChapterAction != null) {
            notification.addAction(NotificationAction.createSimple("下一章", () -> nextChapterAction.run()));
        }
        if (switchBackAction != null) {
            notification.addAction(NotificationAction.createSimple("返回阅读器", () -> switchBackAction.run()));
        }
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