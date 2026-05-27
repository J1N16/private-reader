package com.lv.tool.privatereader.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.messages.MessageBusConnection;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import com.lv.tool.privatereader.settings.ReaderModeSettingsListener;
import org.jetbrains.annotations.NotNull;

/**
 * Service responsible for switching between Reader Panel and Notification mode UI.
 * Implements Disposable for proper resource cleanup.
 */
@Service(Service.Level.APP)
public final class ReaderModeSwitcher implements ReaderModeSettingsListener, Disposable { // Restore interfaces

    private static final Logger LOG = Logger.getInstance(ReaderModeSwitcher.class);
    private static final String TOOL_WINDOW_ID = "PrivateReader"; // Restore constant

    // Restore fields
    private NotificationService notificationService;
    private ReaderModeSettings readerModeSettings;
    private NotificationReaderSettings notificationReaderSettings;
    private MessageBusConnection connection;
    private boolean initialized = false;

    public ReaderModeSwitcher() {
        LOG.info("ReaderModeSwitcher constructor started."); // Cleaned up log message
        // --- RESTORING CONSTRUCTOR LOGIC ---
        try {
            notificationService = ApplicationManager.getApplication().getService(NotificationService.class);
            LOG.info("NotificationService obtained: " + (notificationService != null));
            if (notificationService == null) {
                 LOG.error("NotificationService is null during ReaderModeSwitcher initialization.");
                 // Decide if this is critical
            }

            readerModeSettings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
            LOG.info("ReaderModeSettings obtained: " + (readerModeSettings != null));
             if (readerModeSettings == null) {
                 LOG.error("ReaderModeSettings is null during ReaderModeSwitcher initialization. Aborting initialization.");
                 return; // Critical dependency
            }

            notificationReaderSettings = ApplicationManager.getApplication().getService(NotificationReaderSettings.class);
            LOG.info("NotificationReaderSettings obtained: " + (notificationReaderSettings != null));
            if (notificationReaderSettings == null) {
                LOG.warn("NotificationReaderSettings is null during ReaderModeSwitcher initialization. Auto-read feature will be disabled.");
            }

            // Pass 'this' as the parent Disposable to connect, ensuring the connection
            // is automatically disposed when this service (Disposable) is disposed.
            connection = ApplicationManager.getApplication().getMessageBus().connect(this);
            LOG.info("MessageBusConnection obtained: " + (connection != null));
            if (connection == null) {
                LOG.error("Failed to get MessageBusConnection. Aborting initialization.");
                return; // Critical dependency
            }

            connection.subscribe(ReaderModeSettings.TOPIC, this);
            LOG.info("Subscribed to ReaderModeSettings.TOPIC.");

            // Disposer.register(...) is not needed when using connect(parentDisposable)

            initialized = true; // Mark initialization successful
            LOG.info("ReaderModeSwitcher initialization successful.");

            // Apply initial mode state after successful initialization
            LOG.info("[配置诊断] 在 ReaderModeSwitcher 初始化后应用阅读模式，当前模式: " +
                    (readerModeSettings.isNotificationMode() ? "通知栏模式" : "阅读器模式"));
            applyCurrentMode(readerModeSettings.isNotificationMode());

        } catch (Throwable t) {
            LOG.error("Error during ReaderModeSwitcher initialization", t);
            initialized = false;
        }
        // --- END OF RESTORED LOGIC ---
    }

    // Restore modeChanged method
    @Override
    public void modeChanged(boolean notificationMode) {
        if (!initialized) {
             LOG.warn("ReaderModeSwitcher received modeChanged event but is not initialized. Skipping.");
             return;
        }
        LOG.info("Reader mode changed. Applying mode: " + (notificationMode ? "Notification" : "Panel"));
        applyCurrentMode(notificationMode);
    }

    // Restore applyCurrentMode method (assuming it existed or was part of the commented block)
    private void applyCurrentMode(boolean notificationMode) {
         if (!initialized) {
             LOG.warn("ReaderModeSwitcher trying to apply mode but is not initialized. Skipping.");
             return;
        }
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        if (openProjects.length == 0) {
            LOG.info("No open projects found. UI changes deferred for mode: " + notificationMode);
            return;
        }
        for (Project project : openProjects) {
             applyModeForProject(project, notificationMode);
        }
    }

