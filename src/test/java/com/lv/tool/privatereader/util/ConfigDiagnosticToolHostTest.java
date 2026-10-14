package com.lv.tool.privatereader.util;

import com.lv.tool.privatereader.settings.DiagnosticsHosts;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConfigDiagnosticTool 值检查 / 阅读模式 / 通知栏模式 / 保存 / 应用模式逻辑测试(V27)。
 *
 * <p>V27 把 {@code ApplicationManager} 服务查询抽成 {@link DiagnosticsHosts.Host},
 * 本测试注入全部可用的 fake,确定性覆盖原先在 IntelliJ 容器外不可达的分支
 * (此前该类 22.3%,缺口集中在服务查询与聚合逻辑)。</p>
 *
 * <p>所有分支均返回「服务可用」,避免触发 {@code LOG.error} 路径(测试环境断言错误日志)。</p>
 */
class ConfigDiagnosticToolHostTest {

    // --- 全部服务可用 ---

    private static DiagnosticsHosts.Host allAvailableHost(SaveCounter counter) {
        return new DiagnosticsHosts.Host() {
            @Override public DiagnosticsHosts.PluginSettingsHost pluginSettings() {
                return new DiagnosticsHosts.PluginSettingsHost() {
                    @Override public @Nullable String language() { return "zh"; }
                    @Override public boolean enabled() { return true; }
                    @Override public boolean autoUpdate() { return true; }
                    @Override public boolean showNotifications() { return true; }
                    @Override public boolean debugMode() { return false; }
                    @Override public void save() { counter.plugin++; }
                };
            }

            @Override public DiagnosticsHosts.ReaderSettingsHost readerSettings() {
                return new DiagnosticsHosts.ReaderSettingsHost() {
                    @Override public @Nullable String fontFamily() { return "Consolas"; }
                    @Override public int fontSize() { return 16; }
                    @Override public boolean bold() { return true; }
                    @Override public boolean darkTheme() { return false; }
                    @Override public boolean useAnimation() { return true; }
                    @Override public void save() { counter.reader++; }
                };
            }

            @Override public DiagnosticsHosts.CacheSettingsHost cacheSettings() {
                return new DiagnosticsHosts.CacheSettingsHost() {
                    @Override public boolean enableCache() { return true; }
                    @Override public int maxCacheSize() { return 100; }
                    @Override public int maxCacheAge() { return 7; }
                    @Override public boolean enablePreload() { return true; }
                    @Override public int preloadCount() { return 3; }
                    @Override public void save() { counter.cache++; }
                };
            }

            @Override public DiagnosticsHosts.ReaderModeSettingsHost readerModeSettings() {
                return new DiagnosticsHosts.ReaderModeSettingsHost() {
                    @Override public boolean notificationMode() { return true; }
                    @Override public boolean autoScroll() { return false; }
                    @Override public int scrollSpeed() { return 5; }
                    @Override public boolean showLineNumbers() { return true; }
                    @Override public void save() { counter.mode++; }
                };
            }

            @Override public DiagnosticsHosts.NotificationSettingsHost notificationSettings() {
                return new DiagnosticsHosts.NotificationSettingsHost() {
                    @Override public int pageSize() { return 500; }
                    @Override public boolean showPageNumber() { return true; }
                    @Override public boolean notificationMode() { return true; }
                };
            }

            @Override public DiagnosticsHosts.NotificationServiceHost notificationService() {
                return new DiagnosticsHosts.NotificationServiceHost() {
                    @Override public int currentPage() { return 2; }
                    @Override public int totalPages() { return 10; }
                };
            }

            @Override
            public boolean applyReaderMode(@NotNull DiagnosticsHosts.ReaderModeSettingsHost modeSettings) {
                counter.applied = modeSettings.notificationMode();
                return true;
            }

            @Override public int saveAllConfigs() {
                pluginSettings().save();
                readerSettings().save();
                cacheSettings().save();
                readerModeSettings().save();
                return 4;
            }
        };
    }

    private static final class SaveCounter {
        int plugin;
        int reader;
        int cache;
        int mode;
        boolean applied;
    }

    /** 全 null 宿主,用于覆盖「服务不可用」分支(注意:这些分支走 LOG.warn,非 error)。 */
    private static DiagnosticsHosts.Host allNullHost() {
        return new DiagnosticsHosts.Host() {
            @Override public DiagnosticsHosts.PluginSettingsHost pluginSettings() { return null; }
            @Override public DiagnosticsHosts.ReaderSettingsHost readerSettings() { return null; }
            @Override public DiagnosticsHosts.CacheSettingsHost cacheSettings() { return null; }
            @Override public DiagnosticsHosts.ReaderModeSettingsHost readerModeSettings() { return null; }
            @Override public DiagnosticsHosts.NotificationSettingsHost notificationSettings() { return null; }
            @Override public DiagnosticsHosts.NotificationServiceHost notificationService() { return null; }
            @Override public boolean applyReaderMode(@NotNull DiagnosticsHosts.ReaderModeSettingsHost s) { return false; }
            @Override public int saveAllConfigs() { return 0; }
        };
    }

    // --- checkConfigValues ---

