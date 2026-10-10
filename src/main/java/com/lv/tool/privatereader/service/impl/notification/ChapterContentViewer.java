package com.lv.tool.privatereader.service.impl.notification;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.lv.tool.privatereader.async.ReactiveSchedulers;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 章节内容展示器(V20 从 {@code NotificationServiceImpl} 抽出)。
 * <p>
 * 承接接口 {@code NotificationService.showChapterContent(Project, bookId, chapterId, pageNumber,
 * title, content)} 的完整展示流水线:
 * <ol>
 *   <li>异步按 bookId 获取 {@link Book}</li>
 *   <li>在 UI 线程关闭旧通知并写入书籍/章节状态</li>
 *   <li>恢复保存页码(仅当传入页码为默认值 1)并按需分页</li>
 *   <li>页码区间校验 → 构建通知标题/内容 → 展示当前页</li>
 *   <li>触发章节预加载并保存阅读进度</li>
 * </ol>
 * 通过窄接口 {@link Host} 回调宿主服务完成状态读写与通知展示,使展示流水线可独立测试,
 * 同时进一步缩小 {@code NotificationServiceImpl}。
 */
public final class ChapterContentViewer {
    private static final Logger LOG = Logger.getInstance(ChapterContentViewer.class);

    /**
     * 展示器与宿主服务之间的窄接口。
     * <p>
     * 仅暴露展示所需的最小能力(状态读写、分页、展示、关闭通知、预加载),
     * 避免展示器直接依赖 {@code NotificationServiceImpl} 的完整 API。
     */
    public interface Host {
        /** 读取当前阅读状态不可变快照 */
        @NotNull
        ReaderViewState getViewState();

        /** 以不可变拷贝方式更新阅读状态 */
        void updateViewState(@NotNull UnaryOperator<ReaderViewState> updater);

        /** 对章节内容重新分页并写入状态 */
        void setCurrentChapterContent(@NotNull String content);

        /** 展示当前页通知 */
        void showCurrentPageInternal(@NotNull Project project, @NotNull String title, @NotNull String content);

        /** 展示错误通知 */
        void showError(@NotNull String title, @NotNull String message);

        /** 关闭当前通知(不重置阅读状态) */
        void closeCurrentNotificationInternal();

        /** 触发章节预加载 */
        void triggerChapterPreload(@NotNull Book book, int chapterIndex);
    }

    private final Host host;
    private final BookService bookService;
    private final NotificationReaderSettings notificationSettings;
    private final ReactiveSchedulers reactiveSchedulers;
    private final NotificationProgressManager progressManager;

    public ChapterContentViewer(@NotNull Host host,
                                @NotNull BookService bookService,
                                @NotNull NotificationReaderSettings notificationSettings,
                                @NotNull ReactiveSchedulers reactiveSchedulers,
                                @NotNull NotificationProgressManager progressManager) {
        this.host = host;
        this.bookService = bookService;
        this.notificationSettings = notificationSettings;
        this.reactiveSchedulers = reactiveSchedulers;
        this.progressManager = progressManager;
    }

    /**
     * 显示章节内容通知。
     *
     * @param project    当前项目
     * @param bookId     书籍ID
     * @param chapterId  章节ID
     * @param pageNumber 起始页码(1 基);为 1 时尝试从仓库恢复保存页码
     * @param title      章节标题
     * @param content    章节完整内容
     */
    public void showChapterContent(@NotNull Project project, @NotNull String bookId, @NotNull String chapterId,
                                   int pageNumber, @NotNull String title, @NotNull String content) {
        LOG.debug("ChapterContentViewer: 显示章节内容通知: " + title);

        if (content == null || content.isEmpty()) {
            LOG.warn("章节内容为空,无法显示通知: " + chapterId);
            host.showError("显示章节失败", "章节内容为空");
            return;
        }

        // 异步获取Book对象
        bookService.getBookById(bookId)
            .subscribeOn(reactiveSchedulers.io())
            .subscribe(book -> {
                if (book == null) {
                    LOG.warn("未找到书籍,无法显示通知: " + bookId);
                    host.showError("显示章节失败", "未找到书籍");
                    return;
                }

                // 在UI线程中执行UI更新操作
                ApplicationManager.getApplication().invokeLater(() -> {
                    // 关闭当前通知
                    host.closeCurrentNotificationInternal();

                    // 保存当前书籍、章节和标题信息
                    host.updateViewState(s -> s.withChapter(book, chapterId, title)); // 使用传入的标题

                    // 检查是否有保存的页码信息
                    int savedPageNumber = pageNumber;

                    // 如果传入的页码是1(默认值),尝试从数据库中获取保存的页码
                    if (pageNumber == 1) {
                        savedPageNumber = progressManager.restoreSavedPageNumber(bookId, chapterId, pageNumber);
                    }

                    // 分页并设置当前页码
                    host.setCurrentChapterContent(content);

                    // 确保页码在有效范围内
                    List<String> pages = host.getViewState().getPages();
                    if (savedPageNumber <= 0) {
                        savedPageNumber = 1;
                    } else if (savedPageNumber > pages.size()) {
                        savedPageNumber = pages.size();
                    }

                    // 设置当前页码索引(0-based)
                    int pageIndex = savedPageNumber - 1;
                    final int finalPageIndex = pageIndex;
                    host.updateViewState(s -> s.withPageIndex(finalPageIndex));
                    LOG.debug(String.format("[页码调试] 设置当前页码索引: %d (页码: %d)", pageIndex, savedPageNumber));

                    // 获取当前页内容
                    String pageContent = host.getViewState().getPages().get(pageIndex);

                    // 构建通知标题和内容
                    String notificationTitle = book.getTitle() + " - " + title;

                    // 添加页码信息(如果设置启用)
                    String progressText = notificationSettings != null && notificationSettings.isShowReadingProgress() ?
                            "进度: 第 " + (pageIndex + 1) + " 页,共 " + pages.size() + " 页" : "";

                    String notificationContent = pageContent + (progressText.isEmpty() ? "" : "\n\n" + progressText);

                    // 显示通知
                    host.showCurrentPageInternal(project, notificationTitle, notificationContent);

                    // 触发章节预加载(宿主方法内部已对未初始化的预加载器做保护)
                    try {
                        List<NovelParser.Chapter> cachedChapters = book.getCachedChapters();
                        if (cachedChapters != null) {
                            int chapterIndex = -1;
                            for (int i = 0; i < cachedChapters.size(); i++) {
                                if (chapterId.equals(cachedChapters.get(i).url())) {
                                    chapterIndex = i;
                                    break;
                                }
                            }

                            if (chapterIndex != -1) {
                                LOG.info("[通知栏模式] 触发章节预加载: 书籍=" + book.getTitle() + ", 章节索引=" + chapterIndex);
                                host.triggerChapterPreload(book, chapterIndex);
                            }
                        }
                    } catch (Exception e) {
                        LOG.error("[通知栏模式] 触发章节预加载时出错", e);
                    }

                    // 保存阅读进度
                    progressManager.saveProgress();

                    // 记录事件
                    LOG.info("[事件处理] 通知栏已更新到新章节 '" + title + "' 的第" + savedPageNumber + "页。");
                }, ModalityState.defaultModalityState());
            }, error -> {
                LOG.error("获取书籍对象失败: " + error.getMessage(), error);
                host.showError("显示章节失败", "获取书籍信息时出错: " + error.getMessage());
            });
    }
}