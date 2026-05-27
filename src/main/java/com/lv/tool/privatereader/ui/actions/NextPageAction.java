package com.lv.tool.privatereader.ui.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.service.NotificationBarModeService;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import org.jetbrains.annotations.NotNull;

public class NextPageAction extends BaseAction {
    private static final Logger LOG = Logger.getInstance(NextPageAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            NotificationBarModeService notificationBarModeService = ApplicationManager.getApplication().getService(NotificationBarModeService.class);
            ReaderModeSettings modeSettings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);

            if (notificationBarModeService != null && modeSettings != null && modeSettings.isNotificationMode()) {
                LOG.info("[通知栏模式] 尝试触发下一页");
                try {
                    notificationBarModeService.handleNextPageAction(project);
                    LOG.info("[通知栏模式] 下一页触发完成");
                } catch (Exception ex) {
                    LOG.error("[通知栏模式] 触发下一页时发生异常: " + ex.getMessage(), ex);
                }
            } else {
                LOG.warn("Next Page action is not applicable to ReactiveReaderPanel (uses scrolling). Action performed but had no effect.");
            }
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
        super.update(e);
        if (!e.getPresentation().isEnabled()) {
            return;
        }
        ReaderModeSettings modeSettings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
        e.getPresentation().setEnabled(modeSettings.isNotificationMode());
    }
}