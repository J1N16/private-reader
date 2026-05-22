package com.lv.tool.privatereader.service;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookProgressData;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import com.lv.tool.privatereader.settings.NotificationReaderSettingsListener;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import com.intellij.openapi.application.ModalityState;

import java.util.Optional;

/**
 * 通知栏模式服务，负责管理通知栏阅读模式的状态和行为。
 * 实现为应用级服务，确保在整个IDE中共享同一个实例。
 *
 * 注意：在保存阅读进度时，使用 readingProgressRepository.updateProgress(book, chapterId, chapterTitle, position, page)
 * 方法的重载版本，其中 position 设为 0，page 参数直接使用 currentPageNumber。
 * 这是因为 currentPageNumber 是 1 基索引的页码，而不是滚动位置。
 * 如果使用 updateProgress(book, chapterId, chapterTitle, position) 方法，它会将 position 参数作为滚动位置，
 * 而使用 book.getLastReadPageOrDefault(1) 作为页码，这会导致页码始终是 1 或者是 book 对象中已有的页码。
 */
@Service(Service.Level.APP)
public class NotificationBarModeService implements Disposable, NotificationReaderSettingsListener {

    private static final Logger LOG = Logger.getInstance(NotificationBarModeService.class);

    private final ReaderModeSettings readerModeSettings;
    private final NotificationService notificationService;
    private final ChapterService chapterService;
    private final BookService bookService;
    private final ReadingProgressRepository readingProgressRepository;
    private final NotificationReaderSettings notificationReaderSettings;
    private Project project; // Made non-final to allow initialization in constructor body

    private String currentBookId;
    private String currentChapterId;
    private int currentPageNumber;

    /**
     * 获取 NotificationBarModeService 实例
     * @return NotificationBarModeService 实例
     */
    public static NotificationBarModeService getInstance() {
        return ApplicationManager.getApplication().getService(NotificationBarModeService.class);
    }

    public NotificationBarModeService() {
        LOG.info("NotificationBarModeService 构造函数被调用");

        this.readerModeSettings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
        this.notificationService = ApplicationManager.getApplication().getService(NotificationService.class);
        this.chapterService = ApplicationManager.getApplication().getService(ChapterService.class);
        this.bookService = ApplicationManager.getApplication().getService(BookService.class);
        this.readingProgressRepository = ApplicationManager.getApplication().getService(ReadingProgressRepository.class);
        this.notificationReaderSettings = ApplicationManager.getApplication().getService(NotificationReaderSettings.class);

        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        if (openProjects.length > 0) {
            this.project = openProjects[0];
            LOG.info("使用第一个打开的项目: " + this.project.getName());
        } else {
            this.project = null;
            LOG.warn("没有打开的项目，NotificationBarModeService 可能无法在构造时确定默认项目");
        }

        // Subscribe to settings changes
        ApplicationManager.getApplication().getMessageBus().connect(this) // 'this' as Disposable
            .subscribe(NotificationReaderSettingsListener.TOPIC, this);
        LOG.info("NotificationBarModeService subscribed to NotificationReaderSettingsListener.");

        LOG.info("NotificationBarModeService 初始化完成");
    }

