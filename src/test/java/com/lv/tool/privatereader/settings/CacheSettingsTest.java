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
 * CacheSettings 单元测试
 * 覆盖：默认值、存储加载路径、setter 修改与脏标记、保存路径
 *
 * <p>CacheSettings 继承 BaseSettings，加载/保存均依赖 SettingsStorage.getInstance()。
 * 为避免 IntelliJ Logger.error 在此环境抛 AssertionError（错误路径），
 * 一律返回非 null 的存储 mock：loadSettings 未打桩时返回 null → 走默认值（debug 路径）。</p>
 */
class CacheSettingsTest {

    private MockedStatic<SettingsStorage> mockStorage() {
        MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class);
        storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));
        return storage;
    }

    // --- 默认值（存储无配置文件） ---

    @Test
    void defaultsWhenNoConfigFile() {
        try (MockedStatic<SettingsStorage> ignored = mockStorage()) {
            CacheSettings settings = new CacheSettings();
            assertEquals(24 * 7, settings.getCacheExpiryHours());
            assertEquals(100, settings.getMaxCacheSizeMB());
            assertTrue(settings.isEnableCache());
            assertTrue(settings.isCleanupOnStartup());
            assertTrue(settings.isPreloadNextChapter());
            assertEquals(1, settings.getPreloadCount());
            assertEquals(100, settings.getMaxCacheSize());
            assertEquals(7, settings.getMaxCacheAge());
            assertTrue(settings.isEnablePreload());
            assertEquals(500, settings.getPreloadDelay());
        }
    }

    // --- setter 修改 ---

    @Test
    void settersMutateValuesAndMarkDirty() {
        try (MockedStatic<SettingsStorage> ignored = mockStorage()) {
            CacheSettings settings = new CacheSettings();
            settings.getCacheExpiryHours(); // 先触发一次加载，避免默认值覆盖 setter 结果

            settings.setCacheExpiryHours(48);
            settings.setMaxCacheSizeMB(200);
            settings.setEnableCache(false);
            settings.setCleanupOnStartup(false);
            settings.setPreloadNextChapter(false);
            settings.setPreloadCount(3);
            settings.setMaxCacheSize(150);
            settings.setMaxCacheAge(14);
            settings.setEnablePreload(false);
            settings.setPreloadDelay(1000);

            assertEquals(48, settings.getCacheExpiryHours());
            assertEquals(200, settings.getMaxCacheSizeMB());
            assertFalse(settings.isEnableCache());
            assertFalse(settings.isCleanupOnStartup());
            assertFalse(settings.isPreloadNextChapter());
            assertEquals(3, settings.getPreloadCount());
            assertEquals(150, settings.getMaxCacheSize());
            assertEquals(14, settings.getMaxCacheAge());
            assertFalse(settings.isEnablePreload());
            assertEquals(1000, settings.getPreloadDelay());
        }
    }

    // --- 从存储加载 ---

    @Test
    void loadsFromStorageOverridesDefaults() {
        CacheSettings persisted = new CacheSettings();
        persisted.setCacheExpiryHours(12);
        persisted.setPreloadCount(5);

        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            SettingsStorage storageMock = mock(SettingsStorage.class);
            storage.when(SettingsStorage::getInstance).thenReturn(storageMock);
            when(storageMock.loadSettings(CacheSettings.class)).thenReturn(persisted);

            CacheSettings settings = new CacheSettings();
            assertEquals(12, settings.getCacheExpiryHours());
            assertEquals(5, settings.getPreloadCount());
            // 未持久化的字段保持默认
            assertEquals(100, settings.getMaxCacheSizeMB());
        }
    }

    // --- 保存路径 ---

    @Test
    void saveWritesWhenDirty() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            SettingsStorage storageMock = mock(SettingsStorage.class);
            storage.when(SettingsStorage::getInstance).thenReturn(storageMock);

            CacheSettings settings = new CacheSettings();
            settings.getCacheExpiryHours(); // 触发加载（loaded=true, dirty=false）
            settings.setEnableCache(false);  // 标记 dirty
            settings.saveSettings();
            verify(storageMock).saveSettings(settings);
        }
    }
}
