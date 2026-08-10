package com.lv.tool.privatereader.ui.dialog;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.service.ChapterService;
import com.lv.tool.privatereader.service.NotificationService;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.intellij.openapi.ui.Messages;
import com.lv.tool.privatereader.messaging.CurrentChapterNotifier;
import com.intellij.util.messages.MessageBusConnection;
import javax.swing.SwingUtilities;
import javax.swing.ListModel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ChapterListDialog extends DialogWrapper {
    private final Project project;
    private final Book book;
    private final JBList<NovelParser.Chapter> chapterList;
    private JPanel mainPanel;
    private JLabel infoLabel;
    private JLabel statusLabel; // 添加状态标签
    private JProgressBar loadingProgress;
    private final ChapterService chapterService;
    private static final Logger LOG = LoggerFactory.getLogger(ChapterListDialog.class);

    public ChapterListDialog(Project project, Book book) {
        super(project, true);
        this.project = project;
        this.book = book;
        this.chapterList = new JBList<>();

        this.chapterService = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(com.lv.tool.privatereader.service.ChapterService.class);

        if (this.chapterService == null) {
            LOG.error("无法获取ChapterService服务，这可能导致章节列表无法加载");
            throw new IllegalStateException("ChapterService服务不可用");
        } else {
            LOG.info("成功获取ChapterService服务");
        }

        init();
        setTitle("章节列表 - " + book.getTitle());
        setSize(500, 600);

        // 订阅章节变更事件
        MessageBusConnection connection = ApplicationManager.getApplication().getMessageBus().connect(getDisposable());
        connection.subscribe(CurrentChapterNotifier.TOPIC, new CurrentChapterNotifier() {
            @Override
            public void currentChapterChanged(Book changedBook, NovelParser.Chapter newChapter) {
                SwingUtilities.invokeLater(() -> {
                    if (ChapterListDialog.this.book.equals(changedBook)) {
                        LOG.debug("ChapterListDialog received currentChapterChanged event for book: " + changedBook.getTitle() + ", new chapter: " + newChapter.title() + " (URL: " + newChapter.url() + ")");
                        
                        boolean found = findAndSelectChapter(newChapter.url());

                        if (!found) {
                            LOG.debug("Chapter not found in current list model. Refreshing from book.getCachedChapters() and retrying.");
                            List<NovelParser.Chapter> currentCachedChapters = ChapterListDialog.this.book.getCachedChapters();
                            if (currentCachedChapters != null && !currentCachedChapters.isEmpty()) {
                                // 保存当前选中的索引，以便刷新后尽量恢复视图
                                int previouslySelectedIndex = chapterList.getSelectedIndex();

                                chapterList.setListData(currentCachedChapters.toArray(new NovelParser.Chapter[0]));
                                LOG.debug("ChapterListDialog model refreshed with " + currentCachedChapters.size() + " chapters from book's cache.");
                                
                                found = findAndSelectChapter(newChapter.url());
                                
                                if (!found && previouslySelectedIndex != -1 && previouslySelectedIndex < chapterList.getModel().getSize()) {
                                    // 如果新章节仍未找到，但之前有选中项，则尝试恢复之前的选中，避免列表跳到不相关的开头
                                    chapterList.setSelectedIndex(previouslySelectedIndex);
                                    chapterList.ensureIndexIsVisible(previouslySelectedIndex);
                                }
                            } else {
                                LOG.debug("book.getCachedChapters() is null or empty. Cannot refresh model.");
                            }
                        }
                        if (found) {
                             LOG.debug("ChapterListDialog successfully updated selection to: " + newChapter.title());
                        } else {
                             LOG.warn("ChapterListDialog could not select new chapter even after potential refresh: " + newChapter.title() + " (URL: " + newChapter.url() + ")");
                        }
                    }
                });
            }
        });
    }

    // Helper method to avoid code duplication
    private boolean findAndSelectChapter(String chapterUrl) {
        ListModel<NovelParser.Chapter> model = chapterList.getModel();
        for (int i = 0; i < model.getSize(); i++) {
            if (model.getElementAt(i).url().equals(chapterUrl)) {
                chapterList.setSelectedIndex(i);
                chapterList.ensureIndexIsVisible(i);
                return true; // Found and selected
            }
        }
        return false; // Not found
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(JBUI.Borders.empty(10));

        // 创建信息面板
        createInfoPanel();

        // 创建章节列表
        chapterList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chapterList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelectedChapter();
                }
            }
        });

        JBScrollPane scrollPane = new JBScrollPane(chapterList);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // 创建状态面板（包含状态标签和进度条）
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(JBUI.Borders.empty(5, 0, 0, 0));

        // 创建状态标签
        statusLabel = new JLabel("");
        statusLabel.setForeground(com.intellij.ui.JBColor.GREEN);
        statusLabel.setVisible(false);
        statusPanel.add(statusLabel, BorderLayout.NORTH);

        // 创建加载进度条
        loadingProgress = new JProgressBar();
        loadingProgress.setIndeterminate(true);
        loadingProgress.setVisible(false);
        statusPanel.add(loadingProgress, BorderLayout.SOUTH);

        mainPanel.add(statusPanel, BorderLayout.SOUTH);

        // 加载章节
        loadChapters();

        return mainPanel;
    }

    private void createInfoPanel() {
        JPanel infoPanel = new JPanel(new BorderLayout());
        infoPanel.setBorder(JBUI.Borders.empty(0, 0, 10, 0));

        // 显示书籍信息
        infoLabel = new JLabel(String.format("<html>书名：%s<br>作者：%s<br>进度：%d/%d 章 (%.1f%%)</html>",
            book.getTitle(),
            book.getAuthor(),
            book.getCurrentChapterIndex(),
            book.getTotalChapters(),
            book.getReadingProgress() * 100));
        infoPanel.add(infoLabel, BorderLayout.CENTER);

        // 不再创建按钮面板和刷新按钮
        
        mainPanel.add(infoPanel, BorderLayout.NORTH);
    }

    // 最大重试次数
    private static final int MAX_RETRIES = 3;
    // 当前重试次数
    private int retryCount = 0;
    // 是否正在加载
    private boolean isLoading = false;
    // 选中的章节
    private NovelParser.Chapter selectedChapter;

    private void loadChapters() {
        if (book == null || chapterService == null) {
            LOG.warn("无法加载章节列表：书籍或章节服务为空");
            chapterList.setListData(new NovelParser.Chapter[0]);
            return;
        }

        LOG.info("开始加载章节列表：书籍=" + book.getTitle() + ", URL=" + book.getUrl() + ", 重试次数=" + retryCount);
        setLoading(true);
        isLoading = true;

        // 检查书籍解析器
        if (book.getParser() == null) {
            LOG.error("书籍解析器为空，无法加载章节列表：书籍=" + book.getTitle() + ", URL=" + book.getUrl());
            ApplicationManager.getApplication().getService(NotificationService.class).showError("错误", "无法加载章节列表：书籍解析器初始化失败");
            chapterList.setListData(new NovelParser.Chapter[0]);
            setLoading(false);
            isLoading = false;
            return;
        }

        // 首先检查Book对象的cachedChapters属性
        java.util.List<NovelParser.Chapter> bookCachedChapters = book.getCachedChapters();
        if (bookCachedChapters != null && !bookCachedChapters.isEmpty()) {
            LOG.info("直接使用Book对象的缓存章节列表：书籍=" + book.getTitle() + ", 章节数量=" + bookCachedChapters.size());
            chapterList.setListData(bookCachedChapters.toArray(new NovelParser.Chapter[0]));

            // 尝试选择上次阅读的章节
            String lastChapterId = book.getLastReadChapterId();
            if (lastChapterId != null) {
                for (int i = 0; i < bookCachedChapters.size(); i++) {
                    if (lastChapterId.equals(bookCachedChapters.get(i).url())) {
                        chapterList.setSelectedIndex(i);
                        chapterList.ensureIndexIsVisible(i);
                        LOG.debug("已选择上次阅读的章节：索引=" + i + ", 标题=" + bookCachedChapters.get(i).title());
                        break;
                    }
                }
            }

            updateInfoLabel(bookCachedChapters);
            setLoading(false);
            isLoading = false;
            return;
        } else {
            LOG.info("Book对象的缓存章节列表为空，尝试从服务获取");
        }

        // 检查网络连接
        if (!com.lv.tool.privatereader.util.NetworkUtils.isNetworkAvailable()) {
            LOG.warn("网络连接不可用，尝试使用缓存的章节列表");

            // 尝试使用缓存的章节列表
            java.util.List<NovelParser.Chapter> cachedChapters = book.getCachedChapters();
            if (cachedChapters != null && !cachedChapters.isEmpty()) {
                LOG.info("使用缓存的章节列表：书籍=" + book.getTitle() + ", 章节数量=" + cachedChapters.size());
                chapterList.setListData(cachedChapters.toArray(new NovelParser.Chapter[0]));

                // 尝试选择上次阅读的章节
                String lastChapterId = book.getLastReadChapterId();
                if (lastChapterId != null) {
                    for (int i = 0; i < cachedChapters.size(); i++) {
                        if (lastChapterId.equals(cachedChapters.get(i).url())) {
                            chapterList.setSelectedIndex(i);
                            chapterList.ensureIndexIsVisible(i);
                            break;
                        }
                    }
                }

                updateInfoLabel(cachedChapters);
                setLoading(false);
                isLoading = false;

                // 显示离线模式提示
                Messages.showInfoMessage(project,
                    "网络连接不可用，已加载缓存的章节列表。\n" +
                    "章节数量：" + cachedChapters.size() + "\n\n" +
                    "请检查网络连接后刷新章节列表。",
                    "离线模式");

                return;
            } else {
                LOG.warn("没有缓存的章节列表，无法在离线模式下加载");
                ApplicationManager.getApplication().getService(NotificationService.class).showError("无法加载章节列表", "网络连接不可用，且没有缓存的章节列表。");
                chapterList.setListData(new NovelParser.Chapter[0]);
                setLoading(false);
                isLoading = false;
                return;
            }
        }

        // 创建一个计数器，用于在UI线程中更新加载状态
        final java.util.concurrent.atomic.AtomicInteger loadingCounter = new java.util.concurrent.atomic.AtomicInteger(0);

        // 创建一个定时器，定期更新加载状态
        final javax.swing.Timer loadingTimer = new javax.swing.Timer(500, e -> {
            if (isLoading) {
                int count = loadingCounter.incrementAndGet();
                String dots = "";
                for (int i = 0; i < count % 4; i++) {
                    dots += ".";
                }
                loadingProgress.setString("加载中" + dots);
                loadingProgress.setStringPainted(true);
            }
        });
        loadingTimer.start();

        // 添加超时和重试机制
        chapterService.getChapterList(book)
            .timeout(30, TimeUnit.SECONDS) // 设置30秒超时
            .retry(2) // 失败时重试2次
            .subscribe(
                chapters -> {
                    LOG.info("成功加载章节列表：书籍=" + book.getTitle() + ", 章节数量=" + chapters.size());

                    // 停止加载定时器
                    loadingTimer.stop();
                    loadingProgress.setStringPainted(false);

                    // 检查章节列表是否为空
                    if (chapters.isEmpty()) {
                        LOG.warn("加载的章节列表为空：书籍=" + book.getTitle());

                        // 显示提示，建议用户刷新章节列表
                        Messages.showWarningDialog(project,
                            "加载的章节列表为空。\n\n" +
                            "可能的原因：\n" +
                            "1. 网络连接问题\n" +
                            "2. 书籍源网站结构变化\n" +
                            "3. 书籍URL无效\n\n" +
                            "请点击\"刷新章节\"按钮重试，或检查书籍URL是否正确。",
                            "章节列表为空");

                        // 更新UI，显示空列表
                        chapterList.setListData(new NovelParser.Chapter[0]);

                        // 重置重试计数
                        retryCount = 0;

                        setLoading(false);
                        isLoading = false;
                        return;
                    }

                    // 更新UI
                    chapterList.setListData(chapters.toArray(new NovelParser.Chapter[0]));

                    // Try to select current chapter if provided
                    String lastChapterId = book.getLastReadChapterId();
                    if (lastChapterId != null) {
                        LOG.debug("尝试选择上次阅读的章节：章节ID=" + lastChapterId);
                        for (int i = 0; i < chapters.size(); i++) {
                            if (lastChapterId.equals(chapters.get(i).url())) {
                                chapterList.setSelectedIndex(i);
                                chapterList.ensureIndexIsVisible(i);
                                LOG.debug("已选择上次阅读的章节：索引=" + i + ", 标题=" + chapters.get(i).title());
                                break;
                            }
                        }
                    }
                    updateInfoLabel(chapters);

                    // 缓存章节列表，以便离线使用
                    book.setCachedChapters(chapters);

                    // 重置重试计数
                    retryCount = 0;

                    setLoading(false);
                    isLoading = false;
                },
                error -> {
                    LOG.error("加载章节列表失败：书籍=" + book.getTitle() + ", 错误=" + error.getMessage() + ", 重试次数=" + retryCount, error);

                    // 停止加载定时器
                    loadingTimer.stop();
                    loadingProgress.setStringPainted(false);

                    // 如果还有重试次数，则重试
                    if (retryCount < MAX_RETRIES) {
                        retryCount++;
                        LOG.info("尝试重新加载章节列表：书籍=" + book.getTitle() + ", 重试次数=" + retryCount);

                        // 在后台线程延时1秒后重试，避免在EDT线程上sleep阻塞界面
                        CompletableFuture
                                .delayedExecutor(1, TimeUnit.SECONDS)
                                .execute(this::loadChapters);
                        return;
                    }

                    // 尝试使用缓存的章节列表
                    java.util.List<NovelParser.Chapter> cachedChapters = book.getCachedChapters();
                    if (cachedChapters != null && !cachedChapters.isEmpty()) {
                        LOG.info("加载失败，使用缓存的章节列表：书籍=" + book.getTitle() + ", 章节数量=" + cachedChapters.size());
                        chapterList.setListData(cachedChapters.toArray(new NovelParser.Chapter[0]));

                        // 尝试选择上次阅读的章节
                        String lastChapterId = book.getLastReadChapterId();
                        if (lastChapterId != null) {
                            for (int i = 0; i < cachedChapters.size(); i++) {
                                if (lastChapterId.equals(cachedChapters.get(i).url())) {
                                    chapterList.setSelectedIndex(i);
                                    chapterList.ensureIndexIsVisible(i);
                                    break;
                                }
                            }
                        }

                        updateInfoLabel(cachedChapters);

                        Messages.showWarningDialog(project,
                            "加载章节列表失败: " + error.getMessage() +
                            "\n\n已加载缓存的章节列表。\n" +
                            "章节数量：" + cachedChapters.size() + "\n\n" +
                            "请检查网络连接后刷新章节列表。",
                            "使用缓存");
                    } else {
                        ApplicationManager.getApplication().getService(NotificationService.class).showError("加载章节列表失败", error.getMessage());
                        chapterList.setListData(new NovelParser.Chapter[0]);
                    }

                    // 重置重试计数
                    retryCount = 0;

                    setLoading(false);
                    isLoading = false;
                }
            );
    }

    private void updateInfoLabel(List<NovelParser.Chapter> chapters) {
        if (chapters != null && !chapters.isEmpty()) {
            book.setTotalChapters(chapters.size());
            infoLabel.setText(String.format("<html>书名：%s<br>作者：%s<br>进度：%d/%d 章 (%.1f%%)</html>",
                book.getTitle(),
                book.getAuthor(),
                book.getCurrentChapterIndex(),
                book.getTotalChapters(),
                book.getReadingProgress() * 100));
        }
    }

    /**
     * 刷新章节列表
     * 参考阅读面板中的实现，简化刷新逻辑
     */
    private void refreshChapters() {
        LOG.info("开始刷新章节列表：书籍=" + book.getTitle());

        // 设置加载状态
        setLoading(true);
        isLoading = true;

        // 检查书籍解析器
        if (book.getParser() == null) {
            LOG.error("书籍解析器为空，无法刷新章节列表：书籍=" + book.getTitle() + ", URL=" + book.getUrl());
            ApplicationManager.getApplication().getService(NotificationService.class).showError("错误", "无法刷新章节列表：书籍解析器初始化失败");
            setLoading(false);
            isLoading = false;
            return;
        }

        // 创建一个计数器，用于在UI线程中更新加载状态
        final java.util.concurrent.atomic.AtomicInteger loadingCounter = new java.util.concurrent.atomic.AtomicInteger(0);

        // 创建一个定时器，定期更新加载状态
        final javax.swing.Timer loadingTimer = new javax.swing.Timer(500, e -> {
            if (isLoading) {
                int count = loadingCounter.incrementAndGet();
                String dots = "";
                for (int i = 0; i < count % 4; i++) {
                    dots += ".";
                }
                loadingProgress.setString("刷新中" + dots);
                loadingProgress.setStringPainted(true);
            }
        });
        loadingTimer.start();

        // 清除缓存并获取最新章节列表
        // 1. 清除Book对象的缓存
        book.setCachedChapters(null);

        // 2. 使用ChapterService清除所有缓存
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(com.lv.tool.privatereader.service.ChapterService.class)
            .clearBookCache(book)
            .subscribe(
                () -> {
                    LOG.debug("已清除ChapterService的章节内容缓存");

                    // 在缓存清除成功后，获取最新章节列表
                    chapterService.getChapterList(book)
                        .timeout(30, TimeUnit.SECONDS) // 设置30秒超时
                        .retry(2) // 失败时重试2次
                        .subscribe(
                            chapters -> {
                                LOG.info("成功刷新章节列表：书籍=" + book.getTitle() + ", 章节数量=" + chapters.size());

                                // 停止加载定时器
                                loadingTimer.stop();
                                loadingProgress.setStringPainted(false);

                                // 更新章节列表
                                chapterList.setListData(chapters.toArray(new NovelParser.Chapter[0]));

                                // 尝试选择上次阅读的章节
                                selectLastReadChapter(chapters);

                                // 更新信息标签
                                updateInfoLabel(chapters);

                                // 缓存章节列表
                                book.setCachedChapters(chapters);

                                // 使用状态标签显示成功消息
                                showStatusMessage(String.format("成功刷新章节列表，共 %d 章", chapters.size()), true);

                                // 设置加载状态
                                setLoading(false);
                                isLoading = false;
                            },
                            error -> {
                                LOG.error("刷新章节列表失败：书籍=" + book.getTitle() + ", 错误=" + error.getMessage(), error);

                                // 停止加载定时器
                                loadingTimer.stop();
                                loadingProgress.setStringPainted(false);

                                // 显示错误消息
                                ApplicationManager.getApplication().getService(NotificationService.class).showError("刷新章节列表失败", error.getMessage());

                                // 设置加载状态
                                setLoading(false);
                                isLoading = false;
                            }
                        );
                },
                error -> {
                    LOG.warn("清除ChapterService的章节内容缓存失败：" + error.getMessage(), error);

                    // 即使缓存清除失败，也尝试获取章节列表
                    chapterService.getChapterList(book)
                        .timeout(30, TimeUnit.SECONDS) // 设置30秒超时
                        .retry(2) // 失败时重试2次
                        .subscribe(
                            chapters -> {
                                LOG.info("成功刷新章节列表：书籍=" + book.getTitle() + ", 章节数量=" + chapters.size());

                                // 停止加载定时器
                                loadingTimer.stop();
                                loadingProgress.setStringPainted(false);

                                // 更新章节列表
                                chapterList.setListData(chapters.toArray(new NovelParser.Chapter[0]));

                                // 尝试选择上次阅读的章节
                                selectLastReadChapter(chapters);

                                // 更新信息标签
                                updateInfoLabel(chapters);

                                // 缓存章节列表
                                book.setCachedChapters(chapters);

                                // 使用状态标签显示成功消息
                                showStatusMessage(String.format("成功刷新章节列表，共 %d 章", chapters.size()), true);

                                // 设置加载状态
                                setLoading(false);
                                isLoading = false;
                            },
                            error2 -> {
                                LOG.error("刷新章节列表失败：书籍=" + book.getTitle() + ", 错误=" + error2.getMessage(), error2);

                                // 停止加载定时器
                                loadingTimer.stop();
                                loadingProgress.setStringPainted(false);

                                // 显示错误消息
                                ApplicationManager.getApplication().getService(NotificationService.class).showError("刷新章节列表失败", error2.getMessage());

                                // 设置加载状态
                                setLoading(false);
                                isLoading = false;
                            }
                        );
                }
            );
    }

    /**
     * 重写doOKAction方法，在用户点击确定按钮时设置selectedChapter变量
     */
    @Override
    protected void doOKAction() {
        this.selectedChapter = chapterList.getSelectedValue();
        if (this.selectedChapter != null) {
            LOG.info("点击确定按钮，选中章节：" + this.selectedChapter.title());
            super.doOKAction();
        } else {
            // 如果没有选中章节，显示提示
            Messages.showWarningDialog(project, "请选择一个章节", "提示");
        }
    }

    /**
     * 在用户双击章节时设置selectedChapter变量并关闭对话框
     */
    private void openSelectedChapter() {
        this.selectedChapter = chapterList.getSelectedValue();
        if (this.selectedChapter != null) {
            LOG.info("双击选中章节：" + this.selectedChapter.title());
            close(OK_EXIT_CODE);
        } else {
            // 如果没有选中章节，显示提示
            Messages.showWarningDialog(project, "请选择一个章节", "提示");
        }
    }

    /**
     * 获取选中的章节
     *
     * @return 选中的章节，如果没有选中则返回null
     */
    public NovelParser.Chapter getSelectedChapter() {
        return selectedChapter;
    }

    /**
     * 设置加载状态
     *
     * @param loading 是否正在加载
     */
    private void setLoading(boolean loading) {
        loadingProgress.setIndeterminate(loading);
        loadingProgress.setVisible(loading);

        // 如果不显示加载进度条，则不显示文本
        if (!loading) {
            loadingProgress.setString("");
            loadingProgress.setStringPainted(false);
        }

        // 禁用/启用所有按钮
        if (mainPanel != null) {
            disableAllButtons(mainPanel, loading);
        }
    }

    /**
     * 递归禁用/启用所有按钮
     *
     * @param container 容器
     * @param disable 是否禁用
     */
    private void disableAllButtons(Container container, boolean disable) {
        Component[] components = container.getComponents();
        for (Component component : components) {
            if (component instanceof JButton) {
                JButton button = (JButton) component;
                button.setEnabled(!disable);
            } else if (component instanceof Container) {
                disableAllButtons((Container) component, disable);
            }
        }
    }

    /**
     * 尝试选择上次阅读的章节
     *
     * @param chapters 章节列表
     */
    private void selectLastReadChapter(List<NovelParser.Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            LOG.debug("无法选择上次阅读的章节：章节列表为空");
            return;
        }

        // 获取上次阅读的章节ID
        String lastChapterId = book.getLastReadChapterId();
        if (lastChapterId != null) {
            LOG.debug("尝试选择上次阅读的章节：章节ID=" + lastChapterId);

            // 在章节列表中查找上次阅读的章节
            for (int i = 0; i < chapters.size(); i++) {
                if (lastChapterId.equals(chapters.get(i).url())) {
                    // 选择章节
                    chapterList.setSelectedIndex(i);
                    chapterList.ensureIndexIsVisible(i);
                    LOG.debug("已选择上次阅读的章节：索引=" + i + ", 标题=" + chapters.get(i).title());
                    return;
                }
            }

            LOG.warn("未找到上次阅读的章节：章节ID=" + lastChapterId);
        } else {
            LOG.debug("没有上次阅读的章节记录");
        }

        // 如果没有找到上次阅读的章节，选择第一个章节
        if (!chapters.isEmpty()) {
            chapterList.setSelectedIndex(0);
            chapterList.ensureIndexIsVisible(0);
            LOG.debug("选择第一个章节：标题=" + chapters.get(0).title());
        }
    }

    /**
     * 显示状态消息
     *
     * @param message 消息内容
     * @param isSuccess 是否成功消息
     */
    private void showStatusMessage(String message, boolean isSuccess) {
        // 设置消息文本
        statusLabel.setText(message);

        // 设置颜色和字体
        if (isSuccess) {
            // 成功消息使用绿色和粗体
            statusLabel.setForeground(com.intellij.ui.JBColor.GREEN);
            statusLabel.setFont(statusLabel.getFont().deriveFont(java.awt.Font.BOLD));
        } else {
            // 错误消息使用红色
            statusLabel.setForeground(com.intellij.ui.JBColor.RED);
            statusLabel.setFont(statusLabel.getFont().deriveFont(java.awt.Font.PLAIN));
        }

        // 显示状态标签
        statusLabel.setVisible(true);

        // 创建一个定时器，10秒后隐藏状态消息
        javax.swing.Timer timer = new javax.swing.Timer(10000, e -> {
            statusLabel.setVisible(false);
            // 恢复默认字体
            statusLabel.setFont(statusLabel.getFont().deriveFont(java.awt.Font.PLAIN));
        });
        timer.setRepeats(false);
        timer.start();
    }
}