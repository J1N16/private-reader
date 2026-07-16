package com.lv.tool.privatereader.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import com.lv.tool.privatereader.repository.ReactiveChapterCacheRepository;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.storage.cache.ReactiveChapterPreloader;
import com.intellij.util.messages.MessageBusConnection;
import com.lv.tool.privatereader.settings.ReaderSettings;
import com.lv.tool.privatereader.settings.ReaderSettingsListener;
import com.lv.tool.privatereader.settings.CacheSettings;
import com.lv.tool.privatereader.settings.CacheSettingsListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.Messages;
import com.lv.tool.privatereader.events.ChapterChangeManager;
import com.lv.tool.privatereader.events.ChapterChangeEventSource;
import com.lv.tool.privatereader.service.NotificationService;
import com.intellij.util.ui.JBUI;
import io.reactivex.rxjava3.core.Completable;
import com.lv.tool.privatereader.ui.mvi.ReaderViewModel;
import com.lv.tool.privatereader.ui.mvi.ReaderUiState;
import com.lv.tool.privatereader.ui.mvi.IReaderIntent;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import javax.swing.SwingUtilities;
import com.intellij.openapi.application.ModalityState;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import javax.swing.Timer;

/**
 * 阅读器面板
 * 使用ReactiveUIAdapter加载和显示内容
 */
public class ReaderPanel extends SimpleToolWindowPanel implements Disposable {
    private static final Logger LOG = Logger.getInstance(ReaderPanel.class);

    // 高亮颜色常量
    private static final Color SELECTION_BACKGROUND = new Color(0, 120, 215);
    private static final Color SELECTION_FOREGROUND = Color.WHITE;

    private final Project project;
    private final BookService bookService;
    private final NotificationService notificationService;
    private final ChapterChangeManager chapterChangeManager;
    private final ReactiveChapterCacheRepository chapterCacheRepository;
    private final ReactiveChapterPreloader chapterPreloader;

    // 设置监听器
    private final MessageBusConnection messageBusConnection;
    private final ReaderSettingsListener readerSettingsListener;
    private final CacheSettingsListener cacheSettingsListener;

    // UI组件
    private final DefaultListModel<Book> booksListModel;
    private final JBList<Book> booksList;
    private final DefaultListModel<Chapter> chaptersListModel;
    private final JBList<Chapter> chaptersList;
    private final JTextArea contentTextArea;
    private final JBScrollPane contentScrollPane;
    private final JLabel loadingLabel;
    private final JButton refreshButton;
    private final JButton addBookButton;
    private final JButton deleteBookButton;
    private final JTextField searchField;
    private final JLabel currentChapterDisplayLabel;

    // 当前选中的书籍和章节
    private Book selectedBook;
    private Chapter selectedChapter;

    // 防抖相关
    private final Timer saveProgressDebouncer;

    private final ReaderViewModel viewModel;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private ReaderUiState currentUiState; // Cache the last rendered state

