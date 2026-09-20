package com.lv.tool.privatereader.service.impl;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.util.messages.MessageBusConnection;
import com.lv.tool.privatereader.async.ReactiveSchedulers;
import com.lv.tool.privatereader.config.PrivateReaderConfig;
import com.lv.tool.privatereader.events.ChapterChangeManager;
import com.lv.tool.privatereader.events.ChapterChangeEventSource;
import com.lv.tool.privatereader.messaging.CurrentChapterNotifier;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookProgressData;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import com.lv.tool.privatereader.repository.impl.SqliteReadingProgressRepository;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.service.ChapterService;
import com.lv.tool.privatereader.service.NotificationService;
import com.lv.tool.privatereader.service.impl.notification.ChapterNavigationHelper;
import com.lv.tool.privatereader.service.impl.notification.ChapterPaginationCache;
import com.lv.tool.privatereader.service.impl.notification.NotificationDisplayManager;
import com.lv.tool.privatereader.service.impl.notification.PaginationHelper;
import com.lv.tool.privatereader.service.impl.notification.ProgressSaveHelper;
import com.lv.tool.privatereader.service.impl.notification.ReaderViewState;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import com.lv.tool.privatereader.storage.cache.ReactiveChapterPreloader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import com.intellij.openapi.application.ModalityState;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NotificationService 实现类
 *
 * 注意:在保存阅读进度时,我们需要特别注意页码的处理。
 * 1. 从数据库中恢复的页码是1基索引,我们将其转换为0基索引的 pageIndex
 * 2. 在保存页码时,我们不能使用 bookService.saveReadingProgress 方法,因为它会将 pageIndex 加1
 * 3. 如果我们使用 bookService.saveReadingProgress 方法,它会再次将 pageIndex 加1,导致页码始终是1
 * 4. 我们应该直接使用 SqliteReadingProgressRepository 类的 updateProgress 方法的重载版本,直接传递页码参数
 *
 * V11 重构(V11:短期优化批次 1):
 * - 阅读状态收敛为单一不可变快照 {@link ReaderViewState},由 AtomicReference 承载,
 *   消除原多个 volatile 字段(book/chapterId/chapterTitle/pages/pageIndex)跨帧读写不一致的隐患。
 * - 通知展示逻辑(创建/显示/清理 HTML)拆分为 {@link NotificationDisplayManager},
 *   本类仅保留状态编排与业务流,职责更单一。
 */
@Service(Service.Level.APP)
public final class NotificationServiceImpl implements NotificationService, Disposable {
    private static final Logger LOG = Logger.getInstance(NotificationServiceImpl.class);
    // 通知相关字段
    private final AtomicReference<Notification> currentNotificationRef = new AtomicReference<>(null);
    private MessageBusConnection messageBusConnection;
    private final CompositeDisposable disposables = new CompositeDisposable();

    // 服务相关字段(V12 构造器注入改造:由容器注入,消除惰性 getService)
    private final BookService bookService;
    private final ChapterService chapterService;
    private final NotificationReaderSettings notificationSettings;
    private final ReactiveChapterPreloader chapterPreloader;
    private final ReactiveSchedulers reactiveSchedulers;
    private final ChapterChangeManager chapterChangeManager;

    // 阅读视图状态(V11:单一不可变快照,消除多 volatile 字段一致性隐患)
    private final AtomicReference<ReaderViewState> viewStateRef =
            new AtomicReference<>(ReaderViewState.empty());
    // 分页缓存:内容与 pageSize 未变化时复用分页结果,避免整章重复分页(V3 P3-3)
    private final ChapterPaginationCache paginationCache = new ChapterPaginationCache();

    // 加载状态标志
    private final AtomicBoolean isLoadingChapter = new AtomicBoolean(false);
    private final AtomicBoolean isHandlingEvent = new AtomicBoolean(false);

    /** 读取当前阅读状态的不可变快照 */
    private ReaderViewState getViewState() {
        return viewStateRef.get();
    }

    /** 通过不可变拷贝构造更新阅读状态(保证整组字段一致性) */
    private void updateViewState(java.util.function.UnaryOperator<ReaderViewState> updater) {
        viewStateRef.updateAndGet(updater);
    }

    /**
     * 无参构造:供 IntelliJ 服务容器调用,依赖由容器自动注入(V12 构造器注入改造)。
     */
    public NotificationServiceImpl() {
        this(
            ApplicationManager.getApplication().getService(BookService.class),
            ApplicationManager.getApplication().getService(ChapterService.class),
            ApplicationManager.getApplication().getService(NotificationReaderSettings.class),
            ApplicationManager.getApplication().getService(ReactiveChapterPreloader.class),
            ApplicationManager.getApplication().getService(ChapterChangeManager.class)
        );
    }

    /**
     * 构造器注入:用于测试与依赖注入,依赖由调用方提供。
     */
    public NotificationServiceImpl(BookService bookService, ChapterService chapterService,
                                   NotificationReaderSettings notificationSettings,
                                   ReactiveChapterPreloader chapterPreloader,
                                   ChapterChangeManager chapterChangeManager) {
        LOG.debug("初始化 NotificationServiceImpl");
        this.bookService = bookService;
        this.chapterService = chapterService;
        this.notificationSettings = notificationSettings;
        this.chapterPreloader = chapterPreloader;
        this.chapterChangeManager = chapterChangeManager;
        this.reactiveSchedulers = ReactiveSchedulers.getInstance();
        this.messageBusConnection = ApplicationManager.getApplication().getMessageBus().connect(this);
        this.messageBusConnection.subscribe(CurrentChapterNotifier.TOPIC, new CurrentChapterNotifier() {
            @Override
            public void currentChapterChanged(Book changedBook, NovelParser.Chapter newChapter) {
                handleChapterChangedEvent(changedBook, newChapter);
            }
        });
    }