    // Restore applyModeForProject method (assuming it existed or was part of the commented block)
    private void applyModeForProject(Project project, boolean notificationMode) {
         LOG.info("Applying mode [" + (notificationMode ? "Notification" : "Panel") + "] for project: " + project.getName());
         ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
         ToolWindow toolWindow = toolWindowManager.getToolWindow(TOOL_WINDOW_ID);

         if (notificationMode) {
             // Switch to Notification mode
             if (toolWindow != null && toolWindow.isVisible()) {
                 ApplicationManager.getApplication().invokeLater(() -> {
                     if (project.isDisposed()) return;
                     toolWindow.hide(null);
                     LOG.info("Hid Reader Panel tool window for project: " + project.getName());
                 });
             }

             // 初始化通知栏模式服务
             try {
                 // 获取 NotificationBarModeService 实例
                 NotificationBarModeService notificationBarModeService = ApplicationManager.getApplication().getService(NotificationBarModeService.class);
                 if (notificationBarModeService != null) {
                     LOG.info("Initializing NotificationBarModeService for project: " + project.getName());
                     notificationBarModeService.setProject(project);
                     // 调用初始化方法，恢复上次的阅读位置
                     notificationBarModeService.initializeNotificationBarModeSettings();
                 } else {
                     LOG.error("NotificationBarModeService is null, cannot initialize notification bar mode settings.");
                 }
             } catch (Exception e) {
                 LOG.error("Error initializing NotificationBarModeService", e);
             }

             // 显示通知栏模式信息
             if (notificationService != null) {
                 notificationService.showInfo("阅读模式", "已切换到通知栏模式").subscribe(
                     notification -> {},
                     error -> LOG.error("Error showing notification info", error)
                 );
             } else {
                 LOG.error("NotificationService is null, cannot show notification mode info.");
             }
         } else {
             // Switch back to Panel mode
             if (notificationService != null) {
                 // 直接调用同步方法，不需要 subscribe
                 notificationService.closeAllNotifications();
                 LOG.info("Requested closing all notifications for project: " + project.getName());
             } else {
                 LOG.error("NotificationService is null, cannot close notifications.");
             }

             if (toolWindow != null) {
                 ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed() || !toolWindow.isAvailable()) return;
                    if (!toolWindow.isVisible()) {
                         toolWindow.show(null);
                         LOG.info("Showed Reader Panel tool window for project: " + project.getName());
                    }
                 });
             } else {
                  LOG.warn("Tool window '" + TOOL_WINDOW_ID + "' not found for project: " + project.getName());
             }
         }
    }

    /**
     * Applies the currently configured reader mode to the specified project.
     * Intended to be called by ProjectActivity after project initialization.
     */
    public void applyInitialModeForProject(@NotNull Project project) {
        if (!initialized) {
            LOG.warn("applyInitialModeForProject called but switcher is not initialized. Skipping for project: " + project.getName());
            return;
        }
        if (readerModeSettings == null) {
             LOG.error("applyInitialModeForProject called but readerModeSettings is null. Skipping for project: " + project.getName());
             return;
        }

        boolean isNotificationMode = readerModeSettings.isNotificationMode();
        LOG.info("[页码调试] 应用初始阅读模式: " + (isNotificationMode ? "通知栏模式" : "阅读器模式") +
                 ", 项目: " + project.getName());

        // Directly call the existing method that applies the mode for a project
        applyModeForProject(project, isNotificationMode);

        // 如果是通知栏模式，检查 NotificationBarModeService 是否正确初始化
        if (isNotificationMode) {
            try {
                NotificationBarModeService notificationBarModeService = ApplicationManager.getApplication().getService(NotificationBarModeService.class);
                if (notificationBarModeService != null) {
                    notificationBarModeService.setProject(project);
                }
                LOG.info("[页码调试] NotificationBarModeService 实例: " + (notificationBarModeService != null ? "已获取" : "获取失败"));
            } catch (Exception e) {
                LOG.error("[页码调试] 获取 NotificationBarModeService 实例时出错", e);
            }
        }
    }

    // Restore dispose method
    @Override
    public void dispose() {
         // Connection is automatically disposed because we used connect(this)
         initialized = false;
         LOG.info("ReaderModeSwitcher disposed. Message bus connection automatically disconnected.");
    }
}