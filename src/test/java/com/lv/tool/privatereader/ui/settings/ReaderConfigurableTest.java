package com.lv.tool.privatereader.ui.settings;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.messages.MessageBus;
import com.lv.tool.privatereader.settings.ReaderSettings;
import com.lv.tool.privatereader.settings.ReaderSettingsListener;
import com.lv.tool.privatereader.settings.Theme;
import com.lv.tool.privatereader.settings.ThemePreset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import java.awt.Color;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReaderConfigurable 设置面板测试(V29)。
 *
 * <p>此前 0/132 覆盖。用 {@code @Mock ReaderSettings} 提供确定性的主题/字体状态,
 * 面板组件在 headless 环境下实例化(无窗口弹出:颜色按钮的 {@code ColorPicker} 仅在
 * 点击时才弹窗,测试不触发)。</p>
 */
class ReaderConfigurableTest {

    private MockedStatic<ApplicationManager> appMgr;
    private ReaderSettings settings;
    private ReaderSettingsListener listener;

    @BeforeEach
    void setUp() {
        Application app = mock(Application.class);
        MessageBus bus = mock(MessageBus.class);
        listener = mock(ReaderSettingsListener.class);
        lenient().when(app.getMessageBus()).thenReturn(bus);
        lenient().when(bus.syncPublisher(ReaderSettingsListener.TOPIC)).thenReturn(listener);

        settings = mock(ReaderSettings.class);
        lenient().doAnswer(inv -> null).when(settings).saveSettings();

        appMgr = mockStatic(ApplicationManager.class);
        appMgr.when(ApplicationManager::getApplication).thenReturn(app);
        appMgr.when(() -> ApplicationManager.getApplication().getService(ReaderSettings.class))
                .thenReturn(settings);
    }

    @AfterEach
    void tearDown() {
        appMgr.close();
    }