    @Override
    public void setCurrentChapterContent(@NotNull String content) {

        int pageSize = notificationSettings != null ? notificationSettings.getPageSize() : 70; // Use setting for page size
        LOG.debug("NotificationServiceImpl: 设置当前章节内容,使用页面大小: " + pageSize +
                 ", notificationSettings 是否为 null: " + (notificationSettings == null));

        // 分页缓存:内容与 pageSize 均未变化时复用上次分页结果,避免同一章节内容被重复整章分页
        List<String> pages = paginationCache.paginate(content, pageSize);
        ReaderViewState current = getViewState();
        boolean reused = pages == current.getPages();
        updateViewState(s -> s.withPages(pages, !reused)); // 仅在分页真正变化时重置页码

        LOG.debug("NotificationServiceImpl: 分页完成,总页数: " + getViewState().getPageCount() + (reused ? "(复用缓存)" : ""));
    }

    @Override
    public int calculateTotalPages(@NotNull String content) {

        LOG.debug("NotificationServiceImpl: 计算总页数");
        int pageSize = notificationSettings != null ? notificationSettings.getPageSize() : 70; // Use setting for page size
        return paginateContent(content, pageSize).size();
    }

    @Override
    public String getPageContent(@NotNull String content, int pageNumber) {

        LOG.debug("NotificationServiceImpl: 获取页码 " + pageNumber + " 的内容");
        int pageSize = notificationSettings != null ? notificationSettings.getPageSize() : 70; // Use setting for page size
        List<String> pages = paginateContent(content, pageSize);
        if (pageNumber > 0 && pageNumber <= pages.size()) {
            return pages.get(pageNumber - 1); // pageNumber is 1-based, list index is 0-based
        }
        LOG.warn("NotificationServiceImpl: 无效的页码: " + pageNumber);
        return "Invalid page number.";
    }

    @Override
    public void showChapterContent(@NotNull Project project, @NotNull String bookId, @NotNull String chapterId, int pageNumber, @NotNull String title, @NotNull String content) {

        LOG.debug("NotificationServiceImpl: 显示章节内容通知: " + title);

        if (content == null || content.isEmpty()) {
            LOG.warn("章节内容为空,无法显示通知: " + chapterId);
            showError("显示章节失败", "章节内容为空");
            return;
        }

        // 异步获取Book对象
        bookService.getBookById(bookId)
            .subscribeOn(reactiveSchedulers.io())
            .subscribe(book -> {
                if (book == null) {
                    LOG.warn("未找到书籍,无法显示通知: " + bookId);
                    showError("显示章节失败", "未找到书籍");
                    return;
                }
                
                // 在UI线程中执行UI更新操作
                ApplicationManager.getApplication().invokeLater(() -> {
                    // 关闭当前通知
                    closeCurrentNotificationInternal();
                    
                    // 保存当前书籍、章节和标题信息
                    updateViewState(s -> s.withChapter(book, chapterId, title)); // 使用传入的标题
                    
                    // 检查是否有保存的页码信息
                    int savedPageNumber = pageNumber;

                    // 如果传入的页码是1(默认值),尝试从数据库中获取保存的页码
                    if (pageNumber == 1) {
                        savedPageNumber = restoreSavedPageNumber(bookId, chapterId, pageNumber);
                    }
                    
                    // 分页并设置当前页码
                    setCurrentChapterContent(content);
                    
                    // 确保页码在有效范围内
                    List<String> pages = getViewState().getPages();
                    if (savedPageNumber <= 0) {
                        savedPageNumber = 1;
                    } else if (savedPageNumber > pages.size()) {
                        savedPageNumber = pages.size();
                    }
                    
                    // 设置当前页码索引(0-based)
                    int pageIndex = savedPageNumber - 1;
                    updateViewState(s -> s.withPageIndex(pageIndex));
                    LOG.debug(String.format("[页码调试] 设置当前页码索引: %d (页码: %d)", pageIndex, savedPageNumber));
                    
                    // 获取当前页内容
                    String pageContent = getViewState().getPages().get(pageIndex);
                    
                    // 构建通知标题和内容
                    String notificationTitle = book.getTitle() + " - " + title;
                    
                    // 添加页码信息(如果设置启用)
                    String progressText = notificationSettings != null && notificationSettings.isShowReadingProgress() ?
                            "进度: 第 " + (pageIndex + 1) + " 页,共 " + pages.size() + " 页" : "";
                    
                    String notificationContent = pageContent + (progressText.isEmpty() ? "" : "\n\n" + progressText);
                    
                    // 显示通知
                    showCurrentPageInternal(project, notificationTitle, notificationContent);
                    
                    // 触发章节预加载
                    try {
                        if (chapterPreloader != null && book.getCachedChapters() != null) {
                            int chapterIndex = -1;
                            List<NovelParser.Chapter> cachedChapters = book.getCachedChapters();
                            for (int i = 0; i < cachedChapters.size(); i++) {
                                if (chapterId.equals(cachedChapters.get(i).url())) {
                                    chapterIndex = i;
                                    break;
                                }
                            }
                            
                            if (chapterIndex != -1) {
                                LOG.info("[通知栏模式] 触发章节预加载: 书籍=" + book.getTitle() + ", 章节索引=" + chapterIndex);
                                triggerChapterPreload(book, chapterIndex);
                            }
                        }
                    } catch (Exception e) {
                        LOG.error("[通知栏模式] 触发章节预加载时出错", e);
                    }
                    
                    // 保存阅读进度
                    saveNotificationModeProgress();
                    
                    // 记录事件
                    LOG.info("[事件处理] 通知栏已更新到新章节 '" + title + "' 的第" + savedPageNumber + "页。");
                }, ModalityState.defaultModalityState());
            }, error -> {
                LOG.error("获取书籍对象失败: " + error.getMessage(), error);
                showError("显示章节失败", "获取书籍信息时出错: " + error.getMessage());
            });
    }

