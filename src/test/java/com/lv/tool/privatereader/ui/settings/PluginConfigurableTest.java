package com.lv.tool.privatereader.ui.settings;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.messages.MessageBus;
import com.lv.tool.privatereader.settings.PluginSettings;
import com.lv.tool.privatereader.settings.PluginSettingsListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.swing.JPanel;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PluginConfigurable 设置面板测试(V31)。
 *
 * <p>此前 0/34 覆盖。沿 V29/V30 的 headless + mockStatic 模式:面板为纯 Swing 组件,
 * settings 由 {@code ApplicationManager} 注入(以 mock 提供),发布事件经 mock {@code MessageBus}。</p>
 */
class PluginConfigurableTest {

    private MockedStatic<ApplicationManager> appMgr;
    private PluginSettings settings;
    private PluginSettingsListener listener;

    @BeforeEach
    void setUp() {
        Application app = mock(Application.class);
        MessageBus bus = mock(MessageBus.class);
        listener = mock(PluginSettingsListener.class);
        lenient().when(app.getMessageBus()).thenReturn(bus);
        lenient().when(bus.syncPublisher(PluginSettingsListener.TOPIC)).thenReturn(listener);

        settings = mock(PluginSettings.class);
        lenient().doAnswer(inv -> null).when(settings).saveSettings();

        appMgr = mockStatic(ApplicationManager.class);
        appMgr.when(ApplicationManager::getApplication).thenReturn(app);
        appMgr.when(() -> ApplicationManager.getApplication().getService(PluginSettings.class))
                .thenReturn(settings);
    }

    @AfterEach
    void tearDown() {
        appMgr.close();
    }

    private static Object field(Object target, String name) {
        try {
            Field f = PluginConfigurable.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void displayNameIsPluginSettings() {
        assertEquals("Private Reader Plugin Settings", new PluginConfigurable().getDisplayName());
    }

    @Test
    void createComponentBuildsPanelSeededFromSettings() {
        when(settings.isEnabled()).thenReturn(true);
        PluginConfigurable configurable = new PluginConfigurable();

        JPanel panel = (JPanel) configurable.createComponent();

        assertNotNull(panel);
        assertTrue(((javax.swing.JCheckBox) field(configurable, "enabledCheckBox")).isSelected());
    }

    @Test
    void isModifiedFalseWhenWidgetMatchesSettings() {
        when(settings.isEnabled()).thenReturn(true);
        PluginConfigurable configurable = new PluginConfigurable();
        configurable.createComponent();

        assertFalse(configurable.isModified());
    }

    @Test
    void isModifiedTrueAfterToggle() {
        when(settings.isEnabled()).thenReturn(true);
        PluginConfigurable configurable = new PluginConfigurable();
        configurable.createComponent();

        ((javax.swing.JCheckBox) field(configurable, "enabledCheckBox")).setSelected(false);

        assertTrue(configurable.isModified());
    }

    @Test
    void applyPersistsAndPublishesWhenStateChanges() {
        when(settings.isEnabled()).thenReturn(true);
        PluginConfigurable configurable = new PluginConfigurable();
        configurable.createComponent();
        ((javax.swing.JCheckBox) field(configurable, "enabledCheckBox")).setSelected(false);

        configurable.apply();

        verify(settings).setEnabled(false);
        verify(settings).saveSettings();
        verify(listener, atLeastOnce()).settingsChanged();
    }

    @Test
    void applyWithoutChangeSkipsPublishing() {
        when(settings.isEnabled()).thenReturn(true);
        PluginConfigurable configurable = new PluginConfigurable();
        configurable.createComponent();

        configurable.apply();

        verify(settings).saveSettings();
        verify(listener, never()).settingsChanged();
    }

    @Test
    void resetRestoresCheckboxFromSettings() {
        when(settings.isEnabled()).thenReturn(true);
        PluginConfigurable configurable = new PluginConfigurable();
        configurable.createComponent();
        ((javax.swing.JCheckBox) field(configurable, "enabledCheckBox")).setSelected(false);

        configurable.reset();

        assertTrue(((javax.swing.JCheckBox) field(configurable, "enabledCheckBox")).isSelected());
    }
}