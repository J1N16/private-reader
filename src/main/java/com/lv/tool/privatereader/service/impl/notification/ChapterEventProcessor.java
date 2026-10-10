package com.lv.tool.privatereader.service.impl.notification;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.lv.tool.privatereader.events.ChapterChangeEventSource;
import com.lv.tool.privatereader.events.ChapterChangeManager;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookProgressData;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import com.lv.tool.privatereader.service.ChapterService;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * 章节变更事件处理器。
 * <p>
 * 从 {@code NotificationServiceImpl} 中拆分出的"跨模式章节同步"职责(V16):
 * 阅读器面板切换章节时,若当前处于通知栏模式,则拉取新章节内容、恢复页码、
 * 更新通知栏展示并保存进度。
 * <p>
 * 本类不持有通知/状态字段,通过 {@link Host} 窄接口回调宿主服务,
 * 使事件处理逻辑可独立测试与演进,同时显著缩小 {@code NotificationServiceImpl}。
 */
public final class ChapterEventProcessor {
    private static final Logger LOG = Logger.getInstance(ChapterEventProcessor.class);

    /**
     * 事件处理器与宿主服务之间的窄接口。
     * <p>
     * 仅暴露事件处理所需的最小能力,避免处理器直接依赖 {@code NotificationServiceImpl}
     * 的完整 API(通知引用、分页缓存、消息总线等均不在此列)。
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

        /** 保存通知栏模式阅读进度 */
        void saveNotificationModeProgress();
    }

    private final Host host;
    private final ChapterService chapterService;
    private final NotificationReaderSettings notificationSettings;
    private final ChapterChangeManager chapterChangeManager;
    private final ReadingProgressRepository readingProgressRepository;

    /** 防止重入:同一时刻只处理一个章节变更事件 */
    private final AtomicBoolean isHandlingEvent = new AtomicBoolean(false);

    public ChapterEventProcessor(@NotNull Host host,
                                 @NotNull ChapterService chapterService,
                                 @NotNull NotificationReaderSettings notificationSettings,
                                 @NotNull ChapterChangeManager chapterChangeManager,
                                 @NotNull ReadingProgressRepository readingProgressRepository) {
        this.host = host;
        this.chapterService = chapterService;
        this.notificationSettings = notificationSettings;
        this.chapterChangeManager = chapterChangeManager;
        this.readingProgressRepository = readingProgressRepository;
    }

    /**
     * 处理来自阅读器面板的章节变更事件:在通知栏模式下同步展示新章节。
     * <p>
     * 事件源不是 {@link ChapterChangeEventSource#READER_PANEL} 时直接忽略(避免自身发布的事件回环)。
     *
     * @param changedBook 变更后的书籍
     * @param newChapter  变更后的章节
     */
    public void handleChapterChangedEvent(Book changedBook, Chapter newChapter) {
        if (chapterChangeManager.getLastEventSource() != ChapterChangeEventSource.READER_PANEL) {
            return;
        }

        if (!isHandlingEvent.compareAndSet(false, true)) {
            LOG.debug("[事件处理] 正在处理另一个章节变更事件,忽略当前事件。");
            return;
        }

        try {
            LOG.debug("[事件处理] 接收到章节变更事件: 书籍={}, 新章节={}", changedBook.getTitle(), newChapter.title());

            ReaderModeSettings readerModeSettings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
            if (readerModeSettings == null || !readerModeSettings.isNotificationMode()) {
                LOG.debug("[事件处理] 非通知栏模式,忽略章节变更事件。");
                return;
            }

            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            if (openProjects.length == 0) {
                LOG.warn("[事件处理] 没有打开的项目,无法处理章节变更事件并更新通知。");
                return;
            }
            Project project = openProjects[0]; // 默认使用第一个打开的项目
            if (openProjects.length > 1) {
                LOG.warn("[事件处理] 有多个项目打开,将使用第一个项目: " + project.getName() + " 来更新通知。");
            }

            ReaderViewState currentState = host.getViewState();
            // 检查书籍或章节是否真的改变了
            if (currentState.getBook() != null && currentState.getBook().equals(changedBook) &&
                currentState.getChapterId() != null && currentState.getChapterId().equals(newChapter.url())) {
                LOG.debug("[事件处理] 书籍和章节未发生变化,无需更新通知。");
                return;
            }

            LOG.info("[事件处理] 检测到章节变更 (之前: 书籍='" + (currentState.getBook() != null ? currentState.getBook().getTitle() : "无") +
                     "', 章节ID='" + (currentState.getChapterId() != null ? currentState.getChapterId() : "无") +
                     "'; 现在: 书籍='" + changedBook.getTitle() +
                     "', 章节='" + newChapter.title() + "'), 准备在通知栏模式下更新显示。");

            // 获取新章节内容 - 使用单一响应式链,减少线程切换
            chapterService.getChapterContent(changedBook, newChapter.url())
                .subscribeOn(Schedulers.io()) // 在IO线程执行耗时操作
                .flatMap(content -> {
                    if (content == null || content.isEmpty()) {
                        LOG.warn("[事件处理] 获取到的新章节 '" + newChapter.title() + "' 内容为空,不更新通知。");
                        return Single.error(new IllegalStateException("章节内容为空"));
                    }

                    // 异步获取章节标题
                    return chapterService.getChapterTitle(changedBook.getId(), newChapter.url())
                        .map(fetchedTitle -> {
                            if (fetchedTitle == null || fetchedTitle.isEmpty() || fetchedTitle.startsWith("Error:")) {
                                LOG.warn("[事件处理] 获取到的章节标题无效 ('" + fetchedTitle + "'),回退到 newChapter.title()");
                                return newChapter.title();
                            }
                            return fetchedTitle;
                        })
                        .onErrorReturnItem(newChapter.title()) // 如果获取标题时出错,也使用默认标题
                        .map(finalTitle -> new Object[]{content, finalTitle}); // 将内容和最终标题传递下去
                })
                .observeOn(Schedulers.io())
                .subscribe(
                    data -> {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                String content = (String) data[0];
                                String fetchedTitle = (String) data[1];

                                // 更新当前状态
                                host.updateViewState(s -> s.withChapter(changedBook, newChapter.url(), fetchedTitle));

                                // 分页
                                host.setCurrentChapterContent(content);
                                List<String> pages = host.getViewState().getPages();
                                if (pages.isEmpty()) {
                                    LOG.warn("[事件处理] 新章节 '" + newChapter.title() + "' 分页后内容为空,无法显示通知。");
                                    host.showError("章节内容为空", "无法在通知栏显示章节 " + newChapter.title());
                                    return;
                                }

                                // 尝试恢复页码,否则显示第一页
                                int pageToLoad = 1;
                                try {
                                    if (readingProgressRepository != null) {
                                        Optional<BookProgressData> progressDataOpt = readingProgressRepository.getProgress(changedBook.getId());
                                        if (progressDataOpt.isPresent()) {
                                            BookProgressData progressData = progressDataOpt.get();
                                            if (newChapter.url().equals(progressData.lastReadChapterId())) {
                                                pageToLoad = progressData.lastReadPage();
                                                LOG.debug("[事件处理] 成功恢复页码: " + pageToLoad + " for chapter " + newChapter.title());
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    LOG.error("[事件处理] 恢复页码时出错", e);
                                }

                                if (pageToLoad <= 0) {
                                    pageToLoad = 1;
                                } else if (!pages.isEmpty() && pageToLoad > pages.size()) {
                                    pageToLoad = pages.size();
                                } else if (pages.isEmpty()) {
                                    pageToLoad = 1;
                                }

                                int pageIndex = pageToLoad - 1;
                                host.updateViewState(s -> s.withPageIndex(pageIndex));

                                String pageContentToShow = pages.get(pageIndex);
                                String progressText = notificationSettings != null && notificationSettings.isShowReadingProgress() ?
                                     "进度: 第 " + (pageIndex + 1) + " 页,共 " + pages.size() + " 页" : "";
                                String notificationContent = pageContentToShow + (progressText.isEmpty() ? "" : "\n\n" + progressText);

                                host.showCurrentPageInternal(project, fetchedTitle, notificationContent);
                                LOG.info("[事件处理] 通知栏已更新到新章节 '" + newChapter.title() + "' 的第一页。");
                                host.saveNotificationModeProgress(); // 保存进度
                            } catch (Throwable t) {
                                LOG.error("[事件处理] 在处理章节内容时发生未捕获的错误: " + t.getMessage(), t);
                            }
                        }, ModalityState.defaultModalityState());
                    },
                    error -> {
                        LOG.error("[事件处理] 获取或处理新章节 '" + newChapter.title() + "' 内容失败: " + error.getMessage(), error);
                        host.showError("加载章节失败", "无法加载章节 " + newChapter.title() + " 的内容: " + error.getMessage());
                    }
                );
        } finally {
            isHandlingEvent.set(false);
        }
    }
}