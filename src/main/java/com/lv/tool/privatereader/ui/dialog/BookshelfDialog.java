package com.lv.tool.privatereader.ui.dialog;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.AsyncProcessIcon;
import com.lv.tool.privatereader.config.PrivateReaderConfig;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.repository.BookRepository;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.service.NotificationService;
import com.lv.tool.privatereader.storage.cache.ReactiveChapterPreloader;
import com.lv.tool.privatereader.ui.ReaderPanel;
import com.lv.tool.privatereader.ui.ReaderToolWindowFactory;
import com.lv.tool.privatereader.ui.topics.BookshelfTopics;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import javax.swing.SwingConstants;

public class BookshelfDialog extends DialogWrapper {
    private final Project project;
    private final JBList<Book> bookList;
    private final BookService bookService;
    private final BookRepository bookRepository;
    private final ReadingProgressRepository readingProgressRepository;
    private final ReactiveChapterPreloader chapterPreloader;
    private JPanel mainPanel;
    private JComboBox<String> sortComboBox;
    private AsyncProcessIcon loadingIcon;
    private JPanel loadingPanel;
    private static final Logger LOG = Logger.getInstance(BookshelfDialog.class);

    public BookshelfDialog(Project project) {
        super(project);
        this.project = project;
        this.bookService = ApplicationManager.getApplication().getService(BookService.class);

        // Get repositories directly using ApplicationManager
        this.bookRepository = ApplicationManager.getApplication().getService(BookRepository.class);
        this.readingProgressRepository = ApplicationManager.getApplication().getService(ReadingProgressRepository.class);
        this.chapterPreloader = ApplicationManager.getApplication().getService(ReactiveChapterPreloader.class);

        if (this.bookRepository == null || this.readingProgressRepository == null) {
            LOG.error("Failed to get required repository services (BookRepository or ReadingProgressRepository).");
        }

        this.bookList = new JBList<>();

        // 订阅书籍更新事件
        project.getMessageBus().connect().subscribe(BookshelfTopics.BOOK_UPDATED, book -> {
            if (book != null) {
                refreshBookList();
            }
        });

        init();
        setTitle("书架");
        setSize(600, 400);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(JBUI.Borders.empty(10));

        // 创建工具栏
        JPanel toolBar = createToolBar();
        mainPanel.add(toolBar, BorderLayout.NORTH);

        // 创建书籍列表
        bookList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        bookList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelectedBook();
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    showPopupMenu(e);
                }
            }
        });

        JBScrollPane scrollPane = new JBScrollPane(bookList);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // 创建加载状态面板
        createLoadingPanel();

        // 加载书籍
        refreshBookList();

        return mainPanel;
    }

    private void createLoadingPanel() {
        loadingPanel = new JPanel(new BorderLayout());
        loadingPanel.setVisible(false);

        loadingIcon = new AsyncProcessIcon("加载中");
        JLabel loadingLabel = new JLabel("正在打开书籍，请稍候...");
        loadingLabel.setHorizontalAlignment(SwingConstants.CENTER);

        loadingPanel.add(loadingIcon, BorderLayout.NORTH);
        loadingPanel.add(loadingLabel, BorderLayout.CENTER);

        mainPanel.add(loadingPanel, BorderLayout.SOUTH);
    }

    private void showLoading() {
        loadingPanel.setVisible(true);
        loadingIcon.resume();
    }

    private void hideLoading() {
        loadingPanel.setVisible(false);
        loadingIcon.suspend();
    }

    private JPanel createToolBar() {
        JPanel toolBar = new JPanel(new FlowLayout(FlowLayout.LEFT));

        // 排序下拉框
        sortComboBox = new JComboBox<>(new String[]{"按书名排序", "按进度排序", "按时间排序"});
        sortComboBox.addActionListener(e -> sortBooks());
        toolBar.add(sortComboBox);

        // 刷新按钮
        JButton refreshButton = new JButton("刷新章节");
        refreshButton.addActionListener(e -> {
            Book selectedBook = bookList.getSelectedValue();
            if (selectedBook != null) {
                try {
                    selectedBook.setProject(project);
                    NovelParser parser = selectedBook.getParser();
                    if (parser != null) {
                        List<NovelParser.Chapter> chapters = parser.getChapterList(selectedBook);
                        selectedBook.setCachedChapters(chapters);
                        // 更新存储
                        updateBookInfo(selectedBook);
                        refreshBookList();
                        showNotification(
                            String.format("成功刷新章节列表，共 %d 章", chapters.size()),
                            NotificationType.INFORMATION);
                    }
                } catch (Exception ex) {
                    showNotification(
                        "刷新章节列表失败：" + ex.getMessage(),
                        NotificationType.ERROR);
                }
            }
        });
        toolBar.add(refreshButton);

        // 重置进度按钮
        JButton resetButton = new JButton("重置进度");
        resetButton.addActionListener(e -> {
            Book selectedBook = bookList.getSelectedValue();
            if (selectedBook != null) {
                int result = Messages.showYesNoDialog(
                    project,
                    String.format("确定要重置《%s》的阅读进度吗？这将清除所有阅读记录。", selectedBook.getTitle()),
                    "重置进度",
                    Messages.getQuestionIcon()
                );

                if (result == Messages.YES) {
                    if (readingProgressRepository != null) {
                         try {
                              readingProgressRepository.resetProgress(selectedBook);
                              refreshBookList();
                              Messages.showInfoMessage(project, "已重置进度", "成功");
                         } catch (Exception ex) {
                              ApplicationManager.getApplication().getService(NotificationService.class).showError("错误", "重置进度时出错: " + ex.getMessage());
                              LOG.error("Error resetting progress in dialog for book: " + selectedBook.getId(), ex);
                         }
                    } else {
                         ApplicationManager.getApplication().getService(NotificationService.class).showError("错误", "无法获取阅读进度服务");
                    }
                }
            }
        });
        toolBar.add(resetButton);

        // 移除书籍按钮
        JButton removeButton = new JButton("移除书籍");
        removeButton.addActionListener(e -> {
            Book selectedBook = bookList.getSelectedValue();
            if (selectedBook != null) {
                int result = Messages.showYesNoDialog(
                    project,
                    String.format("确定要移除《%s》吗？", selectedBook.getTitle()),
                    "移除书籍",
                    Messages.getQuestionIcon()
                );

                if (result == Messages.YES) {
                    try {
                        // 停止该书籍的章节预加载任务，避免残留后台任务
                        if (chapterPreloader != null) {
                            chapterPreloader.stopPreload(selectedBook.getId());
                        }
                        removeBook(selectedBook);
                    } catch (Exception ex) {
                        LOG.error("移除书籍失败", ex);
                    }
                }
            }
        });
        toolBar.add(removeButton);

        return toolBar;
    }

    private void refreshBookList() {
        List<Book> books = getAllBooks();
        bookList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Book book = (Book) value;
                String text = String.format("<html><b>%s</b> - %s [%d%%]<br><font color='gray'>URL: %s<br>添加时间: %s</font></html>",
                    book.getTitle(),
                    book.getAuthor(),
                    (int) (book.getReadingProgress() * 100),
                    book.getUrl(),
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(book.getCreateTimeMillis()))
                );
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
            }
        });
        bookList.setListData(books.toArray(new Book[0]));
        sortBooks(); // 应用当前选择的排序
    }

    private void sortBooks() {
        List<Book> books = getAllBooks();
        int selectedIndex = sortComboBox.getSelectedIndex();

        switch (selectedIndex) {
            case 0: // 按书名排序
                books = books.stream()
                    .sorted((b1, b2) -> b1.getTitle().compareTo(b2.getTitle()))
                    .collect(Collectors.toList());
                break;
            case 1: // 按进度排序
                books = books.stream()
                    .sorted((b1, b2) -> Double.compare(b2.getReadingProgress(), b1.getReadingProgress()))
                    .collect(Collectors.toList());
                break;
            case 2: // 按时间排序
                books = books.stream()
                    .sorted((b1, b2) -> Long.compare(b2.getLastReadTimeMillis(), b1.getLastReadTimeMillis()))
                    .collect(Collectors.toList());
                break;
        }

        bookList.setListData(books.toArray(new Book[0]));
    }

    private void showNotification(String content, NotificationType type) {
        Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID)
                .createNotification(content, type)
                .setImportant(false);

        notification.hideBalloon();
        notification.notify(project);
    }

    private void openSelectedBook() {
        // 调用doOKAction方法，它会处理异步加载和UI反馈
        doOKAction();
    }

    private void showPopupMenu(MouseEvent e) {
        Book selectedBook = bookList.getSelectedValue();
        if (selectedBook != null) {
            JPopupMenu popupMenu = new JPopupMenu();

            JMenuItem copyUrlItem = new JMenuItem("复制URL");
            copyUrlItem.addActionListener(event -> {
                String url = selectedBook.getUrl();
                if (url != null && !url.isEmpty()) {
                    Toolkit.getDefaultToolkit()
                        .getSystemClipboard()
                        .setContents(new StringSelection(url), null);
                    showNotification("URL已复制到剪贴板", NotificationType.INFORMATION);
                }
            });
            popupMenu.add(copyUrlItem);

            popupMenu.show(bookList, e.getX(), e.getY());
        }
    }

    @Override
    protected void doOKAction() {
        Book selectedBook = bookList.getSelectedValue();
        if (selectedBook == null) {
            super.doOKAction(); // 如果没有选中书籍，直接关闭对话框
            return;
        }

        // 显示加载状态
        showLoading();

        // 禁用确定按钮，防止重复点击
        getOKAction().setEnabled(false);

        // 在后台线程执行打开书籍操作
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // 获取ReaderPanel实例（后台线程读取，无需EDT）
            ReaderPanel panel = ReaderToolWindowFactory.findPanel(project);
            if (panel == null) {
                SwingUtilities.invokeLater(() -> {
                    hideLoading();
                    getOKAction().setEnabled(true);
                    Messages.showWarningDialog(project, "阅读器面板未初始化", "错误");
                });
                return;
            }

            // 将UI操作派发到EDT执行，避免阻塞后台线程。future 在 EDT 真正完成后才完成，
            // 后台线程通过带超时的 get() 等待，超时由超时保护兜底，不再轮询。
            CompletableFuture<Void> uiFuture = new CompletableFuture<>();
            SwingUtilities.invokeLater(() -> {
                try {
                    // 确保工具窗口可见
                    ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("PrivateReader");
                    if (toolWindow != null) {
                        toolWindow.show(null);
                    }

                    // 选择书籍并加载进度
                    panel.selectBookAndLoadProgress(selectedBook);

                    // 关闭对话框
                    hideLoading();
                    getOKAction().setEnabled(true);
                    super.doOKAction();

                    uiFuture.complete(null);
                } catch (Exception ex) {
                    LOG.error("打开书籍时出错", ex);
                    // 在EDT线程上显示错误消息
                    hideLoading();
                    getOKAction().setEnabled(true);
                    ApplicationManager.getApplication().getService(NotificationService.class).showError("错误", "打开书籍时出错: " + ex.getMessage());
                    uiFuture.completeExceptionally(ex);
                }
            });

            // 超时保护：防止 selectBookAndLoadProgress 内部无限阻塞导致对话框无法关闭
            try {
                uiFuture.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                LOG.warn("打开书籍操作超时，对话框将被关闭", te);
                SwingUtilities.invokeLater(() -> {
                    hideLoading();
                    getOKAction().setEnabled(true);
                    Messages.showWarningDialog(project, "打开书籍操作超时，请稍后再试", "超时");
                });
            } catch (Exception e) {
                // 失败时 EDT 已显示错误通知，这里仅记录
                LOG.debug("打开书籍操作未完成", e);
            }
        });
    }

    private void updateBookInfo(Book book) {
        if (bookService != null) {
            WriteAction.run(() -> bookService.updateBook(book));
        } else {
            LOG.error("BookService 未初始化，无法更新书籍信息");
        }
    }

    private void removeBook(Book book) {
        if (bookService != null) {
            bookService.removeBook(book)
                .subscribe(
                    success -> SwingUtilities.invokeLater(() -> {
                        if (success) {
                            refreshBookList();
                            ReaderPanel panel = ReaderToolWindowFactory.findPanel(project);
                            if (panel != null) {
                                panel.loadBooks();
                            }
                            Messages.showInfoMessage(project, "已移除书籍《" + book.getTitle() + "》", "成功");
                        } else {
                            LOG.warn("移除书籍失败: " + book.getId());
                            Messages.showWarningDialog(project, "无法移除书籍《" + book.getTitle() + "》", "移除失败");
                        }
                    }),
                    error -> SwingUtilities.invokeLater(() -> {
                        LOG.error("移除书籍失败: " + book.getId(), error);
                        ApplicationManager.getApplication().getService(NotificationService.class).showError("错误", "移除书籍时出错: " + error.getMessage());
                    })
                );
        } else {
            LOG.error("BookService 未初始化，无法删除书籍");
        }
    }

    private List<Book> getAllBooks() {
        if (bookService != null) {
            return loadBooks();
        } else {
            LOG.error("BookService 未初始化，无法获取书籍列表");
            return new ArrayList<>();
        }
    }

    private List<Book> loadBooks() {
        BookService bookService = ApplicationManager.getApplication().getService(BookService.class);
        if (bookService == null) {
            LOG.error("无法获取 BookService 实例");
            return new ArrayList<>();
        }
        try {
            // Use read action for potential file access, block for sync result needed by dialog
            return ApplicationManager.getApplication().runReadAction((Computable<List<Book>>) () -> bookService.getAllBooks()
                .toList()
                .timeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .blockingGet());
        } catch (Exception e) {
            LOG.error("加载书籍列表时出错", e);
            ApplicationManager.getApplication().getService(NotificationService.class).showError("错误", "加载书籍列表失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }
}