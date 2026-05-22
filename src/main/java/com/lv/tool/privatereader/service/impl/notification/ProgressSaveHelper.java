package com.lv.tool.privatereader.service.impl.notification;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.repository.impl.SqliteReadingProgressRepository;
import com.lv.tool.privatereader.service.BookService;
import org.jetbrains.annotations.NotNull;

/**
 * 进度保存工具类
 * 提取重复的进度保存逻辑
 */
public final class ProgressSaveHelper {
    private static final Logger LOG = Logger.getInstance(ProgressSaveHelper.class);

    private ProgressSaveHelper() {}

    /**
     * 保存阅读进度（使用直接页码）
     * 注意：不使用 bookService.saveReadingProgress 方法，因为它会将 currentPageIndex 加1
     *
     * @param book 当前书籍
     * @param chapterId 章节ID
     * @param chapterTitle 章节标题
     * @param pageIndex 当前页码索引（0基）
     */
    public static void saveProgress(@NotNull Book book, @NotNull String chapterId, @NotNull String chapterTitle, int pageIndex) {
        SqliteReadingProgressRepository readingProgressRepository = ApplicationManager.getApplication().getService(SqliteReadingProgressRepository.class);
        if (readingProgressRepository != null) {
            // 使用带页码参数的重载方法，position设为0，直接使用pageIndex + 1作为页码
            readingProgressRepository.updateProgress(book, chapterId, chapterTitle, 0, pageIndex + 1);
            LOG.debug(String.format("[页码调试] 直接保存页码: %d", pageIndex + 1));
        } else {
            LOG.warn("[页码调试] 无法获取 SqliteReadingProgressRepository 实例，使用 bookService.saveReadingProgress 方法");
            BookService bookService = ApplicationManager.getApplication().getService(BookService.class);
            if (bookService != null) {
                bookService.saveReadingProgress(book, chapterId, chapterTitle, pageIndex)
                    .subscribe(
                        () -> LOG.debug(String.format("[页码调试] 通过 BookService 保存页码: %d", pageIndex + 1)),
                        error -> LOG.warn("[页码调试] BookService 保存阅读进度失败", error)
                    );
            }
        }
    }

    /**
     * 构建通知内容
     *
     * @param pageContent 页面内容
     * @param pageIndex 当前页码索引（0基）
     * @param totalPages 总页数
     * @param showProgress 是否显示进度
     * @return 格式化后的通知内容
     */
    @NotNull
    public static String buildNotificationContent(@NotNull String pageContent, int pageIndex, int totalPages, boolean showProgress) {
        if (!showProgress) {
            return pageContent;
        }
        String progressText = String.format("进度: 第 %d 页，共 %d 页", pageIndex + 1, totalPages);
        return pageContent + "\n\n" + progressText;
    }

    /**
     * 构建通知标题
     *
     * @param bookTitle 书籍标题
     * @param chapterTitle 章节标题
     * @return 格式化后的标题
     */
    @NotNull
    public static String buildNotificationTitle(@NotNull String bookTitle, @NotNull String chapterTitle) {
        return bookTitle + " - " + chapterTitle;
    }
}
