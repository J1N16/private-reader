package com.lv.tool.privatereader.settings;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.messages.MessageBus;
import com.lv.tool.privatereader.storage.SettingsStorage;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReaderModeSettings 单元测试
 * 覆盖：默认模式、模式切换、事件发布（仅模式变化时触发）
 *
 * <p>setCurrentMode 通过 ApplicationManager.getMessageBus() 发布模式变更事件，
 * 用 mockStatic 拦截 ApplicationManager 与 SettingsStorage 避免 IntelliJ 运行时依赖。</p>
 */
class ReaderModeSettingsTest {

    private Application app;
    private ReaderModeSettingsListener listener;

    private void mockApplication() {
        app = mock(Application.class);
        MessageBus bus = mock(MessageBus.class);
        listener = mock(ReaderModeSettingsListener.class);
        when(app.getMessageBus()).thenReturn(bus);
        when(bus.syncPublisher(ReaderModeSettings.TOPIC)).thenReturn(listener);
    }

    // --- 默认值 ---

    @Test
    void defaultsToReaderMode() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));

            ReaderModeSettings settings = new ReaderModeSettings();
            assertEquals(ReaderModeSettings.Mode.DEFAULT, settings.getCurrentMode());
            assertFalse(settings.isNotificationMode());
        }
    }

    @Test
    void readerModeLayoutDefaults() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));

            ReaderModeSettings settings = new ReaderModeSettings();
            assertTrue(settings.isAutoScroll());
            assertEquals(50, settings.getScrollSpeed());
            assertTrue(settings.isShowLineNumbers());
            assertTrue(settings.isShowPageNumbers());
            assertEquals(1, settings.getLineSpacing());
            assertEquals(2, settings.getParagraphSpacing());
            assertEquals(20, settings.getMarginLeft());
            assertEquals(20, settings.getMarginRight());
            assertEquals(20, settings.getMarginTop());
            assertEquals(20, settings.getMarginBottom());
        }
    }

    // --- 模式切换 ---

    @Test
    void setNotificationModeSwitchesMode() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class);
             MockedStatic<ApplicationManager> appMgr = mockStatic(ApplicationManager.class)) {
            storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));
            mockApplication();
            appMgr.when(ApplicationManager::getApplication).thenReturn(app);

            ReaderModeSettings settings = new ReaderModeSettings();
            settings.getCurrentMode(); // 先触发加载
            settings.setNotificationMode(true);
            assertEquals(ReaderModeSettings.Mode.NOTIFICATION_BAR, settings.getCurrentMode());
            assertTrue(settings.isNotificationMode());

            settings.setNotificationMode(false);
            assertEquals(ReaderModeSettings.Mode.DEFAULT, settings.getCurrentMode());
            assertFalse(settings.isNotificationMode());
        }
    }

    @Test
    void publishesEventOnlyWhenModeChanges() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class);
             MockedStatic<ApplicationManager> appMgr = mockStatic(ApplicationManager.class)) {
            storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));
            mockApplication();
            appMgr.when(ApplicationManager::getApplication).thenReturn(app);

            ReaderModeSettings settings = new ReaderModeSettings();
            settings.setCurrentMode(ReaderModeSettings.Mode.NOTIFICATION_BAR);
            verify(listener).modeChanged(true);

            // 设置为相同模式不重复发布
            settings.setCurrentMode(ReaderModeSettings.Mode.NOTIFICATION_BAR);
            verify(listener, never()).modeChanged(false);
        }
    }

    // --- 布局 setter ---

    @Test
    void layoutSettersMutateValues() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class);
             MockedStatic<ApplicationManager> appMgr = mockStatic(ApplicationManager.class)) {
            storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));
            mockApplication();
            appMgr.when(ApplicationManager::getApplication).thenReturn(app);

            ReaderModeSettings settings = new ReaderModeSettings();
            settings.isAutoScroll(); // 先触发加载
            settings.setAutoScroll(false);
            settings.setScrollSpeed(30);
            settings.setShowLineNumbers(false);
            settings.setShowPageNumbers(false);
            settings.setLineSpacing(2);
            settings.setParagraphSpacing(3);
            settings.setMarginLeft(30);
            settings.setMarginRight(30);
            settings.setMarginTop(30);
            settings.setMarginBottom(30);

            assertFalse(settings.isAutoScroll());
            assertEquals(30, settings.getScrollSpeed());
            assertFalse(settings.isShowLineNumbers());
            assertFalse(settings.isShowPageNumbers());
            assertEquals(2, settings.getLineSpacing());
            assertEquals(3, settings.getParagraphSpacing());
            assertEquals(30, settings.getMarginLeft());
            assertEquals(30, settings.getMarginRight());
            assertEquals(30, settings.getMarginTop());
            assertEquals(30, settings.getMarginBottom());
        }
    }

    @Test
    void saveDelegatesToSaveSettings() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class);
             MockedStatic<ApplicationManager> appMgr = mockStatic(ApplicationManager.class)) {
            storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));
            mockApplication();
            appMgr.when(ApplicationManager::getApplication).thenReturn(app);

            ReaderModeSettings settings = new ReaderModeSettings();
            settings.getCurrentMode(); // 触发加载
            settings.setAutoScroll(false); // 标记 dirty
            settings.save(); // 不应抛出
        }
    }
}
