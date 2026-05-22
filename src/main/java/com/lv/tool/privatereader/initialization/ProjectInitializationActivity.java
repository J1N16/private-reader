package com.lv.tool.privatereader.initialization;

import com.intellij.ide.util.RunOnceUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.service.ReaderModeSwitcher;
import com.lv.tool.privatereader.settings.PluginSettings;
import com.lv.tool.privatereader.ui.ReaderPanel;
import com.lv.tool.privatereader.ui.ReaderToolWindowFactory;
import org.jetbrains.annotations.NotNull;

public class ProjectInitializationActivity implements StartupActivity, DumbAware {

    private static final Logger LOG = Logger.getInstance(ProjectInitializationActivity.class);
    // Key for cleaning up legacy files only once, copied from ProjectOpenListener
    private static final String CLEANUP_RUN_ONCE_KEY = "com.lv.tool.privatereader.startup.cleanupLegacyFiles";

    @Override
    public void runActivity(@NotNull Project project) {
        LOG.info("ProjectInitializationActivity: Initializing Private Reader for project: " + project.getName());

        // --- Ensure one-time initialization per project ---
        if (ReaderToolWindowFactory.PROJECT_PANELS.containsKey(project)) {
            LOG.info("ProjectInitializationActivity: Initialization already performed for project: " + project.getName());
            return;
        }
        LOG.info("ProjectInitializationActivity: Performing one-time initialization for project: " + project.getName());

        try {
            // 1. 检查插件是否启用
            PluginSettings settings = ApplicationManager.getApplication().getService(PluginSettings.class);
            if (settings == null || !settings.isEnabled()) {
                LOG.info("ProjectInitializationActivity: Plugin disabled, skipping initialization for project: " + project.getName());
                return;
            }

            // 3. 清理旧的配置文件
            RunOnceUtil.runOnceForProject(project, CLEANUP_RUN_ONCE_KEY, () -> {
                LOG.info("ProjectInitializationActivity: Performing legacy file cleanup for project: " + project.getName());
                cleanupLegacyFiles(project); // Copied from ProjectOpenListener
            });

            // 4. **创建 ReaderPanel 实例并存储**
            LOG.info("ProjectInitializationActivity: Creating and storing ReaderPanel instance for project: " + project.getName());
            ReaderPanel readerPanel = new ReaderPanel(project); // Assuming constructor is safe to call here
            ReaderToolWindowFactory.PROJECT_PANELS.put(project, readerPanel);
            LOG.info("ProjectInitializationActivity: ReaderPanel instance created and stored for project: " + project.getName());

            // 5. **应用初始阅读模式**
            LOG.info("ProjectInitializationActivity: [配置诊断] 尝试应用初始阅读模式 for project: " + project.getName());
            ReaderModeSwitcher modeSwitcher = ApplicationManager.getApplication().getService(ReaderModeSwitcher.class);
            if (modeSwitcher != null) {
                LOG.info("ProjectInitializationActivity: [配置诊断] 获取 ReaderModeSwitcher 服务成功，应用初始阅读模式 for project: " + project.getName());
                modeSwitcher.applyInitialModeForProject(project);
            } else {
                LOG.warn("ProjectInitializationActivity: [配置诊断] 无法获取 ReaderModeSwitcher 服务，跳过应用初始阅读模式 for project: " + project.getName());
            }

            // 6. **异步加载上次阅读的书籍**
            BookService bookService = ApplicationManager.getApplication().getService(BookService.class);
            if (bookService != null) {
                LOG.info("ProjectInitializationActivity: BookService obtained, fetching last read book asynchronously for project: " + project.getName());
                bookService.getLastReadBook()
                    .subscribe(
                        lastReadBook -> {
                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (project.isDisposed()) {
                                    LOG.warn("ProjectInitializationActivity: Project " + project.getName() + " disposed before last read book could be loaded.");
                                    return;
                                }
                                ReaderPanel currentPanel = ReaderToolWindowFactory.PROJECT_PANELS.get(project);
                                if (currentPanel != null) { // Check if panel still exists and project not disposed
                                    if (lastReadBook != null) {
                                        LOG.info("ProjectInitializationActivity: Last read book found: " + lastReadBook.getTitle() + ". Selecting in panel for project: " + project.getName());
                                        currentPanel.selectBookAndLoadProgress(lastReadBook);
                                    } else {
                                        LOG.info("ProjectInitializationActivity: No last read book found. Panel will show default state for project: " + project.getName());
                                    }
                                } else {
                                    LOG.warn("ProjectInitializationActivity: Panel became invalid before last read book could be loaded for project: " + project.getName());
                                }
                            }, project.getDisposed()); // Ensure run on EDT and only if project not disposed
                        },
                        error -> {
                            LOG.error("ProjectInitializationActivity: Error fetching last read book during project open for project: " + project.getName(), error);
                        }
                    );
            } else {
                LOG.error("ProjectInitializationActivity: BookService not available during project open initialization for project: " + project.getName());
            }
            LOG.info("ProjectInitializationActivity: Private Reader initialization tasks scheduled for project: " + project.getName());

        } catch (Exception e) {
            LOG.error("ProjectInitializationActivity: Error during Private Reader project open initialization for project: " + project.getName(), e);
            // Cleanup if initialization failed midway
            // Ensure project is not disposed before trying to remove from map
            if (!project.isDisposed()) {
                 ReaderToolWindowFactory.PROJECT_PANELS.remove(project);
                 LOG.info("ProjectInitializationActivity: Removed panel from map due to initialization error for project: " + project.getName());
            } else {
                 LOG.warn("ProjectInitializationActivity: Project " + project.getName() + " already disposed, cannot remove panel from map after initialization error.");
            }
        }
    }

    // --- Helper Methods ---

    /**
     * 清理旧的配置文件
     */
    private static void cleanupLegacyFiles(@NotNull Project project) {
        try {
            String basePath = project.getBasePath();
            if (basePath == null) {
                LOG.warn("ProjectInitializationActivity: Could not get project base path, skipping cleanup");
                return;
            }
            java.io.File legacyFile = new java.io.File(basePath, ".idea/private-reader-books.xml");
            if (legacyFile.exists() && legacyFile.delete()) {
                LOG.info("ProjectInitializationActivity: Deleted legacy file: " + legacyFile.getAbsolutePath());
            }
        } catch (Exception e) {
            LOG.error("ProjectInitializationActivity: Exception during legacy file cleanup", e);
        }
    }
} 