    public ReaderPanel(Project project) {
        super(true);
        this.project = project;
        this.viewModel = new ReaderViewModel(project);

        // Initialize BookService
        this.bookService = ApplicationManager.getApplication().getService(BookService.class);
        if (this.bookService == null) {
            LOG.error("Failed to get BookService instance. Reading progress saving will be disabled.");
        }

        // Initialize NotificationService
        this.notificationService = ApplicationManager.getApplication().getService(NotificationService.class);
        if (this.notificationService == null) {
            LOG.error("Failed to get NotificationService instance. Notifications will be disabled.");
        }

        this.chapterChangeManager = ApplicationManager.getApplication().getService(ChapterChangeManager.class);
        this.chapterCacheRepository = ApplicationManager.getApplication().getService(ReactiveChapterCacheRepository.class);
        this.chapterPreloader = ApplicationManager.getApplication().getService(ReactiveChapterPreloader.class);

        // 初始化设置监听器
        this.messageBusConnection = ApplicationManager.getApplication().getMessageBus().connect();
        this.readerSettingsListener = this::handleReaderSettingsChanged;
        this.cacheSettingsListener = this::handleCacheSettingsChanged;

        // 注册设置监听器
        messageBusConnection.subscribe(ReaderSettingsListener.TOPIC, readerSettingsListener);
        messageBusConnection.subscribe(CacheSettingsListener.TOPIC, cacheSettingsListener);

        // 订阅章节变更事件 (由通知栏模式等发布)
        messageBusConnection.subscribe(com.lv.tool.privatereader.messaging.CurrentChapterNotifier.TOPIC, new com.lv.tool.privatereader.messaging.CurrentChapterNotifier() {
            @Override
            public void currentChapterChanged(Book changedBook, Chapter newChapterFromEvent) {
                if (chapterChangeManager.getLastEventSource() == ChapterChangeEventSource.NOTIFICATION_SERVICE) {
                    if (changedBook != null && newChapterFromEvent != null) {
                        LOG.debug("ReaderPanel (CurrentChapterNotifier) event received. Processing intent. Book: " + changedBook.getTitle() + ", Chapter: " + newChapterFromEvent.title());
                        // 防止无限循环：将事件源更新为 READER_PANEL，这样当 ViewModel 加载完成并再次发布事件时，ReaderPanel 不会再次处理它
                        chapterChangeManager.setEventSource(ChapterChangeEventSource.READER_PANEL);
                        viewModel.processIntent(new IReaderIntent.HandleExternalChapterChange(changedBook, newChapterFromEvent));
                    } else {
                        LOG.warn("ReaderPanel (CurrentChapterNotifier): Received null book or chapter in event. Ignoring.");
                    }
                }
            }
        });

        // 初始化UI组件
        booksListModel = new DefaultListModel<>();
        booksList = new JBList<>(booksListModel);
        booksList.setCellRenderer(new BookListCellRenderer());

        chaptersListModel = new DefaultListModel<>();
        chaptersList = new JBList<>(chaptersListModel);
        chaptersList.setCellRenderer(new ChapterListCellRenderer());

        contentTextArea = new JTextArea();
        contentTextArea.setEditable(false);
        contentTextArea.setLineWrap(true);
        contentTextArea.setWrapStyleWord(true);
        contentTextArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 16));
        contentTextArea.setBorder(new EmptyBorder(10, 10, 10, 10));

        contentScrollPane = new JBScrollPane(contentTextArea);

        loadingLabel = new JLabel("加载中...");
        loadingLabel.setVisible(false);

        refreshButton = new JButton("刷新");
        addBookButton = new JButton("添加书籍");
        deleteBookButton = new JButton("删除书籍");
        searchField = new JTextField(20);

        // 初始化新增的章节标题标签
        currentChapterDisplayLabel = new JLabel(" "); // 初始为空白
        currentChapterDisplayLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 14)); // 设置字体
        currentChapterDisplayLabel.setBorder(JBUI.Borders.empty(5, 10)); // 设置边距

        // 设置布局
        setupLayout();

        // 添加事件监听器
        setupEventListeners();

        // Debouncer for saving progress
        saveProgressDebouncer = new Timer(1500, e -> {
            Chapter currentChapter = chaptersList.getSelectedValue();
            if (currentChapter != null) {
                int position = contentScrollPane.getVerticalScrollBar().getValue();
                viewModel.processIntent(new IReaderIntent.SaveProgress(currentChapter.url(), position));
            }
        });
        saveProgressDebouncer.setRepeats(false);

        // Set up the MVI loop
        disposables.add(viewModel.getState()
                .subscribe(
                    state -> ApplicationManager.getApplication().invokeLater(() -> render(state), ModalityState.defaultModalityState()),
                    throwable -> LOG.error("Error in UI State", throwable)
                ));
        
        // Trigger initial data load
        viewModel.processIntent(new IReaderIntent.LoadInitialData());
        this.currentUiState = ReaderUiState.initial(); // Initialize with default state

        LOG.info("初始化ReactiveReaderPanel");
    }

    /**
     * 设置布局
     */
    private void setupLayout() {
        // 创建左侧面板（书籍和章节列表）
        JPanel leftPanel = new JPanel(new BorderLayout());

        // 书籍列表面板
        JPanel booksPanel = new JPanel(new BorderLayout());
        JPanel booksToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        booksToolbar.add(addBookButton);
        booksToolbar.add(deleteBookButton);
        booksToolbar.add(new JLabel("搜索:"));
        booksToolbar.add(searchField);
        booksPanel.add(booksToolbar, BorderLayout.NORTH);
        booksPanel.add(new JBScrollPane(booksList), BorderLayout.CENTER);

        // 章节列表面板
        JPanel chaptersPanel = new JPanel(new BorderLayout());
        JPanel chaptersToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        chaptersToolbar.add(refreshButton);
        chaptersPanel.add(chaptersToolbar, BorderLayout.NORTH);
        chaptersPanel.add(new JBScrollPane(chaptersList), BorderLayout.CENTER);

        // 将书籍和章节列表添加到左侧面板
        JSplitPane leftSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, booksPanel, chaptersPanel);
        leftSplitPane.setDividerLocation(200);
        leftPanel.add(leftSplitPane, BorderLayout.CENTER);

        // 创建右侧面板（内容显示）
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(currentChapterDisplayLabel, BorderLayout.NORTH);
        rightPanel.add(contentScrollPane, BorderLayout.CENTER);

        // 加载状态面板
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusPanel.add(loadingLabel);
        rightPanel.add(statusPanel, BorderLayout.SOUTH);

        // 创建主分割面板
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        mainSplitPane.setDividerLocation(300);
        mainSplitPane.setResizeWeight(0.3); // 设置调整权重，使右侧面板获得更多空间

        // 确保内容区域可见
        contentTextArea.setVisible(true);
        contentScrollPane.setVisible(true);
        rightPanel.setVisible(true);

        // 设置最小大小
        rightPanel.setMinimumSize(new Dimension(400, 300));
        contentScrollPane.setMinimumSize(new Dimension(400, 300));

        // 设置主面板
        setContent(mainSplitPane);
    }

    /**
     * 设置事件监听器
     */
    private void setupEventListeners() {
        // 书籍列表选择事件
        booksList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Book newlySelectedBook = booksList.getSelectedValue();
                // 关键修复：只有当用户的选择与当前UI状态不一致时，才发送意图，以打破渲染循环
                if (newlySelectedBook != null && !newlySelectedBook.getId().equals(currentUiState.getSelectedBookId())) {
                    chapterChangeManager.setEventSource(ChapterChangeEventSource.READER_PANEL);
                    viewModel.processIntent(new IReaderIntent.SelectBook(newlySelectedBook.getId()));
                }
            }
        });

        // 章节列表选择事件
        chaptersList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Chapter newlySelectedChapter = chaptersList.getSelectedValue();
                // 关键修复：只有当用户的选择与当前UI状态不一致时，才发送意图，以打破渲染循环
                if (newlySelectedChapter != null && (currentUiState == null || !newlySelectedChapter.url().equals(currentUiState.getSelectedChapterId()))) {
                    chapterChangeManager.setEventSource(ChapterChangeEventSource.READER_PANEL);
                    viewModel.processIntent(new IReaderIntent.SelectChapter(newlySelectedChapter.url()));
                }
            }
        });

        // 章节列表单击/双击事件 (Combined logic)
        // 此监听器已被移除，因为它与ListSelectionListener的功能重叠并导致了竞态条件。
        // ListSelectionListener现在是处理章节选择和内容加载的唯一入口。

        // 刷新按钮点击事件
        refreshButton.addActionListener(e -> {
            viewModel.processIntent(new IReaderIntent.RefreshChapters());
        });

        // 添加书籍按钮点击事件
        addBookButton.addActionListener(e -> {
            String url = JOptionPane.showInputDialog(this, "请输入小说网址:", "添加书籍", JOptionPane.PLAIN_MESSAGE);
            if (url != null && !url.trim().isEmpty()) {
                viewModel.processIntent(new IReaderIntent.AddBook(url.trim()));
            }
        });

        // 删除书籍按钮点击事件
        deleteBookButton.addActionListener(e -> {
            Book bookToDelete = booksList.getSelectedValue();
            if (bookToDelete != null) {
                int result = JOptionPane.showConfirmDialog(
                    this,
                    "确定要删除书籍 \"" + bookToDelete.getTitle() + "\" 吗?",
                    "删除书籍",
                    JOptionPane.YES_NO_OPTION
                );

                if (result == JOptionPane.YES_OPTION) {
                    viewModel.processIntent(new IReaderIntent.DeleteBook(bookToDelete.getId()));
                }
            }
        });

        // 搜索框事件
        searchField.addActionListener(e -> {
            String keyword = searchField.getText().trim();
            viewModel.processIntent(new IReaderIntent.SearchBook(keyword));
        });

        // 滚动事件，用于自动保存进度
        contentScrollPane.getVerticalScrollBar().addAdjustmentListener(new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent e) {
                if (!e.getValueIsAdjusting()) {
                    if (saveProgressDebouncer != null) saveProgressDebouncer.restart();
                }
            }
        });
    }

    /**
     * 加载书籍列表。
     */
    public void loadBooks() {
        LOG.info("Initiating books loading...");
        viewModel.processIntent(new IReaderIntent.LoadInitialData());
    }

    /**
     * 书籍列表单元格渲染器
     */
    private static class BookListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof Book) {
                Book book = (Book) value;
                setText(book.getTitle());
                setToolTipText(book.getAuthor() + " - " + book.getUrl());
            }

            return this;
        }
    }

    /**
     * 章节列表单元格渲染器
     */
    private static class ChapterListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof Chapter) {
                Chapter chapter = (Chapter) value;
                setText(chapter.title());
                setToolTipText(chapter.url());
            }

            return this;
        }
    }

    /**
     * 处理阅读器设置变更
     */
    private void handleReaderSettingsChanged() {
        ReaderSettings settings = ApplicationManager.getApplication().getService(ReaderSettings.class);
        if (settings != null) {
            // 更新字体设置
            contentTextArea.setFont(new Font(
                settings.getFontFamily(),
                settings.isBold() ? Font.BOLD : Font.PLAIN,
                settings.getFontSize()
            ));

            // 更新主题设置
            if (settings.isDarkTheme()) {
                contentTextArea.setBackground(Color.DARK_GRAY);
                contentTextArea.setForeground(Color.WHITE);
            } else {
                contentTextArea.setBackground(Color.WHITE);
                contentTextArea.setForeground(Color.BLACK);
            }

            // 重新加载当前章节以应用新设置
            if (selectedChapter != null) {
                viewModel.processIntent(new IReaderIntent.SelectChapter(selectedChapter.url()));
            }
        }
    }

    /**
     * 处理缓存设置变更
     */
    private void handleCacheSettingsChanged(CacheSettings settings) {
        if (settings != null) {
            // 更新缓存策略
            if (!settings.isEnableCache()) {
                // 如果禁用缓存，清理当前缓存
                clearChapterCache();
            }

            // 更新预加载策略
            if (!settings.isEnablePreload()) {
                // 如果禁用预加载，停止当前预加载任务
                stopPreloading();
            } else {
                // 如果启用预加载，重新开始预加载
                startPreloading();
            }
        }
    }

    /**
     * 清理章节缓存
     */
    private void clearChapterCache() {
        if (chapterCacheRepository == null) {
            LOG.warn("章节缓存仓库未初始化，无法清理缓存");
            return;
        }

        Book bookToClear = selectedBook;
        Completable clearOperation = bookToClear != null
            ? chapterCacheRepository.clearCacheReactive(bookToClear.getId())
            : chapterCacheRepository.clearAllCacheReactive();

        disposables.add(clearOperation
            .doOnSubscribe(disposable -> LOG.info(bookToClear != null
                ? "开始清理当前书籍章节缓存: " + bookToClear.getTitle()
                : "开始清理全部章节缓存"))
            .subscribe(
                () -> LOG.info(bookToClear != null
                    ? "当前书籍章节缓存已清理: " + bookToClear.getTitle()
                    : "全部章节缓存已清理"),
                error -> LOG.error("清理章节缓存失败", error)
            ));
    }

    /**
     * 停止预加载
     */
    private void stopPreloading() {
        if (chapterPreloader == null) {
            LOG.warn("章节预加载器未初始化，无法停止预加载");
            return;
        }
        if (selectedBook == null) {
            LOG.debug("当前未选择书籍，无需停止预加载");
            return;
        }

        chapterPreloader.stopPreload(selectedBook.getId());
        LOG.info("已请求停止当前书籍预加载: " + selectedBook.getTitle());
    }

    /**
     * 开始预加载
     */
    private void startPreloading() {
        if (chapterPreloader == null) {
            LOG.warn("章节预加载器未初始化，无法开始预加载");
            return;
        }
        Book bookToPreload = selectedBook;
        Chapter chapterToPreload = selectedChapter;
        if (bookToPreload == null || chapterToPreload == null) {
            LOG.debug("当前未选择书籍或章节，跳过预加载");
            return;
        }

        int currentChapterIndex = bookToPreload.getChapterIndex(chapterToPreload.url());
        if (currentChapterIndex < 0) {
            LOG.warn("无法开始预加载：当前章节不在书籍章节列表中，章节=" + chapterToPreload.title());
            return;
        }

        disposables.add(chapterPreloader.preloadChaptersReactive(bookToPreload, currentChapterIndex)
            .subscribe(
                () -> LOG.info("当前书籍预加载任务完成: " + bookToPreload.getTitle()),
                error -> LOG.error("当前书籍预加载失败: " + bookToPreload.getTitle(), error)
            ));
    }

    /**
     * 释放资源
     */
    @Override
    public void dispose() {
        LOG.info("开始释放 ReactiveReaderPanel 资源");
        try {
            // Stop any pending save
            saveProgressDebouncer.stop();
            // Final save before disposing
            Chapter currentChapter = chaptersList.getSelectedValue();
            if (currentChapter != null) {
                int position = contentScrollPane.getVerticalScrollBar().getValue();
                viewModel.processIntent(new IReaderIntent.SaveProgress(currentChapter.url(), position));
            }

            // 断开消息总线连接
        if (messageBusConnection != null) {
                LOG.info("断开 ReaderPanel 的消息总线连接...");
            messageBusConnection.disconnect();
        }

        // 从 PROJECT_PANELS 中移除自身
            if (project != null && !project.isDisposed()) {
            ReaderPanel panelInMap = ReaderToolWindowFactory.PROJECT_PANELS.get(project);
            if (panelInMap == this) {
                ReaderToolWindowFactory.PROJECT_PANELS.remove(project);
                    LOG.info("已从 PROJECT_PANELS 中移除项目: " + project.getName() + " 的 ReaderPanel");
            } else if (panelInMap != null) {
                    LOG.warn("项目 " + project.getName() + " 的 ReaderPanel 在映射中与当前实例不同，不移除");
            }
        } else if (project != null && project.isDisposed()) {
                LOG.info("项目 " + project.getName() + " 已释放，跳过从 PROJECT_PANELS 中移除");
            }
            
            disposables.dispose();
            viewModel.dispose();
            LOG.info("ReactiveReaderPanel 资源释放完成");
        } catch (Exception e) {
            LOG.error("释放 ReactiveReaderPanel 资源时发生错误: " + e.getMessage(), e);
        }
    }

    // --- Public API for Actions and External Components ---

    /**
     * 获取当前选中的书籍
     * @return 当前选中的 Book 对象，如果未选择则返回 null
     */
    public Book getSelectedBook() {
        return selectedBook;
    }

    /**
     * 获取当前选中的章节
     * @return 当前选中的 Chapter 对象，如果未选择则返回 null
     */
    public Chapter getSelectedChapter() {
        return selectedChapter;
    }

    /**
     * 导航到上一章或下一章
     * @param direction -1 表示上一章, 1 表示下一章
     */
    public void navigateChapter(int direction) {
        if (chaptersListModel.isEmpty() || selectedChapter == null) {
            LOG.warn("无法导航章节：列表为空或未选择章节");
            return;
        }
        int currentIndex = chaptersList.getSelectedIndex();
        int targetIndex = currentIndex + direction;

        if (targetIndex >= 0 && targetIndex < chaptersListModel.getSize()) {
            LOG.debug("导航章节: 从索引 " + currentIndex + " 到 " + targetIndex);
            chaptersList.setSelectedIndex(targetIndex);
            chaptersList.ensureIndexIsVisible(targetIndex);
            // 列表选择监听器会自动调用 loadChapterContent
        } else {
            LOG.warn("无法导航章节：目标索引 " + targetIndex + " 超出范围 [0, " + (chaptersListModel.getSize() - 1) + "]");
            // Optionally provide user feedback (e.g., notification)
            if (notificationService != null) {
                if (direction < 0) {
                    notificationService.showInfo("导航", "已经是第一章了").subscribe();
                } else {
                    notificationService.showInfo("导航", "已经是最后一章了").subscribe();
                }
            }
        }
    }

    /**
     * 重新加载当前选中章节的内容
     */
    public void reloadCurrentChapter() {
        if (selectedBook != null && selectedChapter != null) {
            LOG.info("重新加载章节内容: " + selectedChapter.title());
            viewModel.processIntent(new IReaderIntent.SelectChapter(selectedChapter.url()));
            // Provide user feedback
            if (notificationService != null) {
                notificationService.showInfo("刷新", "已刷新当前章节内容").subscribe();
            }
        } else {
            LOG.warn("无法重新加载章节：未选择书籍或章节");
            if (notificationService != null) {
                notificationService.showInfo("警告", "请先选择书籍和章节").subscribe();
            }
        }
    }

    /**
     * 刷新当前选中书籍的章节列表
     */
    public void refreshChapterList() {
        if (selectedBook != null) {
            LOG.info("刷新章节列表: " + selectedBook.getTitle());
            viewModel.processIntent(new IReaderIntent.RefreshChapters());
             // Provide user feedback
            if (notificationService != null) {
                notificationService.showInfo("刷新", "已刷新章节列表").subscribe();
            }
        } else {
            LOG.warn("无法刷新章节列表：未选择书籍");
            if (notificationService != null) {
                notificationService.showInfo("警告", "请先选择书籍").subscribe();
            }
        }
    }

    /**
     * 触发加载上次阅读的状态（书籍和章节）
     * 由启动逻辑调用
     */
    public void triggerLoadLastReadState() {
        LOG.info("外部触发加载上次阅读状态...");
        loadBooks();
    }

    /**
     * 选择指定的书籍并触发其章节和进度的加载
     * @param bookToSelect 要选择的书籍
     */
    public void selectBookAndLoadProgress(Book bookToSelect) {
        if (bookToSelect == null) {
            LOG.warn("无法选择书籍：提供的书籍为 null");
            return;
        }
        LOG.info("外部请求选择书籍: " + bookToSelect.getTitle());
        viewModel.processIntent(new IReaderIntent.SelectBook(bookToSelect.getId()));
    }

    /**
     * 加载指定的章节
     *
     * @param book 书籍
     * @param chapter 章节
     */
    public void loadChapter(Book book, Chapter chapter) {
        if (book == null || chapter == null) {
            LOG.warn("无法加载章节：书籍或章节为空");
            return;
        }

        LOG.info("加载指定章节：书籍=" + book.getTitle() + ", 章节=" + chapter.title());
        if (selectedBook == null || !selectedBook.getId().equals(book.getId())) {
            viewModel.processIntent(new IReaderIntent.HandleExternalChapterChange(book, chapter));
        } else {
            viewModel.processIntent(new IReaderIntent.SelectChapter(chapter.url()));
        }
    }

    // --- End Public API ---

    private void render(ReaderUiState state) {
        // This will be the single source of truth for UI updates.
        // Update the current state reference immediately so listeners can compare against the new state
        this.currentUiState = state;

        // Update loading indicators and list enabled state
        loadingLabel.setVisible(state.isLoadingBooks() || state.isLoadingChapters() || state.isLoadingContent());
        booksList.setEnabled(!state.isLoadingBooks());
        chaptersList.setEnabled(!state.isLoadingChapters());

        // Update books list
        // A more efficient update would be better, but for now this is fine.
        if (booksListModel.isEmpty() || !state.getBooks().equals(booksListModel.elements().asIterator())) {
            booksListModel.clear();
            for (Book book : state.getBooks()) {
                booksListModel.addElement(book);
            }
        }
        
        // Update selected book
        selectedBook = null;
        if (state.getSelectedBookId() != null) {
            for (int i = 0; i < booksListModel.getSize(); i++) {
                if (booksListModel.getElementAt(i).getId().equals(state.getSelectedBookId())) {
                    selectedBook = booksListModel.getElementAt(i);
                    if (booksList.getSelectedIndex() != i) {
                        booksList.setSelectedIndex(i);
                        booksList.ensureIndexIsVisible(i);
                    }
                    break;
                }
            }
        }

        // Update chapters, content, etc. will be added here
        if (state.getChapters() != null) {
            chaptersListModel.clear();
            for (Chapter chapter : state.getChapters()) {
                chaptersListModel.addElement(chapter);
            }
        }
        
        selectedChapter = null;
        if (state.getSelectedChapterId() != null) {
            for (int i = 0; i < chaptersListModel.getSize(); i++) {
                if (chaptersListModel.getElementAt(i).url().equals(state.getSelectedChapterId())) {
                    selectedChapter = chaptersListModel.getElementAt(i);
                    if (chaptersList.getSelectedIndex() != i) {
                        chaptersList.setSelectedIndex(i);
                        chaptersList.ensureIndexIsVisible(i);
                    }
                    break;
                }
            }
        }
        
        currentChapterDisplayLabel.setText(state.getCurrentChapterTitle());
        String newContent = state.isLoadingContent() ? "" : state.getContent();
        if (newContent == null) {
            newContent = "";
        }

        // 只有当内容实际发生变化时才更新UI，避免不必要的重绘
        if (!newContent.equals(contentTextArea.getText())) {
            contentTextArea.setText(newContent);
            // 立即将光标和滚动条设置到顶部
            contentTextArea.setCaretPosition(0);
            SwingUtilities.invokeLater(() -> contentScrollPane.getVerticalScrollBar().setValue(0));
        }

        // Handle errors
        // 错误处理逻辑现在由ViewModel通过NotificationService处理，因此UI层面不再需要显示弹窗
        if (state.getError() != null && !state.getError().isEmpty()) {
            // Messages.showErrorDialog(project, state.getError(), "错误");
            // Optionally clear the error from the state after showing it
        }

        // Cache the state after rendering is complete
        // this.currentUiState = state; // Moved to the beginning of the method
    }
}