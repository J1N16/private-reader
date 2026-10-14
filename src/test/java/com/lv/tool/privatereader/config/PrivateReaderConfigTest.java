package com.lv.tool.privatereader.config;

import com.lv.tool.privatereader.config.PrivateReaderConfig.ReaderMode;
import com.lv.tool.privatereader.settings.CacheSettings;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import com.lv.tool.privatereader.settings.PluginSettings;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import com.lv.tool.privatereader.settings.ReaderSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PrivateReaderConfig 聚合视图测试(V23)。
 *
 * <p>{@code PrivateReaderConfig} 此前 0 覆盖。通过公开的构造器注入 mock 的各类设置,
 * 覆盖每个 getter 的「有值」与「依赖为 null 时的默认值」两条分支。
 * 不依赖 IntelliJ 容器的默认构造器。</p>
 */
class PrivateReaderConfigTest {

    // --- 全部依赖存在 ---

    @Test
    void aggregatesValuesFromAllSettings() {
        PluginSettings plugin = mock(PluginSettings.class);
        when(plugin.isEnabled()).thenReturn(true);
        ReaderSettings reader = mock(ReaderSettings.class);
        when(reader.getFontFamily()).thenReturn("Monospaced");
        when(reader.getFontSize()).thenReturn(20);
        CacheSettings cache = mock(CacheSettings.class);
        when(cache.isEnableCache()).thenReturn(true);
        when(cache.getMaxCacheSize()).thenReturn(256);
        when(cache.getMaxCacheAge()).thenReturn(14);
        when(cache.getPreloadCount()).thenReturn(5);
        NotificationReaderSettings notification = mock(NotificationReaderSettings.class);
        when(notification.getPageSize()).thenReturn(80);
        when(notification.isShowPageNumber()).thenReturn(true);
        ReaderModeSettings mode = mock(ReaderModeSettings.class);
        when(mode.isNotificationMode()).thenReturn(true);

        PrivateReaderConfig config = new PrivateReaderConfig(plugin, reader, cache, notification, mode);

        assertTrue(config.isPluginEnabled());
        assertEquals("Monospaced", config.getReaderFont());
        assertEquals(20, config.getReaderFontSize());
        assertEquals(1.5f, config.getReaderLineSpacing());
        assertTrue(config.isCacheEnabled());
        assertEquals(256, config.getCacheSizeLimit());
        assertEquals(14, config.getCacheExpirationDays());
        assertEquals(5, config.getPreloadChapterCount());
        assertEquals(80, config.getNotificationPageSize());
        assertTrue(config.isShowPageNumber());
        assertEquals(ReaderMode.NOTIFICATION, config.getReaderMode());
    }

    @Test
    void panelModeWhenNotificationModeDisabled() {
        ReaderModeSettings mode = mock(ReaderModeSettings.class);
        when(mode.isNotificationMode()).thenReturn(false);

        PrivateReaderConfig config = new PrivateReaderConfig(null, null, null, null, mode);

        assertEquals(ReaderMode.PANEL, config.getReaderMode());
    }

    // --- 各依赖为 null 时的默认值 ---

    @Test
    void defaultsWhenAllDependenciesNull() {
        PrivateReaderConfig config = new PrivateReaderConfig(null, null, null, null, null);

        assertFalse(config.isPluginEnabled());
        assertEquals("Serif", config.getReaderFont());
        assertEquals(16, config.getReaderFontSize());
        assertEquals(1.5f, config.getReaderLineSpacing());
        assertFalse(config.isCacheEnabled());
        assertEquals(100, config.getCacheSizeLimit());
        assertEquals(7, config.getCacheExpirationDays());
        assertEquals(3, config.getPreloadChapterCount());
        assertEquals(70, config.getNotificationPageSize());
        assertFalse(config.isShowPageNumber());
        assertEquals(ReaderMode.PANEL, config.getReaderMode());
    }
}