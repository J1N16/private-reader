package com.lv.tool.privatereader.ui.settings;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.util.messages.MessageBus;
import com.lv.tool.privatereader.settings.CacheSettings;
import com.lv.tool.privatereader.settings.CacheSettingsListener;
import com.lv.tool.privatereader.service.NotificationService;
import com.lv.tool.privatereader.storage.StorageManager;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CacheConfigurable 设置面板测试(V29)。
 *
 * <p>此前 0/245 覆盖。构造器从 {@code ApplicationManager} 取 {@code CacheSettings} 与
 * {@code NotificationService};{@code createComponent} 还会经
 * {@code ProjectManager.getOpenProjects()} + {@code Project.getService(StorageManager)} 查询路径。
 * 全部以 mockStatic 提供,面板组件在 headless 下实例化。</p>
 *
 * <p>不打开任何对话框、不点击链接,故不触发 {@code LOG.error} 分支。</p>
 */
class CacheConfigurableTest {

    private MockedStatic<ApplicationManager> appMgr;
    private CacheSettings settings;
    private NotificationService notificationService;
    private CacheSettingsListener listener;

    @BeforeEach
    void setUp() {
        Application app = mock(Application.class);
        MessageBus bus = mock(MessageBus.class);
        listener = mock(CacheSettingsListener.class);
        lenient().when(app.getMessageBus()).thenReturn(bus);
        lenient().when(bus.syncPublisher(CacheSettingsListener.TOPIC)).thenReturn(listener);

        settings = mock(CacheSettings.class);
        lenient().doAnswer(inv -> null).when(settings).saveSettings();
        notificationService = mock(NotificationService.class);

        appMgr = mockStatic(ApplicationManager.class);
        appMgr.when(ApplicationManager::getApplication).thenReturn(app);
        appMgr.when(() -> ApplicationManager.getApplication().getService(CacheSettings.class)).thenReturn(settings);
        appMgr.when(() -> ApplicationManager.getApplication().getService(NotificationService.class))
                .thenReturn(notificationService);

        // 默认:没有打开的项目 → CacheConfigurable 走 settings.getCacheDirectoryPath() 回退
        ProjectManager projectManager = mock(ProjectManager.class);
        lenient().when(projectManager.getOpenProjects()).thenReturn(new Project[0]);
        lenient().when(ProjectManager.getInstance()).thenReturn(projectManager);
    }

    @AfterEach
    void tearDown() {
        appMgr.close();
    }

