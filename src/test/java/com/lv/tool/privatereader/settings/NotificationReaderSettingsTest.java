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
 * NotificationReaderSettings 单元测试
 * 覆盖：默认值、setter 修改、设置变更事件发布（仅值变化时）
 */
class NotificationReaderSettingsTest {

    private Application app;
    private NotificationReaderSettingsListener listener;

    private void mockApplication() {
        app = mock(Application.class);
        MessageBus bus = mock(MessageBus.class);
        listener = mock(NotificationReaderSettingsListener.class);
        when(app.getMessageBus()).thenReturn(bus);
        when(bus.syncPublisher(NotificationReaderSettingsListener.TOPIC)).thenReturn(listener);
    }

    @Test
    void defaultsWhenNoConfigFile() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));

            NotificationReaderSettings settings = new NotificationReaderSettings();
            assertTrue(settings.isMarkAsReadOnClose());
            assertEquals(70, settings.getPageSize());
            assertTrue(settings.isShowPageNumber());
            assertFalse(settings.isEnabled());
            assertTrue(settings.isShowChapterTitle());
            assertTrue(settings.isShowReadingProgress());
            assertTrue(settings.isShowButtons());
        }
    }

    @Test
    void settersMutateValuesAndPublishEvent() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class);
             MockedStatic<ApplicationManager> appMgr = mockStatic(ApplicationManager.class)) {
            storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));
            mockApplication();
            appMgr.when(ApplicationManager::getApplication).thenReturn(app);

            NotificationReaderSettings settings = new NotificationReaderSettings();
            settings.getPageSize(); // 触发加载

            settings.setMarkAsReadOnClose(false);
            settings.setPageSize(100);
            settings.setShowPageNumber(false);
            settings.setEnabled(true);
            settings.setShowChapterTitle(false);
            settings.setShowReadingProgress(false);
            settings.setShowButtons(false);

            assertFalse(settings.isMarkAsReadOnClose());
            assertEquals(100, settings.getPageSize());
            assertFalse(settings.isShowPageNumber());
            assertTrue(settings.isEnabled());
            assertFalse(settings.isShowChapterTitle());
            assertFalse(settings.isShowReadingProgress());
            assertFalse(settings.isShowButtons());
            verify(listener, org.mockito.Mockito.times(7)).settingsChanged();
        }
    }

    @Test
    void doesNotPublishEventWhenValueUnchanged() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class);
             MockedStatic<ApplicationManager> appMgr = mockStatic(ApplicationManager.class)) {
            storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));
            mockApplication();
            appMgr.when(ApplicationManager::getApplication).thenReturn(app);

            NotificationReaderSettings settings = new NotificationReaderSettings();
            settings.getPageSize(); // 触发加载

            settings.setEnabled(false); // 与默认值相同
            verify(listener, never()).settingsChanged();

            settings.setPageSize(70); // 与默认值相同
            verify(listener, never()).settingsChanged();
        }
    }

    @Test
    void showPageNumbersAliasDelegatesToShowPageNumber() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class);
             MockedStatic<ApplicationManager> appMgr = mockStatic(ApplicationManager.class)) {
            storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));
            mockApplication();
            appMgr.when(ApplicationManager::getApplication).thenReturn(app);

            NotificationReaderSettings settings = new NotificationReaderSettings();
            assertTrue(settings.isShowPageNumbers());
            settings.setShowPageNumber(false);
            assertFalse(settings.isShowPageNumbers());
        }
    }
}
