package com.lv.tool.privatereader.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.lv.tool.privatereader.service.NotificationService;
import com.lv.tool.privatereader.service.ReaderModeSwitcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 生产实现:把 {@link DiagnosticsHosts.Host} 的各查询委托给
 * {@code ApplicationManager} 的服务查找(V27)。
 *
 * <p>该适配器把「环境查询」与「诊断决策」分离:{@code ConfigDiagnosticTool} 只依赖 Host,
 * 测试可注入 fake,生产用本类。行为与拆分前的直接调用完全一致。</p>
 */
public final class ApplicationManagerHost implements DiagnosticsHosts.Host {

    private static final Logger LOG = Logger.getInstance(ApplicationManagerHost.class);

    private static <T> T service(Class<T> type) {
        return ApplicationManager.getApplication().getService(type);
    }

    @Override
    public @Nullable DiagnosticsHosts.PluginSettingsHost pluginSettings() {
        PluginSettings s = service(PluginSettings.class);
        if (s == null) {
            return null;
        }
        return new DiagnosticsHosts.PluginSettingsHost() {
            @Override public @Nullable String language() { return s.getLanguage(); }
            @Override public boolean enabled() { return s.isEnabled(); }
            @Override public boolean autoUpdate() { return s.isAutoUpdate(); }
            @Override public boolean showNotifications() { return s.isShowNotifications(); }
            @Override public boolean debugMode() { return s.isDebugMode(); }
            @Override public void save() { s.saveSettings(); }
        };
    }

    @Override
    public @Nullable DiagnosticsHosts.ReaderSettingsHost readerSettings() {
        ReaderSettings s = service(ReaderSettings.class);
        if (s == null) {
            return null;
        }
        return new DiagnosticsHosts.ReaderSettingsHost() {
            @Override public @Nullable String fontFamily() { return s.getFontFamily(); }
            @Override public int fontSize() { return s.getFontSize(); }
            @Override public boolean bold() { return s.isBold(); }
            @Override public boolean darkTheme() { return s.isDarkTheme(); }
            @Override public boolean useAnimation() { return s.isUseAnimation(); }
            @Override public void save() { s.saveSettings(); }
        };
    }

    @Override
    public @Nullable DiagnosticsHosts.CacheSettingsHost cacheSettings() {
        CacheSettings s = service(CacheSettings.class);
        if (s == null) {
            return null;
        }
        return new DiagnosticsHosts.CacheSettingsHost() {
            @Override public boolean enableCache() { return s.isEnableCache(); }
            @Override public int maxCacheSize() { return s.getMaxCacheSize(); }
            @Override public int maxCacheAge() { return s.getMaxCacheAge(); }
            @Override public boolean enablePreload() { return s.isEnablePreload(); }
            @Override public int preloadCount() { return s.getPreloadCount(); }
            @Override public void save() { s.saveSettings(); }
        };
    }

    @Override
    public @Nullable DiagnosticsHosts.ReaderModeSettingsHost readerModeSettings() {
        ReaderModeSettings s = service(ReaderModeSettings.class);
        return adaptReaderModeSettings(s);
    }

    @Nullable
    private DiagnosticsHosts.ReaderModeSettingsHost adaptReaderModeSettings(@Nullable ReaderModeSettings s) {
        if (s == null) {
            return null;
        }
        return new DiagnosticsHosts.ReaderModeSettingsHost() {
            @Override public boolean notificationMode() { return s.isNotificationMode(); }
            @Override public boolean autoScroll() { return s.isAutoScroll(); }
            @Override public int scrollSpeed() { return s.getScrollSpeed(); }
            @Override public boolean showLineNumbers() { return s.isShowLineNumbers(); }
            @Override public void save() { s.saveSettings(); }
        };
    }

    @Override
    public @Nullable DiagnosticsHosts.NotificationSettingsHost notificationSettings() {
        NotificationReaderSettings s = service(NotificationReaderSettings.class);
        if (s == null) {
            return null;
        }
        return new DiagnosticsHosts.NotificationSettingsHost() {
            @Override public int pageSize() { return s.getPageSize(); }
            @Override public boolean showPageNumber() { return s.isShowPageNumber(); }
            @Override public boolean notificationMode() {
                ReaderModeSettings m = service(ReaderModeSettings.class);
                return m != null && m.isNotificationMode();
            }
        };
    }

    @Override
    public @Nullable DiagnosticsHosts.NotificationServiceHost notificationService() {
        NotificationService s = service(NotificationService.class);
        if (s == null) {
            return null;
        }
        return new DiagnosticsHosts.NotificationServiceHost() {
            @Override public int currentPage() { return s.getCurrentPage(); }
            @Override public int totalPages() { return s.getTotalPages(); }
        };
    }

    @Override
    public boolean applyReaderMode(@NotNull DiagnosticsHosts.ReaderModeSettingsHost modeSettings) {
        ReaderModeSwitcher modeSwitcher = service(ReaderModeSwitcher.class);
        if (modeSwitcher == null) {
            LOG.error("[配置诊断] 无法获取阅读模式切换器实例");
            return false;
        }
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        if (openProjects.length == 0) {
            LOG.warn("[配置诊断] 没有打开的项目,无法应用阅读模式");
            return false;
        }
        boolean notificationMode = modeSettings.notificationMode();
        LOG.debug("[配置诊断] 当前阅读模式: " + (notificationMode ? "通知栏模式" : "阅读器模式"));
        for (Project project : openProjects) {
            LOG.debug("[配置诊断] 应用阅读模式到项目: " + project.getName());
            modeSwitcher.applyInitialModeForProject(project);
        }
        LOG.debug("[配置诊断] 成功应用阅读模式");
        return true;
    }

    @Override
    public int saveAllConfigs() {
        int saved = 0;
        DiagnosticsHosts.PluginSettingsHost plugin = pluginSettings();
        if (plugin != null) {
            plugin.save();
            LOG.debug("已强制保存插件设置");
            saved++;
        }
        DiagnosticsHosts.ReaderSettingsHost reader = readerSettings();
        if (reader != null) {
            reader.save();
            LOG.debug("已强制保存阅读器设置");
            saved++;
        }
        DiagnosticsHosts.CacheSettingsHost cache = cacheSettings();
        if (cache != null) {
            cache.save();
            LOG.debug("已强制保存缓存设置");
            saved++;
        }
        DiagnosticsHosts.ReaderModeSettingsHost mode = readerModeSettings();
        if (mode != null) {
            mode.save();
            LOG.debug("已强制保存阅读模式设置");
            saved++;
        }
        return saved;
    }
}