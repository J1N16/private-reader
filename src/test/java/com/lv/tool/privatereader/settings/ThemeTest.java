package com.lv.tool.privatereader.settings;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Theme / ThemePreset 单元测试
 * 覆盖：Builder 默认值与构建、预定义亮/暗主题、主题预设的成对管理与切换
 */
class ThemeTest {

    // --- Theme.Builder ---

    @Test
    void builderDefaults() {
        Theme theme = new Theme.Builder().build();
        assertEquals("Custom Theme", theme.getName());
        assertEquals(Color.WHITE, theme.getBackgroundColor());
        assertEquals(Color.BLACK, theme.getTextColor());
        assertEquals(new Color(0, 120, 215), theme.getAccentColor());
        assertFalse(theme.isDark());
    }

    @Test
    void builderCustomizesAllFields() {
        Theme theme = new Theme.Builder()
                .setName("Ocean")
                .setBackgroundColor(Color.BLUE)
                .setTextColor(Color.WHITE)
                .setAccentColor(Color.CYAN)
                .setDark(true)
                .build();
        assertEquals("Ocean", theme.getName());
        assertEquals(Color.BLUE, theme.getBackgroundColor());
        assertEquals(Color.WHITE, theme.getTextColor());
        assertEquals(Color.CYAN, theme.getAccentColor());
        assertTrue(theme.isDark());
    }

    // --- 预定义主题 ---

    @Test
    void lightThemeIsLightAndWhiteBackground() {
        Theme theme = Theme.lightTheme();
        assertEquals("Light Theme", theme.getName());
        assertFalse(theme.isDark());
        assertEquals(Color.WHITE, theme.getBackgroundColor());
        assertEquals(new Color(43, 43, 43), theme.getTextColor());
    }

    @Test
    void darkThemeIsDark() {
        Theme theme = Theme.darkTheme();
        assertEquals("Dark Theme", theme.getName());
        assertTrue(theme.isDark());
        assertEquals(new Color(30, 30, 30), theme.getBackgroundColor());
    }

    @Test
    void lightAndDarkThemesDiffer() {
        assertNotEquals(Theme.lightTheme().getBackgroundColor(), Theme.darkTheme().getBackgroundColor());
    }

    // --- ThemePreset ---

    @Test
    void presetExposesNameAndThemes() {
        Theme light = Theme.lightTheme();
        Theme dark = Theme.darkTheme();
        ThemePreset preset = new ThemePreset("Default", light, dark);

        assertEquals("Default", preset.getName());
        assertSame(light, preset.getLightTheme());
        assertSame(dark, preset.getDarkTheme());
    }

    @Test
    void presetGetThemeSelectsByDarkFlag() {
        ThemePreset preset = ThemePreset.defaultPreset();
        assertSame(preset.getLightTheme(), preset.getTheme(false));
        assertSame(preset.getDarkTheme(), preset.getTheme(true));
    }

    @Test
    void defaultPresetIsDefaultNamedWithBothThemes() {
        ThemePreset preset = ThemePreset.defaultPreset();
        assertEquals("Default", preset.getName());
        assertNotNull(preset.getLightTheme(), "默认预设应始终包含亮色主题");
        assertFalse(preset.getLightTheme().isDark());
        assertTrue(preset.getDarkTheme().isDark());
    }
}
