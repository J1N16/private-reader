package com.lv.tool.privatereader.settings;

import com.lv.tool.privatereader.storage.SettingsStorage;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PluginSettings 单元测试
 * 覆盖：默认值、存储加载、setter 修改、保存路径
 */
class PluginSettingsTest {

    @Test
    void defaultsWhenNoConfigFile() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));

            PluginSettings settings = new PluginSettings();
            assertTrue(settings.isEnabled());
            assertTrue(settings.isAutoUpdate());
            assertTrue(settings.isShowNotifications());
            assertEquals("zh_CN", settings.getLanguage());
            assertFalse(settings.isDebugMode());
        }
    }

    @Test
    void settersMutateValues() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));

            PluginSettings settings = new PluginSettings();
            settings.isEnabled(); // 先触发一次加载，避免默认值覆盖 setter 结果

            settings.setEnabled(false);
            settings.setAutoUpdate(false);
            settings.setShowNotifications(false);
            settings.setLanguage("en_US");
            settings.setDebugMode(true);

            assertFalse(settings.isEnabled());
            assertFalse(settings.isAutoUpdate());
            assertFalse(settings.isShowNotifications());
            assertEquals("en_US", settings.getLanguage());
            assertTrue(settings.isDebugMode());
        }
    }

    @Test
    void loadsFromStorageOverridesDefaults() {
        PluginSettings persisted = new PluginSettings();
        persisted.setEnabled(false);
        persisted.setLanguage("ja_JP");

        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            SettingsStorage storageMock = mock(SettingsStorage.class);
            storage.when(SettingsStorage::getInstance).thenReturn(storageMock);
            when(storageMock.loadSettings(PluginSettings.class)).thenReturn(persisted);

            PluginSettings settings = new PluginSettings();
            assertFalse(settings.isEnabled());
            assertEquals("ja_JP", settings.getLanguage());
            assertTrue(settings.isAutoUpdate());
        }
    }

    @Test
    void saveWritesWhenDirty() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            SettingsStorage storageMock = mock(SettingsStorage.class);
            storage.when(SettingsStorage::getInstance).thenReturn(storageMock);

            PluginSettings settings = new PluginSettings();
            settings.isEnabled(); // 触发加载
            settings.setEnabled(false); // 标记 dirty
            settings.saveSettings();
            verify(storageMock).saveSettings(settings);
        }
    }
}