    private static Object field(Object target, String name) {
        try {
            Field f = ReaderConfigurable.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Theme theme(String presetName, Color text, Color bg, Color accent) {
        return new Theme.Builder()
                .setName(presetName)
                .setTextColor(text)
                .setBackgroundColor(bg)
                .setAccentColor(accent)
                .build();
    }

    private void stubDefaultPreset() {
        Theme t = theme("Default Theme", Color.BLACK, Color.WHITE, new Color(0, 120, 215));
        ThemePreset preset = new ThemePreset("Default", t, t);
        lenient().when(settings.getFontFamily()).thenReturn("Consolas");
        lenient().when(settings.getFontSize()).thenReturn(16);
        lenient().when(settings.isBold()).thenReturn(false);
        lenient().when(settings.isDarkTheme()).thenReturn(false);
        lenient().when(settings.isUseAnimation()).thenReturn(true);
        lenient().when(settings.getCurrentTheme()).thenReturn(t);
        lenient().when(settings.getCurrentPreset()).thenReturn(preset);
    }

    @Test
    void displayNameIsReaderSettings() {
        assertEquals("Reader Settings", new ReaderConfigurable().getDisplayName());
    }

    @Test
    void createComponentBuildsPanelAndSeedsWidgets() {
        stubDefaultPreset();
        ReaderConfigurable configurable = new ReaderConfigurable();

        JPanel panel = (JPanel) configurable.createComponent();

        assertNotNull(panel);
        assertEquals("Consolas", ((javax.swing.JComboBox<?>) field(configurable, "fontFamilyComboBox"))
                .getSelectedItem());
        assertEquals(16, ((javax.swing.JComboBox<?>) field(configurable, "fontSizeComboBox"))
                .getSelectedItem());
        assertTrue(((JCheckBox) field(configurable, "animationCheckBox")).isSelected());
        // 默认预设下颜色按钮应禁用
        assertFalse(((JButton) field(configurable, "textColorButton")).isEnabled());
    }

    @Test
    void isModifiedIsFalseWhenWidgetsMatchSettings() {
        stubDefaultPreset();
        ReaderConfigurable configurable = new ReaderConfigurable();
        configurable.createComponent();

        assertFalse(configurable.isModified());
    }

    @Test
    void isModifiedIsTrueAfterFontSizeChanges() {
        stubDefaultPreset();
        ReaderConfigurable configurable = new ReaderConfigurable();
        configurable.createComponent();

        @SuppressWarnings("unchecked")
        javax.swing.JComboBox<Integer> sizeBox =
                (javax.swing.JComboBox<Integer>) field(configurable, "fontSizeComboBox");
        sizeBox.setSelectedItem(24);

        assertTrue(configurable.isModified());
    }

    @Test
    void isModifiedIsTrueAfterBoldToggle() {
        stubDefaultPreset();
        ReaderConfigurable configurable = new ReaderConfigurable();
        configurable.createComponent();

        ((JCheckBox) field(configurable, "boldCheckBox")).setSelected(true);

        assertTrue(configurable.isModified());
    }

    @Test
    void applyWithDefaultPresetPersistsFontsAndPublishes() {
        stubDefaultPreset();
        lenient().when(settings.isDarkTheme()).thenReturn(false);
        ReaderConfigurable configurable = new ReaderConfigurable();
        configurable.createComponent();

        @SuppressWarnings("unchecked")
        javax.swing.JComboBox<Integer> sizeBox =
                (javax.swing.JComboBox<Integer>) field(configurable, "fontSizeComboBox");
        sizeBox.setSelectedItem(22);
        ((JCheckBox) field(configurable, "boldCheckBox")).setSelected(true);
        ((JCheckBox) field(configurable, "animationCheckBox")).setSelected(false);

        configurable.apply();

        verify(settings).setFontFamily("Consolas");
        verify(settings).setFontSize(22);
        verify(settings).setBold(true);
        verify(settings).setUseAnimation(false);
        verify(settings).setCurrentPresetName("Default");
        verify(settings).saveSettings();
        verify(listener, atLeastOnce()).settingsChanged();
    }

    @Test
    void applyWithCustomPresetCreatesCustomTheme() {
        Theme current = theme("Custom Theme", Color.BLACK, Color.WHITE, new Color(0, 120, 215));
        ThemePreset custom = new ThemePreset("Custom", current, current);
        lenient().when(settings.getFontFamily()).thenReturn("Consolas");
        lenient().when(settings.getFontSize()).thenReturn(16);
        lenient().when(settings.isBold()).thenReturn(false);
        lenient().when(settings.isDarkTheme()).thenReturn(false);
        lenient().when(settings.isUseAnimation()).thenReturn(true);
        lenient().when(settings.getCurrentTheme()).thenReturn(current);
        lenient().when(settings.getCurrentPreset()).thenReturn(custom);
        ReaderConfigurable configurable = new ReaderConfigurable();
        configurable.createComponent();

        @SuppressWarnings("unchecked")
        javax.swing.JComboBox<String> presetBox =
                (javax.swing.JComboBox<String>) field(configurable, "themePresetComboBox");
        presetBox.setSelectedItem("Custom");

        configurable.apply();

        // 进入自定义主题分支:应设置预设名为 Custom 并写入预设集合
        verify(settings).setCurrentPresetName("Custom");
        verify(settings).setThemePresets(any());
        verify(settings).saveSettings();
    }

    @Test
    void applyTogglesThemeWhenDarkModeDiffersFromSettings() {
        stubDefaultPreset();
        // settings 认为非深色,但用户勾选深色 → 应触发 toggleTheme
        lenient().when(settings.isDarkTheme()).thenReturn(false);
        ReaderConfigurable configurable = new ReaderConfigurable();
        configurable.createComponent();

        ((JCheckBox) field(configurable, "darkModeCheckBox")).setSelected(true);

        configurable.apply();

        verify(settings).toggleTheme();
        verify(settings).saveSettings();
    }

    @Test
    void applyDoesNotToggleThemeWhenDarkModeUnchanged() {
        stubDefaultPreset();
        lenient().when(settings.isDarkTheme()).thenReturn(false);
        ReaderConfigurable configurable = new ReaderConfigurable();
        configurable.createComponent();

        configurable.apply();

        verify(settings, never()).toggleTheme();
    }

    @Test
    void resetReloadsSettingsIntoWidgets() {
        stubDefaultPreset();
        ReaderConfigurable configurable = new ReaderConfigurable();
        configurable.createComponent();

        @SuppressWarnings("unchecked")
        javax.swing.JComboBox<Integer> sizeBox =
                (javax.swing.JComboBox<Integer>) field(configurable, "fontSizeComboBox");
        sizeBox.setSelectedItem(30);
        assertTrue(configurable.isModified());

        configurable.reset();

        assertEquals(16, sizeBox.getSelectedItem());
        assertFalse(configurable.isModified());
    }

    @Test
    void themePresetSwitchToCustomEnablesColorControls() {
        stubDefaultPreset();
        ReaderConfigurable configurable = new ReaderConfigurable();
        configurable.createComponent();

        @SuppressWarnings("unchecked")
        javax.swing.JComboBox<String> presetBox =
                (javax.swing.JComboBox<String>) field(configurable, "themePresetComboBox");
        presetBox.setSelectedItem("Custom");

        assertTrue(((JButton) field(configurable, "textColorButton")).isEnabled());
        assertTrue(((JButton) field(configurable, "bgColorButton")).isEnabled());
        assertTrue(((JButton) field(configurable, "accentColorButton")).isEnabled());
    }
}