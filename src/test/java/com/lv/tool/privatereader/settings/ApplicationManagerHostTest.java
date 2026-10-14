package com.lv.tool.privatereader.settings;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.lv.tool.privatereader.service.NotificationService;
import com.lv.tool.privatereader.service.ReaderModeSwitcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

/**
 * ApplicationManagerHost 服务查询适配测试(V27)。
 *
 * <p>校验 {@link ApplicationManagerHost} 把 {@code ApplicationManager.getService(...)} 的查询
 * 正确适配为 {@link DiagnosticsHosts} 的最小接口,并覆盖 {@code saveAllConfigs} 与
 * {@code applyReaderMode} 的聚合逻辑。所有设置服务均以 Mockito mock 提供,
 * 避免触发 {@code BaseSettings.ensureSettingsLoaded()} 的真实存储访问。</p>
 *
 * <p>全部用例只驱动「服务可用 / 服务缺失」的正常分支,服务缺失走 {@code LOG.warn},
 * 不触碰 {@code LOG.error} 路径。</p>
 */
class ApplicationManagerHostTest {

    private Application app;
    private MockedStatic<ApplicationManager> appMgr;

    @BeforeEach
    void setUp() {
        app = mock(Application.class);
        appMgr = mockStatic(ApplicationManager.class);
        appMgr.when(ApplicationManager::getApplication).thenReturn(app);
    }

    @AfterEach
    void tearDown() {
        appMgr.close();
    }

    private void register(Class<?> type, Object instance) {
        lenient().doReturn(instance).when(app).getService(type);
    }

    // --- 服务缺失全部返回 null ---

    @Test
    void allQueriesReturnNullWhenServicesMissing() {
        ApplicationManagerHost host = new ApplicationManagerHost();

        assertNull(host.pluginSettings());
        assertNull(host.readerSettings());
        assertNull(host.cacheSettings());
        assertNull(host.readerModeSettings());
        assertNull(host.notificationSettings());
        assertNull(host.notificationService());
        assertEquals(0, host.saveAllConfigs());
    }

    // --- 各设置适配器字段映射 ---

    @Test
    void pluginSettingsAdapterMapsEveryGetter() {
        PluginSettings s = mock(PluginSettings.class);
        when(s.isEnabled()).thenReturn(true);
        when(s.isAutoUpdate()).thenReturn(true);
        when(s.isShowNotifications()).thenReturn(false);
        when(s.getLanguage()).thenReturn("zh");
        when(s.isDebugMode()).thenReturn(true);
        register(PluginSettings.class, s);

        DiagnosticsHosts.PluginSettingsHost host = new ApplicationManagerHost().pluginSettings();
        assertNotNull(host);
        assertTrue(host.enabled());
        assertTrue(host.autoUpdate());
        assertFalse(host.showNotifications());
        assertEquals("zh", host.language());
        assertTrue(host.debugMode());

        host.save();
        verify(s).saveSettings();
    }

    @Test
    void readerSettingsAdapterMapsEveryGetter() {
        ReaderSettings s = mock(ReaderSettings.class);
        when(s.getFontFamily()).thenReturn("Consolas");
        when(s.getFontSize()).thenReturn(16);
        when(s.isBold()).thenReturn(true);
        when(s.isDarkTheme()).thenReturn(false);
        when(s.isUseAnimation()).thenReturn(true);
        register(ReaderSettings.class, s);

        DiagnosticsHosts.ReaderSettingsHost host = new ApplicationManagerHost().readerSettings();
        assertNotNull(host);
        assertEquals("Consolas", host.fontFamily());
        assertEquals(16, host.fontSize());
        assertTrue(host.bold());
        assertFalse(host.darkTheme());
        assertTrue(host.useAnimation());

        host.save();
        verify(s).saveSettings();
    }

    @Test
    void cacheSettingsAdapterMapsEveryGetter() {
        CacheSettings s = mock(CacheSettings.class);
        when(s.isEnableCache()).thenReturn(true);
        when(s.getMaxCacheSize()).thenReturn(100);
        when(s.getMaxCacheAge()).thenReturn(7);
        when(s.isEnablePreload()).thenReturn(false);
        when(s.getPreloadCount()).thenReturn(3);
        register(CacheSettings.class, s);

        DiagnosticsHosts.CacheSettingsHost host = new ApplicationManagerHost().cacheSettings();
        assertNotNull(host);
        assertTrue(host.enableCache());
        assertEquals(100, host.maxCacheSize());
        assertEquals(7, host.maxCacheAge());
        assertFalse(host.enablePreload());
        assertEquals(3, host.preloadCount());

        host.save();
        verify(s).saveSettings();
    }

    @Test
    void readerModeSettingsAdapterMapsEveryGetter() {
        ReaderModeSettings s = mock(ReaderModeSettings.class);
        when(s.isNotificationMode()).thenReturn(true);
        when(s.isAutoScroll()).thenReturn(true);
        when(s.getScrollSpeed()).thenReturn(5);
        when(s.isShowLineNumbers()).thenReturn(true);
        register(ReaderModeSettings.class, s);

        DiagnosticsHosts.ReaderModeSettingsHost host = new ApplicationManagerHost().readerModeSettings();
        assertNotNull(host);
        assertTrue(host.notificationMode());
        assertTrue(host.autoScroll());
        assertEquals(5, host.scrollSpeed());
        assertTrue(host.showLineNumbers());

        host.save();
        verify(s).saveSettings();
    }