    @Test
    @SuppressWarnings("unchecked")
    void checkConfigValuesPopulatesAllSettingGroupsWhenServicesAvailable() {
        SaveCounter counter = new SaveCounter();
        Map<String, Object> result = new HashMap<>();

        ConfigDiagnosticTool.checkConfigValues(result, allAvailableHost(counter));

        Map<String, Object> plugin = (Map<String, Object>) result.get("pluginSettings");
        assertEquals("zh", plugin.get("language"));
        assertEquals(true, plugin.get("enabled"));

        Map<String, Object> reader = (Map<String, Object>) result.get("readerSettings");
        assertEquals(16, reader.get("fontSize"));
        assertEquals("Consolas", reader.get("fontFamily"));

        Map<String, Object> cache = (Map<String, Object>) result.get("cacheSettings");
        assertEquals(100, cache.get("maxCacheSize"));
        assertEquals(3, cache.get("preloadCount"));

        Map<String, Object> mode = (Map<String, Object>) result.get("modeSettings");
        assertEquals(true, mode.get("notificationMode"));
        assertEquals(5, mode.get("scrollSpeed"));

        assertNull(result.get("pluginSettingsAvailable"));
    }

    @Test
    void checkConfigValuesFlagsUnavailableServices() {
        Map<String, Object> result = new HashMap<>();

        ConfigDiagnosticTool.checkConfigValues(result, allNullHost());

        assertEquals(false, result.get("pluginSettingsAvailable"));
        assertEquals(false, result.get("readerSettingsAvailable"));
        assertEquals(false, result.get("cacheSettingsAvailable"));
        assertEquals(false, result.get("modeSettingsAvailable"));
        assertNull(result.get("configValuesCheckError"));
    }

    // --- checkReaderMode ---

    @Test
    void checkReaderModeReportsNotificationMode() {
        Map<String, Object> result = new HashMap<>();

        ConfigDiagnosticTool.checkReaderMode(result, allAvailableHost(new SaveCounter()));

        assertEquals(true, result.get("readerModeNotification"));
        assertNull(result.get("readerModeAvailable"));
    }

    @Test
    void checkReaderModeFlagsUnavailableService() {
        Map<String, Object> result = new HashMap<>();

        ConfigDiagnosticTool.checkReaderMode(result, allNullHost());

        assertEquals(false, result.get("readerModeAvailable"));
        assertNull(result.get("readerModeNotification"));
    }

    // --- checkNotificationMode ---

    @Test
    @SuppressWarnings("unchecked")
    void checkNotificationModeCollectsSettingsAndPageCounts() {
        Map<String, Object> result = new HashMap<>();

        ConfigDiagnosticTool.checkNotificationMode(result, allAvailableHost(new SaveCounter()));

        assertEquals(true, result.get("notificationServiceAvailable"));
        assertEquals(true, result.get("notificationSettingsAvailable"));
        assertEquals(2, result.get("currentPage"));
        assertEquals(10, result.get("totalPages"));
        assertEquals(true, result.get("isNotificationModeEnabled"));
        Map<String, Object> settings = (Map<String, Object>) result.get("notificationSettings");
        assertEquals(500, settings.get("pageSize"));
        assertEquals(true, settings.get("showPageNumber"));
    }

    @Test
    void checkNotificationModeBailsWhenServiceMissing() {
        Map<String, Object> result = new HashMap<>();

        ConfigDiagnosticTool.checkNotificationMode(result, allNullHost());

        assertEquals(false, result.get("notificationServiceAvailable"));
        assertNull(result.get("notificationSettings"));
    }

    @Test
    void checkNotificationModeBailsWhenSettingsMissing() {
        DiagnosticsHosts.Host host = new DiagnosticsHosts.Host() {
            @Override public DiagnosticsHosts.PluginSettingsHost pluginSettings() { return null; }
            @Override public DiagnosticsHosts.ReaderSettingsHost readerSettings() { return null; }
            @Override public DiagnosticsHosts.CacheSettingsHost cacheSettings() { return null; }
            @Override public DiagnosticsHosts.ReaderModeSettingsHost readerModeSettings() { return null; }
            @Override public DiagnosticsHosts.NotificationSettingsHost notificationSettings() { return null; }
            @Override public DiagnosticsHosts.NotificationServiceHost notificationService() {
                return new DiagnosticsHosts.NotificationServiceHost() {
                    @Override public int currentPage() { return 1; }
                    @Override public int totalPages() { return 1; }
                };
            }
            @Override public boolean applyReaderMode(@NotNull DiagnosticsHosts.ReaderModeSettingsHost s) { return false; }
            @Override public int saveAllConfigs() { return 0; }
        };
        Map<String, Object> result = new HashMap<>();

        ConfigDiagnosticTool.checkNotificationMode(result, host);

        assertEquals(true, result.get("notificationServiceAvailable"));
        assertEquals(false, result.get("notificationSettingsAvailable"));
        assertNull(result.get("currentPage"));
    }

    // --- forceSaveAllConfigs ---

    @Test
    void forceSaveAllConfigsSavesEveryAvailableGroup() {
        SaveCounter counter = new SaveCounter();

        assertTrue(ConfigDiagnosticTool.forceSaveAllConfigs(allAvailableHost(counter)));

        assertEquals(1, counter.plugin);
        assertEquals(1, counter.reader);
        assertEquals(1, counter.cache);
        assertEquals(1, counter.mode);
    }

    @Test
    void forceSaveAllConfigsReturnsTrueWithNoServices() {
        assertTrue(ConfigDiagnosticTool.forceSaveAllConfigs(allNullHost()));
    }

    // --- applyReaderMode ---

    @Test
    void applyReaderModeDelegatesToHostWhenSettingsAvailable() {
        SaveCounter counter = new SaveCounter();

        assertTrue(ConfigDiagnosticTool.applyReaderMode(allAvailableHost(counter)));

        assertTrue(counter.applied, "宿主应收到应用阅读模式的委托");
    }
}