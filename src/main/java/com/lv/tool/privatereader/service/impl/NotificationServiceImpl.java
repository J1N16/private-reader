package com.lv.tool.privatereader.service.impl;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
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
import com.lv.tool.privatereader.events.ChapterChangeManager;
import com.lv.tool.privatereader.events.ChapterChangeEventSource;
import com.lv.tool.privatereader.messaging.CurrentChapterNotifier;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookProgressData;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import com.lv.tool.privatereader.repository.impl.SqliteReadingProgressRepository;
import com.lv.tool.privatereader.config.PrivateReaderConfig;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.service.ChapterService;
import com.lv.tool.privatereader.service.NotificationService;
import com.lv.tool.privatereader.service.impl.notification.ChapterNavigationHelper;
import com.lv.tool.privatereader.service.impl.notification.ProgressSaveHelper;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import com.lv.tool.privatereader.storage.cache.ReactiveChapterPreloader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import com.intellij.openapi.application.ModalityState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NotificationService 实现类
 *
 * 注意：在保存阅读进度时，我们需要特别注意页码的处理。
 * 1. 从数据库中恢复的页码是1基索引，我们将其转换为0基索引的 currentPageIndex
 * 2. 在保存页码时，我们不能使用 bookService.saveReadingProgress 方法，因为它会将 currentPageIndex 加1
 * 3. 如果我们使用 bookService.saveReadingProgress 方法，它会再次将 currentPageIndex 加1，导致页码始终是1
 * 4. 我们应该直接使用 SqliteReadingProgressRepository 类的 updateProgress 方法的重载版本，直接传递页码参数
 */
@Service(Service.Level.APP)
public final class NotificationServiceImpl implements NotificationService, Disposable {
    private static final Logger LOG = Logger.getInstance(NotificationServiceImpl.class);
    // 通知相关字段
    private final AtomicReference<Notification> currentNotificationRef = new AtomicReference<>(null);
    private MessageBusConnection messageBusConnection;

    // 服务相关字段
    private BookService bookService;
    private ChapterService chapterService;
    private NotificationReaderSettings notificationSettings;
    private ReactiveChapterPreloader chapterPreloader;
    private final ReactiveSchedulers reactiveSchedulers;
    private ChapterChangeManager chapterChangeManager;

    // 分页相关字段
    private List<String> currentPages = new ArrayList<>();
    private int currentPageIndex = 0;
    private Book currentBook;
    private String currentChapterId;
    private String currentChapterTitle;

    // 加载状态标志
    private final AtomicBoolean isLoadingChapter = new AtomicBoolean(false);
    private final AtomicBoolean isHandlingEvent = new AtomicBoolean(false);

    /**
     * 无参构造方法
     */
    public NotificationServiceImpl() {
        LOG.debug("初始化 NotificationServiceImpl");
        this.reactiveSchedulers = ReactiveSchedulers.getInstance();
        // 其他服务会在首次使用时延迟初始化
        this.messageBusConnection = ApplicationManager.getApplication().getMessageBus().connect(this);
        this.messageBusConnection.subscribe(CurrentChapterNotifier.TOPIC, new CurrentChapterNotifier() {
            @Override
            public void currentChapterChanged(Book changedBook, NovelParser.Chapter newChapter) {
                handleChapterChangedEvent(changedBook, newChapter);
            }
        });
    }

    /**
     * 确保服务已初始化
     */
    private void ensureServicesInitialized() {
        if (bookService == null) {
            bookService = ApplicationManager.getApplication().getService(BookService.class);
        }

        if (chapterService == null) {
            chapterService = ApplicationManager.getApplication().getService(ChapterService.class);
        }

        if (notificationSettings == null) {
            notificationSettings = ApplicationManager.getApplication().getService(NotificationReaderSettings.class);
        }

        if (chapterPreloader == null) {
            chapterPreloader = ApplicationManager.getApplication().getService(ReactiveChapterPreloader.class);
        }
        if (chapterChangeManager == null) {
            chapterChangeManager = ApplicationManager.getApplication().getService(ChapterChangeManager.class);
        }
    }

    @Override
    public void setCurrentChapterContent(@NotNull String content) {
        ensureServicesInitialized();
        int pageSize = notificationSettings != null ? notificationSettings.getPageSize() : 70; // Use setting for page size
        LOG.debug("NotificationServiceImpl: 设置当前章节内容，使用页面大小: " + pageSize +
                 ", notificationSettings 是否为 null: " + (notificationSettings == null));

        // 记录当前页码索引
        int oldPageIndex = this.currentPageIndex;
        LOG.debug(String.format("[页码调试] setCurrentChapterContent 调用前的页码索引: %d", oldPageIndex));

        this.currentPages = paginateContent(content, pageSize);
        this.currentPageIndex = 0; // Reset to first page

        LOG.debug(String.format("[页码调试] setCurrentChapterContent 调用后的页码索引: %d (重置为0)", this.currentPageIndex));
        LOG.debug("NotificationServiceImpl: 分页完成，总页数: " + this.currentPages.size());
    }

    @Override
    public int calculateTotalPages(@NotNull String content) {
        ensureServicesInitialized();
        LOG.debug("NotificationServiceImpl: 计算总页数");
        int pageSize = notificationSettings != null ? notificationSettings.getPageSize() : 70; // Use setting for page size
        return paginateContent(content, pageSize).size();
    }

