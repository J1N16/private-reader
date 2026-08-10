package com.lv.tool.privatereader.service;

import com.intellij.notification.Notification;
import com.lv.tool.privatereader.model.Book;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import org.jetbrains.annotations.NotNull;

/**
 * 通知服务接口
 * 提供通知相关的服务，支持通知栏阅读模式
 */
public interface NotificationService {

    /**
     * 设置当前章节内容，用于通知栏模式的内部分页
     * @param content 章节的完整内容
     */
    void setCurrentChapterContent(@NotNull String content);

    /**
     * 计算给定内容的总页数
     * @param content 章节内容
     * @return 总页数
     */
    int calculateTotalPages(@NotNull String content);

    /**
     * 获取给定章节内容指定页码的内容
     * @param content 章节内容
     * @param pageNumber 页码 (1-based)
     * @return 指定页码的内容
     */
    String getPageContent(@NotNull String content, int pageNumber);

    /**
     * 显示章节内容通知，用于通知栏模式
     * @param project 当前项目
     * @param bookId 书籍ID
     * @param chapterId 章节ID
     * @param pageNumber 起始页码
     * @param title 通知标题
     * @param content 通知内容
     */
    void showChapterContent(@NotNull com.intellij.openapi.project.Project project, @NotNull String bookId, @NotNull String chapterId, int pageNumber, @NotNull String title, @NotNull String content);

    /**
     * 更新现有通知的内容
     * @param project 当前项目
     * @param content 新的通知内容
     */
    void updateNotificationContent(@NotNull com.intellij.openapi.project.Project project, @NotNull String content);

    /**
     * 关闭所有通知
     */
    void closeAllNotifications();

    /**
     * 获取当前通知显示的页码 (用于通知栏模式)
     *
     * @return 当前页码
     */
    int getCurrentPage();

    /**
     * 获取通知内容的总页数 (用于通知栏模式)
     *
     * @return 总页数
     */
    int getTotalPages();

    /**
     * 获取当前通知显示的书籍ID (用于通知栏模式)
     * @return 书籍ID
     */
    String getCurrentBookId();

    /**
     * 获取当前通知显示的章节ID (用于通知栏模式)
     * @return 章节ID
     */
    String getCurrentChapterId();

    /**
     * 显示上一页内容并更新通知 (用于通知栏模式)
     * @param project 当前项目
     */
    void showPrevPage(@NotNull com.intellij.openapi.project.Project project);

    /**
     * 显示下一页内容并更新通知 (用于通知栏模式)
     * @param project 当前项目
     */
    void showNextPage(@NotNull com.intellij.openapi.project.Project project);

    /**
     * 导航到上一章或下一章并更新通知 (用于通知栏模式)
     * @param project 当前项目
     * @param direction 方向，-1 表示上一章，1 表示下一章
     */
    void navigateChapter(@NotNull com.intellij.openapi.project.Project project, int direction);

    /**
     * 显示加载状态通知
     * @param project 当前项目
     * @param message 加载消息
     */
    void showLoadingNotification(@NotNull com.intellij.openapi.project.Project project, @NotNull String message);

    // Existing reactive methods (kept for compatibility if still used elsewhere)
    Single<Notification> showChapterContent(@NotNull Book book, @NotNull String chapterId, @NotNull String content);
    Single<Notification> showError(@NotNull String title, @NotNull String message);
    Single<Notification> showInfo(@NotNull String title, @NotNull String message);
    Completable closeAllNotificationsReactive();
}