    /**
     * 内部方法,用于在通知中显示当前页面内容
     * 展示逻辑委托 {@link NotificationDisplayManager},本方法仅校验状态并维护通知引用
     *
     * @param project 当前项目
     * @param title 通知标题
     * @param content 页面内容
     */
    private void showCurrentPageInternal(@NotNull Project project, @NotNull String title, @NotNull String content) {
        ReaderViewState state = getViewState();
        List<String> pages = state.getPages();
        int pageIndex = state.getPageIndex();
        if (pages.isEmpty() || pageIndex < 0 || pageIndex >= pages.size()) {
            LOG.warn("当前页索引无效: " + pageIndex);
            showError("显示页面失败", "当前页索引无效");
            return;
        }

        boolean showButtons = notificationSettings != null && notificationSettings.isShowButtons();
        boolean showPageNumbers = notificationSettings != null && notificationSettings.isShowPageNumbers();
        Notification notification = NotificationDisplayManager.showReadingNotification(
                project, title, content, pageIndex, pages, showButtons, showPageNumbers);

        // 记录当前通知引用(供后续更新/关闭)
        currentNotificationRef.set(notification);
        this.isLoadingChapter.set(false);

        LOG.debug("[通知栏模式] 显示通知: " + title + ", 当前页: " + (pageIndex + 1) + "/" + pages.size());
    }

    @Override
    public void updateNotificationContent(@NotNull Project project, @NotNull String content) {

        LOG.debug("NotificationServiceImpl: 更新通知内容");

        Notification existingNotification = currentNotificationRef.get();
        if (existingNotification != null && !existingNotification.isExpired()) {
            String oldContent = existingNotification.getContent(); // Get current content from notification
            if (content.equals(oldContent)) {
                LOG.debug("[通知栏模式] 更新内容与当前通知内容相同 (传入长度: " + content.length() + ",现有长度: " + (oldContent != null ? oldContent.length() : "null") + "),跳过重新通知");
                return; // Content is the same, do not re-notify
            }
            // Assuming the content parameter here is the *full* content to display for the current page
            // This method is called by the refresh timer, which rebuilds the content string
            existingNotification.setContent(content);
            // Re-notify to update the display
            existingNotification.notify(project);

            // 记录日志
            LOG.debug("[通知栏模式] 更新通知内容成功 (内容已变更)");
        } else {
            LOG.warn("没有现有通知可更新或通知已过期");
        }
    }

    @Override
    public Single<Notification> showError(@NotNull String title, @NotNull String message) {

        LOG.debug("NotificationServiceImpl: 显示错误: " + title + " - " + message);
        this.isLoadingChapter.set(false); // 清除正在加载章节状态,因为加载已失败

        Notification notification = NotificationDisplayManager.showError(title, message);

        // 记录日志
        LOG.debug("[通知栏模式] 显示错误通知: " + title);
        return Single.just(notification); // Return Single for compatibility
    }

    @Override
    public Single<Notification> showInfo(@NotNull String title, @NotNull String message) {

        LOG.debug("NotificationServiceImpl: 显示信息: " + title + " - " + message);

        Notification notification = NotificationDisplayManager.showInfo(title, message);

        // 记录日志
        LOG.debug("[通知栏模式] 显示信息通知: " + title);
        return Single.just(notification); // Return Single for compatibility
    }

    @Override
    public Completable closeAllNotificationsReactive() {

        LOG.debug("NotificationServiceImpl (Reactive): 关闭所有通知");
        // This reactive method might be used by other parts of the application
        // It should not interfere with the state managed by the notification bar mode
        // It will just expire the current notification if it exists
        Notification existingNotification = currentNotificationRef.getAndSet(null);
        if (existingNotification != null && !existingNotification.isExpired()) {
            existingNotification.expire();
        }
        return Completable.complete();
    }

    @Override
    public void closeAllNotifications() {

        LOG.debug("NotificationServiceImpl: 关闭所有通知");
        closeCurrentNotificationInternal();

        // 记录日志
        LOG.debug("[通知栏模式] 已关闭所有通知");
    }

    @Override
    public int getCurrentPage() {
        ReaderViewState state = getViewState();
        if (state.getPages().isEmpty()) {
            return 0;
        }
        // 返回1基索引的页码(pageIndex是0基索引)
        return state.getPageIndex() + 1;
    }

    @Override
    public int getTotalPages() {
        return getViewState().getPageCount();
    }

    @Override
    public String getCurrentBookId() {
        Book book = getViewState().getBook();
        return book != null ? book.getId() : null;
    }

    @Override
    public String getCurrentChapterId() {
        return getViewState().getChapterId();
    }

    @Override
    public void showPrevPage(@NotNull Project project) {
        if (isLoadingChapter.get() || !isReadingActive()) {
            return;
        }
        int pageIndex = getViewState().getPageIndex();
        if (pageIndex <= 0) {
            LOG.info("[通知栏模式] 当前是第一页,尝试跳转到上一章的最后一页");
            navigateChapterToLastPage(project, -1);
        } else {
            updateAndShowPage(project, pageIndex - 1);
        }
    }

    @Override
    public void showNextPage(@NotNull Project project) {
        if (isLoadingChapter.get() || !isReadingActive()) {
            return;
        }
        int pageIndex = getViewState().getPageIndex();
        if (pageIndex >= getViewState().getPageCount() - 1) {
            LOG.info("[通知栏模式] 当前是最后一页,尝试跳转到下一章的第一页");
            navigateChapter(project, 1);
        } else {
            updateAndShowPage(project, pageIndex + 1);
        }
    }