    @Test
    void notificationSettingsAdapterMapsValuesAndReadsReaderMode() {
        NotificationReaderSettings ns = mock(NotificationReaderSettings.class);
        when(ns.getPageSize()).thenReturn(500);
        when(ns.isShowPageNumber()).thenReturn(true);
        ReaderModeSettings ms = mock(ReaderModeSettings.class);
        when(ms.isNotificationMode()).thenReturn(true);
        register(NotificationReaderSettings.class, ns);
        register(ReaderModeSettings.class, ms);

        DiagnosticsHosts.NotificationSettingsHost host = new ApplicationManagerHost().notificationSettings();
        assertNotNull(host);
        assertEquals(500, host.pageSize());
        assertTrue(host.showPageNumber());
        assertTrue(host.notificationMode());
    }

    @Test
    void notificationSettingsNotificationModeFalseWhenReaderModeMissing() {
        NotificationReaderSettings ns = mock(NotificationReaderSettings.class);
        register(NotificationReaderSettings.class, ns);

        DiagnosticsHosts.NotificationSettingsHost host = new ApplicationManagerHost().notificationSettings();
        assertNotNull(host);
        assertFalse(host.notificationMode());
    }

    @Test
    void notificationServiceAdapterMapsPageCounts() {
        NotificationService s = mock(NotificationService.class);
        when(s.getCurrentPage()).thenReturn(2);
        when(s.getTotalPages()).thenReturn(10);
        register(NotificationService.class, s);

        DiagnosticsHosts.NotificationServiceHost host = new ApplicationManagerHost().notificationService();
        assertNotNull(host);
        assertEquals(2, host.currentPage());
        assertEquals(10, host.totalPages());
    }

    // --- saveAllConfigs ---

    @Test
    void saveAllConfigsSavesEachRegisteredGroup() {
        PluginSettings ps = mock(PluginSettings.class);
        ReaderSettings rs = mock(ReaderSettings.class);
        CacheSettings cs = mock(CacheSettings.class);
        ReaderModeSettings ms = mock(ReaderModeSettings.class);
        register(PluginSettings.class, ps);
        register(ReaderSettings.class, rs);
        register(CacheSettings.class, cs);
        register(ReaderModeSettings.class, ms);

        assertEquals(4, new ApplicationManagerHost().saveAllConfigs());

        verify(ps).saveSettings();
        verify(rs).saveSettings();
        verify(cs).saveSettings();
        verify(ms).saveSettings();
    }

    @Test
    void saveAllConfigsCountsOnlyAvailableGroups() {
        CacheSettings cs = mock(CacheSettings.class);
        register(CacheSettings.class, cs);

        assertEquals(1, new ApplicationManagerHost().saveAllConfigs());

        verify(cs).saveSettings();
    }

    // --- applyReaderMode ---

    @Test
    void applyReaderModeAppliesToEveryOpenProject() {
        ReaderModeSettings ms = mock(ReaderModeSettings.class);
        when(ms.isNotificationMode()).thenReturn(true);
        ReaderModeSwitcher switcher = mock(ReaderModeSwitcher.class);
        register(ReaderModeSettings.class, ms);
        register(ReaderModeSwitcher.class, switcher);

        Project p1 = mock(Project.class);
        Project p2 = mock(Project.class);
        ProjectManager pm = mock(ProjectManager.class);
        when(pm.getOpenProjects()).thenReturn(new Project[]{p1, p2});
        try (MockedStatic<ProjectManager> pmStatic = mockStatic(ProjectManager.class)) {
            pmStatic.when(ProjectManager::getInstance).thenReturn(pm);

            DiagnosticsHosts.ReaderModeSettingsHost modeHost =
                    new ApplicationManagerHost().readerModeSettings();
            assertNotNull(modeHost);

            assertTrue(new ApplicationManagerHost().applyReaderMode(modeHost));
            verify(switcher).applyInitialModeForProject(p1);
            verify(switcher).applyInitialModeForProject(p2);
        }
    }

    @Test
    void applyReaderModeReturnsFalseWhenNoProjectOpen() {
        ReaderModeSettings ms = mock(ReaderModeSettings.class);
        ReaderModeSwitcher switcher = mock(ReaderModeSwitcher.class);
        register(ReaderModeSettings.class, ms);
        register(ReaderModeSwitcher.class, switcher);

        ProjectManager pm = mock(ProjectManager.class);
        when(pm.getOpenProjects()).thenReturn(new Project[0]);
        try (MockedStatic<ProjectManager> pmStatic = mockStatic(ProjectManager.class)) {
            pmStatic.when(ProjectManager::getInstance).thenReturn(pm);

            DiagnosticsHosts.ReaderModeSettingsHost modeHost =
                    new ApplicationManagerHost().readerModeSettings();
            assertNotNull(modeHost);

            assertFalse(new ApplicationManagerHost().applyReaderMode(modeHost));
            verify(switcher, never()).applyInitialModeForProject(any());
        }
    }

    @Test
    void applyReaderModeInReaderModeAppliesToOpenProject() {
        ReaderModeSettings ms = mock(ReaderModeSettings.class);
        when(ms.isNotificationMode()).thenReturn(false);
        ReaderModeSwitcher switcher = mock(ReaderModeSwitcher.class);
        register(ReaderModeSettings.class, ms);
        register(ReaderModeSwitcher.class, switcher);

        Project p = mock(Project.class);
        when(p.getName()).thenReturn("proj");
        ProjectManager pm = mock(ProjectManager.class);
        when(pm.getOpenProjects()).thenReturn(new Project[]{p});
        try (MockedStatic<ProjectManager> pmStatic = mockStatic(ProjectManager.class)) {
            pmStatic.when(ProjectManager::getInstance).thenReturn(pm);

            ApplicationManagerHost host = new ApplicationManagerHost();
            DiagnosticsHosts.ReaderModeSettingsHost modeHost = host.readerModeSettings();
            assertNotNull(modeHost);

            assertTrue(host.applyReaderMode(modeHost));
            verify(switcher).applyInitialModeForProject(p);
        }
    }
}