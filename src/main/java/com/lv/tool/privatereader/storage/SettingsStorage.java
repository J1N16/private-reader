package com.lv.tool.privatereader.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.settings.BaseSettings;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 设置存储服务
 */
@Service(Service.Level.APP)
public final class SettingsStorage {
    private static final Logger LOG = Logger.getInstance(SettingsStorage.class);
    private static final String SETTINGS_DIR = "private-reader";
    private static final String SETTINGS_EXT = ".json";

    private final Gson gson;

    public SettingsStorage() {
        this.gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    }

    public static SettingsStorage getInstance() {
        return ApplicationManager.getApplication().getService(SettingsStorage.class);
    }

    public <T extends BaseSettings<T>> void saveSettings(T settings) {
        LOG.debug("[配置诊断] SettingsStorage 开始保存设置: " + settings.getClass().getSimpleName());
        try {
            String json = gson.toJson(settings);
            LOG.debug("[配置诊断] 序列化设置完成: " + settings.getClass().getSimpleName() + ", JSON 长度: " + json.length());

            Path filePath = getSettingsFilePath(settings.getClass());
            LOG.debug("[配置诊断] 将保存设置到文件: " + filePath);

            writeFile(settings.getClass(), json);
            LOG.debug("[配置诊断] 成功保存设置到文件: " + filePath);
        } catch (Exception e) {
            LOG.error("[配置诊断] 保存设置失败: " + settings.getClass().getSimpleName(), e);
        }
    }

    public <T extends BaseSettings<T>> T loadSettings(Class<T> settingsClass) {
        LOG.debug("[配置诊断] SettingsStorage 开始加载设置: " + settingsClass.getSimpleName());
        try {
            Path filePath = getSettingsFilePath(settingsClass);
            LOG.debug("[配置诊断] 将从文件加载设置: " + filePath);

            if (!Files.exists(filePath)) {
                LOG.debug("[配置诊断] 设置文件不存在: " + filePath);
                return null;
            }

            String json = readFile(settingsClass);
            if (json == null || json.trim().isEmpty()) {
                LOG.debug("[配置诊断] 设置文件为空: " + filePath);
                return null;
            }

            LOG.debug("[配置诊断] 成功读取设置文件: " + filePath + ", JSON 长度: " + json.length());
            T settings = gson.fromJson(json, settingsClass);
            LOG.debug("[配置诊断] 成功反序列化设置: " + settingsClass.getSimpleName());
            return settings;
        } catch (Exception e) {
            LOG.error("[配置诊断] 加载设置失败: " + settingsClass.getSimpleName(), e);
            return null;
        }
    }

    private <T> String readFile(Class<T> settingsClass) throws IOException {
        Path filePath = getSettingsFilePath(settingsClass);
        if (!Files.exists(filePath)) {
            LOG.debug("[配置诊断] readFile: 文件不存在: " + filePath);
            return null;
        }

        LOG.debug("[配置诊断] readFile: 开始读取文件: " + filePath);
        try (FileReader reader = new FileReader(filePath.toFile())) {
            char[] buffer = new char[1024];
            StringBuilder content = new StringBuilder();
            int length;
            while ((length = reader.read(buffer)) != -1) {
                content.append(buffer, 0, length);
            }
            String result = content.toString();
            LOG.debug("[配置诊断] readFile: 成功读取文件: " + filePath + ", 长度: " + result.length());
            return result;
        } catch (IOException e) {
            LOG.error("[配置诊断] readFile: 读取文件失败: " + filePath, e);
            throw e;
        }
    }

    private <T> void writeFile(Class<T> settingsClass, String content) throws IOException {
        Path filePath = getSettingsFilePath(settingsClass);
        Path parentDir = filePath.getParent();

        LOG.debug("[配置诊断] writeFile: 开始写入文件: " + filePath);

        if (!Files.exists(parentDir)) {
            LOG.debug("[配置诊断] writeFile: 创建目录: " + parentDir);
            try {
                Files.createDirectories(parentDir);
            } catch (IOException e) {
                LOG.error("[配置诊断] writeFile: 创建目录失败: " + parentDir, e);
                throw e;
            }
        }

        try (FileWriter writer = new FileWriter(filePath.toFile())) {
            writer.write(content);
            LOG.debug("[配置诊断] writeFile: 成功写入文件: " + filePath + ", 长度: " + content.length());
        } catch (IOException e) {
            LOG.error("[配置诊断] writeFile: 写入文件失败: " + filePath, e);
            throw e;
        }
    }

    private <T> Path getSettingsFilePath(Class<T> settingsClass) {
        String userHome = System.getProperty("user.home");
        Path path = Paths.get(userHome, ".private-reader", "settings", settingsClass.getSimpleName() + SETTINGS_EXT);
        LOG.debug("[配置诊断] getSettingsFilePath: " + settingsClass.getSimpleName() + " -> " + path);
        return path;
    }
}