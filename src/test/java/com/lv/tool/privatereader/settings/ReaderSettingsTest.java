package com.lv.tool.privatereader.settings;

import com.lv.tool.privatereader.storage.SettingsStorage;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReaderSettings 单元测试
 * 覆盖：默认值、主题预设管理、主题切换、字体设置、setter 修改
 *
 * <p>ReaderSettings 无事件通知，仅依赖 SettingsStorage.getInstance()，
 * 用非 null 存储 mock 避开 IntelliJ Logger.error 抛 AssertionError 的错误路径。</p>
 */
class ReaderSettingsTest {

    private static MockedStatic<SettingsStorage> mockStorage() {
        MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class);
        storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));
        return storage;
    }

    @Test
    void defaultsWhenNoConfigFile() {
        try (MockedStatic<SettingsStorage> storage = mockStorage()) {
            ReaderSettings settings = new ReaderSettings();
            assertFalse(settings.isBold());
            assertNotNull(settings.getThemePresets());
            assertFalse(settings.isDarkTheme());
            assertTrue(settings.isUseAnimation());
            assertEquals(300, settings.getAnimationDuration());
            assertTrue(settings.isAutoScroll());
            assertEquals(50, settings.getScrollSpeed());
            assertEquals(1, settings.getLineSpacing());
            assertEquals(2, settings.getParagraphSpacing());
            assertEquals(20, settings.getMarginLeft());
            assertEquals(20, settings.getMarginRight());
            assertEquals(20, settings.getMarginTop());
            assertEquals(20, settings.getMarginBottom());
        }
    }

    // --- 字体设置 ---

    @Test
    void fontFamilyAndSizeDefaultsAreReasonable() {
        try (MockedStatic<SettingsStorage> storage = mockStorage()) {
            ReaderSettings settings = new ReaderSettings();
            // 无显示环境时 UIManager.getFont 返回 null，降级为默认值
            assertNotNull(settings.getFontFamily());
            assertTrue(settings.getFontSize() > 0);
        }
    }

    @Test
    void fontSettersMutateValues() {
        try (MockedStatic<SettingsStorage> storage = mockStorage()) {
            ReaderSettings settings = new ReaderSettings();
            settings.getFontSize(); // 先触发加载
            settings.setFontFamily("Arial");
            settings.setFontSize(20);
            settings.setBold(true);

            assertEquals("Arial", settings.getFontFamily());
            assertEquals(20, settings.getFontSize());
            assertTrue(settings.isBold());
        }
    }

    // --- 主题预设 ---

    @Test
    void currentPresetDefaultsToDefaultPreset() {
        try (MockedStatic<SettingsStorage> storage = mockStorage()) {
            ReaderSettings settings = new ReaderSettings();
            ThemePreset preset = settings.getCurrentPreset();
            assertEquals("Default", preset.getName());
            assertNotNull(preset.getLightTheme());
            assertNotNull(preset.getDarkTheme());
        }
    }

    @Test
    void addAndSelectCustomPreset() {
        try (MockedStatic<SettingsStorage> storage = mockStorage()) {
            ReaderSettings settings = new ReaderSettings();
            // 先触发一次加载（无存储→默认值），否则首次 getCurrentPreset 会把预设重置为默认
            settings.getFontSize();

            ThemePreset ocean = new ThemePreset("Ocean", Theme.lightTheme(), Theme.darkTheme());
            settings.addThemePreset(ocean);
            settings.setCurrentPresetName("Ocean");

            assertSame(ocean, settings.getCurrentPreset());
        }
    }

    @Test
    void unknownPresetNameFallsBackToDefault() {
        try (MockedStatic<SettingsStorage> storage = mockStorage()) {
            ReaderSettings settings = new ReaderSettings();
            settings.getFontSize(); // 触发加载

            settings.setCurrentPresetName("NotExist");
            assertEquals("Default", settings.getCurrentPreset().getName());
        }
    }

    @Test
    void toggleThemeFlipsDarkFlag() {
        try (MockedStatic<SettingsStorage> storage = mockStorage()) {
            ReaderSettings settings = new ReaderSettings();
            assertFalse(settings.isDarkTheme());
            settings.toggleTheme();
            assertTrue(settings.isDarkTheme());
            settings.toggleTheme();
            assertFalse(settings.isDarkTheme());
        }
    }

    @Test
    void currentThemeFollowsDarkFlag() {
        try (MockedStatic<SettingsStorage> storage = mockStorage()) {
            ReaderSettings settings = new ReaderSettings();
            Theme light = settings.getCurrentTheme();
            assertFalse(light.isDark());
            assertEquals(Color.WHITE, light.getBackgroundColor());

            settings.toggleTheme();
            Theme dark = settings.getCurrentTheme();
            assertTrue(dark.isDark());
        }
    }

    // --- 布局与动画 setter ---

    @Test
    void layoutAndAnimationSettersMutateValues() {
        try (MockedStatic<SettingsStorage> storage = mockStorage()) {
            ReaderSettings settings = new ReaderSettings();
            settings.isAutoScroll(); // 先触发加载
            settings.setAutoScroll(false);
            settings.setScrollSpeed(30);
            settings.setShowLineNumbers(false);
            settings.setShowPageNumbers(false);
            settings.setLineSpacing(2);
            settings.setParagraphSpacing(4);
            settings.setMarginLeft(30);
            settings.setMarginRight(30);
            settings.setMarginTop(30);
            settings.setMarginBottom(30);
            settings.setUseAnimation(false);
            settings.setAnimationDuration(600);

            assertFalse(settings.isAutoScroll());
            assertEquals(30, settings.getScrollSpeed());
            assertFalse(settings.isShowLineNumbers());
            assertFalse(settings.isShowPageNumbers());
            assertEquals(2, settings.getLineSpacing());
            assertEquals(4, settings.getParagraphSpacing());
            assertEquals(30, settings.getMarginLeft());
            assertEquals(30, settings.getMarginRight());
            assertEquals(30, settings.getMarginTop());
            assertEquals(30, settings.getMarginBottom());
            assertFalse(settings.isUseAnimation());
            assertEquals(600, settings.getAnimationDuration());
        }
    }

    // --- 存储路径 ---

    @Test
    void loadsFromStorageOverridesDefaults() {
        ReaderSettings persisted = new ReaderSettings();
        persisted.setFontSize(24);
        persisted.toggleTheme(); // 置为暗色

        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            SettingsStorage storageMock = mock(SettingsStorage.class);
            storage.when(SettingsStorage::getInstance).thenReturn(storageMock);
            when(storageMock.loadSettings(ReaderSettings.class)).thenReturn(persisted);

            ReaderSettings settings = new ReaderSettings();
            assertEquals(24, settings.getFontSize());
            assertTrue(settings.isDarkTheme());
        }
    }

    @Test
    void saveWritesWhenDirty() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            SettingsStorage storageMock = mock(SettingsStorage.class);
            storage.when(SettingsStorage::getInstance).thenReturn(storageMock);

            ReaderSettings settings = new ReaderSettings();
            settings.getFontSize(); // 触发加载
            settings.setBold(true);  // 标记 dirty
            settings.saveSettings();
            verify(storageMock).saveSettings(settings);
        }
    }
}
