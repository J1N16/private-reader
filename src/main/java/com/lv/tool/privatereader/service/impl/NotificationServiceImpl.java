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
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.service.ChapterService;
import com.lv.tool.privatereader.service.NotificationService;
import com.lv.tool.privatereader.service.impl.notification.ChapterEventProcessor;
import com.lv.tool.privatereader.service.impl.notification.ChapterNavigator;
import com.lv.tool.privatereader.service.impl.notification.ChapterPaginationCache;
import com.lv.tool.privatereader.service.impl.notification.NotificationBarModeServiceUtils;
import com.lv.tool.privatereader.service.impl.notification.NotificationDisplayManager;
import com.lv.tool.privatereader.service.impl.notification.PaginationHelper;
import com.lv.tool.privatereader.service.impl.notification.ReaderViewState;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import com.lv.tool.privatereader.storage.cache.ReactiveChapterPreloader;
import org.jetbrains.annotations.NotNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import com.intellij.openapi.application.ModalityState;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

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
    // V14:阅读仓库由构造器注入,消除四处按需 getService 硬编码(V12 构造器注入的收尾)
    private final ReadingProgressRepository readingProgressRepository;

    // 阅读视图状态(V11:单一不可变快照,消除多 volatile 字段一致性隐患)
    private final AtomicReference<ReaderViewState> viewStateRef =
            new AtomicReference<>(ReaderViewState.empty());
    // 分页缓存:内容与 pageSize 未变化时复用分页结果,避免整章重复分页(V3 P3-3)
    private final ChapterPaginationCache paginationCache = new ChapterPaginationCache();

    // 加载状态标志
    private final AtomicBoolean isLoadingChapter = new AtomicBoolean(false);

    // V16:章节变更事件处理拆分至独立处理器,本类仅通过窄接口 Host 回调
    private final ChapterEventProcessor chapterEventProcessor;

    // V17:章节导航流水线拆分至独立导航器,本类仅通过窄接口 Host 回调
    private final ChapterNavigator chapterNavigator;

    /**
     * 事件处理器宿主的窄接口实现。
     * <p>将 {@link ChapterEventProcessor} 所需的最小能力映射到本服务的内部方法,
     * 避免处理器直接依赖完整 API。
     */
    private final ChapterEventProcessor.Host eventProcessorHost = new ChapterEventProcessor.Host() {
        @Override
        public ReaderViewState getViewState() {
            return NotificationServiceImpl.this.getViewState();
        }

        @Override
        public void updateViewState(@NotNull UnaryOperator<ReaderViewState> updater) {
            NotificationServiceImpl.this.updateViewState(updater);
        }

        @Override
        public void setCurrentChapterContent(@NotNull String content) {
            NotificationServiceImpl.this.setCurrentChapterContent(content);
        }

        @Override
        public void showCurrentPageInternal(@NotNull Project project, @NotNull String title, @NotNull String content) {
            NotificationServiceImpl.this.showCurrentPageInternal(project, title, content);
        }

        @Override
        public void showError(@NotNull String title, @NotNull String message) {
            NotificationServiceImpl.this.showError(title, message);
        }

        @Override
        public void saveNotificationModeProgress() {
            NotificationServiceImpl.this.saveNotificationModeProgress();
        }
    };

    /**
     * 导航器宿主的窄接口实现。
     * <p>将 {@link ChapterNavigator} 所需的最小能力映射到本服务的内部方法。
     */
    private final ChapterNavigator.Host navigatorHost = new ChapterNavigator.Host() {
        @Override
        public ReaderViewState getViewState() {
            return NotificationServiceImpl.this.getViewState();
        }

        @Override
        public void updateViewState(@NotNull UnaryOperator<ReaderViewState> updater) {
            NotificationServiceImpl.this.updateViewState(updater);
        }

        @Override
        public void setCurrentChapterContent(@NotNull String content) {
            NotificationServiceImpl.this.setCurrentChapterContent(content);
        }

        @Override
        public void showCurrentPageInternal(@NotNull Project project, @NotNull String title, @NotNull String content) {
            NotificationServiceImpl.this.showCurrentPageInternal(project, title, content);
        }

        @Override
        public void showError(@NotNull String title, @NotNull String message) {
            NotificationServiceImpl.this.showError(title, message);
        }

        @Override
        public void showInfo(@NotNull String title, @NotNull String message) {
            NotificationServiceImpl.this.showInfo(title, message);
        }

        @Override
        public void showLoadingNotification(@NotNull Project project, @NotNull String message) {
            NotificationServiceImpl.this.showLoadingNotification(project, message);
        }

        @Override
        public void triggerChapterPreload(@NotNull Book book, int chapterIndex) {
            NotificationServiceImpl.this.triggerChapterPreload(book, chapterIndex);
        }

        @Override
        public void publishChapterChanged(@NotNull Book book, @NotNull NovelParser.Chapter chapter) {
            NotificationServiceImpl.this.publishChapterChanged(book, chapter);
        }
    };

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
            ApplicationManager.getApplication().getService(ChapterChangeManager.class),
            ApplicationManager.getApplication().getService(ReadingProgressRepository.class)
        );
    }

    /**
     * 构造器注入:用于测试与依赖注入,依赖由调用方提供。
     */
    public NotificationServiceImpl(BookService bookService, ChapterService chapterService,
                                   NotificationReaderSettings notificationSettings,
                                   ReactiveChapterPreloader chapterPreloader,
                                   ChapterChangeManager chapterChangeManager,
                                   ReadingProgressRepository readingProgressRepository) {
        LOG.debug("初始化 NotificationServiceImpl");
        this.bookService = bookService;
        this.chapterService = chapterService;
        this.notificationSettings = notificationSettings;
        this.chapterPreloader = chapterPreloader;
        this.chapterChangeManager = chapterChangeManager;
        this.readingProgressRepository = readingProgressRepository;
        this.reactiveSchedulers = ReactiveSchedulers.getInstance();
        this.chapterEventProcessor = new ChapterEventProcessor(
                eventProcessorHost,
                chapterService,
                notificationSettings,
                chapterChangeManager,
                readingProgressRepository);
        this.chapterNavigator = new ChapterNavigator(
                navigatorHost,
                bookService,
                notificationSettings,
                reactiveSchedulers);
        this.messageBusConnection = ApplicationManager.getApplication().getMessageBus().connect(this);
        this.messageBusConnection.subscribe(CurrentChapterNotifier.TOPIC, new CurrentChapterNotifier() {
            @Override
            public void currentChapterChanged(Book changedBook, NovelParser.Chapter newChapter) {
                chapterEventProcessor.handleChapterChangedEvent(changedBook, newChapter);
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
                project, title, content, pageIndex, pages, showButtons, showPageNumbers,
                () -> showPrevPage(project),
                () -> showNextPage(project),
                () -> navigateChapter(project, -1),
                () -> navigateChapter(project, 1),
                NotificationBarModeServiceUtils::switchBackToReader);

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
    public String getCurrentChapterTitle() {
        return getViewState().getChapterTitle();
    }

    @Override
    public void showPrevPage(@NotNull Project project) {
        if (isLoadingChapter.get() || !isReadingActive()) {
            return;
        }
        int pageIndex = getViewState().getPageIndex();
        if (pageIndex <= 0) {
            LOG.info("[通知栏模式] 当前是第一页,尝试跳转到上一章的最后一页");
            chapterNavigator.navigateToLastPage(project, -1);
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
            chapterNavigator.navigateChapter(project, 1);
        } else {
            updateAndShowPage(project, pageIndex + 1);
        }
    }

    @Override
    public void navigateChapter(@NotNull Project project, int direction) {
        if (isLoadingChapter.get() || !isReadingActive()) {
            return;
        }
        chapterNavigator.navigateChapter(project, direction);
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

    /**
     * 设置事件源并发布章节变更事件(供 {@link ChapterNavigator} 导航后回调)。
     *
     * @param book    当前书籍
     * @param chapter 目标章节
     */
    private void publishChapterChanged(@NotNull Book book, @NotNull NovelParser.Chapter chapter) {
        if (chapterChangeManager != null) {
            chapterChangeManager.setEventSource(ChapterChangeEventSource.NOTIFICATION_SERVICE);
        }
        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(CurrentChapterNotifier.TOPIC)
                .currentChapterChanged(book, chapter);
    }

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
                LOG.warn("[页码调试] 无法获取阅读进度仓库,无法恢复页码");
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
            // V14:阅读仓库改为构造器注入,不再按需从服务容器获取(参数保留以兼容既有调用语义)
            if (readingProgressRepository != null) {
                ReaderViewState state = getViewState();
                int pageToSave = state.getPageIndex() + 1;
                readingProgressRepository.updateProgress(state.getBook(), state.getChapterId(), state.getChapterTitle(), 0, pageToSave);
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