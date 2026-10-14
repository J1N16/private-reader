package com.lv.tool.privatereader.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConfigDiagnosticTool 纯文件系统诊断测试(V23)。
 *
 * <p>此前该类 0 覆盖。通过新增的包级重载(目录由调用方注入)测试配置目录/配置文件
 * 诊断与重置逻辑,不依赖真实 {@code ~/.private-reader} 与 IntelliJ 容器,也不触发
 * 服务查询分支。</p>
 */
class ConfigDiagnosticToolTest {

    @TempDir
    Path tempDir;

    // --- configDir:默认路径拼接 ---

    @Test
    void configDirPointsToPrivateReaderSettings() {
        Path dir = ConfigDiagnosticTool.configDir();
        assertTrue(dir.endsWith(Path.of(".private-reader", "settings")), dir.toString());
    }

    // --- checkConfigDirectory ---

    @Test
    void checkConfigDirectoryReportsExistingWritableDir() throws Exception {
        Path dir = tempDir.resolve("settings");
        Files.createDirectories(dir);

        Map<String, Object> result = new HashMap<>();
        ConfigDiagnosticTool.checkConfigDirectory(result, dir);

        assertEquals(true, result.get("configDirExists"));
        assertEquals(true, result.get("configDirReadable"));
        assertEquals(true, result.get("configDirWritable"));
        assertEquals(dir.toString(), result.get("configDirPath"));
    }

    @Test
    void checkConfigDirectoryCreatesMissingDir() {
        Path dir = tempDir.resolve("missing-settings");
        assertFalse(Files.exists(dir));

        Map<String, Object> result = new HashMap<>();
        ConfigDiagnosticTool.checkConfigDirectory(result, dir);

        assertEquals(false, result.get("configDirExists"));
        assertEquals(true, result.get("configDirCreated"));
        assertTrue(Files.exists(dir), "诊断应创建缺失的配置目录");
    }

    // --- checkConfigFiles ---

    @Test
    void checkConfigFilesReportsExistingFileMetadata() throws Exception {
        Path dir = tempDir.resolve("settings");
        Files.createDirectories(dir);
        Path pluginSettings = dir.resolve("PluginSettings.json");
        Files.writeString(pluginSettings, "{\"enabled\":true}", StandardCharsets.UTF_8);

        Map<String, Object> result = new HashMap<>();
        ConfigDiagnosticTool.checkConfigFiles(result, dir);

        assertEquals(true, result.get("PluginSettingsExists"));
        assertEquals(true, result.get("PluginSettingsReadable"));
        assertEquals(true, result.get("PluginSettingsWritable"));
        assertTrue((Long) result.get("PluginSettingsSize") > 0);

        // 未创建的配置文件应报告为不存在
        assertEquals(false, result.get("ReaderSettingsExists"));
    }

    @Test
    void checkConfigFilesHandlesMissingDirGracefully() {
        Path dir = tempDir.resolve("no-settings");
        Map<String, Object> result = new HashMap<>();

        ConfigDiagnosticTool.checkConfigFiles(result, dir);

        assertEquals(false, result.get("PluginSettingsExists"));
        assertEquals(false, result.get("CacheSettingsExists"));
        assertEquals(0L, result.get("CacheSettingsSize"));
    }

    // --- resetConfigs ---

    @Test
    void resetConfigsDeletesAllKnownConfigFiles() throws Exception {
        Path dir = tempDir.resolve("settings");
        Files.createDirectories(dir);
        for (String name : new String[]{"PluginSettings.json", "ReaderSettings.json", "CacheSettings.json",
                "NotificationReaderSettings.json", "ReaderModeSettings.json"}) {
            Files.writeString(dir.resolve(name), "{}", StandardCharsets.UTF_8);
        }

        ConfigDiagnosticTool.resetConfigs(dir);

        try (var stream = Files.list(dir)) {
            assertEquals(0, stream.count(), "所有已知配置文件应被删除");
        }
    }

    @Test
    void resetConfigsIsNoOpWhenFilesAbsent() {
        Path dir = tempDir.resolve("empty-settings");
        // 目录不存在也不应抛异常
        ConfigDiagnosticTool.resetConfigs(dir);
    }
}