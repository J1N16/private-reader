package com.lv.tool.privatereader.ui;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.intellij.openapi.project.DumbAware;
import com.lv.tool.privatereader.util.PluginUtil;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 阅读器工具窗口工厂
 * 用于注册和显示阅读器面板
 */
public class ReaderToolWindowFactory implements ToolWindowFactory, DumbAware {
    // Map to store active panels per project
    // 使用 WeakHashMap 避免 Project 对象泄漏
    public static final Map<Project, ReaderPanel> PROJECT_PANELS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Logger LOG = Logger.getInstance(ReaderToolWindowFactory.class);
    
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        LOG.info("创建阅读器工具窗口内容 for project: " + project.getName());

        // **尝试获取由 ProjectActivity 预创建的 ReaderPanel 实例**
        ReaderPanel readerPanel = PROJECT_PANELS.get(project);

        if (readerPanel == null) {
            // **回退逻辑：如果 ProjectActivity 未能创建或存储 Panel，则在这里创建**
            LOG.warn("ReaderPanel instance not found in static map for project: " + project.getName() + ". Creating a new one (fallback).");
            readerPanel = new ReaderPanel(project);
            // Store the newly created panel instance for consistency
            PROJECT_PANELS.put(project, readerPanel);
        } else {
            LOG.info("Using pre-created ReaderPanel instance from static map for project: " + project.getName());
        }

        // 创建内容
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(readerPanel, "阅读器", false);
        content.setDisposer(readerPanel); // Disposer will call panel.dispose()
        
        ContentManager contentManager = toolWindow.getContentManager();
        // 添加内容到工具窗口
        contentManager.addContent(content);
        
        // **保留 ContentManagerListener 以处理面板移除时的清理**
        contentManager.addContentManagerListener(new ContentManagerListener() {
            @Override
            public void contentRemoved(@NotNull com.intellij.ui.content.ContentManagerEvent event) {
                if (event.getContent().getComponent() instanceof ReaderPanel) {
                    ReaderPanel panelToRemove = (ReaderPanel) event.getContent().getComponent();
                    // Check if the panel being removed is the one stored for this project
                    if (PROJECT_PANELS.get(project) == panelToRemove) {
                        // Panel's own dispose() method SHOULD handle removal from the map,
                        // but we can remove it here defensively IF the disposer didn't run yet
                        // or failed. However, calling remove directly might interfere with the
                        // panel's dispose logic if it expects to be in the map.
                        // Let's rely on the panel's dispose method for map removal.
                        // LOG.info("Content removed for project: " + project.getName() + ". Panel's dispose should handle map cleanup.");
                    } else {
                         LOG.warn("Removed content's component is a ReaderPanel, but not the one currently stored in the static map for project: " + project.getName());
                    }
                } else {
                     // If the removed component is not ReaderPanel, log it for debugging? Might be noisy.
                     // LOG.debug("Content removed, but component was not a ReaderPanel for project: " + project.getName());
                }
            }
            
            // Also remove panel if project is closing (although disposer *should* handle this)
            @Override
            public void contentRemoveQuery(@NotNull com.intellij.ui.content.ContentManagerEvent event) {
                // This might be too aggressive if multiple contents exist
                // if (event.getContent().getComponent() == readerPanel) {
                //     // Check if project is disposing? Need a safe way.
                // }
            }
        });

        // Ensure removal on project close via Disposable registration in panel itself
        // (No explicit code needed here if panel correctly implements Disposable
        // and removes itself from the map in its dispose() method)

        LOG.info("阅读器工具窗口内容创建完成 for project: " + project.getName());
    }

    /**
     * Finds the active ReaderPanel for a given project.
     *
     * @param project The project.
     * @return The ReaderPanel instance, or null if not found.
     */
    public static @Nullable ReaderPanel findPanel(@NotNull Project project) {
        ReaderPanel panel = PROJECT_PANELS.get(project);
        // Keep debug logging if needed
        // LOG.debug("findPanel called for project: " + project.getName() + ". Panel found: " + (panel != null));
        return panel;
    }
    
    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        toolWindow.setTitle("Private Reader");
        toolWindow.setStripeTitle("Private Reader");
    }
    
    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return PluginUtil.isPluginEnabled();
    }
} 