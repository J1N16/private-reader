package com.lv.tool.privatereader.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.service.NotificationService;
import com.lv.tool.privatereader.service.ReaderModeSwitcher;
import com.lv.tool.privatereader.settings.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

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

    /**
     * 运行诊断
     *
     * @return 诊断结果
     */
    public static Map<String, Object> runDiagnostic() {
        Map<String, Object> result = new HashMap<>();

        // 检查配置目录
        checkConfigDirectory(result);

        // 检查配置文件
        checkConfigFiles(result);

        // 检查配置值
        checkConfigValues(result);

        // 检查阅读模式
        checkReaderMode(result);

        // 检查通知栏模式
        checkNotificationMode(result);

        return result;
    }

    /**
     * 检查配置目录
     *
     * @param result 诊断结果
     */
    private static void checkConfigDirectory(Map<String, Object> result) {
        try {
            String userHome = System.getProperty("user.home");
            Path configDir = Paths.get(userHome, ".private-reader", "settings");

            boolean dirExists = Files.exists(configDir);
            boolean dirReadable = Files.isReadable(configDir);
            boolean dirWritable = Files.isWritable(configDir);

            result.put("configDirExists", dirExists);
            result.put("configDirReadable", dirReadable);
            result.put("configDirWritable", dirWritable);
            result.put("configDirPath", configDir.toString());

            LOG.debug("配置目录诊断: 存在=" + dirExists + ", 可读=" + dirReadable + ", 可写=" + dirWritable + ", 路径=" + configDir);

            // 如果目录不存在，尝试创建
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
        try {
            String userHome = System.getProperty("user.home");
            Path configDir = Paths.get(userHome, ".private-reader", "settings");

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
    private static void checkConfigValues(Map<String, Object> result) {
        try {
            // 检查插件设置
            PluginSettings pluginSettings = ApplicationManager.getApplication().getService(PluginSettings.class);
            if (pluginSettings != null) {
                Map<String, Object> pluginSettingsValues = new HashMap<>();
                pluginSettingsValues.put("enabled", pluginSettings.isEnabled());
                pluginSettingsValues.put("autoUpdate", pluginSettings.isAutoUpdate());
                pluginSettingsValues.put("showNotifications", pluginSettings.isShowNotifications());
                pluginSettingsValues.put("language", pluginSettings.getLanguage());
                pluginSettingsValues.put("debugMode", pluginSettings.isDebugMode());

                result.put("pluginSettings", pluginSettingsValues);
                LOG.debug("插件设置值: " + pluginSettingsValues);
            } else {
                result.put("pluginSettingsAvailable", false);
                LOG.warn("无法获取插件设置实例");
            }

            // 检查阅读器设置
            ReaderSettings readerSettings = ApplicationManager.getApplication().getService(ReaderSettings.class);
            if (readerSettings != null) {
                Map<String, Object> readerSettingsValues = new HashMap<>();
                readerSettingsValues.put("fontFamily", readerSettings.getFontFamily());
                readerSettingsValues.put("fontSize", readerSettings.getFontSize());
                readerSettingsValues.put("bold", readerSettings.isBold());
                readerSettingsValues.put("darkTheme", readerSettings.isDarkTheme());
                readerSettingsValues.put("useAnimation", readerSettings.isUseAnimation());

                result.put("readerSettings", readerSettingsValues);
                LOG.debug("阅读器设置值: " + readerSettingsValues);
            } else {
                result.put("readerSettingsAvailable", false);
                LOG.warn("无法获取阅读器设置实例");
            }

            // 检查缓存设置
            CacheSettings cacheSettings = ApplicationManager.getApplication().getService(CacheSettings.class);
            if (cacheSettings != null) {
                Map<String, Object> cacheSettingsValues = new HashMap<>();
                cacheSettingsValues.put("enableCache", cacheSettings.isEnableCache());
                cacheSettingsValues.put("maxCacheSize", cacheSettings.getMaxCacheSize());
                cacheSettingsValues.put("maxCacheAge", cacheSettings.getMaxCacheAge());
                cacheSettingsValues.put("enablePreload", cacheSettings.isEnablePreload());
                cacheSettingsValues.put("preloadCount", cacheSettings.getPreloadCount());

                result.put("cacheSettings", cacheSettingsValues);
                LOG.debug("缓存设置值: " + cacheSettingsValues);
            } else {
                result.put("cacheSettingsAvailable", false);
                LOG.warn("无法获取缓存设置实例");
            }

            // 检查阅读模式设置
            ReaderModeSettings modeSettings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
            if (modeSettings != null) {
                Map<String, Object> modeSettingsValues = new HashMap<>();
                modeSettingsValues.put("notificationMode", modeSettings.isNotificationMode());
                modeSettingsValues.put("autoScroll", modeSettings.isAutoScroll());
                modeSettingsValues.put("scrollSpeed", modeSettings.getScrollSpeed());
                modeSettingsValues.put("showLineNumbers", modeSettings.isShowLineNumbers());

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
        try {
            String userHome = System.getProperty("user.home");
            Path configDir = Paths.get(userHome, ".private-reader", "settings");

            // 如果目录存在，删除所有配置文件
            if (Files.exists(configDir)) {
                // 删除各个配置文件
                deleteConfigFile(configDir, "PluginSettings.json");
                deleteConfigFile(configDir, "ReaderSettings.json");
                deleteConfigFile(configDir, "CacheSettings.json");
                deleteConfigFile(configDir, "NotificationReaderSettings.json");
                deleteConfigFile(configDir, "ReaderModeSettings.json");

                LOG.debug("已重置所有配置文件");
                return true;
            } else {
                LOG.warn("配置目录不存在，无需重置");
                return false;
            }
        } catch (Exception e) {
            LOG.error("重置配置时出错: " + e.getMessage(), e);
            return false;
        }
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
        try {
            // 强制保存插件设置
            PluginSettings pluginSettings = ApplicationManager.getApplication().getService(PluginSettings.class);
            if (pluginSettings != null) {
                // 先标记为已修改
                pluginSettings.setEnabled(pluginSettings.isEnabled());
                pluginSettings.saveSettings();
                LOG.debug("已强制保存插件设置");
            }

            // 强制保存阅读器设置
            ReaderSettings readerSettings = ApplicationManager.getApplication().getService(ReaderSettings.class);
            if (readerSettings != null) {
                // 先标记为已修改
                readerSettings.setFontFamily(readerSettings.getFontFamily());
                readerSettings.saveSettings();
                LOG.debug("已强制保存阅读器设置");
            }

            // 强制保存缓存设置
            CacheSettings cacheSettings = ApplicationManager.getApplication().getService(CacheSettings.class);
            if (cacheSettings != null) {
                // 先标记为已修改
                cacheSettings.setEnableCache(cacheSettings.isEnableCache());
                cacheSettings.saveSettings();
                LOG.debug("已强制保存缓存设置");
            }

            // 强制保存阅读模式设置
            ReaderModeSettings modeSettings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
            if (modeSettings != null) {
                // 先标记为已修改
                modeSettings.setNotificationMode(modeSettings.isNotificationMode());
                modeSettings.saveSettings();
                LOG.debug("已强制保存阅读模式设置");
            }

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
     */
    private static void checkReaderMode(Map<String, Object> result) {
        try {
            // 获取阅读模式设置
            ReaderModeSettings modeSettings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
            if (modeSettings != null) {
                boolean notificationMode = modeSettings.isNotificationMode();
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
    private static void checkNotificationMode(Map<String, Object> result) {
        try {
            LOG.debug("[诊断] 开始检查通知栏模式");

            // 检查通知服务
            NotificationService notificationService = ApplicationManager.getApplication().getService(NotificationService.class);
            if (notificationService == null) {
                result.put("notificationServiceAvailable", false);
                LOG.warn("[诊断] 无法获取通知服务实例");
                return;
            }

            result.put("notificationServiceAvailable", true);
            LOG.debug("[诊断] 成功获取通知服务实例");

            // 检查通知栏设置
            NotificationReaderSettings notificationSettings = ApplicationManager.getApplication().getService(NotificationReaderSettings.class);
            if (notificationSettings == null) {
                result.put("notificationSettingsAvailable", false);
                LOG.warn("[诊断] 无法获取通知栏设置实例");
                return;
            }

            result.put("notificationSettingsAvailable", true);
            LOG.debug("[诊断] 成功获取通知栏设置实例");

            // 获取通知栏设置值
            Map<String, Object> notificationSettingsValues = new HashMap<>();
            notificationSettingsValues.put("pageSize", notificationSettings.getPageSize());
            notificationSettingsValues.put("showPageNumber", notificationSettings.isShowPageNumber());

            result.put("notificationSettings", notificationSettingsValues);
            LOG.debug("[诊断] 通知栏设置值: " + notificationSettingsValues);

            // 获取当前页码和总页数
            int currentPage = notificationService.getCurrentPage();
            int totalPages = notificationService.getTotalPages();

            result.put("currentPage", currentPage);
            result.put("totalPages", totalPages);
            LOG.debug("[诊断] 当前页码: " + currentPage + ", 总页数: " + totalPages);

            // 获取阅读模式设置
            ReaderModeSettings modeSettings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
            if (modeSettings != null) {
                boolean notificationMode = modeSettings.isNotificationMode();
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
        try {
            // 获取阅读模式设置
            ReaderModeSettings modeSettings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
            if (modeSettings == null) {
                LOG.error("[配置诊断] 无法获取阅读模式设置实例");
                return false;
            }

            // 获取阅读模式切换器
            ReaderModeSwitcher modeSwitcher = ApplicationManager.getApplication().getService(ReaderModeSwitcher.class);
            if (modeSwitcher == null) {
                LOG.error("[配置诊断] 无法获取阅读模式切换器实例");
                return false;
            }

            // 获取当前阅读模式
            boolean notificationMode = modeSettings.isNotificationMode();
            LOG.debug("[配置诊断] 当前阅读模式: " + (notificationMode ? "通知栏模式" : "阅读器模式"));

            // 应用阅读模式
            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
            if (openProjects.length == 0) {
                LOG.warn("[配置诊断] 没有打开的项目，无法应用阅读模式");
                return false;
            }

            for (Project project : openProjects) {
                LOG.debug("[配置诊断] 应用阅读模式到项目: " + project.getName());
                modeSwitcher.applyInitialModeForProject(project);
            }

            LOG.debug("[配置诊断] 成功应用阅读模式");
            return true;
        } catch (Exception e) {
            LOG.error("[配置诊断] 应用阅读模式时出错: " + e.getMessage(), e);
            return false;
        }
    }
}
