package com.lv.tool.privatereader.repository;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * RepositoryModule 服务查找与惰性缓存测试(V31)。
 *
 * <p>此前 1/33(3.0%)覆盖。模块本身不依赖 IntelliJ 容器,只需
 * {@code ApplicationManager.getApplication().getService(...)} 可由 mockStatic 提供。</p>
 */
class RepositoryModuleTest {

    private MockedStatic<ApplicationManager> appMgr;
    private Application app;

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

    @Test
    void gettersReturnRegisteredServices() {
        StorageRepository sr = mock(StorageRepository.class);
        BookRepository br = mock(BookRepository.class);
        ReadingProgressRepository pr = mock(ReadingProgressRepository.class);
        ReactiveChapterCacheRepository cr = mock(ReactiveChapterCacheRepository.class);
        register(StorageRepository.class, sr);
        register(BookRepository.class, br);
        register(ReadingProgressRepository.class, pr);
        register(ReactiveChapterCacheRepository.class, cr);

        RepositoryModule module = new RepositoryModule();

        assertSame(sr, module.getStorageRepository());
        assertSame(br, module.getBookRepository());
        assertSame(pr, module.getReadingProgressRepository());
        assertSame(cr, module.getChapterCacheRepository());
    }

    @Test
    void gettersReturnNullWhenServicesUnavailable() {
        RepositoryModule module = new RepositoryModule();

        assertNull(module.getStorageRepository());
        assertNull(module.getBookRepository());
        assertNull(module.getReadingProgressRepository());
        assertNull(module.getChapterCacheRepository());
    }

    @Test
    void lazyInitializationOnlyQueriesServiceOncePerType() {
        StorageRepository sr = mock(StorageRepository.class);
        register(StorageRepository.class, sr);

        RepositoryModule module = new RepositoryModule();
        module.getStorageRepository();
        module.getStorageRepository();
        module.getStorageRepository();

        // 首次解析后缓存于字段,不再重复查询服务容器
        org.mockito.Mockito.verify(app, times(1)).getService(StorageRepository.class);
    }

    @Test
    void getInstanceReturnsServiceInstanceWhenAvailable() {
        RepositoryModule existing = new RepositoryModule();
        when(app.getService(RepositoryModule.class)).thenReturn(existing);

        assertSame(existing, RepositoryModule.getInstance());
    }

    @Test
    void getInstanceFallsBackToNewInstanceWhenServiceMissing() {
        RepositoryModule module = RepositoryModule.getInstance();

        assertNotNull(module);
    }

    @Test
    void partialServicesStillResolveIndependently() {
        BookRepository br = mock(BookRepository.class);
        register(BookRepository.class, br);

        RepositoryModule module = new RepositoryModule();

        assertNull(module.getStorageRepository());
        assertSame(br, module.getBookRepository());
        assertTrue(module.getBookRepository() != null);
    }
}