    @Override
    public String getPageContent(@NotNull String content, int pageNumber) {
        ensureServicesInitialized();
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
        ensureServicesInitialized();
        LOG.debug("NotificationServiceImpl: 显示章节内容通知: " + title);

        if (content == null || content.isEmpty()) {
            LOG.warn("章节内容为空，无法显示通知: " + chapterId);
            showError("显示章节失败", "章节内容为空");
            return;
        }

        // 异步获取Book对象
        bookService.getBookById(bookId)
            .subscribeOn(reactiveSchedulers.io())
            .subscribe(book -> {
                if (book == null) {
                    LOG.warn("未找到书籍，无法显示通知: " + bookId);
                    showError("显示章节失败", "未找到书籍");
                    return;
                }
                
                // 在UI线程中执行UI更新操作
                ApplicationManager.getApplication().invokeLater(() -> {
                    // 关闭当前通知
                    closeCurrentNotificationInternal();
                    
                    // 保存当前书籍、章节和标题信息
                    currentBook = book;
                    currentChapterId = chapterId;
                    currentChapterTitle = title; // 使用传入的标题
                    
                    // 检查是否有保存的页码信息
                    int savedPageNumber = pageNumber;

                    // 如果传入的页码是1（默认值），尝试从数据库中获取保存的页码
                    if (pageNumber == 1) {
                        savedPageNumber = restoreSavedPageNumber(bookId, chapterId, pageNumber);
                    }
                    
                    // 分页并设置当前页码
                    setCurrentChapterContent(content);
                    
                    // 确保页码在有效范围内
                    if (savedPageNumber <= 0) {
                        savedPageNumber = 1;
                    } else if (savedPageNumber > currentPages.size()) {
                        savedPageNumber = currentPages.size();
                    }
                    
                    // 设置当前页码索引（0-based）
                    currentPageIndex = savedPageNumber - 1;
                    LOG.debug(String.format("[页码调试] 设置当前页码索引: %d (页码: %d)", currentPageIndex, savedPageNumber));
                    
                    // 获取当前页内容
                    String pageContent = currentPages.get(currentPageIndex);
                    
                    // 构建通知标题和内容
                    String notificationTitle = book.getTitle() + " - " + title;
                    
                    // 添加页码信息（如果设置启用）
                    String progressText = notificationSettings != null && notificationSettings.isShowReadingProgress() ?
                            "进度: 第 " + (currentPageIndex + 1) + " 页，共 " + currentPages.size() + " 页" : "";
                    
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
     * 内部方法，用于在通知中显示当前页面内容
     * 增强版本支持更多的交互选项和更好的格式化
     *
     * @param project 当前项目
     * @param title 通知标题
     * @param content 页面内容
     */
    private void showCurrentPageInternal(@NotNull Project project, @NotNull String title, @NotNull String content) {
        if (currentPages.isEmpty() || currentPageIndex < 0 || currentPageIndex >= currentPages.size()) {
            LOG.warn("当前页索引无效: " + currentPageIndex);
            showError("显示页面失败", "当前页索引无效");
            return;
        }

        // 构建通知标题
        String notificationTitle = (currentPageIndex == 0) ? title : "阅读中";
        if (notificationSettings != null && notificationSettings.isShowPageNumbers()) {
            notificationTitle += String.format(" (第%d页/共%d页)", currentPageIndex + 1, currentPages.size());
        }

        // 清理内容并创建通知
        String cleanContent = cleanHtmlTags(content);
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID_READER)
                .createNotification(cleanContent, NotificationType.INFORMATION)
                .setTitle(notificationTitle);

        // 如果启用，则添加导航操作
        if (notificationSettings != null && notificationSettings.isShowButtons()) {
            addNotificationActions(project, notification);
        }

        // 显示通知
        currentNotificationRef.set(notification);
        com.intellij.notification.Notifications.Bus.notify(notification, project);
        this.isLoadingChapter.set(false);

        LOG.debug("[通知栏模式] 显示通知: " + notificationTitle + ", 当前页: " + (currentPageIndex + 1) + "/" + currentPages.size());
    }
private void addNotificationActions(@NotNull Project project, @NotNull Notification notification) {
        // Page navigation
        if (currentPageIndex > 0) {
            notification.addAction(NotificationAction.createSimple("上一页", () -> showPrevPage(project)));
        }
        if (currentPageIndex < currentPages.size() - 1) {
            notification.addAction(NotificationAction.createSimple("下一页", () -> showNextPage(project)));
        }

        // Chapter navigation
        notification.addAction(NotificationAction.createSimple("上一章", () -> navigateChapter(project, -1)));
        notification.addAction(NotificationAction.createSimple("下一章", () -> navigateChapter(project, 1)));

        // Return to reader mode
        notification.addAction(NotificationAction.createSimple("返回阅读器", () -> {
            ReaderModeSettings settings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
            if (settings != null) {
                settings.setNotificationMode(false);
            }
        }));
    }

    @Override
    public void updateNotificationContent(@NotNull Project project, @NotNull String content) {
        ensureServicesInitialized();
        LOG.debug("NotificationServiceImpl: 更新通知内容");

        Notification existingNotification = currentNotificationRef.get();
        if (existingNotification != null && !existingNotification.isExpired()) {
            String oldContent = existingNotification.getContent(); // Get current content from notification
            if (content.equals(oldContent)) {
                LOG.debug("[通知栏模式] 更新内容与当前通知内容相同 (传入长度: " + content.length() + ",现有长度: " + (oldContent != null ? oldContent.length() : "null") + ")，跳过重新通知");
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
        ensureServicesInitialized();
        LOG.debug("NotificationServiceImpl: 显示错误: " + title + " - " + message);
        this.isLoadingChapter.set(false); // 清除正在加载章节状态，因为加载已失败

        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID_READER)
                .createNotification(message, NotificationType.ERROR);

        notification.setTitle(title);
        notification.notify(null); // Notify without project context for general errors

        // 记录日志
        LOG.debug("[通知栏模式] 显示错误通知: " + title);
        return Single.just(notification); // Return Single for compatibility
    }

    @Override
    public Single<Notification> showInfo(@NotNull String title, @NotNull String message) {
        ensureServicesInitialized();
        LOG.debug("NotificationServiceImpl: 显示信息: " + title + " - " + message);

        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID_READER)
                .createNotification(message, NotificationType.INFORMATION);

        notification.setTitle(title);
        notification.notify(null); // Notify without project context for general info

        // 记录日志
        LOG.debug("[通知栏模式] 显示信息通知: " + title);
        return Single.just(notification); // Return Single for compatibility
    }

    @Override
    public Completable closeAllNotificationsReactive() {
        ensureServicesInitialized();
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
        ensureServicesInitialized();
        LOG.debug("NotificationServiceImpl: 关闭所有通知");
        closeCurrentNotificationInternal();

        // 记录日志
        LOG.debug("[通知栏模式] 已关闭所有通知");
    }

    @Override
    public int getCurrentPage() {
        if (currentPages.isEmpty()) {
            return 0;
        }
        // 返回1基索引的页码（currentPageIndex是0基索引）
        return currentPageIndex + 1;
    }

    @Override
    public int getTotalPages() {
        return currentPages.size();
    }

    @Override
    public String getCurrentBookId() {
        return currentBook != null ? currentBook.getId() : null;
    }

    @Override
    public String getCurrentChapterId() {
        return currentChapterId;
    }

    @Override
    public void showPrevPage(@NotNull Project project) {
        if (isLoadingChapter.get() || !isReadingActive()) {
            return;
        }
        if (currentPageIndex <= 0) {
            LOG.info("[通知栏模式] 当前是第一页，尝试跳转到上一章的最后一页");
            navigateChapterToLastPage(project, -1);
        } else {
            updateAndShowPage(project, currentPageIndex - 1);
        }
    }

    @Override
    public void showNextPage(@NotNull Project project) {
        if (isLoadingChapter.get() || !isReadingActive()) {
            return;
        }
        if (currentPageIndex >= currentPages.size() - 1) {
            LOG.info("[通知栏模式] 当前是最后一页，尝试跳转到下一章的第一页");
            navigateChapter(project, 1);
        } else {
            updateAndShowPage(project, currentPageIndex + 1);
        }
    }

    @Override
    public void navigateChapter(@NotNull Project project, int direction) {
        if (isLoadingChapter.get() || !isReadingActive()) {
            return;
        }
        showLoadingNotification(project, "正在加载章节...");

        List<Chapter> cachedChapters = currentBook.getCachedChapters();
        if (cachedChapters != null && !cachedChapters.isEmpty()) {
            LOG.info("使用Book中的cachedChapters进行导航，章节数量: " + cachedChapters.size());
            reactiveSchedulers.runOnUI(() -> processChapterNavigationWithCachedChapters(project, cachedChapters, direction));
        } else {
            LOG.info("Book中的cachedChapters为空，使用bookService.getChaptersSync获取章节列表");
            Single.fromCallable(() -> bookService.getChaptersSync(currentBook.getId()))
                .subscribeOn(Schedulers.io())
                .timeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .doOnError(e -> reactiveSchedulers.runOnUI(() -> showError("导航失败", "获取章节列表时出错: " + e.getMessage())))
                .subscribe(chapters -> reactiveSchedulers.runOnUI(() -> processChapterNavigation(project, chapters, direction)));
        }
    }

    private void updateAndShowPage(@NotNull Project project, int newPageIndex) {
        this.currentPageIndex = newPageIndex;
        String title = currentBook.getTitle() + " - " + currentChapterTitle;
        String pageContent = currentPages.get(currentPageIndex);
        String progressText = notificationSettings != null && notificationSettings.isShowReadingProgress() ?
                String.format("进度: 第 %d 页，共 %d 页", currentPageIndex + 1, currentPages.size()) : "";
        String notificationContent = pageContent + (progressText.isEmpty() ? "" : "\n\n" + progressText);
        
        showCurrentPageInternal(project, title, notificationContent);
        saveNotificationModeProgress();
        LOG.info("[通知栏模式] 成功显示页面，当前页索引: " + currentPageIndex);
    }
    
    private boolean isReadingActive() {
        if (currentBook == null || currentChapterId == null || currentChapterTitle == null) {
            LOG.warn("[通知栏模式] 当前没有正在阅读的内容");
            showInfo("导航", "当前没有正在阅读的内容");
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

        // 创建加载状态通知
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID_READER)
                .createNotification(message, NotificationType.INFORMATION);

        notification.setTitle("加载中");
        currentNotificationRef.set(notification);
        com.intellij.notification.Notifications.Bus.notify(notification, project);

        LOG.info("[通知栏模式] 显示加载状态通知: " + message);
    }

    /**
     * 处理章节导航逻辑
     * 在UI线程上执行
     *
     * @param project 当前项目
     * @param chapters 章节列表
     * @param direction 导航方向
     */
    private void processChapterNavigation(@NotNull Project project,
                                         @Nullable List<ChapterService.EnhancedChapter> chapters,
                                         int direction) {
        if (chapters == null || chapters.isEmpty()) {
            LOG.warn("章节列表为空");
            showInfo("导航", "章节列表为空");
            return;
        }

        // 提取章节URL列表
        List<String> chapterUrls = chapters.stream().map(ChapterService.EnhancedChapter::url).toList();

        // 使用工具类查找当前章节索引
        int currentIndex = ChapterNavigationHelper.findChapterIndex(currentBook, currentChapterId, chapterUrls);

        // 验证导航目标
        String validationError = ChapterNavigationHelper.validateNavigation(currentIndex, direction, chapters.size());
        if (validationError != null) {
            LOG.warn(validationError);
            if (currentIndex < 0) {
                showError("导航失败", validationError);
            } else {
                showInfo("导航", validationError);
            }
            return;
        }

        int targetIndex = ChapterNavigationHelper.calculateTargetIndex(currentIndex, direction);

        // Get the target chapter and its content
        ChapterService.EnhancedChapter targetChapter = chapters.get(targetIndex);
        String targetChapterId = targetChapter.url();
        String targetChapterTitle = targetChapter.title();
        String targetChapterContent = targetChapter.getContent();

        if (targetChapterContent == null || targetChapterContent.isEmpty()) {
             LOG.warn("目标章节内容为空: " + targetChapterId);
             showError("导航失败", "目标章节内容为空");
             return;
        }

        // Update current book, chapter, and page
        this.currentChapterId = targetChapterId;
        this.currentChapterTitle = targetChapterTitle;
        setCurrentChapterContent(targetChapterContent);
        this.currentPageIndex = 0;

        if (currentPages.isEmpty()) {
            LOG.warn("分页后内容为空，无法显示通知: " + targetChapterId);
            showError("显示章节失败", "分页后内容为空");
            return;
        }

        // 使用工具类构建通知内容并显示
        String title = ProgressSaveHelper.buildNotificationTitle(currentBook.getTitle(), targetChapterTitle);
        String notificationContent = ProgressSaveHelper.buildNotificationContent(
            currentPages.get(currentPageIndex), currentPageIndex, currentPages.size(),
            notificationSettings != null && notificationSettings.isShowReadingProgress());

        showCurrentPageInternal(project, title, notificationContent);

        // 使用工具类保存进度
        ProgressSaveHelper.saveProgress(currentBook, targetChapterId, targetChapterTitle, currentPageIndex);

        LOG.info("[通知栏模式] 导航到章节: " + targetChapterId);

        // 触发章节预加载
        triggerChapterPreload(currentBook, targetIndex);

        // 设置事件源并发布章节变更事件
        if (chapterChangeManager != null) {
            chapterChangeManager.setEventSource(ChapterChangeEventSource.NOTIFICATION_SERVICE);
        }
        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(CurrentChapterNotifier.TOPIC)
                .currentChapterChanged(currentBook, new NovelParser.Chapter(targetChapterTitle, targetChapterId));
        LOG.info("[通知栏模式] 已发布章节变更事件: " + targetChapterTitle);
    }

    /**
     * 使用Book中的cachedChapters处理章节导航逻辑
     * 在UI线程上执行
     *
     * @param project 当前项目
     * @param cachedChapters 缓存的章节列表
     * @param direction 导航方向
     */
    private void processChapterNavigationWithCachedChapters(@NotNull Project project,
                                                          @Nullable List<Chapter> cachedChapters,
                                                          int direction) {
        if (cachedChapters == null || cachedChapters.isEmpty()) {
            LOG.warn("缓存的章节列表为空，无法导航");
            showInfo("导航", "章节列表为空");
            return;
        }

        // 提取章节URL列表
        List<String> chapterUrls = cachedChapters.stream().map(Chapter::url).toList();

        // 使用工具类查找当前章节索引
        int currentIndex = ChapterNavigationHelper.findChapterIndex(currentBook, currentChapterId, chapterUrls);

        // 验证导航目标
        String validationError = ChapterNavigationHelper.validateNavigation(currentIndex, direction, cachedChapters.size());
        if (validationError != null) {
            LOG.warn(validationError);
            if (currentIndex < 0) {
                showError("导航失败", validationError);
            } else {
                showInfo("导航", validationError);
            }
            return;
        }

        int targetIndex = ChapterNavigationHelper.calculateTargetIndex(currentIndex, direction);

        // 获取目标章节
        Chapter targetChapter = cachedChapters.get(targetIndex);
        String targetChapterId = targetChapter.url();
        String targetChapterTitle = targetChapter.title();

        // 使用异步方式获取章节内容，避免阻塞UI线程
        Single.fromCallable(() -> {
            if (currentBook.getParser() != null) {
                return currentBook.getParser().getChapterContent(targetChapterId, currentBook);
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
            reactiveSchedulers.runOnUI(() -> {
                if (content == null || content.isEmpty()) {
                    LOG.warn("目标章节内容为空: " + targetChapterId);
                    showError("导航失败", "目标章节内容为空");
                    return;
                }

                // 更新当前章节信息
                currentChapterId = targetChapterId;
                currentChapterTitle = targetChapterTitle;
                setCurrentChapterContent(content);
                if (currentPages.isEmpty()) {
                    LOG.warn("[通知栏模式] 分页后内容为空，无法显示通知: " + targetChapterId);
                    showError("显示章节失败", "分页后内容为空");
                    return;
                }
                currentPageIndex = 0;

                // 使用工具类构建通知内容并显示
                String title = ProgressSaveHelper.buildNotificationTitle(currentBook.getTitle(), targetChapterTitle);
                String notificationContent = ProgressSaveHelper.buildNotificationContent(
                    currentPages.get(currentPageIndex), currentPageIndex, currentPages.size(),
                    notificationSettings != null && notificationSettings.isShowReadingProgress());

                showCurrentPageInternal(project, title, notificationContent);

                // 使用工具类保存进度
                ProgressSaveHelper.saveProgress(currentBook, targetChapterId, targetChapterTitle, currentPageIndex);

                LOG.info("[通知栏模式] 使用cachedChapters导航到章节: " + targetChapterId);

                // 触发章节预加载
                triggerChapterPreload(currentBook, targetIndex);

                // 设置事件源并发布章节变更事件
                if (chapterChangeManager != null) {
                    chapterChangeManager.setEventSource(ChapterChangeEventSource.NOTIFICATION_SERVICE);
                }
                ApplicationManager.getApplication().getMessageBus()
                        .syncPublisher(CurrentChapterNotifier.TOPIC)
                        .currentChapterChanged(currentBook, targetChapter);
                LOG.info("[通知栏模式] 已发布章节变更事件: " + targetChapter.title());
            });
        });
    }

    // Existing reactive methods (kept for compatibility if still used elsewhere)
    @Override
    public Single<Notification> showChapterContent(@NotNull Book book, @NotNull String chapterId, @NotNull String content) {
        return Single.defer(() -> chapterService.getChapterTitle(book.getId(), chapterId)
                .flatMap(title -> {
                    ensureServicesInitialized();
                    LOG.info("[通知栏模式] (Reactive) 显示章节内容: " + book.getTitle() + " - " + chapterId + ", 内容长度: " + content.length());

                    // 关闭当前通知
                    closeCurrentNotificationInternal();

                    if (content == null || content.isEmpty()) {
                        LOG.warn("[通知栏模式] (Reactive) 章节内容为空，无法显示通知: " + chapterId);
                        return showError("显示章节失败", "章节内容为空");
                    }

                    // 保存当前书籍、章节和内容信息
                    this.currentBook = book;
                    this.currentChapterId = chapterId;
                    this.currentChapterTitle = title;

                    // 检查是否有保存的页码信息
                    int savedPageNumber = 1; // 默认从第1页开始（对应索引0）

            // 尝试从数据库中获取保存的页码
            savedPageNumber = restoreSavedPageNumber(book.getId(), chapterId, savedPageNumber);

            // 记录恢复的页码
            LOG.info(String.format("[页码调试] (Reactive) 恢复的页码: %d", savedPageNumber));

            // 使用 setCurrentChapterContent 方法进行分页
            setCurrentChapterContent(content);

            // 分页后，设置恢复的页码索引
            if (savedPageNumber > 0 && savedPageNumber <= currentPages.size()) {
                this.currentPageIndex = savedPageNumber - 1;
            } else {
                this.currentPageIndex = 0;
            }
            LOG.info(String.format("[页码调试] (Reactive) 重新设置页码索引: %d (对应页码: %d)",
                    this.currentPageIndex, this.currentPageIndex + 1));

            // 更新UI
            // updateNotificationContent(project, book.getTitle(), currentChapterTitle);

            // 确保页码索引在有效范围内
            if (this.currentPageIndex < 0) {
                LOG.info("[页码调试] (Reactive) 页码索引小于0，重置为0");
                this.currentPageIndex = 0;
            } else if (this.currentPageIndex >= currentPages.size()) {
                LOG.info("[页码调试] (Reactive) 页码索引超出范围，重置为最后一页");
                this.currentPageIndex = Math.max(0, currentPages.size() - 1);
            }

            if (currentPages.isEmpty()) {
                LOG.warn("[通知栏模式] (Reactive) 分页后内容为空，无法显示通知: " + chapterId);
                return showError("显示章节失败", "分页后内容为空");
            }

            // 获取当前页内容
            String pageContent = currentPages.get(currentPageIndex);
            String progressText = notificationSettings != null && notificationSettings.isShowReadingProgress() ?
                    "进度: 第 " + (currentPageIndex + 1) + " 页，共 " + currentPages.size() + " 页" : "";
            String notificationContent = pageContent + (progressText.isEmpty() ? "" : "\n\n" + progressText);

            // 构建通知标题
            // String title = book.getTitle() + " - " + currentChapterTitle; // 变量title已在此作用域中定义

            // 使用 Project 对象，如果可用
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
                // 注意：不使用 bookService.saveReadingProgress 方法，因为它会将 currentPageIndex 加1
                // 而我们已经从数据库中恢复的页码是1基索引，转换为 currentPageIndex 时减了1
                // 如果再使用 bookService.saveReadingProgress 方法，它会再次将 currentPageIndex 加1，导致页码始终是1
                SqliteReadingProgressRepository readingProgressRepository = ApplicationManager.getApplication().getService(SqliteReadingProgressRepository.class);
                if (readingProgressRepository != null) {
                    // 使用带页码参数的重载方法，position设为0，直接使用currentPageIndex + 1作为页码
                    readingProgressRepository.updateProgress(book, chapterId, currentChapterTitle, 0, currentPageIndex + 1);
                    LOG.info(String.format("[页码调试] (Reactive) 直接保存页码: %d", currentPageIndex + 1));
                } else {
                    LOG.warn("[页码调试] (Reactive) 无法获取 SqliteReadingProgressRepository 实例，使用 bookService.saveReadingProgress 方法");
                    bookService.saveReadingProgress(book, chapterId, currentChapterTitle, currentPageIndex);
                }
                LOG.info("[通知栏模式] (Reactive) 显示章节内容成功，使用 Project 对象");

                // 查找当前章节在章节列表中的索引，并触发预加载
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
                        LOG.warn("[通知栏模式] (Reactive) 无法找到当前章节在列表中的索引，跳过预加载");
                    }
                } else {
                    LOG.warn("[通知栏模式] (Reactive) 章节列表为空，无法预加载");
                }

                return Single.just(currentNotificationRef.get());
            } else {
                // 如果无法获取 Project 对象，使用简单的通知
                LOG.warn("[通知栏模式] (Reactive) 无法获取 Project 对象，使用简单通知");
                // 清理内容中的HTML标签
                String cleanContent = cleanHtmlTags(notificationContent);
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
    public Single<Notification> showPrevPageReactive() {
        // This reactive method is likely for the main reader panel and might need different logic
        // For now, it will just log a warning
        LOG.warn("showPrevPageReactive called - Implementation needed for main reader panel.");
        return Single.error(new UnsupportedOperationException("Not implemented for notification bar mode"));
    }

    @Override
    public Single<Notification> showNextPageReactive() {
         // This reactive method is likely for the main reader panel and might need different logic
        // For now, it will just log a warning
        LOG.warn("showNextPageReactive called - Implementation needed for main reader panel.");
        return Single.error(new UnsupportedOperationException("Not implemented for notification bar mode"));
    }

    @Override
    public Single<Notification> navigateChapterReactive(int direction) {
         // This reactive method is likely for the main reader panel and might need different logic
        // For now, it will just log a warning
        LOG.warn("navigateChapterReactive called - Implementation needed for main reader panel.");
        return Single.error(new UnsupportedOperationException("Not implemented for notification bar mode"));
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
        // Temporarily removing chapterPreloader dispose due to linter error. Will investigate its lifecycle later.

        // 尝试保存通知模式的阅读进度
        ReaderModeSettings currentReaderModeSettings = null;
        try {
            currentReaderModeSettings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
        } catch (Exception e) {
            LOG.error("Failed to get ReaderModeSettings service in dispose", e);
            // 如果无法获取服务，则不执行后续操作
            return;
        }

        if (currentReaderModeSettings != null && currentReaderModeSettings.isNotificationMode()) {
            LOG.info("[Dispose] Current NotificationServiceImpl state: currentPageIndex = " + this.currentPageIndex + ", currentPages.size() = " + (this.currentPages != null ? this.currentPages.size() : "null")); // ADDED LOG
            LOG.info("Currently in notification mode, attempting to save progress before disposing.");
            saveNotificationModeProgress();
        } else {
            LOG.info("Not in notification mode or ReaderModeSettings is null, no progress to save from notification bar.");
        }
        LOG.debug("NotificationServiceImpl disposed.");
    }

    /**
     * 将内容分页
     * 严格按照pageSize进行分页，同时尽量在段落或句子结束处分页，提供更好的阅读体验
     * 每页字符数严格等于pageSize（除了最后一页可能少于pageSize）
     *
     * @param content 内容
     * @param pageSize 每页字符数
     * @return 分页后的内容列表
     */
    private List<String> paginateContent(String content, int pageSize) {
        List<String> pages = new ArrayList<>();
        if (content == null || content.isEmpty() || pageSize <= 0) {
            return pages;
        }

        LOG.info("[分页] 开始分页，内容长度: " + content.length() + ", 页面大小: " + pageSize);

        int textLength = content.length();
        int startIndex = 0;

        while (startIndex < textLength) {
            int endIndex = Math.min(startIndex + pageSize, textLength);
            
            // If this is not the last chunk of text, try to find a natural break point.
            if (endIndex < textLength) {
                int breakPoint = -1;
                // Look for the last newline character within the current page candidate.
                for (int i = endIndex - 1; i >= startIndex; i--) {
                    if (content.charAt(i) == '\n') {
                        breakPoint = i + 1; // Break after the newline
                        break;
                    }
                }

                // If no newline, look for a sentence break, but don't look back too far.
                if (breakPoint == -1) {
                    for (int i = endIndex - 1; i >= startIndex && i > endIndex - 50; i--) { // Look back max 50 chars
                        char c = content.charAt(i);
                        if ("。！？.?!".indexOf(c) != -1) {
                            breakPoint = i + 1;
                            break;
                        }
                    }
                }
                
                // If we found a good break point, adjust the end index.
                if (breakPoint > startIndex) { // Ensure we are making progress
                    endIndex = breakPoint;
                }
            }
            
            pages.add(content.substring(startIndex, endIndex));
            startIndex = endIndex;
        }

        // Log page sizes for debugging
        if (!pages.isEmpty()) {
            StringBuilder pageSizeInfo = new StringBuilder("[分页] 各页字符数: ");
            for (int i = 0; i < pages.size(); i++) {
                if (i > 0) pageSizeInfo.append(", ");
                pageSizeInfo.append("#").append(i + 1).append(": ").append(pages.get(i).length());
            }
            LOG.info(pageSizeInfo.toString());
        }

        LOG.info("[分页] 分页完成，总页数: " + pages.size());
        return pages;
    }

    /**
     * 导航到指定章节的最后一页
     * 类似于 navigateChapter，但是跳转到目标章节的最后一页，而不是第一页
     *
     * @param project 当前项目
     * @param direction 方向，-1 表示上一章，1 表示下一章
     */
    private void navigateChapterToLastPage(@NotNull Project project, int direction) {
        if (this.isLoadingChapter.get()) {
            LOG.warn("[通知栏模式] 正在加载章节内容，忽略章节导航至末尾操作。");
            return;
        }
        ensureServicesInitialized();
        LOG.info("[通知栏模式] 导航到章节的最后一页，方向: " + direction);

        if (currentBook == null || currentChapterId == null) {
            LOG.warn("[通知栏模式] 当前没有正在阅读的内容");
            showInfo("导航", "当前没有正在阅读的内容");
            return;
        }

        // 显示加载状态通知
        showLoadingNotification(project, "正在加载章节...");

        // 首先尝试使用Book中的cachedChapters
        List<NovelParser.Chapter> cachedChapters = currentBook.getCachedChapters();
        if (cachedChapters != null && !cachedChapters.isEmpty()) {
            LOG.info("使用Book中的cachedChapters导航到最后一页，章节数量: " + cachedChapters.size());
            // 在UI线程上处理导航逻辑
            reactiveSchedulers.runOnUI(() -> processChapterNavigationToLastPageWithCachedChapters(project, cachedChapters, direction));
            return;
        }

        // 如果cachedChapters为空，则使用异步方式获取章节列表
        LOG.info("Book中的cachedChapters为空，使用bookService.getChaptersSync获取章节列表");
        Single.fromCallable(() -> bookService.getChaptersSync(currentBook.getId()))
            .subscribeOn(Schedulers.io()) // 在IO线程上执行
            .timeout(30, java.util.concurrent.TimeUnit.SECONDS) // 设置超时
            .doOnError(e -> {
                LOG.error("[通知栏模式] 获取章节列表时出错: " + e.getMessage(), e);
                reactiveSchedulers.runOnUI(() -> showError("导航失败", "获取章节列表时出错: " + e.getMessage()));
            })
            .subscribe(chapters -> {
                // 在获取到章节列表后，在UI线程上处理导航逻辑
                reactiveSchedulers.runOnUI(() -> processChapterNavigationToLastPage(project, chapters, direction));
            });
    }

    /**
     * 处理章节导航到最后一页的逻辑
     * 在UI线程上执行
     *
     * @param project 当前项目
     * @param chapters 章节列表
     * @param direction 导航方向
     */
    private void processChapterNavigationToLastPage(@NotNull Project project,
                                                  @Nullable List<ChapterService.EnhancedChapter> chapters,
                                                  int direction) {
        if (chapters == null || chapters.isEmpty()) {
            LOG.warn("[通知栏模式] 章节列表为空");
            showInfo("导航", "章节列表为空");
            return;
        }

        // 提取章节URL列表
        List<String> chapterUrls = chapters.stream().map(ChapterService.EnhancedChapter::url).toList();

        // 使用工具类查找当前章节索引
        int currentIndex = ChapterNavigationHelper.findChapterIndex(currentBook, currentChapterId, chapterUrls);

        // 验证导航目标
        String validationError = ChapterNavigationHelper.validateNavigation(currentIndex, direction, chapters.size());
        if (validationError != null) {
            LOG.warn("[通知栏模式] " + validationError);
            if (currentIndex < 0) {
                showError("导航失败", validationError);
            } else {
                showInfo("导航", validationError);
            }
            return;
        }

        int targetIndex = ChapterNavigationHelper.calculateTargetIndex(currentIndex, direction);

        // 获取目标章节的信息
        ChapterService.EnhancedChapter targetChapter = chapters.get(targetIndex);
        String targetChapterId = targetChapter.url();
        String targetChapterTitle = targetChapter.title();
        String targetChapterContent = targetChapter.getContent();

        if (targetChapterContent == null || targetChapterContent.isEmpty()) {
            LOG.warn("[通知栏模式] 目标章节内容为空: " + targetChapterId);
            showError("导航失败", "目标章节内容为空");
            return;
        }

        // 更新当前书籍、章节信息
        this.currentChapterId = targetChapterId;
        this.currentChapterTitle = targetChapterTitle;

        // 分页并设置为最后一页
        setCurrentChapterContent(targetChapterContent);
        if (currentPages.isEmpty()) {
            LOG.warn("[通知栏模式] 分页后内容为空，无法显示通知: " + targetChapterId);
            showError("显示章节失败", "分页后内容为空");
            return;
        }
        this.currentPageIndex = currentPages.size() - 1;

        // 使用工具类构建通知内容并显示
        String title = ProgressSaveHelper.buildNotificationTitle(currentBook.getTitle(), targetChapterTitle);
        String notificationContent = ProgressSaveHelper.buildNotificationContent(
            currentPages.get(currentPageIndex), currentPageIndex, currentPages.size(),
            notificationSettings != null && notificationSettings.isShowReadingProgress());

        showCurrentPageInternal(project, title, notificationContent);

        // 使用工具类保存进度
        ProgressSaveHelper.saveProgress(currentBook, targetChapterId, targetChapterTitle, currentPageIndex);

        LOG.info("[通知栏模式] 导航到章节的最后一页: " + targetChapterId);

        // 触发章节预加载
        triggerChapterPreload(currentBook, targetIndex);

        // 设置事件源并发布章节变更事件
        if (chapterChangeManager != null) {
            chapterChangeManager.setEventSource(ChapterChangeEventSource.NOTIFICATION_SERVICE);
        }
        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(CurrentChapterNotifier.TOPIC)
                .currentChapterChanged(currentBook, new NovelParser.Chapter(targetChapterTitle, targetChapterId));
        LOG.info("[通知栏模式] 已发布章节变更事件: " + targetChapterTitle);
    }

    /**
     * 使用Book中的cachedChapters处理章节导航到最后一页的逻辑
     * 在UI线程上执行
     *
     * @param project 当前项目
     * @param cachedChapters 缓存的章节列表
     * @param direction 导航方向
     */
    private void processChapterNavigationToLastPageWithCachedChapters(@NotNull Project project,
                                                                    @Nullable List<Chapter> cachedChapters,
                                                                    int direction) {
        if (cachedChapters == null || cachedChapters.isEmpty()) {
            LOG.warn("[通知栏模式] 缓存的章节列表为空");
            showInfo("导航", "章节列表为空");
            return;
        }

        // 提取章节URL列表
        List<String> chapterUrls = cachedChapters.stream().map(Chapter::url).toList();

        // 使用工具类查找当前章节索引
        int currentIndex = ChapterNavigationHelper.findChapterIndex(currentBook, currentChapterId, chapterUrls);

        // 验证导航目标
        String validationError = ChapterNavigationHelper.validateNavigation(currentIndex, direction, cachedChapters.size());
        if (validationError != null) {
            LOG.warn("[通知栏模式] " + validationError);
            if (currentIndex < 0) {
                showError("导航失败", validationError);
            } else {
                showInfo("导航", validationError);
            }
            return;
        }

        int targetIndex = ChapterNavigationHelper.calculateTargetIndex(currentIndex, direction);

        // 获取目标章节信息
        Chapter targetChapter = cachedChapters.get(targetIndex);
        String targetChapterId = targetChapter.url();
        String targetChapterTitle = targetChapter.title();

        // 显示加载状态通知
        showLoadingNotification(project, "正在加载章节内容...");

        // 使用异步方式获取章节内容，避免阻塞UI线程
        Single.fromCallable(() -> {
            if (currentBook.getParser() != null) {
                return currentBook.getParser().getChapterContent(targetChapterId, currentBook);
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
            reactiveSchedulers.runOnUI(() -> {
                if (content == null || content.isEmpty()) {
                    LOG.warn("[通知栏模式] 目标章节内容为空: " + targetChapterId);
                    showError("导航失败", "目标章节内容为空");
                    return;
                }

                // 更新当前章节信息
                currentChapterId = targetChapterId;
                currentChapterTitle = targetChapterTitle;
                setCurrentChapterContent(content);
                if (currentPages.isEmpty()) {
                    LOG.warn("[通知栏模式] 分页后内容为空，无法显示通知: " + targetChapterId);
                    showError("显示章节失败", "分页后内容为空");
                    return;
                }
                currentPageIndex = currentPages.size() - 1;

                // 使用工具类构建通知内容并显示
                String title = ProgressSaveHelper.buildNotificationTitle(currentBook.getTitle(), targetChapterTitle);
                String notificationContent = ProgressSaveHelper.buildNotificationContent(
                    currentPages.get(currentPageIndex), currentPageIndex, currentPages.size(),
                    notificationSettings != null && notificationSettings.isShowReadingProgress());

                showCurrentPageInternal(project, title, notificationContent);

                // 使用工具类保存进度
                ProgressSaveHelper.saveProgress(currentBook, targetChapterId, targetChapterTitle, currentPageIndex);

                LOG.info("[通知栏模式] 使用cachedChapters导航到章节的最后一页: " + targetChapterId);

                // 触发章节预加载
                triggerChapterPreload(currentBook, targetIndex);

                // 设置事件源并发布章节变更事件
                if (chapterChangeManager != null) {
                    chapterChangeManager.setEventSource(ChapterChangeEventSource.NOTIFICATION_SERVICE);
                }
                ApplicationManager.getApplication().getMessageBus()
                        .syncPublisher(CurrentChapterNotifier.TOPIC)
                        .currentChapterChanged(currentBook, targetChapter);
                LOG.info("[通知栏模式] 已发布章节变更事件: " + targetChapter.title());
            });
        });
    }

    /**
     * 清理HTML标签，保留换行符和基本格式
     * @param content 包含HTML标签的内容
     * @return 清理后的纯文本内容
     */
    private String cleanHtmlTags(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        // 记录原始内容长度
        int originalLength = content.length();
        LOG.debug("[通知栏模式] 清理HTML标签前内容长度: " + originalLength);

        // 1. 替换常见的HTML实体
        String result = content
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");

        // 2. 使用正则表达式移除所有HTML标签
        result = result.replaceAll("<[^>]*>", "");

        // 3. 确保段落之间有适当的换行
        result = result.replaceAll("\\n{3,}", "\n\n"); // 将3个或更多换行符替换为2个

        // 记录清理后内容长度
        int newLength = result.length();
        LOG.debug("[通知栏模式] 清理HTML标签后内容长度: " + newLength + ", 减少了: " + (originalLength - newLength) + " 个字符");

        return result;
    }

    /**
     * 关闭当前通知
     * 注意：只关闭通知，不重置其他状态（如 currentBook、currentChapterId 等）
     */
    private void closeCurrentNotificationInternal() {
        LOG.info("[通知栏模式] 关闭当前通知，当前页索引: " + currentPageIndex + ", 总页数: " + currentPages.size());

        Notification oldNotification = currentNotificationRef.getAndSet(null);
        if (oldNotification != null && !oldNotification.isExpired()) {
            try {
                oldNotification.expire();
                LOG.info("[通知栏模式] 成功关闭通知");
            } catch (Exception e) {
                LOG.error("[通知栏模式] 关闭通知时出错: " + e.getMessage(), e);
            }
        } else {
            LOG.info("[通知栏模式] 没有通知需要关闭或通知已过期");
        }

        // 不重置其他状态，以便在显示新通知时保持阅读进度
        // currentBook = null;
        // currentChapterId = null;
        // currentChapterTitle = null;
        // currentPages.clear();
        // currentPageIndex = 0;
    }

    /**
     * 触发章节预加载
     * 根据当前章节索引预加载前后章节
     *
     * @param book 当前阅读的书籍
     * @param chapterIndex 当前章节索引
     */
    private void triggerChapterPreload(@NotNull Book book, int chapterIndex) {
        ensureServicesInitialized();

        if (chapterPreloader == null) {
            LOG.warn("[通知栏模式] ReactiveChapterPreloader 未初始化，无法预加载章节");
            return;
        }

        LOG.info("[通知栏模式] 触发章节预加载: 书籍=" + book.getTitle() + ", 章节索引=" + chapterIndex);

        // 使用 ReactiveChapterPreloader 预加载前后章节
        chapterPreloader.preloadChaptersReactive(book, chapterIndex)
            .subscribe();
    }

    private void handleChapterChangedEvent(Book changedBook, Chapter newChapter) {
        ensureServicesInitialized(); // 确保所有依赖的服务都已初始化
        if (chapterChangeManager.getLastEventSource() != ChapterChangeEventSource.READER_PANEL) {
            return;
        }

        if (!isHandlingEvent.compareAndSet(false, true)) {
            LOG.debug("[事件处理] 正在处理另一个章节变更事件，忽略当前事件。");
            return;
        }

        try {
            LOG.debug("[事件处理] 接收到章节变更事件: 书籍={}, 新章节={}", changedBook.getTitle(), newChapter.title());

            ReaderModeSettings readerModeSettings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
            if (readerModeSettings == null || !readerModeSettings.isNotificationMode()) {
                LOG.debug("[事件处理] 非通知栏模式，忽略章节变更事件。");
                return;
            }

            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            if (openProjects.length == 0) {
                LOG.warn("[事件处理] 没有打开的项目，无法处理章节变更事件并更新通知。");
                return;
            }
            Project project = openProjects[0]; // 默认使用第一个打开的项目
            if (openProjects.length > 1) {
                LOG.warn("[事件处理] 有多个项目打开，将使用第一个项目: " + project.getName() + " 来更新通知。");
            }

            // 检查书籍或章节是否真的改变了
            if (this.currentBook != null && this.currentBook.equals(changedBook) &&
                this.currentChapterId != null && this.currentChapterId.equals(newChapter.url())) {
                LOG.debug("[事件处理] 书籍和章节未发生变化，无需更新通知。");
                return;
            }

            LOG.info("[事件处理] 检测到章节变更 (之前: 书籍='" + (this.currentBook != null ? this.currentBook.getTitle() : "无") +
                     "', 章节ID='" + (this.currentChapterId != null ? this.currentChapterId : "无") +
                     "'; 现在: 书籍='" + changedBook.getTitle() +
                     "', 章节='" + newChapter.title() + "'), 准备在通知栏模式下更新显示。");

            // 获取新章节内容 - 使用单一响应式链，减少线程切换
            chapterService.getChapterContent(changedBook, newChapter.url())
                .subscribeOn(Schedulers.io()) // 在IO线程执行耗时操作
                .flatMap(content -> {
                    if (content == null || content.isEmpty()) {
                        LOG.warn("[事件处理] 获取到的新章节 '" + newChapter.title() + "' 内容为空，不更新通知。");
                        return Single.error(new IllegalStateException("章节内容为空"));
                    }

                    // 异步获取章节标题
                    return chapterService.getChapterTitle(changedBook.getId(), newChapter.url())
                        .map(fetchedTitle -> {
                            if (fetchedTitle == null || fetchedTitle.isEmpty() || fetchedTitle.startsWith("Error:")) {
                                LOG.warn("[事件处理] 获取到的章节标题无效 ('" + fetchedTitle + "')，回退到 newChapter.title()");
                                return newChapter.title();
                            }
                            return fetchedTitle;
                        })
                        .onErrorReturnItem(newChapter.title()) // 如果获取标题时出错，也使用默认标题
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
                                this.currentBook = changedBook;
                                this.currentChapterId = newChapter.url();
                                this.currentChapterTitle = fetchedTitle;

                                // 分页
                                setCurrentChapterContent(content);
                                if (this.currentPages.isEmpty()) {
                                    LOG.warn("[事件处理] 新章节 '" + newChapter.title() + "' 分页后内容为空，无法显示通知。");
                                    showError("章节内容为空", "无法在通知栏显示章节 " + newChapter.title());
                                    return;
                                }

                                // 尝试恢复页码，否则显示第一页
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
                                } else if (!this.currentPages.isEmpty() && pageToLoad > this.currentPages.size()) {
                                    pageToLoad = this.currentPages.size();
                                } else if (this.currentPages.isEmpty()) {
                                    pageToLoad = 1;
                                }

                                this.currentPageIndex = pageToLoad - 1;

                                String pageContentToShow = this.currentPages.get(this.currentPageIndex);
                                String progressText = notificationSettings != null && notificationSettings.isShowReadingProgress() ?
                                     "进度: 第 " + (this.currentPageIndex + 1) + " 页，共 " + this.currentPages.size() + " 页" : "";
                                String notificationContent = pageContentToShow + (progressText.isEmpty() ? "" : "\n\n" + progressText);

                                showCurrentPageInternal(project, this.currentChapterTitle, notificationContent);
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
     * @return 恢复的页码，如果未找到则返回默认页码
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
                        LOG.debug("[页码调试] 章节ID不匹配，无法恢复页码");
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
        if (!isReadingActive()) {
            LOG.warn("[进度保存] 无法保存通知栏模式进度：没有活动的阅读会话。");
            return;
        }
        
        try {
            SqliteReadingProgressRepository repository = ApplicationManager.getApplication().getService(SqliteReadingProgressRepository.class);
            if (repository != null) {
                int pageToSave = currentPageIndex + 1;
                repository.updateProgress(currentBook, currentChapterId, currentChapterTitle, 0, pageToSave);
                LOG.info(String.format("[进度保存] 成功保存通知栏模式阅读进度：书籍='%s', 章节='%s', 页码=%d",
                        currentBook.getTitle(), currentChapterTitle, pageToSave));
            } else {
                LOG.error("[进度保存] SqliteReadingProgressRepository 服务未找到，无法保存进度。");
            }
        } catch (Exception e) {
            LOG.error("[进度保存] 保存通知栏模式阅读进度时发生意外错误。", e);
        }
    }
}