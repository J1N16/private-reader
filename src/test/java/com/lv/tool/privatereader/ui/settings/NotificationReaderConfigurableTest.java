package com.lv.tool.privatereader.ui.settings;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.messages.MessageBus;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import com.lv.tool.privatereader.settings.NotificationReaderSettingsListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationReaderConfigurable 设置面板测试(V29)。
 *
 * <p>此前该类 0/92 覆盖(settings 由 {@code ApplicationManager} 注入,组件依赖服务)。
 * V29 复用 V25 的发现:mocked setters 返回 {@code this},故 mock 用
 * {@code RETURNS_DEFAULTS} 并以字段读写模拟。面板为纯 Swing/IntelliJ Swing 组件,
 * 在 headless 环境下可实例化(已于 V29 预研确认)。</p>
 */
class NotificationReaderConfigurableTest {

    private MockedStatic<ApplicationManager> appMgr;
    private NotificationReaderSettings settings;
    private NotificationReaderSettingsListener listener;

    @BeforeEach
    void setUp() {
        Application app = mock(Application.class);
        MessageBus bus = mock(MessageBus.class);
        listener = mock(NotificationReaderSettingsListener.class);
        when(app.getMessageBus()).thenReturn(bus);
        when(bus.syncPublisher(NotificationReaderSettingsListener.TOPIC)).thenReturn(listener);

        // 内存实现:字段读写,避免真实 BaseSettings 加载
        settings = mock(NotificationReaderSettings.class);
        doAnswer(inv -> null).when(settings).saveSettings();

        appMgr = mockStatic(ApplicationManager.class);
        appMgr.when(ApplicationManager::getApplication).thenReturn(app);
        appMgr.when(() -> ApplicationManager.getApplication().getService(NotificationReaderSettings.class))
                .thenReturn(settings);
    }

    @AfterEach
    void tearDown() {
        appMgr.close();
    }

    private static Object field(Object target, String name) {
        try {
            Field f = NotificationReaderConfigurable.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private NotificationReaderConfigurable createWithSpinners(int pageSize, boolean... flags) {
        // 桩化初始值
        when(settings.getPageSize()).thenReturn(pageSize);
        when(settings.isShowPageNumber()).thenReturn(flags[0]);
        when(settings.isShowChapterTitle()).thenReturn(flags[1]);
        when(settings.isShowReadingProgress()).thenReturn(flags[2]);
        when(settings.isShowButtons()).thenReturn(flags[3]);
        when(settings.isEnabled()).thenReturn(flags[4]);

        NotificationReaderConfigurable configurable = new NotificationReaderConfigurable();
        configurable.createComponent();
        return configurable;
    }

    @Test
    void displayNameIsNotificationReaderSettings() {
        assertEquals("通知栏阅读设置", new NotificationReaderConfigurable().getDisplayName());
    }

    @Test
    void createComponentBuildsPanelAndSeedsWidgetsFromSettings() {
        NotificationReaderConfigurable configurable =
                createWithSpinners(120, true, false, true, false, true);

        JPanel panel = (JPanel) configurable.createComponent();
        assertNotNull(panel);

        JSpinner spinner = (JSpinner) field(configurable, "pageSizeSpinner");
        assertEquals(120, spinner.getValue());
        assertTrue(((JCheckBox) field(configurable, "showPageNumberCheckBox")).isSelected());
        assertFalse(((JCheckBox) field(configurable, "showChapterTitleCheckBox")).isSelected());
        assertTrue(((JCheckBox) field(configurable, "showReadingProgressCheckBox")).isSelected());
        assertFalse(((JCheckBox) field(configurable, "showButtonsCheckBox")).isSelected());
        assertTrue(((JCheckBox) field(configurable, "enabledCheckBox")).isSelected());
    }

    @Test
    void initiallyNotModified() {
        NotificationReaderConfigurable configurable =
                createWithSpinners(70, true, true, true, true, true);

        assertFalse(configurable.isModified());
    }

    @Test
    void pageSizeChangeMarksModifiedAndPublishesImmediately() {
        NotificationReaderConfigurable configurable =
                createWithSpinners(70, true, true, true, true, true);
        JSpinner spinner = (JSpinner) field(configurable, "pageSizeSpinner");

        spinner.setValue(90);

        assertTrue(configurable.isModified());
        verify(settings).setPageSize(90);
        verify(listener, atLeastOnce()).settingsChanged();
    }

    @Test
    void pageSizeChangeToSameValueDoesNotPublish() {
        NotificationReaderConfigurable configurable =
                createWithSpinners(70, true, true, true, true, true);
        JSpinner spinner = (JSpinner) field(configurable, "pageSizeSpinner");

        spinner.setValue(70);

        // 值未变化:不应调用 setPageSize
        verify(settings, org.mockito.Mockito.never()).setPageSize(any(Integer.class));
    }

    @Test
    void checkboxTogglePublishesAndMarksModified() {
        NotificationReaderConfigurable configurable =
                createWithSpinners(70, true, true, true, true, true);
        JCheckBox box = (JCheckBox) field(configurable, "showPageNumberCheckBox");

        box.setSelected(false);

        assertTrue(configurable.isModified());
        verify(settings).setShowPageNumber(false);
        verify(listener, atLeastOnce()).settingsChanged();
    }

    @Test
    void applyWritesAllWidgetsToSettingsAndClearsModified() {
        NotificationReaderConfigurable configurable =
                createWithSpinners(70, true, true, true, true, true);

        ((JSpinner) field(configurable, "pageSizeSpinner")).setValue(150);
        ((JCheckBox) field(configurable, "showPageNumberCheckBox")).setSelected(false);
        ((JCheckBox) field(configurable, "showChapterTitleCheckBox")).setSelected(false);
        ((JCheckBox) field(configurable, "showReadingProgressCheckBox")).setSelected(false);
        ((JCheckBox) field(configurable, "showButtonsCheckBox")).setSelected(false);
        ((JCheckBox) field(configurable, "enabledCheckBox")).setSelected(false);

        configurable.apply();

        // 即时预览监听器会先行写入,apply 再次写入,故用 atLeastOnce
        verify(settings, atLeastOnce()).setPageSize(150);
        verify(settings, atLeastOnce()).setShowPageNumber(false);
        verify(settings, atLeastOnce()).setShowChapterTitle(false);
        verify(settings, atLeastOnce()).setShowReadingProgress(false);
        verify(settings, atLeastOnce()).setShowButtons(false);
        verify(settings, atLeastOnce()).setEnabled(false);
        verify(settings).saveSettings();
        assertFalse(configurable.isModified());
    }

    @Test
    void resetRestoresWidgetsFromSettings() {
        NotificationReaderConfigurable configurable =
                createWithSpinners(70, true, true, true, true, true);
        ((JSpinner) field(configurable, "pageSizeSpinner")).setValue(200);
        assertTrue(configurable.isModified());

        // 重置时 settings 返回同一初值
        configurable.reset();

        assertEquals(70, ((JSpinner) field(configurable, "pageSizeSpinner")).getValue());
        assertTrue(((JCheckBox) field(configurable, "showPageNumberCheckBox")).isSelected());
        assertFalse(configurable.isModified());
    }
}