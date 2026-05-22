package com.lv.tool.privatereader.ui.dialog;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.lv.tool.privatereader.config.PrivateReaderConfig;
import com.lv.tool.privatereader.exception.ExceptionHandler;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.service.NotificationService;
import com.lv.tool.privatereader.parser.ParserFactory;
import com.lv.tool.privatereader.service.BookService;
import org.jetbrains.annotations.Nullable;
import com.lv.tool.privatereader.async.ReactiveSchedulers;
import com.lv.tool.privatereader.ui.topics.BookshelfTopics;
import com.intellij.openapi.application.ApplicationManager;
import com.lv.tool.privatereader.events.BookEvents;

import javax.swing.*;
import java.awt.*;
import java.util.UUID;

public class AddBookDialog extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(AddBookDialog.class);
    private final JBTextField urlField;
    private final Project project;
    private final BookService bookService;

    public AddBookDialog(Project project) {
        super(project);
        this.project = project;
        this.bookService = com.intellij.openapi.application.ApplicationManager.getApplication().getService(BookService.class);

        setTitle("添加书籍");
        setSize(400, 150);

        urlField = new JBTextField();
        urlField.setPreferredSize(new Dimension(300, 30));

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(10));

        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        inputPanel.add(new JBLabel("书籍网址:"), BorderLayout.WEST);
        inputPanel.add(urlField, BorderLayout.CENTER);

        panel.add(inputPanel, BorderLayout.NORTH);

        JLabel tipLabel = new JBLabel("提示: 输入小说网址，系统将自动解析书籍信息");
        tipLabel.setForeground(Color.GRAY);
        panel.add(tipLabel, BorderLayout.SOUTH);

        return panel;
    }

    @Override
    protected void doOKAction() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            ApplicationManager.getApplication().getService(NotificationService.class).showError("错误", "请输入书籍网址");
            return;
        }

        try {
            // 创建解析器
            NovelParser parser = ParserFactory.createParser(url);
            if (parser == null) {
                ApplicationManager.getApplication().getService(NotificationService.class).showError("错误", "不支持的网站或网址格式不正确");
                return;
            }

            // 获取书籍信息
            String title = parser.getTitle();
            String author = parser.getAuthor();

            if (title == null || title.isEmpty()) {
                ApplicationManager.getApplication().getService(NotificationService.class).showError("错误", "无法获取书籍标题");
                return;
            }

            // 创建书籍对象
            Book book = new Book();
            book.setId(UUID.randomUUID().toString());
            book.setTitle(title);
            book.setAuthor(author != null ? author : "未知");
            book.setUrl(url);
            book.setCreateTimeMillis(System.currentTimeMillis());
            book.setParser(parser);

            // 使用BookService添加书籍
            BookService bookService = com.intellij.openapi.application.ApplicationManager.getApplication().getService(BookService.class);

            // 检查服务是否存在
            if (bookService == null) {
                LOG.error("无法获取 BookService 实例");
                ApplicationManager.getApplication().getService(NotificationService.class).showError("错误", "无法添加书籍，服务不可用。");
                return;
            }

            // 先关闭对话框，然后再执行异步操作
            super.doOKAction(); // 立即关闭对话框

            // 创建通知组
            com.intellij.notification.NotificationGroup notificationGroup =
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID);

            // 执行异步操作
            bookService.addBook(book)
                .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                .subscribe(
                    success -> {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (success) {
                                LOG.info("成功添加书籍: " + book.getTitle());

                                // 显示成功通知
                                notificationGroup.createNotification(
                                    "添加书籍成功",
                                    "成功添加书籍: " + book.getTitle(),
                                    com.intellij.notification.NotificationType.INFORMATION)
                                    .notify(project);

                                // 通过事件总线通知刷新书架
                                ApplicationManager.getApplication().getMessageBus()
                                    .syncPublisher(BookEvents.BookDataListener.BOOK_DATA_TOPIC).bookDataLoaded();
                            } else {
                                LOG.warn("添加书籍失败 (返回 false): " + book.getTitle());

                                // 显示失败通知
                                notificationGroup.createNotification(
                                    "添加书籍失败",
                                    "添加书籍失败，请检查URL或稍后再试。",
                                    com.intellij.notification.NotificationType.WARNING)
                                    .notify(project);
                            }
                        });
                    },
                    error -> {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            LOG.error("添加书籍时出错: " + book.getTitle(), error);

                            // 显示错误通知
                            notificationGroup.createNotification(
                                "添加书籍错误",
                                "添加书籍时发生错误: " + error.getMessage(),
                                com.intellij.notification.NotificationType.ERROR)
                                .notify(project);
                        });
                    }
                );
        } catch (Exception e) {
            ExceptionHandler.handle(project, e, "添加书籍失败: " + e.getMessage());
            LOG.error("添加书籍失败", e);
        }
    }
}