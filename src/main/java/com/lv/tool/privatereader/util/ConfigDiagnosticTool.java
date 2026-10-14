package com.lv.tool.privatereader.util;

import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.settings.ApplicationManagerHost;
import com.lv.tool.privatereader.settings.DiagnosticsHosts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 配置诊断工具
 *
 * 用于诊断配置加载、保存和应用过程中的问题
 * 这是一个临时工具，用于排查配置没有生效的问题
 */
public class ConfigDiagnosticTool {
    private static final Logger LOG = Logger.getInstance(ConfigDiagnosticTool.class);

    /** 配置目录:~/.private-reader/settings */
    static Path configDir() {
        return Paths.get(System.getProperty("user.home"), ".private-reader", "settings");
    }

    /**
     * 运行诊断
     *
     * @return 诊断结果
     */
    public static Map<String, Object> runDiagnostic() {
        return runDiagnostic(new ApplicationManagerHost());
    }

    /**
     * 运行诊断(环境查询由调用方注入,便于 IntelliJ 容器外测试)。
     *
     * @param host 环境查询宿主
     * @return 诊断结果
     */
    static Map<String, Object> runDiagnostic(DiagnosticsHosts.Host host) {
        Map<String, Object> result = new HashMap<>();

        // 检查配置目录
        checkConfigDirectory(result);

        // 检查配置文件
        checkConfigFiles(result);

        // 检查配置值
        checkConfigValues(result, host);

        // 检查阅读模式
        checkReaderMode(result, host);

        // 检查通知栏模式
        checkNotificationMode(result, host);

        return result;
    }

    /**
     * 检查配置目录
     *
     * @param result 诊断结果
     */
    private static void checkConfigDirectory(Map<String, Object> result) {
        checkConfigDirectory(result, configDir());
    }

