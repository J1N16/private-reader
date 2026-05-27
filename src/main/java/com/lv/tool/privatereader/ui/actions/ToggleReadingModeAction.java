package com.lv.tool.privatereader.ui.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.project.Project;
import com.lv.tool.privatereader.settings.PluginSettings;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import com.lv.tool.privatereader.service.ReaderModeSwitcher;

/**
 * 切换阅读模式动作
 * 在通知栏模式和阅读器模式之间切换
 */
public class ToggleReadingModeAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(ToggleReadingModeAction.class);

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            PluginSettings pluginSettings = ApplicationManager.getApplication().getService(PluginSettings.class);
            if (!pluginSettings.isEnabled()) {
                e.getPresentation().setEnabled(false);
                return;
            }
            ReaderModeSettings settings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
            if (settings != null) {
                boolean isNotificationMode = settings.isNotificationMode();
                e.getPresentation().setText(isNotificationMode ?
                    "切换到阅读器模式" : "切换到通知栏模式");
                e.getPresentation().setDescription(isNotificationMode ?
                    "切换到阅读器模式显示内容" : "切换到通知栏模式显示内容");
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
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        // project may be null, for example, if the action is invoked from the Welcome Screen.
        // However, settings are Application level, so we don't strictly need a project,
        // but the mode switching logic might depend on project context later.
        // For now, we proceed even if project is null, as settings access doesn't require it.

        ReaderModeSettings settings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
        if (settings != null) {
            // --- REMOVING DIAGNOSTIC STEP --- 
            /*
            LOG.info("ToggleAction: Ensuring ReaderModeSwitcher is loaded before changing settings...");
            try {
                // Explicitly get the service instance to force its loading if not already loaded.
                ReaderModeSwitcher switcher = ApplicationManager.getApplication().getService(ReaderModeSwitcher.class);
                if (switcher == null) {
                     // This case might happen if the service failed to load previously.
                     LOG.warn("ToggleAction: ReaderModeSwitcher service obtained as null. Mode switching might not work.");
                } else {
                     LOG.info("ToggleAction: ReaderModeSwitcher service instance obtained successfully.");
                }
            } catch (Throwable t) {
                LOG.error("ToggleAction: Error explicitly getting ReaderModeSwitcher service", t);
                // If loading fails here, the listener likely won't work.
            }
            */
            // --- END OF REMOVED DIAGNOSTIC STEP ---
            
            boolean currentMode = settings.isNotificationMode();
            boolean newMode = !currentMode;
            settings.setNotificationMode(newMode);
            LOG.info("Toggled reading mode setting. Switched to " + (newMode ? "Notification Mode" : "Reader Panel Mode"));
            // The actual UI switching is handled by the listener (ReaderModeSwitcher)
        } else {
            LOG.error("ReaderModeSettings service not found. Cannot toggle reading mode.");
        }
    }
} 