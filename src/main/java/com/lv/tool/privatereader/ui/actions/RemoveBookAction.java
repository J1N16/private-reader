package com.lv.tool.privatereader.ui.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.service.NotificationService;
import com.intellij.openapi.application.ApplicationManager;
import com.lv.tool.privatereader.ui.ReaderPanel;
import com.lv.tool.privatereader.ui.ReaderToolWindowFactory;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 移除书籍动作
 */
public class RemoveBookAction extends AnAction implements DumbAware {
    private static final Logger LOG = LoggerFactory.getLogger(RemoveBookAction.class);

    public RemoveBookAction() {
        super("删除书籍", "从书架中删除选中的书籍", null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        ReaderPanel readerPanel = ReaderToolWindowFactory.findPanel(project);
        if (readerPanel == null) {
            Messages.showWarningDialog(project,
                "阅读器窗口未初始化",
                "提示");
            return;
        }

        Book selectedBook = readerPanel.getSelectedBook();
        if (selectedBook == null) {
            Messages.showWarningDialog(project,
                "请先选择要删除的书籍",
                "提示");
            return;
        }

        if (Messages.showYesNoDialog(readerPanel, "确定要删除选定的书籍 '" + selectedBook.getTitle() + "' 吗？\n此操作不可撤销。", "确认删除", Messages.getQuestionIcon()) == Messages.YES) {
            BookService bookService = ApplicationManager.getApplication().getService(BookService.class);
            if (bookService == null) {
                ApplicationManager.getApplication().getService(NotificationService.class).showError("错误", "书籍服务不可用");
                return;
            }

            bookService.removeBook(selectedBook)
                .subscribe(
                    success -> {
                        if (success) {
                            LOG.info("成功删除书籍: " + selectedBook.getTitle());
                            Messages.showInfoMessage(readerPanel, "书籍 '" + selectedBook.getTitle() + "' 已删除。", "删除成功");
                            readerPanel.loadBooks();
                        } else {
                            LOG.warn("删除书籍失败 (返回false): " + selectedBook.getTitle());
                            Messages.showWarningDialog(readerPanel, "无法删除书籍 '" + selectedBook.getTitle() + "'。", "删除失败");
                        }
                    },
                    error -> {
                        LOG.error("删除书籍时发生错误 for book: " + selectedBook.getTitle(), error);
                        ApplicationManager.getApplication().getService(NotificationService.class).showError("删除错误", "删除书籍时发生错误: " + error.getMessage());
                    }
                );
        }
    }

    @Override
    @NotNull
    public ActionUpdateThread getActionUpdateThread() {
        // 告诉 IntelliJ 在后台线程而非 EDT 线程中执行 update 方法
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            e.getPresentation().setEnabled(false);
            return;
        }

        ReaderPanel readerPanel = ReaderToolWindowFactory.findPanel(project);
        if (readerPanel == null) {
            e.getPresentation().setEnabled(false);
            return;
        }

        Book selectedBook = readerPanel.getSelectedBook();
        e.getPresentation().setEnabled(selectedBook != null);
    }
} 