    /**
     * 检查配置目录并回填诊断结果(包级可见,目录由调用方提供,便于测试)。
     */
    static void checkConfigDirectory(Map<String, Object> result, Path configDir) {
        try {
            boolean dirExists = Files.exists(configDir);
            boolean dirReadable = Files.isReadable(configDir);
            boolean dirWritable = Files.isWritable(configDir);

            result.put("configDirExists", dirExists);
            result.put("configDirReadable", dirReadable);
            result.put("configDirWritable", dirWritable);
            result.put("configDirPath", configDir.toString());

            LOG.debug("配置目录诊断: 存在=" + dirExists + ", 可读=" + dirReadable + ", 可写=" + dirWritable + ", 路径=" + configDir);

            // 如果目录不存在,尝试创建
            if (!dirExists) {
                try {
                    Files.createDirectories(configDir);
                    LOG.debug("已创建配置目录: " + configDir);
                    result.put("configDirCreated", true);
                } catch (Exception e) {
                    LOG.error("创建配置目录失败: " + e.getMessage(), e);
                    result.put("configDirCreated", false);
                    result.put("configDirCreateError", e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.error("检查配置目录时出错: " + e.getMessage(), e);
            result.put("configDirCheckError", e.getMessage());
        }
    }

    /**
     * 检查配置文件
     *
     * @param result 诊断结果
     */
    private static void checkConfigFiles(Map<String, Object> result) {
        checkConfigFiles(result, configDir());
    }

    /**
     * 检查各配置文件存在/可读/可写/大小(包级可见,目录由调用方提供,便于测试)。
     */
    static void checkConfigFiles(Map<String, Object> result, Path configDir) {
        try {
            // 检查各个配置文件
            checkConfigFile(result, configDir, "PluginSettings.json");
            checkConfigFile(result, configDir, "ReaderSettings.json");
            checkConfigFile(result, configDir, "CacheSettings.json");
            checkConfigFile(result, configDir, "NotificationReaderSettings.json");
            checkConfigFile(result, configDir, "ReaderModeSettings.json");
        } catch (Exception e) {
            LOG.error("检查配置文件时出错: " + e.getMessage(), e);
            result.put("configFilesCheckError", e.getMessage());
        }
    }

    /**
     * 检查单个配置文件
     *
     * @param result 诊断结果
     * @param configDir 配置目录
     * @param fileName 文件名
     */
    private static void checkConfigFile(Map<String, Object> result, Path configDir, String fileName) {
        try {
            Path filePath = configDir.resolve(fileName);
            boolean fileExists = Files.exists(filePath);
            boolean fileReadable = Files.isReadable(filePath);
            boolean fileWritable = Files.isWritable(filePath);
            long fileSize = fileExists ? Files.size(filePath) : 0;

            String fileKey = fileName.replace(".json", "");
            result.put(fileKey + "Exists", fileExists);
            result.put(fileKey + "Readable", fileReadable);
            result.put(fileKey + "Writable", fileWritable);
            result.put(fileKey + "Size", fileSize);

            LOG.debug("配置文件诊断 [" + fileName + "]: 存在=" + fileExists + ", 可读=" + fileReadable +
                    ", 可写=" + fileWritable + ", 大小=" + fileSize + "字节");

            // 如果文件存在且可读，读取内容
            if (fileExists && fileReadable && fileSize > 0) {
                String content = new String(Files.readAllBytes(filePath));
                // 不记录完整内容，只记录前100个字符，避免日志过大
                LOG.debug("配置文件内容 [" + fileName + "] (前100个字符): " +
                        (content.length() > 100 ? content.substring(0, 100) + "..." : content));
            }
        } catch (Exception e) {
            LOG.error("检查配置文件 [" + fileName + "] 时出错: " + e.getMessage(), e);
            result.put(fileName.replace(".json", "") + "CheckError", e.getMessage());
        }
    }

    /**
     * 检查配置值
     *
     * @param result 诊断结果
     */
    static void checkConfigValues(Map<String, Object> result, DiagnosticsHosts.Host host) {
        try {
            // 检查插件设置
            DiagnosticsHosts.PluginSettingsHost pluginSettings = host.pluginSettings();
            if (pluginSettings != null) {
                Map<String, Object> pluginSettingsValues = new HashMap<>();
                pluginSettingsValues.put("enabled", pluginSettings.enabled());
                pluginSettingsValues.put("autoUpdate", pluginSettings.autoUpdate());
                pluginSettingsValues.put("showNotifications", pluginSettings.showNotifications());
                pluginSettingsValues.put("language", pluginSettings.language());
                pluginSettingsValues.put("debugMode", pluginSettings.debugMode());

                result.put("pluginSettings", pluginSettingsValues);
                LOG.debug("插件设置值: " + pluginSettingsValues);
            } else {
                result.put("pluginSettingsAvailable", false);
                LOG.warn("无法获取插件设置实例");
            }

            // 检查阅读器设置
            DiagnosticsHosts.ReaderSettingsHost readerSettings = host.readerSettings();
            if (readerSettings != null) {
                Map<String, Object> readerSettingsValues = new HashMap<>();
                readerSettingsValues.put("fontFamily", readerSettings.fontFamily());
                readerSettingsValues.put("fontSize", readerSettings.fontSize());
                readerSettingsValues.put("bold", readerSettings.bold());
                readerSettingsValues.put("darkTheme", readerSettings.darkTheme());
                readerSettingsValues.put("useAnimation", readerSettings.useAnimation());

                result.put("readerSettings", readerSettingsValues);
                LOG.debug("阅读器设置值: " + readerSettingsValues);
            } else {
                result.put("readerSettingsAvailable", false);
                LOG.warn("无法获取阅读器设置实例");
            }

            // 检查缓存设置
            DiagnosticsHosts.CacheSettingsHost cacheSettings = host.cacheSettings();
            if (cacheSettings != null) {
                Map<String, Object> cacheSettingsValues = new HashMap<>();
                cacheSettingsValues.put("enableCache", cacheSettings.enableCache());
                cacheSettingsValues.put("maxCacheSize", cacheSettings.maxCacheSize());
                cacheSettingsValues.put("maxCacheAge", cacheSettings.maxCacheAge());
                cacheSettingsValues.put("enablePreload", cacheSettings.enablePreload());
                cacheSettingsValues.put("preloadCount", cacheSettings.preloadCount());

                result.put("cacheSettings", cacheSettingsValues);
                LOG.debug("缓存设置值: " + cacheSettingsValues);
            } else {
                result.put("cacheSettingsAvailable", false);
                LOG.warn("无法获取缓存设置实例");
            }

            // 检查阅读模式设置
            DiagnosticsHosts.ReaderModeSettingsHost modeSettings = host.readerModeSettings();
            if (modeSettings != null) {
                Map<String, Object> modeSettingsValues = new HashMap<>();
                modeSettingsValues.put("notificationMode", modeSettings.notificationMode());
                modeSettingsValues.put("autoScroll", modeSettings.autoScroll());
                modeSettingsValues.put("scrollSpeed", modeSettings.scrollSpeed());
                modeSettingsValues.put("showLineNumbers", modeSettings.showLineNumbers());

                result.put("modeSettings", modeSettingsValues);
                LOG.debug("阅读模式设置值: " + modeSettingsValues);
            } else {
                result.put("modeSettingsAvailable", false);
                LOG.warn("无法获取阅读模式设置实例");
            }
        } catch (Exception e) {
            LOG.error("检查配置值时出错: " + e.getMessage(), e);
            result.put("configValuesCheckError", e.getMessage());
        }
    }

    /**
     * 重置所有配置
     *
     * @return 是否成功重置
     */
    public static boolean resetAllConfigs() {
        Path dir = configDir();
        // 如果目录存在,删除所有配置文件
        if (Files.exists(dir)) {
            resetConfigs(dir);
            LOG.debug("已重置所有配置文件");
            return true;
        } else {
            LOG.warn("配置目录不存在,无需重置");
            return false;
        }
    }

    /**
     * 删除配置目录下的所有已知配置文件(包级可见,目录由调用方提供,便于测试)。
     */
    static void resetConfigs(Path configDir) {
        deleteConfigFile(configDir, "PluginSettings.json");
        deleteConfigFile(configDir, "ReaderSettings.json");
        deleteConfigFile(configDir, "CacheSettings.json");
        deleteConfigFile(configDir, "NotificationReaderSettings.json");
        deleteConfigFile(configDir, "ReaderModeSettings.json");
    }

    /**
     * 删除配置文件
     *
     * @param configDir 配置目录
     * @param fileName 文件名
     */
    private static void deleteConfigFile(Path configDir, String fileName) {
        try {
            Path filePath = configDir.resolve(fileName);
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                LOG.debug("已删除配置文件: " + fileName);
            }
        } catch (Exception e) {
            LOG.error("删除配置文件 [" + fileName + "] 时出错: " + e.getMessage(), e);
        }
    }

    /**
     * 强制保存所有配置
     *
     * @return 是否成功保存
     */
    public static boolean forceSaveAllConfigs() {
        return forceSaveAllConfigs(new ApplicationManagerHost());
    }

    /**
     * 强制保存所有配置(环境查询由调用方注入,便于 IntelliJ 容器外测试)。
     *
     * @param host 环境查询宿主
     * @return 是否成功保存
     */
    static boolean forceSaveAllConfigs(DiagnosticsHosts.Host host) {
        try {
            host.saveAllConfigs();
            return true;
        } catch (Exception e) {
            LOG.error("强制保存配置时出错: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 检查阅读模式
     *
     * @param result 诊断结果
     * @param host   环境查询宿主
     */
    static void checkReaderMode(Map<String, Object> result, DiagnosticsHosts.Host host) {
        try {
            // 获取阅读模式设置
            DiagnosticsHosts.ReaderModeSettingsHost modeSettings = host.readerModeSettings();
            if (modeSettings != null) {
                boolean notificationMode = modeSettings.notificationMode();
                result.put("readerModeNotification", notificationMode);
                LOG.debug("[配置诊断] 当前阅读模式: " + (notificationMode ? "通知栏模式" : "阅读器模式"));
            } else {
                result.put("readerModeAvailable", false);
                LOG.warn("[配置诊断] 无法获取阅读模式设置实例");
            }
        } catch (Exception e) {
            LOG.error("[配置诊断] 检查阅读模式时出错: " + e.getMessage(), e);
            result.put("readerModeCheckError", e.getMessage());
        }
    }

    /**
     * 检查通知栏模式
     *
     * @param result 诊断结果
     */
    static void checkNotificationMode(Map<String, Object> result, DiagnosticsHosts.Host host) {
        try {
            LOG.debug("[诊断] 开始检查通知栏模式");

            // 检查通知服务
            DiagnosticsHosts.NotificationServiceHost notificationService = host.notificationService();
            if (notificationService == null) {
                result.put("notificationServiceAvailable", false);
                LOG.warn("[诊断] 无法获取通知服务实例");
                return;
            }

            result.put("notificationServiceAvailable", true);
            LOG.debug("[诊断] 成功获取通知服务实例");

            // 检查通知栏设置
            DiagnosticsHosts.NotificationSettingsHost notificationSettings = host.notificationSettings();
            if (notificationSettings == null) {
                result.put("notificationSettingsAvailable", false);
                LOG.warn("[诊断] 无法获取通知栏设置实例");
                return;
            }

            result.put("notificationSettingsAvailable", true);
            LOG.debug("[诊断] 成功获取通知栏设置实例");

            // 获取通知栏设置值
            Map<String, Object> notificationSettingsValues = new HashMap<>();
            notificationSettingsValues.put("pageSize", notificationSettings.pageSize());
            notificationSettingsValues.put("showPageNumber", notificationSettings.showPageNumber());

            result.put("notificationSettings", notificationSettingsValues);
            LOG.debug("[诊断] 通知栏设置值: " + notificationSettingsValues);

            // 获取当前页码和总页数
            int currentPage = notificationService.currentPage();
            int totalPages = notificationService.totalPages();

            result.put("currentPage", currentPage);
            result.put("totalPages", totalPages);
            LOG.debug("[诊断] 当前页码: " + currentPage + ", 总页数: " + totalPages);

            // 获取阅读模式设置
            DiagnosticsHosts.ReaderModeSettingsHost modeSettings = host.readerModeSettings();
            if (modeSettings != null) {
                boolean notificationMode = modeSettings.notificationMode();
                result.put("isNotificationModeEnabled", notificationMode);
                LOG.debug("[诊断] 通知栏模式是否启用: " + notificationMode);
            }

            LOG.debug("[诊断] 通知栏模式检查完成");
        } catch (Exception e) {
            LOG.error("[诊断] 检查通知栏模式时出错: " + e.getMessage(), e);
            result.put("notificationModeCheckError", e.getMessage());
        }
    }

    /**
     * 应用阅读模式
     *
     * @return 是否成功应用
     */
    public static boolean applyReaderMode() {
        return applyReaderMode(new ApplicationManagerHost());
    }

    /**
     * 应用阅读模式(环境查询由调用方注入,便于 IntelliJ 容器外测试)。
     *
     * @param host 环境查询宿主
     * @return 是否成功应用
     */
    static boolean applyReaderMode(DiagnosticsHosts.Host host) {
        try {
            DiagnosticsHosts.ReaderModeSettingsHost modeSettings = host.readerModeSettings();
            if (modeSettings == null) {
                LOG.error("[配置诊断] 无法获取阅读模式设置实例");
                return false;
            }
            return host.applyReaderMode(modeSettings);
        } catch (Exception e) {
            LOG.error("[配置诊断] 应用阅读模式时出错: " + e.getMessage(), e);
            return false;
        }
    }
}
