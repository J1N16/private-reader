package com.lv.tool.privatereader.repository.impl;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.lv.tool.privatereader.repository.StorageRepository;
import com.lv.tool.privatereader.settings.CacheSettings;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * ReactiveChapterCacheRepositoryImpl 启动清理行为测试
 *
 * <p>回归背景:此前 {@code cleanupOnStartup} 设置从未被读取,仓储仅在构造器里调度
 * 「每 6 小时」的定时清理,首次触发要等 6 小时,短时使用/频繁重启的用户永远等不到清理,
 * 过期章节长期驻留磁盘。</p>
 *
 * <p>仓储构造器走 {@code subscribeOn(Schedulers.io())} 并调用
 * {@code ApplicationManager.getService(StorageRepository.class)},故用
 * RxJavaPlugins 将 io 调度器替换为 trampoline,并 mockStatic 拦截 ApplicationManager。</p>
 */
class ReactiveChapterCacheRepositoryImplTest {

    private static final String BOOK_ID = "book-1";
    private static final String CHAPTER_ID = "https://example.com/chapter-1";

    @TempDir
    Path tempDir;

    private CacheSettings cacheSettings;
    private MockedStatic<ApplicationManager> appMgr;
    private ReactiveChapterCacheRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        // 仅替换 io 调度器:构造器里的清理使用 io,定时任务使用 computation,
        // 若把 computation 也换成 trampoline,Observable.interval 会同步阻塞。
        RxJavaPlugins.reset();
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        Schedulers.io(); // 触发重建,使其捕获新的 handler

        Application app = mock(Application.class);
        StorageRepository storageRepository = storageRepository();
        when(app.getService(StorageRepository.class)).thenReturn(storageRepository);
        appMgr = mockStatic(ApplicationManager.class);
        appMgr.when(ApplicationManager::getApplication).thenReturn(app);

        cacheSettings = mock(CacheSettings.class);
        lenient().when(cacheSettings.getCacheExpiryHours()).thenReturn(24L); // 1 天
        lenient().when(cacheSettings.getMaxCacheSizeMB()).thenReturn(10_000); // 不触发容量清理
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.dispose();
        }
        RxJavaPlugins.reset();
        appMgr.close();
    }

    private StorageRepository storageRepository() {
        StorageRepository storageRepository = mock(StorageRepository.class);
        when(storageRepository.getCachePath()).thenReturn(tempDir.toString());
        return storageRepository;
    }

    /** 创建仓储,按需触发启动清理。 */
    private void createRepository(boolean cleanupOnStartup) {
        when(cacheSettings.isCleanupOnStartup()).thenReturn(cleanupOnStartup);
        repository = new ReactiveChapterCacheRepositoryImpl(cacheSettings);
    }

    /** 在磁盘缓存目录写入章节文件,并把修改时间设为指定天数之前。 */
    private Path writeCacheFile(long ageInDays) throws Exception {
        Path bookDir = tempDir.resolve("chapter_cache").resolve(BOOK_ID);
        Files.createDirectories(bookDir);
        Path file = bookDir.resolve(md5(CHAPTER_ID));
        Files.writeString(file, "章节内容", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(
                Instant.now().minus(ageInDays, ChronoUnit.DAYS)));
        return file;
    }

    private static String md5(String value) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // --- 启动清理 ---

    @Test
    void startupCleanupRemovesExpiredCacheFilesWhenEnabled() throws Exception {
        Path expired = writeCacheFile(30);

        createRepository(true);

        assertFalse(Files.exists(expired), "启用启动清理时,过期缓存应在启动阶段被删除");
    }

    @Test
    void startupCleanupKeepsExpiredFilesWhenDisabled() throws Exception {
        Path expired = writeCacheFile(30);

        createRepository(false);

        assertTrue(Files.exists(expired), "禁用启动清理时,过期缓存不应在启动阶段被删除");
    }

    @Test
    void startupCleanupKeepsFreshCacheFiles() throws Exception {
        Path fresh = writeCacheFile(0);

        createRepository(true);

        assertTrue(Files.exists(fresh), "未过期缓存不应被启动清理删除");
    }

    // --- 过期判断使用设置的小时数 ---

    @Test
    void expiryUsesConfiguredHours() throws Exception {
        Path twoDaysOld = writeCacheFile(2);

        // 过期时间放宽到 7 天,2 天前的文件应保留
        when(cacheSettings.getCacheExpiryHours()).thenReturn(24L * 7);
        createRepository(true);

        assertTrue(Files.exists(twoDaysOld), "未超过配置过期时间的缓存应保留");
    }
}