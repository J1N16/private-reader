package com.lv.tool.privatereader.service.impl.notification;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.lv.tool.privatereader.async.ReactiveSchedulers;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.service.ChapterService;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * 章节导航器。
 * <p>
 * 从 {@code NotificationServiceImpl} 中拆分出的"上一章/下一章/跳转上一章末页"导航流水线(V17):
 * <ul>
 *   <li>{@link #navigateChapter} — 导航到目标章节第一页</li>
 *   <li>{@link #navigateToLastPage} — 导航到目标章节最后一页(首页再往前翻时触发)</li>
 *   <li>两种章节数据源收敛:{@code Book.cachedChapters}(内存)与 {@code BookService.getChaptersSync}(异步获取)</li>
 *   <li>统一展示流水线 {@code showNavigatedChapter}:更新状态 → 分页 → 显示通知 → 保存进度 → 预加载 → 发布事件</li>
 * </ul>
 * 本类不持有通知引用/分页缓存等状态,通过 {@link Host} 窄接口回调宿主服务,
 * 使导航逻辑可独立测试,同时进一步缩小 {@code NotificationServiceImpl}。
 */
public final class ChapterNavigator {
    private static final Logger LOG = Logger.getInstance(ChapterNavigator.class);

    /**
     * 导航器与宿主服务之间的窄接口。
     * <p>
     * 仅暴露导航所需的最小能力(状态读写、分页、展示、通知发布与预加载),
     * 避免导航器直接依赖 {@code NotificationServiceImpl} 的完整 API。
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

        /** 展示信息通知 */
        void showInfo(@NotNull String title, @NotNull String message);

        /** 展示加载中通知(并置位加载标志) */
        void showLoadingNotification(@NotNull Project project, @NotNull String message);

        /** 触发章节预加载 */
        void triggerChapterPreload(@NotNull Book book, int chapterIndex);

        /** 设置事件源并发布章节变更事件 */
        void publishChapterChanged(@NotNull Book book, @NotNull NovelParser.Chapter chapter);
    }

    private final Host host;
    private final BookService bookService;
    private final NotificationReaderSettings notificationSettings;
    private final ReactiveSchedulers reactiveSchedulers;

    public ChapterNavigator(@NotNull Host host,
                            @NotNull BookService bookService,
                            @NotNull NotificationReaderSettings notificationSettings,
                            @NotNull ReactiveSchedulers reactiveSchedulers) {
        this.host = host;
        this.bookService = bookService;
        this.notificationSettings = notificationSettings;
        this.reactiveSchedulers = reactiveSchedulers;
    }

    /**
     * 导航到相邻章节的第一页。
     * <p>
     * 优先使用 {@code Book.cachedChapters};为空时异步调用 {@code BookService.getChaptersSync}。
     * 调用方负责活动会话与加载状态门控。
     *
     * @param project   当前项目
     * @param direction 方向,-1 表示上一章,1 表示下一章
     */
    public void navigateChapter(@NotNull Project project, int direction) {
        host.showLoadingNotification(project, "正在加载章节...");

        Book book = host.getViewState().getBook();
        List<Chapter> cachedChapters = book.getCachedChapters();
        if (cachedChapters != null && !cachedChapters.isEmpty()) {
            LOG.debug("使用Book中的cachedChapters进行导航,章节数量: " + cachedChapters.size());
            reactiveSchedulers.runOnUI(() -> processChapterNavigationWithCachedChapters(project, cachedChapters, direction, false));
        } else {
            LOG.debug("Book中的cachedChapters为空,使用bookService.getChaptersSync获取章节列表");
            Single.fromCallable(() -> bookService.getChaptersSync(book.getId()))
                .subscribeOn(Schedulers.io())
                .timeout(30, TimeUnit.SECONDS)
                .doOnError(e -> reactiveSchedulers.runOnUI(() -> host.showError("导航失败", "获取章节列表时出错: " + e.getMessage())))
                .subscribe(chapters -> reactiveSchedulers.runOnUI(() -> processChapterNavigation(project, chapters, direction, false)));
        }
    }

    /**
     * 导航到相邻章节的最后一页(首页继续往前翻时触发)。
     * <p>
     * 与 {@link #navigateChapter} 相同的数据源选择策略,区别在于定位到目标章节末页。
     * 调用方负责活动会话与加载状态门控。
     *
     * @param project   当前项目
     * @param direction 方向,-1 表示上一章,1 表示下一章
     */
    public void navigateToLastPage(@NotNull Project project, int direction) {
        LOG.info("[通知栏模式] 导航到章节的最后一页,方向: " + direction);

        ReaderViewState state = host.getViewState();
        if (state.getBook() == null || state.getChapterId() == null) {
            LOG.warn("[通知栏模式] 当前没有正在阅读的内容");
            host.showInfo("导航", "当前没有正在阅读的内容");
            return;
        }
        Book book = state.getBook();

        // 显示加载状态通知
        host.showLoadingNotification(project, "正在加载章节...");

        // 首先尝试使用Book中的cachedChapters
        List<NovelParser.Chapter> cachedChapters = book.getCachedChapters();
        if (cachedChapters != null && !cachedChapters.isEmpty()) {
            LOG.debug("使用Book中的cachedChapters导航到最后一页,章节数量: " + cachedChapters.size());
            // 在UI线程上处理导航逻辑
            reactiveSchedulers.runOnUI(() -> processChapterNavigationWithCachedChapters(project, cachedChapters, direction, true));
            return;
        }

        // 如果cachedChapters为空,则使用异步方式获取章节列表
        LOG.debug("Book中的cachedChapters为空,使用bookService.getChaptersSync获取章节列表");
        Single.fromCallable(() -> bookService.getChaptersSync(book.getId()))
            .subscribeOn(Schedulers.io()) // 在IO线程上执行
            .timeout(30, TimeUnit.SECONDS) // 设置超时
            .doOnError(e -> {
                LOG.error("[通知栏模式] 获取章节列表时出错: " + e.getMessage(), e);
                reactiveSchedulers.runOnUI(() -> host.showError("导航失败", "获取章节列表时出错: " + e.getMessage()));
            })
            .subscribe(chapters -> {
                // 在获取到章节列表后,在UI线程上处理导航逻辑
                reactiveSchedulers.runOnUI(() -> processChapterNavigation(project, chapters, direction, true));
            });
    }

    /**
     * 解析导航目标索引:查找当前章节索引 → 验证导航方向 → 计算目标索引。
     * 验证失败时弹通知并返回 -1(调用方应停止)。
     *
     * @param chapterUrls  章节 URL 列表
     * @param direction    导航方向,-1 表示上一章,1 表示下一章
     * @param totalChapters 章节总数
     * @param logPrefix    日志前缀(区分调用场景)
     * @return 目标章节索引;验证失败时返回 -1
     */
    private int resolveNavigationTarget(List<String> chapterUrls, int direction, int totalChapters, String logPrefix) {
        ReaderViewState state = host.getViewState();
        int currentIndex = ChapterNavigationHelper.findChapterIndex(state.getBook(), state.getChapterId(), chapterUrls);

        String validationError = ChapterNavigationHelper.validateNavigation(currentIndex, direction, totalChapters);
        if (validationError != null) {
            LOG.warn(logPrefix + validationError);
            if (currentIndex < 0) {
                host.showError("导航失败", validationError);
            } else {
                host.showInfo("导航", validationError);
            }
            return -1;
        }
        return ChapterNavigationHelper.calculateTargetIndex(currentIndex, direction);
    }

    /**
     * 处理章节导航逻辑(同步数据源,章节内容已包含在 {@link ChapterService.EnhancedChapter} 中)。
     * 在UI线程上执行。
     *
     * @param project           当前项目
     * @param chapters          章节列表
     * @param direction         导航方向
     * @param navigateToLastPage 是否导航到目标章节的最后一页(否则第一页)
     */
    private void processChapterNavigation(@NotNull Project project,
                                         @Nullable List<ChapterService.EnhancedChapter> chapters,
                                         int direction,
                                         boolean navigateToLastPage) {
        if (chapters == null || chapters.isEmpty()) {
            LOG.warn("章节列表为空");
            host.showInfo("导航", "章节列表为空");
            return;
        }

        // 提取章节URL列表
        List<String> chapterUrls = chapters.stream().map(ChapterService.EnhancedChapter::url).toList();

        int targetIndex = resolveNavigationTarget(chapterUrls, direction, chapters.size(), "");
        if (targetIndex < 0) {
            return;
        }

        // Get the target chapter and its content
        ChapterService.EnhancedChapter targetChapter = chapters.get(targetIndex);
        NovelParser.Chapter eventChapter = new NovelParser.Chapter(targetChapter.title(), targetChapter.url());
        showNavigatedChapter(project, eventChapter, targetChapter.getContent(), targetIndex, navigateToLastPage, "");
    }

    /**
     * 使用Book中的cachedChapters处理章节导航逻辑(异步数据源,章节内容需从 parser 获取)。
     * 在UI线程上执行。
     *
     * @param project           当前项目
     * @param cachedChapters    缓存的章节列表
     * @param direction         导航方向
     * @param navigateToLastPage 是否导航到目标章节的最后一页(否则第一页)
     */
    private void processChapterNavigationWithCachedChapters(@NotNull Project project,
                                                          @Nullable List<Chapter> cachedChapters,
                                                          int direction,
                                                          boolean navigateToLastPage) {
        if (cachedChapters == null || cachedChapters.isEmpty()) {
            LOG.warn("缓存的章节列表为空,无法导航");
            host.showInfo("导航", "章节列表为空");
            return;
        }

        // 提取章节URL列表
        List<String> chapterUrls = cachedChapters.stream().map(Chapter::url).toList();

        int targetIndex = resolveNavigationTarget(chapterUrls, direction, cachedChapters.size(), "[通知栏模式] ");
        if (targetIndex < 0) {
            return;
        }

        // 获取目标章节
        Chapter targetChapter = cachedChapters.get(targetIndex);
        String targetChapterId = targetChapter.url();

        // 显示加载状态通知
        host.showLoadingNotification(project, "正在加载章节内容...");

        Book book = host.getViewState().getBook();
        // 使用异步方式获取章节内容,避免阻塞UI线程
        Single.fromCallable(() -> {
            if (book.getParser() != null) {
                return book.getParser().getChapterContent(targetChapterId, book);
            }
            return null;
        })
        .subscribeOn(Schedulers.io())
        .timeout(30, TimeUnit.SECONDS)
        .doOnError(e -> {
            LOG.error("获取章节内容时出错: " + e.getMessage(), e);
            reactiveSchedulers.runOnUI(() -> host.showError("导航失败", "获取章节内容时出错: " + e.getMessage()));
        })
        .subscribe(content -> {
            reactiveSchedulers.runOnUI(() ->
                showNavigatedChapter(project, targetChapter, content, targetIndex, navigateToLastPage, "cachedChapters"));
        });
    }

    /**
     * 处理章节导航后的统一展示流程。
     * <p>
     * 由 4 组导航方法共享的"更新状态 → 分页 → 显示通知 → 保存进度 → 预加载 → 发布事件"流水线。
     * 同步(EnhancedChapter)与异步(cachedChapters)两个数据源均收敛到此唯一入口。
     *
     * @param project 当前项目
     * @param targetChapter 目标章节(含 url/title,用于发布事件)
     * @param targetChapterContent 目标章节内容
     * @param targetIndex 目标章节索引
     * @param navigateToLastPage 是否导航到最后一页(否则导航到第一页)
     * @param sourceLogTag 日志来源标识(数据源差异,如 "cachedChapters")
     */
    private void showNavigatedChapter(@NotNull Project project,
                                      @NotNull NovelParser.Chapter targetChapter,
                                      @NotNull String targetChapterContent,
                                      int targetIndex,
                                      boolean navigateToLastPage,
                                      @NotNull String sourceLogTag) {
        String targetChapterId = targetChapter.url();
        String targetChapterTitle = targetChapter.title();

        if (targetChapterContent == null || targetChapterContent.isEmpty()) {
            LOG.warn("[通知栏模式] 目标章节内容为空: " + targetChapterId);
            host.showError("导航失败", "目标章节内容为空");
            return;
        }

        Book book = host.getViewState().getBook();
        // 更新当前书籍、章节信息
        host.updateViewState(s -> s.withChapter(book, targetChapterId, targetChapterTitle));

        // 分页并定位到目标页
        host.setCurrentChapterContent(targetChapterContent);
        List<String> pages = host.getViewState().getPages();
        if (pages.isEmpty()) {
            LOG.warn("[通知栏模式] 分页后内容为空,无法显示通知: " + targetChapterId);
            host.showError("显示章节失败", "分页后内容为空");
            return;
        }
        int pageIndex = navigateToLastPage ? pages.size() - 1 : 0;
        host.updateViewState(s -> s.withPageIndex(pageIndex));

        // 使用工具类构建通知内容并显示
        String title = ProgressSaveHelper.buildNotificationTitle(book.getTitle(), targetChapterTitle);
        String notificationContent = ProgressSaveHelper.buildNotificationContent(
            pages.get(pageIndex), pageIndex, pages.size(),
            notificationSettings != null && notificationSettings.isShowReadingProgress());

        host.showCurrentPageInternal(project, title, notificationContent);

        // 使用工具类保存进度
        ProgressSaveHelper.saveProgress(book, targetChapterId, targetChapterTitle, pageIndex);

        LOG.info("[通知栏模式] 使用" + sourceLogTag + "导航到章节" + (navigateToLastPage ? "的最后一页" : "") + ": " + targetChapterId);

        // 触发章节预加载
        host.triggerChapterPreload(book, targetIndex);

        // 设置事件源并发布章节变更事件
        host.publishChapterChanged(book, targetChapter);
        LOG.info("[通知栏模式] 已发布章节变更事件: " + targetChapterTitle);
    }
}