    @Override
    public void navigateChapter(@NotNull Project project, int direction) {
        if (isLoadingChapter.get() || !isReadingActive()) {
            return;
        }
        showLoadingNotification(project, "正在加载章节...");

        Book book = getViewState().getBook();
        List<Chapter> cachedChapters = book.getCachedChapters();
        if (cachedChapters != null && !cachedChapters.isEmpty()) {
            LOG.debug("使用Book中的cachedChapters进行导航,章节数量: " + cachedChapters.size());
            reactiveSchedulers.runOnUI(() -> processChapterNavigationWithCachedChapters(project, cachedChapters, direction, false));
        } else {
            LOG.debug("Book中的cachedChapters为空,使用bookService.getChaptersSync获取章节列表");
            Single.fromCallable(() -> bookService.getChaptersSync(book.getId()))
                .subscribeOn(Schedulers.io())
                .timeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .doOnError(e -> reactiveSchedulers.runOnUI(() -> showError("导航失败", "获取章节列表时出错: " + e.getMessage())))
                .subscribe(chapters -> reactiveSchedulers.runOnUI(() -> processChapterNavigation(project, chapters, direction, false)));
        }
    }

    private void updateAndShowPage(@NotNull Project project, int newPageIndex) {
        updateViewState(s -> s.withPageIndex(newPageIndex));
        ReaderViewState state = getViewState();
        String title = state.getBook().getTitle() + " - " + state.getChapterTitle();
        String pageContent = state.getPages().get(state.getPageIndex());
        String progressText = notificationSettings != null && notificationSettings.isShowReadingProgress() ?
                String.format("进度: 第 %d 页,共 %d 页", state.getPageIndex() + 1, state.getPageCount()) : "";
        String notificationContent = pageContent + (progressText.isEmpty() ? "" : "\n\n" + progressText);
        
        showCurrentPageInternal(project, title, notificationContent);
        saveNotificationModeProgress();
        LOG.debug("[通知栏模式] 成功显示页面,当前页索引: " + state.getPageIndex());
    }
    
    private boolean isReadingActive() {
        return isReadingActive(true);
    }

    private boolean isReadingActive(boolean notifyWhenInactive) {
        if (!getViewState().isReadingActive()) {
            if (notifyWhenInactive) {
                LOG.warn("[通知栏模式] 当前没有正在阅读的内容");
                showInfo("导航", "当前没有正在阅读的内容");
            } else {
                LOG.debug("[通知栏模式] 当前没有正在阅读的内容");
            }
            return false;
        }
        return true;
    }

