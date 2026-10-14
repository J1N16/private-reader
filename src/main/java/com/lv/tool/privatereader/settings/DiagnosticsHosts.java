package com.lv.tool.privatereader.settings;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 配置诊断所需的外部环境抽象(V27)。
 *
 * <p>{@code ConfigDiagnosticTool} 原先直接静态调用 {@code ApplicationManager} 获取各类设置
 * 服务与通知服务,导致 IntelliJ 容器外的诊断逻辑几乎不可测。这里把「环境查询」抽成
 * 由 {@code ConfigDiagnosticTool.DiagnosticsHost} 聚合的四个最小接口,生产环境由
 * {@code ApplicationManagerHost} 委托原逻辑,测试环境可注入轻量 fake。</p>
 *
 * <p>各接口方法一律返回 {@code null} 表示「服务不可用」,与现有诊断分支一一对应。</p>
 */
public final class DiagnosticsHosts {

    private DiagnosticsHosts() {
    }

    /** 插件设置查询。 */
    public interface PluginSettingsHost {
        @Nullable String language();

        boolean enabled();

        boolean autoUpdate();

        boolean showNotifications();

        boolean debugMode();

        void save();
    }

    /** 阅读器设置查询。 */
    public interface ReaderSettingsHost {
        @Nullable String fontFamily();

        int fontSize();

        boolean bold();

        boolean darkTheme();

        boolean useAnimation();

        void save();
    }

    /** 缓存设置查询。 */
    public interface CacheSettingsHost {
        boolean enableCache();

        int maxCacheSize();

        int maxCacheAge();

        boolean enablePreload();

        int preloadCount();

        void save();
    }

    /** 阅读模式设置查询。 */
    public interface ReaderModeSettingsHost {
        boolean notificationMode();

        boolean autoScroll();

        int scrollSpeed();

        boolean showLineNumbers();

        void save();
    }

    /** 通知栏设置查询(诊断通知栏模式用)。 */
    public interface NotificationSettingsHost {
        int pageSize();

        boolean showPageNumber();

        boolean notificationMode();
    }

    /** 通知服务查询(诊断通知栏模式用)。 */
    public interface NotificationServiceHost {
        int currentPage();

        int totalPages();
    }

    /**
     * 聚合全部环境查询能力的宿主。任一查询返回 {@code null} 表示该服务不可用。
     */
    public interface Host {
        @Nullable PluginSettingsHost pluginSettings();

        @Nullable ReaderSettingsHost readerSettings();

        @Nullable CacheSettingsHost cacheSettings();

        @Nullable ReaderModeSettingsHost readerModeSettings();

        @Nullable NotificationSettingsHost notificationSettings();

        @Nullable NotificationServiceHost notificationService();

        /** 应用当前阅读模式;返回是否成功。 */
        boolean applyReaderMode(@NotNull ReaderModeSettingsHost modeSettings);

        /**
         * 强制保存全部设置,返回保存的条目数。
         */
        int saveAllConfigs();
    }
}