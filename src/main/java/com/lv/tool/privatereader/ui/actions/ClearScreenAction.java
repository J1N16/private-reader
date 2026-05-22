package com.lv.tool.privatereader.ui.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.project.Project;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notification;
import com.intellij.openapi.application.ApplicationManager;
import com.lv.tool.privatereader.config.PrivateReaderConfig;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import org.jetbrains.annotations.NotNull;

public class ClearScreenAction extends BaseAction {
    private static final int CLEAR_NOTIFICATIONS = 20;  // 生成的通知数量

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            // 获取通知栏阅读设置
            NotificationReaderSettings settings = ApplicationManager.getApplication()
                    .getService(NotificationReaderSettings.class);

            // 生成多条空白通知来"清屏"
            for (int i = 0; i < CLEAR_NOTIFICATIONS; i++) {
                Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID)
                    .createNotification("", "　", NotificationType.INFORMATION)
                    .setImportant(false);

                notification.hideBalloon();
                notification.notify(project);
            }
        }
    }
    
    @Override
    @NotNull
    public ActionUpdateThread getActionUpdateThread() {
        // 告诉 IntelliJ 在后台线程而非 EDT 线程中执行 update 方法
        return ActionUpdateThread.BGT;
    }
} 