    /**
     * Activates the notification bar reading mode.
     * @param bookId The ID of the book to read.
     * @param chapterId The ID of the chapter to read.
     * @param pageNumber The page number to start reading from.
     */
    public void activateNotificationBarMode(String bookId, String chapterId, int pageNumber) {
        // 1. Update ReaderModeSettings to notification bar mode
        readerModeSettings.setCurrentMode(ReaderModeSettings.Mode.NOTIFICATION_BAR);

        this.currentBookId = bookId;
        this.currentChapterId = chapterId;
        this.currentPageNumber = pageNumber;

        // 获取当前打开的项目
        Project currentProject = this.project;
        if (currentProject == null) {
            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            if (openProjects.length > 0) {
                currentProject = openProjects[0];
            }
        }

        if (currentProject == null) {
            LOG.error("无法激活通知栏模式：没有打开的项目");
            return;
        }

        // 显示加载状态通知
        notificationService.showLoadingNotification(currentProject, "正在加载章节内容...");

        // 异步获取章节内容和标题
        final Project finalProject = currentProject;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // Since we are on a pooled thread, we can block to get the book object.
                Book book = bookService.getBookById(bookId).blockingGet();
                if (book == null) {
                    throw new IllegalStateException("Book not found: " + bookId);
                }

                // Get content and title in parallel
                Single<String> contentSingle = chapterService.getChapterContent(book, chapterId);
                Single<String> titleSingle = chapterService.getChapterTitle(bookId, chapterId);

                // Zip them together
                Single.zip(contentSingle, titleSingle, (content, title) -> new String[]{content, title})
                    .subscribe(
                        result -> {
                            String chapterContent = result[0];
                            String chapterTitle = result[1];

                            ApplicationManager.getApplication().invokeLater(() -> {
                                notificationService.setCurrentChapterContent(chapterContent);
                                int totalPages = notificationService.calculateTotalPages(chapterContent);

                                int validPageNumber = Math.max(1, Math.min(pageNumber, totalPages > 0 ? totalPages : 1));
                                this.currentPageNumber = validPageNumber;

                                notificationService.showChapterContent(finalProject, bookId, chapterId, validPageNumber, chapterTitle, chapterContent);
                            }, ModalityState.defaultModalityState());
                        },
                        error -> {
                            LOG.error("Failed to activate notification mode", error);
                            ApplicationManager.getApplication().invokeLater(() -> {
                                notificationService.showError("Activation Failed", "Could not load chapter: " + error.getMessage());
                            }, ModalityState.defaultModalityState());
                        }
                    );
            } catch (Exception e) {
                LOG.error("Top-level error activating notification mode", e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    notificationService.showError("Activation Error", "A critical error occurred: " + e.getMessage());
                }, ModalityState.defaultModalityState());
            }
        });
    }

    /**
     * Deactivates the notification bar reading mode.
     */
    public void deactivateNotificationBarMode() {
        // 2. Close all notifications
        notificationService.closeAllNotifications();

        // 3. Update ReaderModeSettings to default mode (or previous mode)
        readerModeSettings.setCurrentMode(ReaderModeSettings.Mode.DEFAULT); // Or handle previous mode

        // Clear current book/chapter/page
        this.currentBookId = null;
        this.currentChapterId = null;
        this.currentPageNumber = 0;
    }

    /**
     * Handles the next page action triggered from the notification.
     */
    public void handleNextPageAction() {
        if (!ensureReadingReadyForPageAction(1)) {
            return;
        }
        notificationService.showNextPage(project);
        this.currentPageNumber = notificationService.getCurrentPage();
        saveCurrentReadingProgressAsync();
    }

    /**
     * Handles the previous page action triggered from the notification.
     */
    public void handlePrevPageAction() {
        if (!ensureReadingReadyForPageAction(-1)) {
            return;
        }
        notificationService.showPrevPage(project);
        this.currentPageNumber = notificationService.getCurrentPage();
        saveCurrentReadingProgressAsync();
    }

    private boolean ensureReadingReadyForPageAction(int pageDelta) {
        if (project == null) {
            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            if (openProjects.length > 0) {
                project = openProjects[0];
            } else {
                LOG.warn("无法执行通知栏翻页：没有打开的项目");
                return false;
            }
        }

        String activeBookId = notificationService.getCurrentBookId();
        String activeChapterId = notificationService.getCurrentChapterId();
        if (activeBookId != null && activeChapterId != null) {
            this.currentBookId = activeBookId;
            this.currentChapterId = activeChapterId;
            this.currentPageNumber = notificationService.getCurrentPage();
            return true;
        }

        Optional<BookProgressData> lastReadBookOpt = readingProgressRepository.getLastReadProgressData();
        if (lastReadBookOpt.isEmpty()) {
            LOG.info("没有上次阅读记录，无法通过翻页恢复通知栏");
            return false;
        }

        BookProgressData lastReadBook = lastReadBookOpt.get();
        int targetPage = Math.max(1, lastReadBook.lastReadPage() + pageDelta);
        activateNotificationBarMode(lastReadBook.bookId(), lastReadBook.lastReadChapterId(), targetPage);
        return false;
    }

    /**
     * Handles the next chapter action triggered from the notification.
     */
    public void handleNextChapterAction() {
        // 1. Call NotificationService's navigate chapter method
        notificationService.navigateChapter(project, 1); // 1 means next chapter

        // 2. Save current reading progress (start page of the new chapter)
        this.currentBookId = notificationService.getCurrentBookId();
        this.currentChapterId = notificationService.getCurrentChapterId();
        this.currentPageNumber = notificationService.getCurrentPage();
        saveCurrentReadingProgressAsync();
    }

    /**
     * Handles the previous chapter action triggered from the notification.
     */
    public void handlePrevChapterAction() {
        // 1. Call NotificationService's navigate chapter method
        notificationService.navigateChapter(project, -1); // -1 means previous chapter

        // 2. Save current reading progress (start page of the new chapter)
        this.currentBookId = notificationService.getCurrentBookId();
        this.currentChapterId = notificationService.getCurrentChapterId();
        this.currentPageNumber = notificationService.getCurrentPage();
        saveCurrentReadingProgressAsync();
    }

    /**
     * 在后台线程异步保存当前阅读进度，避免阻塞 UI 线程。
     */
    private void saveCurrentReadingProgressAsync() {
        String bookId = this.currentBookId;
        String chapterId = this.currentChapterId;
        int page = this.currentPageNumber;
        if (bookId == null || chapterId == null) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                BookService bookService = ApplicationManager.getApplication().getService(BookService.class);
                if (bookService != null) {
                    bookService.getBookById(bookId)
                        .subscribeOn(Schedulers.io())
                        .subscribe(
                            book -> {
                                if (book != null) {
                                    readingProgressRepository.updateProgress(book, chapterId, null, 0, page);
                                }
                            },
                            error -> LOG.warn("异步保存阅读进度失败: " + error.getMessage(), error)
                        );
                }
            } catch (Exception e) {
                LOG.warn("异步保存阅读进度失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Initializes notification bar mode settings without displaying the last reading record on startup.
     */
    public void initializeNotificationBarModeSettings() {
        LOG.info("初始化通知栏模式设置");

        if (notificationReaderSettings.isEnabled()) {
            Optional<BookProgressData> lastReadBookOpt = readingProgressRepository.getLastReadProgressData();
            if (lastReadBookOpt.isPresent()) {
                BookProgressData lastReadBook = lastReadBookOpt.get();
                LOG.info("通知栏模式已启用，上次阅读位置将在翻页时恢复: " + lastReadBook.bookId() +
                         ", 章节: " + lastReadBook.lastReadChapterId() +
                         ", 页码: " + lastReadBook.lastReadPage());
            } else {
                LOG.info("通知栏模式已启用，但没有找到上次阅读记录");
            }
        } else {
            LOG.info("通知栏模式未启用，跳过恢复阅读位置");
        }

        LOG.info("通知栏模式设置初始化完成");
    }

    @Override
    public void dispose() {
        // Connection to MessageBus is automatically disposed as 'this' was passed to connect()
        LOG.info("NotificationBarModeService disposed.");
    }

    // Listener method for settings changes
    @Override
    public void settingsChanged() {
        LOG.info("NotificationReaderSettings changed event received by NotificationBarModeService.");
        // Check if currently in notification bar mode and if essential data is present
        if (project != null && // Ensure project context is available
            readerModeSettings.getCurrentMode() == ReaderModeSettings.Mode.NOTIFICATION_BAR &&
            currentBookId != null && !currentBookId.isEmpty() &&
            currentChapterId != null && !currentChapterId.isEmpty()) {

            LOG.info("Currently in notification bar mode with an active book/chapter. Triggering refresh due to settings change.");
            refreshNotificationDisplay();
        } else {
            LOG.info("Not in notification bar mode, or no current book/chapter/project, or project is null. Skipping refresh on settings change.");
        }
    }

    private void refreshNotificationDisplay() {
        if (project == null) {
            LOG.warn("Cannot refresh notification display: project is null at the beginning of refreshNotificationDisplay.");
            // Attempt to re-acquire project context if it was lost (e.g. original project closed)
            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            if (openProjects.length > 0) {
                this.project = openProjects[0]; // Use the first available open project
                LOG.info("Re-acquired project context for refresh: " + this.project.getName());
            } else {
                LOG.error("No open projects found. Cannot refresh notification display.");
                return;
            }
        }

        if (currentBookId == null || currentChapterId == null) {
            LOG.warn("Cannot refresh notification display: currentBookId or currentChapterId is null.");
            return;
        }

        LOG.debug("Attempting to refresh notification display for book: " + currentBookId +
                  ", chapter: " + currentChapterId + ", page: " + currentPageNumber);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Book book = bookService.getBookById(currentBookId).blockingGet();
                if (book == null) {
                    throw new IllegalStateException("Book not found for refresh: " + currentBookId);
                }

                Single<String> contentSingle = chapterService.getChapterContent(book, currentChapterId);
                Single<String> titleSingle = chapterService.getChapterTitle(currentBookId, currentChapterId);

                Single.zip(contentSingle, titleSingle, (content, title) -> new String[]{content, title})
                    .subscribe(
                        result -> {
                            String chapterContent = result[0];
                            String chapterTitle = result[1];

                            if (chapterContent == null || chapterContent.isEmpty()) {
                                LOG.error("Failed to refresh: content is null/empty for chapter " + currentChapterId);
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    notificationService.showError("Refresh Failed", "Could not retrieve chapter content.");
                                }, ModalityState.defaultModalityState());
                                return;
                            }

                            ApplicationManager.getApplication().invokeLater(()-> {
                                notificationService.showChapterContent(project, currentBookId, currentChapterId, currentPageNumber, chapterTitle, chapterContent);
                            }, ModalityState.defaultModalityState());
                        },
                        error -> {
                            LOG.error("Failed to refresh notification", error);
                             ApplicationManager.getApplication().invokeLater(() -> {
                                notificationService.showError("Refresh Failed", "Error applying settings: " + error.getMessage());
                            }, ModalityState.defaultModalityState());
                        }
                    );
            } catch (Exception e) {
                 LOG.error("Top-level error refreshing notification", e);
                 ApplicationManager.getApplication().invokeLater(() -> {
                    notificationService.showError("Refresh Error", "A critical error occurred: " + e.getMessage());
                }, ModalityState.defaultModalityState());
            }
        });
    }
}