    private static Object field(Object target, String name) {
        try {
            Field f = CacheConfigurable.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private CacheConfigurable configured() {
        lenient().when(settings.isEnableCache()).thenReturn(true);
        lenient().when(settings.getMaxCacheSize()).thenReturn(100);
        lenient().when(settings.getMaxCacheAge()).thenReturn(7);
        lenient().when(settings.isEnablePreload()).thenReturn(true);
        lenient().when(settings.getPreloadCount()).thenReturn(3);
        lenient().when(settings.getPreloadDelay()).thenReturn(500);
        lenient().when(settings.getCacheDirectoryPath()).thenReturn(System.getProperty("java.io.tmpdir"));
        CacheConfigurable configurable = new CacheConfigurable();
        configurable.createComponent();
        return configurable;
    }

    @Test
    void displayNameIsCacheSettings() {
        assertEquals("Private Reader 缓存设置", new CacheConfigurable().getDisplayName());
    }

    @Test
    void createComponentBuildsPanelAndSeedsWidgetsFromSettings() {
        CacheConfigurable configurable = configured();

        assertNotNull(field(configurable, "mainPanel"));
        assertTrue(((JCheckBox) field(configurable, "enableCacheCheckBox")).isSelected());
        assertEquals(100, ((JSpinner) field(configurable, "maxCacheSizeSpinner")).getValue());
        assertEquals(7, ((JSpinner) field(configurable, "maxCacheAgeSpinner")).getValue());
        assertTrue(((JCheckBox) field(configurable, "enablePreloadCheckBox")).isSelected());
        assertEquals(3, ((JSpinner) field(configurable, "preloadCountSpinner")).getValue());
        assertEquals(500, ((JSpinner) field(configurable, "preloadDelaySpinner")).getValue());
    }

    @Test
    void preloadWidgetsEnabledWhenPreloadOn() {
        CacheConfigurable configurable = configured();

        assertTrue(((JSpinner) field(configurable, "preloadCountSpinner")).isEnabled());
        assertTrue(((JSpinner) field(configurable, "preloadDelaySpinner")).isEnabled());
    }

    @Test
    void preloadWidgetsDisabledWhenPreloadOff() {
        lenient().when(settings.isEnableCache()).thenReturn(false);
        lenient().when(settings.isEnablePreload()).thenReturn(false);
        lenient().when(settings.getCacheDirectoryPath()).thenReturn(System.getProperty("java.io.tmpdir"));
        CacheConfigurable configurable = new CacheConfigurable();
        configurable.createComponent();

        assertFalse(((JSpinner) field(configurable, "preloadCountSpinner")).isEnabled());
        assertFalse(((JSpinner) field(configurable, "preloadDelaySpinner")).isEnabled());
    }

    @Test
    void isModifiedFalseWhenWidgetsMatchSettings() {
        assertFalse(configured().isModified());
    }

    @Test
    void isModifiedTrueAfterEnableCacheToggle() {
        CacheConfigurable configurable = configured();

        ((JCheckBox) field(configurable, "enableCacheCheckBox")).setSelected(false);

        assertTrue(configurable.isModified());
    }

    @Test
    void isModifiedTrueAfterSpinnerChange() {
        CacheConfigurable configurable = configured();

        ((JSpinner) field(configurable, "maxCacheSizeSpinner")).setValue(200);

        assertTrue(configurable.isModified());
    }

    @Test
    void applyPersistsAllSettingsAndPublishesOnChange() throws Exception {
        CacheConfigurable configurable = configured();

        ((JCheckBox) field(configurable, "enableCacheCheckBox")).setSelected(false);
        ((JSpinner) field(configurable, "maxCacheSizeSpinner")).setValue(250);
        ((JSpinner) field(configurable, "maxCacheAgeSpinner")).setValue(14);
        ((JCheckBox) field(configurable, "enablePreloadCheckBox")).setSelected(false);
        ((JSpinner) field(configurable, "preloadCountSpinner")).setValue(5);
        ((JSpinner) field(configurable, "preloadDelaySpinner")).setValue(1000);

        configurable.apply();

        verify(settings).setEnableCache(false);
        verify(settings).setMaxCacheSize(250);
        verify(settings).setMaxCacheAge(14);
        verify(settings).setEnablePreload(false);
        verify(settings).setPreloadCount(5);
        verify(settings).setPreloadDelay(1000);
        verify(settings).saveSettings();
        // 发生变化 → 发布事件
        verify(listener).cacheSettingsChanged(settings);
    }

    @Test
    void applyWithoutChangesSkipsEventPublishing() throws Exception {
        // settings 返回值的桩需与控件值一致 → 无变化
        lenient().when(settings.isEnableCache()).thenReturn(true);
        lenient().when(settings.getMaxCacheSize()).thenReturn(100);
        lenient().when(settings.getMaxCacheAge()).thenReturn(7);
        lenient().when(settings.isEnablePreload()).thenReturn(true);
        lenient().when(settings.getPreloadCount()).thenReturn(3);
        lenient().when(settings.getPreloadDelay()).thenReturn(500);
        lenient().when(settings.getCacheDirectoryPath()).thenReturn(System.getProperty("java.io.tmpdir"));
        CacheConfigurable configurable = new CacheConfigurable();
        configurable.createComponent();

        configurable.apply();

        verify(settings).saveSettings();
        verify(listener, never()).cacheSettingsChanged(any());
    }

    @Test
    void resetRestoresWidgetsAndTogglesPreloadEnablement() {
        CacheConfigurable configurable = configured();
        ((JCheckBox) field(configurable, "enablePreloadCheckBox")).setSelected(false);

        configurable.reset();

        assertTrue(((JCheckBox) field(configurable, "enablePreloadCheckBox")).isSelected());
        assertTrue(((JSpinner) field(configurable, "preloadCountSpinner")).isEnabled());
    }

    @Test
    void createComponentUsesStorageManagerWhenProjectOpen() {
        Project project = mock(Project.class);
        StorageManager storageManager = mock(StorageManager.class);
        lenient().when(storageManager.getCachePath()).thenReturn(System.getProperty("java.io.tmpdir"));
        lenient().when(storageManager.getBooksFilePath())
                .thenReturn(System.getProperty("java.io.tmpdir") + "/books/index.json");
        lenient().when(project.getService(StorageManager.class)).thenReturn(storageManager);
        ProjectManager projectManager = mock(ProjectManager.class);
        lenient().when(projectManager.getOpenProjects()).thenReturn(new Project[]{project});
        try (MockedStatic<ProjectManager> pmStatic = mockStatic(ProjectManager.class)) {
            pmStatic.when(ProjectManager::getInstance).thenReturn(projectManager);

            lenient().when(settings.isEnableCache()).thenReturn(true);
            lenient().when(settings.isEnablePreload()).thenReturn(false);
            CacheConfigurable configurable = new CacheConfigurable();
            JPanel panel = (JPanel) configurable.createComponent();

            assertNotNull(panel);
            assertNotNull(field(configurable, "booksPathLabel"));
        }
    }
}