    /**
     * 显示加载状态通知
     * @param project 当前项目
     * @param message 加载消息
     */
    @Override
    public void showLoadingNotification(@NotNull Project project, @NotNull String message) {
        this.isLoadingChapter.set(true); // 设置正在加载章节状态
        // 关闭当前通知
        closeCurrentNotificationInternal();

        // 创建并记录加载状态通知
        Notification notification = NotificationDisplayManager.showLoadingNotification(project, message);
        currentNotificationRef.set(notification);

        LOG.info("[通知栏模式] 显示加载状态通知: " + message);
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
        ReaderViewState state = getViewState();
        int currentIndex = ChapterNavigationHelper.findChapterIndex(state.getBook(), state.getChapterId(), chapterUrls);

        String validationError = ChapterNavigationHelper.validateNavigation(currentIndex, direction, totalChapters);
        if (validationError != null) {
            LOG.warn(logPrefix + validationError);
            if (currentIndex < 0) {
                showError("导航失败", validationError);
            } else {
                showInfo("导航", validationError);
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
            showInfo("导航", "章节列表为空");
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
        showNavigatedChapter(project, targetChapter.url(), targetChapter.title(), targetChapter.getContent(), targetIndex, navigateToLastPage);
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
            showInfo("导航", "章节列表为空");
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
        showLoadingNotification(project, "正在加载章节内容...");

        Book book = getViewState().getBook();
        // 使用异步方式获取章节内容,避免阻塞UI线程
        Single.fromCallable(() -> {
            if (book.getParser() != null) {
                return book.getParser().getChapterContent(targetChapterId, book);
            }
            return null;
        })
        .subscribeOn(Schedulers.io())
        .timeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .doOnError(e -> {
            LOG.error("获取章节内容时出错: " + e.getMessage(), e);
            reactiveSchedulers.runOnUI(() -> showError("导航失败", "获取章节内容时出错: " + e.getMessage()));
        })
        .subscribe(content -> {
            reactiveSchedulers.runOnUI(() ->
                showNavigatedChapter(project, targetChapter, content, targetIndex, navigateToLastPage));
        });
    }

    // Existing reactive methods (kept for compatibility if still used elsewhere)
    @Override
    public Single<Notification> showChapterContent(@NotNull Book book, @NotNull String chapterId, @NotNull String content) {
        return Single.defer(() -> chapterService.getChapterTitle(book.getId(), chapterId)
                .flatMap(title -> {

                    LOG.info("[通知栏模式] (Reactive) 显示章节内容: " + book.getTitle() + " - " + chapterId + ", 内容长度: " + content.length());

                    // 关闭当前通知
                    closeCurrentNotificationInternal();

                    if (content == null || content.isEmpty()) {
                        LOG.warn("[通知栏模式] (Reactive) 章节内容为空,无法显示通知: " + chapterId);
                        return showError("显示章节失败", "章节内容为空");
                    }

                    // 保存当前书籍、章节和内容信息
                    updateViewState(s -> s.withChapter(book, chapterId, title));

                    // 检查是否有保存的页码信息
                    int savedPageNumber = 1; // 默认从第1页开始(对应索引0)

            // 尝试从数据库中获取保存的页码
            savedPageNumber = restoreSavedPageNumber(book.getId(), chapterId, savedPageNumber);

            // 记录恢复的页码
            LOG.debug("[页码调试] (Reactive) 恢复的页码: {}", savedPageNumber);

            // 使用 setCurrentChapterContent 方法进行分页
            setCurrentChapterContent(content);

            // 分页后,设置恢复的页码索引
            List<String> pages = getViewState().getPages();
            int savedPageIndex;
            if (savedPageNumber > 0 && savedPageNumber <= pages.size()) {
                savedPageIndex = savedPageNumber - 1;
            } else {
                savedPageIndex = 0;
            }
            final int pageIndex0 = savedPageIndex;
            updateViewState(s -> s.withPageIndex(pageIndex0));
            LOG.debug("[页码调试] (Reactive) 重新设置页码索引: {} (对应页码: {})",
                    pageIndex0, pageIndex0 + 1);

            // 确保页码索引在有效范围内
            int finalPageIndex = pageIndex0;
            if (finalPageIndex < 0) {
                LOG.debug("[页码调试] (Reactive) 页码索引小于0,重置为0");
                finalPageIndex = 0;
            } else if (finalPageIndex >= pages.size()) {
                LOG.debug("[页码调试] (Reactive) 页码索引超出范围,重置为最后一页");
                finalPageIndex = Math.max(0, pages.size() - 1);
            }
            final int pageIndex1 = finalPageIndex;
            updateViewState(s -> s.withPageIndex(pageIndex1));

            if (pages.isEmpty()) {
                LOG.warn("[通知栏模式] (Reactive) 分页后内容为空,无法显示通知: " + chapterId);
                return showError("显示章节失败", "分页后内容为空");
            }

            // 获取当前页内容
            String pageContent = pages.get(pageIndex1);
            String progressText = notificationSettings != null && notificationSettings.isShowReadingProgress() ?
                    "进度: 第 " + (pageIndex1 + 1) + " 页,共 " + pages.size() + " 页" : "";
            String notificationContent = pageContent + (progressText.isEmpty() ? "" : "\n\n" + progressText);

            // 使用 Project 对象,如果可用
            Project project = null;
            try {
                project = ProjectManager.getInstance().getOpenProjects()[0]; // 获取第一个打开的项目
            } catch (Exception e) {
                LOG.warn("[通知栏模式] (Reactive) 无法获取 Project 对象: " + e.getMessage());
            }

            // 使用 showCurrentPageInternal 方法显示通知
            if (project != null) {
                showCurrentPageInternal(project, title, notificationContent);
                // 保存阅读进度
                // 注意:不使用 bookService.saveReadingProgress 方法,因为它会将 pageIndex 加1
                // 而我们已经从数据库中恢复的页码是1基索引,转换为 pageIndex 时减了1
                // 如果再使用 bookService.saveReadingProgress 方法,它会再次将 pageIndex 加1,导致页码始终是1
                SqliteReadingProgressRepository readingProgressRepository = ApplicationManager.getApplication().getService(SqliteReadingProgressRepository.class);
                if (readingProgressRepository != null) {
                    // 使用带页码参数的重载方法,position设为0,直接使用pageIndex + 1作为页码
                    readingProgressRepository.updateProgress(book, chapterId, getViewState().getChapterTitle(), 0, pageIndex1 + 1);
                    LOG.debug("[页码调试] (Reactive) 直接保存页码: {}", pageIndex1 + 1);
                } else {
                    LOG.warn("[页码调试] (Reactive) 无法获取 SqliteReadingProgressRepository 实例,使用 bookService.saveReadingProgress 方法");
                    bookService.saveReadingProgress(book, chapterId, getViewState().getChapterTitle(), pageIndex1);
                }
                LOG.info("[通知栏模式] (Reactive) 显示章节内容成功,使用 Project 对象");

                // 查找当前章节在章节列表中的索引,并触发预加载
                List<NovelParser.Chapter> cachedChapters = book.getCachedChapters();
                if (cachedChapters != null && !cachedChapters.isEmpty()) {
                    int currentIndex = -1;
                    for (int i = 0; i < cachedChapters.size(); i++) {
                        if (cachedChapters.get(i).url().equals(chapterId)) {
                            currentIndex = i;
                            break;
                        }
                    }

                    if (currentIndex != -1) {
                        // 触发章节预加载
                        triggerChapterPreload(book, currentIndex);
                    } else {
                        LOG.warn("[通知栏模式] (Reactive) 无法找到当前章节在列表中的索引,跳过预加载");
                    }
                } else {
                    LOG.warn("[通知栏模式] (Reactive) 章节列表为空,无法预加载");
                }

                return Single.just(currentNotificationRef.get());
            } else {
                // 如果无法获取 Project 对象,使用简单的通知
                LOG.warn("[通知栏模式] (Reactive) 无法获取 Project 对象,使用简单通知");
                // 清理内容中的HTML标签
                String cleanContent = NotificationDisplayManager.cleanHtmlTags(notificationContent);
                Notification notification = NotificationGroupManager.getInstance()
                        .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID_READER)
                        .createNotification(cleanContent, NotificationType.INFORMATION);

                notification.setTitle(title);
                notification.notify(null);
                currentNotificationRef.set(notification);
                return Single.just(notification);
            }
                })).subscribeOn(Schedulers.io());
    }

    @Override
    public void dispose() {
        LOG.debug("Disposing NotificationServiceImpl");
        // stopAutoRead(); // Call was already removed
        closeCurrentNotificationInternal(); // 确保关闭当前通知
        if (this.messageBusConnection != null) {
            this.messageBusConnection.disconnect();
            this.messageBusConnection = null;
            LOG.debug("MessageBus connection disconnected.");
        }
        disposables.dispose();

        // 尝试保存通知模式的阅读进度
        ReaderModeSettings currentReaderModeSettings;
        try {
            currentReaderModeSettings = ApplicationManager.getApplication().getServiceIfCreated(ReaderModeSettings.class);
        } catch (Exception e) {
            LOG.warn("Failed to get existing ReaderModeSettings service in dispose", e);
            return;
        }

        if (currentReaderModeSettings != null && currentReaderModeSettings.isNotificationMode()) {
            ReaderViewState state = getViewState();
            LOG.info("[Dispose] Current NotificationServiceImpl state: pageIndex = " + state.getPageIndex() + ", pages.size() = " + state.getPageCount());
            LOG.info("Currently in notification mode, attempting to save progress before disposing.");
            saveNotificationModeProgress(false, false);
        } else {
            LOG.info("Not in notification mode or ReaderModeSettings is null, no progress to save from notification bar.");
        }
        LOG.debug("NotificationServiceImpl disposed.");
    }

    /**
     * 将内容分页
     * 严格按照pageSize进行分页,同时尽量在段落或句子结束处分页,提供更好的阅读体验
     * 每页字符数严格等于pageSize(除了最后一页可能少于pageSize)
     *
     * @param content 内容
     * @param pageSize 每页字符数
     * @return 分页后的内容列表
     */
    private List<String> paginateContent(String content, int pageSize) {
        List<String> pages = PaginationHelper.paginate(content, pageSize);

        // Log page sizes for debugging
        if (!pages.isEmpty()) {
            StringBuilder pageSizeInfo = new StringBuilder("[分页] 各页字符数: ");
            for (int i = 0; i < pages.size(); i++) {
                if (i > 0) pageSizeInfo.append(", ");
                pageSizeInfo.append("#").append(i + 1).append(": ").append(pages.get(i).length());
            }
            LOG.debug(pageSizeInfo.toString());
        }

        LOG.debug("[分页] 分页完成,总页数: " + pages.size());
        return pages;
    }

    /**
     * 导航到指定章节的最后一页
     * 类似于 navigateChapter,但是跳转到目标章节的最后一页,而不是第一页
     *
     * @param project 当前项目
     * @param direction 方向,-1 表示上一章,1 表示下一章
     */
    private void navigateChapterToLastPage(@NotNull Project project, int direction) {
        if (this.isLoadingChapter.get()) {
            LOG.warn("[通知栏模式] 正在加载章节内容,忽略章节导航至末尾操作。");
            return;
        }

        LOG.info("[通知栏模式] 导航到章节的最后一页,方向: " + direction);

        ReaderViewState state = getViewState();
        if (state.getBook() == null || state.getChapterId() == null) {
            LOG.warn("[通知栏模式] 当前没有正在阅读的内容");
            showInfo("导航", "当前没有正在阅读的内容");
            return;
        }
        Book book = state.getBook();

        // 显示加载状态通知
        showLoadingNotification(project, "正在加载章节...");

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
            .timeout(30, java.util.concurrent.TimeUnit.SECONDS) // 设置超时
            .doOnError(e -> {
                LOG.error("[通知栏模式] 获取章节列表时出错: " + e.getMessage(), e);
                reactiveSchedulers.runOnUI(() -> showError("导航失败", "获取章节列表时出错: " + e.getMessage()));
            })
            .subscribe(chapters -> {
                // 在获取到章节列表后,在UI线程上处理导航逻辑
                reactiveSchedulers.runOnUI(() -> processChapterNavigation(project, chapters, direction, true));
            });
    }

    /**
     * 处理章节导航后的统一展示流程(同步数据源专用)。
     * <p>
     * 由 4 组导航方法共享的"更新状态 → 分页 → 显示通知 → 保存进度 → 预加载 → 发布事件"流水线。
     * 该重载在调用方(UI 线程)执行,适用于目标章节内容已直接可得的场景(EnhancedChapter)。
     *
     * @param project 当前项目
     * @param targetChapterId 目标章节ID
     * @param targetChapterTitle 目标章节标题
     * @param targetChapterContent 目标章节内容
     * @param targetIndex 目标章节索引
     * @param navigateToLastPage 是否导航到最后一页(否则导航到第一页)
     */
    private void showNavigatedChapter(@NotNull Project project,
                                      @NotNull String targetChapterId,
                                      @NotNull String targetChapterTitle,
                                      @NotNull String targetChapterContent,
                                      int targetIndex,
                                      boolean navigateToLastPage) {
        if (targetChapterContent == null || targetChapterContent.isEmpty()) {
            LOG.warn("[通知栏模式] 目标章节内容为空: " + targetChapterId);
            showError("导航失败", "目标章节内容为空");
            return;
        }

        Book book = getViewState().getBook();
        // 更新当前书籍、章节信息
        updateViewState(s -> s.withChapter(book, targetChapterId, targetChapterTitle));

        // 分页并定位到目标页
        setCurrentChapterContent(targetChapterContent);
        List<String> pages = getViewState().getPages();
        if (pages.isEmpty()) {
            LOG.warn("[通知栏模式] 分页后内容为空,无法显示通知: " + targetChapterId);
            showError("显示章节失败", "分页后内容为空");
            return;
        }
        int pageIndex = navigateToLastPage ? pages.size() - 1 : 0;
        updateViewState(s -> s.withPageIndex(pageIndex));

        // 使用工具类构建通知内容并显示
        String title = ProgressSaveHelper.buildNotificationTitle(book.getTitle(), targetChapterTitle);
        String notificationContent = ProgressSaveHelper.buildNotificationContent(
            pages.get(pageIndex), pageIndex, pages.size(),
            notificationSettings != null && notificationSettings.isShowReadingProgress());

        showCurrentPageInternal(project, title, notificationContent);

        // 使用工具类保存进度
        ProgressSaveHelper.saveProgress(book, targetChapterId, targetChapterTitle, pageIndex);

        LOG.info("[通知栏模式] 导航到章节: " + targetChapterId + (navigateToLastPage ? " (最后一页)" : ""));

        // 触发章节预加载
        triggerChapterPreload(book, targetIndex);

        // 设置事件源并发布章节变更事件
        if (chapterChangeManager != null) {
            chapterChangeManager.setEventSource(ChapterChangeEventSource.NOTIFICATION_SERVICE);
        }
        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(CurrentChapterNotifier.TOPIC)
                .currentChapterChanged(book, new NovelParser.Chapter(targetChapterTitle, targetChapterId));
        LOG.info("[通知栏模式] 已发布章节变更事件: " + targetChapterTitle);
    }

    /**
     * 处理章节导航后的统一展示流程(异步数据源专用)。
     * <p>
     * 与 {@link #showNavigatedChapter(Project, String, String, String, int, boolean)} 相同,
     * 但它在异步订阅回调中执行,目标章节以 {@link Chapter} 对象传入(内容需已异步获取)。
     * 与同步重载的唯一行为差异是发布事件时使用原始 Chapter 对象(保留 url/title 字段)。
     *
     * @param project 当前项目
     * @param targetChapter 目标章节(内容已获取)
     * @param content 目标章节内容
     * @param targetIndex 目标章节索引
     * @param navigateToLastPage 是否导航到最后一页
     */
    private void showNavigatedChapter(@NotNull Project project,
                                      @NotNull NovelParser.Chapter targetChapter,
                                      @NotNull String content,
                                      int targetIndex,
                                      boolean navigateToLastPage) {
        String targetChapterId = targetChapter.url();
        String targetChapterTitle = targetChapter.title();

        if (content == null || content.isEmpty()) {
            LOG.warn("[通知栏模式] 目标章节内容为空: " + targetChapterId);
            showError("导航失败", "目标章节内容为空");
            return;
        }

        Book book = getViewState().getBook();
        // 更新当前章节信息
        updateViewState(s -> s.withChapter(book, targetChapterId, targetChapterTitle));

        // 分页并定位到目标页
        setCurrentChapterContent(content);
        List<String> pages = getViewState().getPages();
        if (pages.isEmpty()) {
            LOG.warn("[通知栏模式] 分页后内容为空,无法显示通知: " + targetChapterId);
            showError("显示章节失败", "分页后内容为空");
            return;
        }
        int pageIndex = navigateToLastPage ? pages.size() - 1 : 0;
        updateViewState(s -> s.withPageIndex(pageIndex));

        // 使用工具类构建通知内容并显示
        String title = ProgressSaveHelper.buildNotificationTitle(book.getTitle(), targetChapterTitle);
        String notificationContent = ProgressSaveHelper.buildNotificationContent(
            pages.get(pageIndex), pageIndex, pages.size(),
            notificationSettings != null && notificationSettings.isShowReadingProgress());

        showCurrentPageInternal(project, title, notificationContent);

        // 使用工具类保存进度
        ProgressSaveHelper.saveProgress(book, targetChapterId, targetChapterTitle, pageIndex);

        LOG.info("[通知栏模式] 使用cachedChapters导航到章节" + (navigateToLastPage ? "的最后一页" : "") + ": " + targetChapterId);

        // 触发章节预加载
        triggerChapterPreload(book, targetIndex);

        // 设置事件源并发布章节变更事件
        if (chapterChangeManager != null) {
            chapterChangeManager.setEventSource(ChapterChangeEventSource.NOTIFICATION_SERVICE);
        }
        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(CurrentChapterNotifier.TOPIC)
                .currentChapterChanged(book, targetChapter);
        LOG.info("[通知栏模式] 已发布章节变更事件: " + targetChapter.title());
    }

    /**
     * 关闭当前通知
     * 注意:只关闭通知,不重置其他状态(如书籍、章节等)
     */
    private void closeCurrentNotificationInternal() {
        ReaderViewState state = getViewState();
        LOG.debug("[通知栏模式] 关闭当前通知,当前页索引: " + state.getPageIndex() + ", 总页数: " + state.getPageCount());

        Notification oldNotification = currentNotificationRef.getAndSet(null);
        if (oldNotification != null && !oldNotification.isExpired()) {
            try {
                oldNotification.expire();
                LOG.debug("[通知栏模式] 成功关闭通知");
            } catch (Exception e) {
                LOG.error("[通知栏模式] 关闭通知时出错: " + e.getMessage(), e);
            }
        } else {
            LOG.debug("[通知栏模式] 没有通知需要关闭或通知已过期");
        }

        // 不重置其他状态,以便在显示新通知时保持阅读进度
    }

    /**
     * 触发章节预加载
     * 根据当前章节索引预加载前后章节
     *
     * @param book 当前阅读的书籍
     * @param chapterIndex 当前章节索引
     */
    private void triggerChapterPreload(@NotNull Book book, int chapterIndex) {


        if (chapterPreloader == null) {
            LOG.warn("[通知栏模式] ReactiveChapterPreloader 未初始化,无法预加载章节");
            return;
        }

        LOG.info("[通知栏模式] 触发章节预加载: 书籍=" + book.getTitle() + ", 章节索引=" + chapterIndex);

        // 使用 ReactiveChapterPreloader 预加载前后章节
        disposables.add(chapterPreloader.preloadChaptersReactive(book, chapterIndex)
            .subscribe(
                () -> LOG.debug("[通知栏模式] 章节预加载完成: 书籍=" + book.getTitle() + ", 章节索引=" + chapterIndex),
                error -> LOG.error("[通知栏模式] 章节预加载失败: 书籍=" + book.getTitle() + ", 章节索引=" + chapterIndex, error)
            ));
    }

    private void handleChapterChangedEvent(Book changedBook, Chapter newChapter) {
 // 确保所有依赖的服务都已初始化
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

            ReaderViewState currentState = getViewState();
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
                .observeOn(Schedulers.io()) // 确保UI更新在UI线程
                .subscribe(
                    data -> {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                String content = (String) data[0];
                                String fetchedTitle = (String) data[1];

                                // 更新当前状态
                                updateViewState(s -> s.withChapter(changedBook, newChapter.url(), fetchedTitle));

                                // 分页
                                setCurrentChapterContent(content);
                                List<String> pages = getViewState().getPages();
                                if (pages.isEmpty()) {
                                    LOG.warn("[事件处理] 新章节 '" + newChapter.title() + "' 分页后内容为空,无法显示通知。");
                                    showError("章节内容为空", "无法在通知栏显示章节 " + newChapter.title());
                                    return;
                                }

                                // 尝试恢复页码,否则显示第一页
                                int pageToLoad = 1;
                                try {
                                    SqliteReadingProgressRepository readingProgressRepository = ApplicationManager.getApplication().getService(SqliteReadingProgressRepository.class);
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
                                updateViewState(s -> s.withPageIndex(pageIndex));

                                String pageContentToShow = pages.get(pageIndex);
                                String progressText = notificationSettings != null && notificationSettings.isShowReadingProgress() ?
                                     "进度: 第 " + (pageIndex + 1) + " 页,共 " + pages.size() + " 页" : "";
                                String notificationContent = pageContentToShow + (progressText.isEmpty() ? "" : "\n\n" + progressText);

                                showCurrentPageInternal(project, fetchedTitle, notificationContent);
                                LOG.info("[事件处理] 通知栏已更新到新章节 '" + newChapter.title() + "' 的第一页。");
                                saveNotificationModeProgress(); // 保存进度
                            } catch (Throwable t) {
                                LOG.error("[事件处理] 在处理章节内容时发生未捕获的错误: " + t.getMessage(), t);
                            }
                        }, ModalityState.defaultModalityState());
                    },
                    error -> {
                        LOG.error("[事件处理] 获取或处理新章节 '" + newChapter.title() + "' 内容失败: " + error.getMessage(), error);
                        showError("加载章节失败", "无法加载章节 " + newChapter.title() + " 的内容: " + error.getMessage());
                    }
                );
        } finally {
            isHandlingEvent.set(false);
        }
    }

    // Placed before handleChapterChangedEvent for logical grouping.
    /**
     * 从数据库恢复保存的页码
     *
     * @param bookId 书籍ID
     * @param chapterId 章节ID
     * @param defaultPage 默认页码
     * @return 恢复的页码,如果未找到则返回默认页码
     */
    private int restoreSavedPageNumber(String bookId, String chapterId, int defaultPage) {
        try {
            SqliteReadingProgressRepository readingProgressRepository = ApplicationManager.getApplication().getService(SqliteReadingProgressRepository.class);
            if (readingProgressRepository != null) {
                Optional<BookProgressData> progressDataOpt = readingProgressRepository.getProgress(bookId);
                if (progressDataOpt.isPresent()) {
                    BookProgressData progressData = progressDataOpt.get();
                    if (chapterId.equals(progressData.lastReadChapterId())) {
                        int savedPage = progressData.lastReadPage();
                        LOG.debug("[页码调试] 从数据库恢复页码: {}", savedPage);
                        return savedPage;
                    } else {
                        LOG.debug("[页码调试] 章节ID不匹配,无法恢复页码");
                    }
                } else {
                    LOG.debug("[页码调试] 未找到书籍的阅读进度记录");
                }
            } else {
                LOG.warn("[页码调试] 无法获取 SqliteReadingProgressRepository 实例");
            }
        } catch (Exception e) {
            LOG.error("[页码调试] 恢复页码时出错", e);
        }
        return defaultPage;
    }

    private void saveNotificationModeProgress() {
        saveNotificationModeProgress(true, true);
    }

    private void saveNotificationModeProgress(boolean notifyWhenInactive, boolean createRepositoryIfNeeded) {
        if (!isReadingActive(notifyWhenInactive)) {
            if (notifyWhenInactive) {
                LOG.warn("[进度保存] 无法保存通知栏模式进度:没有活动的阅读会话。");
            } else {
                LOG.debug("[进度保存] 无法保存通知栏模式进度:没有活动的阅读会话。");
            }
            return;
        }

        try {
            SqliteReadingProgressRepository repository = createRepositoryIfNeeded
                    ? ApplicationManager.getApplication().getService(SqliteReadingProgressRepository.class)
                    : ApplicationManager.getApplication().getServiceIfCreated(SqliteReadingProgressRepository.class);
            if (repository != null) {
                ReaderViewState state = getViewState();
                int pageToSave = state.getPageIndex() + 1;
                repository.updateProgress(state.getBook(), state.getChapterId(), state.getChapterTitle(), 0, pageToSave);
                LOG.info(String.format("[进度保存] 成功保存通知栏模式阅读进度:书籍='%s', 章节='%s', 页码=%d",
                        state.getBook().getTitle(), state.getChapterTitle(), pageToSave));
            } else {
                LOG.warn("[进度保存] SqliteReadingProgressRepository 服务未初始化,跳过保存进度。");
            }
        } catch (Exception e) {
            LOG.error("[进度保存] 保存通知栏模式阅读进度时发生意外错误。", e);
        }
    }
}