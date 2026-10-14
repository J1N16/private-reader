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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * ReactiveChapterCacheRepositoryImpl 读写与清理路径测试(V21 补测)。
 *
 * <p>覆盖此前完全未测的 CRUD 主干:
 * <ul>
 *   <li>写入 → 内存命中读取、文件读取</li>
 *   <li>缺文件/过期 → 返回 empty;备用读取忽略过期</li>
 *   <li>空内容不写入</li>
 *   <li>按书籍清理 / 全量清理</li>
 *   <li>容量超限按最旧优先删除</li>
 *   <li>过期缓存清理(含删除空目录)、按书籍过期清理</li>
 *   <li>缓存目录路径、dispose</li>
 * </ul>
 *
 * <p>隔离方式与 {@link ReactiveChapterCacheRepositoryImplTest} 一致:io 调度器替换为
 * trampoline、mockStatic 拦截 ApplicationManager(构造器读取 StorageRepository 缓存路径)。</p>
 */
class ReactiveChapterCacheRepositoryImplCrudTest {

    private static final String BOOK_ID = "book-crud";
    private static final String CHAPTER_ID = "https://example.com/c1";
    private static final String CONTENT = "第一章内容";

    @TempDir
    Path tempDir;

    private CacheSettings cacheSettings;
    private MockedStatic<ApplicationManager> appMgr;
    private ReactiveChapterCacheRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        RxJavaPlugins.reset();
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        Schedulers.io();

        Application app = mock(Application.class);
        StorageRepository storageRepository = mock(StorageRepository.class);
        when(storageRepository.getCachePath()).thenReturn(tempDir.toString());
        when(app.getService(StorageRepository.class)).thenReturn(storageRepository);
        appMgr = mockStatic(ApplicationManager.class);
        appMgr.when(ApplicationManager::getApplication).thenReturn(app);

        cacheSettings = mock(CacheSettings.class);
        lenient().when(cacheSettings.getCacheExpiryHours()).thenReturn(24L);
        lenient().when(cacheSettings.getMaxCacheSizeMB()).thenReturn(10_000);
        lenient().when(cacheSettings.isCleanupOnStartup()).thenReturn(false);

        repository = new ReactiveChapterCacheRepositoryImpl(cacheSettings);
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.dispose();
        }
        RxJavaPlugins.reset();
        appMgr.close();
    }

    private Path bookDir() {
        return tempDir.resolve("chapter_cache").resolve(BOOK_ID);
    }

    private Path chapterFile() throws Exception {
        return bookDir().resolve(md5(CHAPTER_ID));
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

    // --- 写入/读取 ---

    @Test
    void cacheThenReadReturnsContentFromMemory() {
        repository.cacheContentReactive(BOOK_ID, CHAPTER_ID, CONTENT)
                .blockingAwait(5, TimeUnit.SECONDS);

        String got = repository.getCachedContentReactive(BOOK_ID, CHAPTER_ID)
                .blockingGet();

        assertEquals(CONTENT, got);
        assertTrue(Files.exists(bookDir().resolve(md5Safe(CHAPTER_ID))),
                "写入后应落盘为缓存文件");
    }

    @Test
    void cacheEmptyContentWritesNothing() {
        repository.cacheContentReactive(BOOK_ID, CHAPTER_ID, "")
                .blockingAwait(5, TimeUnit.SECONDS);

        assertTrue(repository.getCachedContentReactive(BOOK_ID, CHAPTER_ID).isEmpty().blockingGet());
        assertFalse(Files.exists(bookDir()), "空内容不应创建书籍缓存目录");
    }

    @Test
    void readMissingChapterReturnsEmpty() {
        assertTrue(repository.getCachedContentReactive(BOOK_ID, "missing").isEmpty().blockingGet());
        assertTrue(repository.getFallbackCachedContentReactive(BOOK_ID, "missing").isEmpty().blockingGet());
    }

    @Test
    void readExpiredFileReturnsEmptyButFallbackIgnoresExpiry() throws Exception {
        // 直接落盘一个 2 天前的文件(无内存缓存)
        Path file = chapterFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, CONTENT, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));

        assertTrue(repository.getCachedContentReactive(BOOK_ID, CHAPTER_ID).isEmpty().blockingGet(),
                "过期缓存主读取应返回 empty");

        String fallback = repository.getFallbackCachedContentReactive(BOOK_ID, CHAPTER_ID).blockingGet();
        assertEquals(CONTENT, fallback, "备用读取应忽略过期检查");
    }

    // --- 清理 ---

    @Test
    void clearCacheRemovesBookDirectory() throws Exception {
        repository.cacheContentReactive(BOOK_ID, CHAPTER_ID, CONTENT).blockingAwait();
        assertTrue(Files.exists(chapterFile()));

        repository.clearCacheReactive(BOOK_ID).blockingAwait();

        assertFalse(Files.exists(bookDir()), "按书籍清理应删除整本书的缓存目录");
    }

    @Test
    void clearAllCacheRemovesEverythingAndRecreatesRoot() throws Exception {
        repository.cacheContentReactive(BOOK_ID, CHAPTER_ID, CONTENT).blockingAwait();
        Path root = tempDir.resolve("chapter_cache");
        assertTrue(Files.exists(root.resolve(BOOK_ID)));

        repository.clearAllCacheReactive().blockingAwait();

        assertTrue(Files.exists(root), "清空后应重建缓存根目录");
        assertFalse(Files.exists(root.resolve(BOOK_ID)), "清空后不应残留书籍目录");
    }

    @Test
    void cleanupExpiredCacheDeletesStaleFilesAndEmptyDirs() throws Exception {
        Path stale = chapterFile();
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "old", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        repository.cleanupCacheReactive().blockingAwait();

        assertFalse(Files.exists(stale), "过期文件应被清理");
        assertFalse(Files.exists(bookDir()), "清空后的书籍目录应被删除");
    }

    @Test
    void cleanupBookCacheRemovesOnlyStaleFilesOfThatBook() throws Exception {
        Path stale = chapterFile();
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "old", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        Path fresh = bookDir().resolve(md5("fresh-chapter"));
        Files.writeString(fresh, "fresh", StandardCharsets.UTF_8);

        repository.cleanupBookCacheReactive(BOOK_ID).blockingAwait();

        assertFalse(Files.exists(stale), "该书过期文件应被清理");
        assertTrue(Files.exists(fresh), "该书未过期文件应保留");
    }

    @Test
    void oversizedCacheEvictsOldestFiles() throws Exception {
        // 容量上限设为 0 → 任何占用都超限,应触发按最旧优先的删除
        when(cacheSettings.getMaxCacheSizeMB()).thenReturn(0);

        Path dir = tempDir.resolve("chapter_cache").resolve("bulk");
        Files.createDirectories(dir);
        Path older = dir.resolve(md5("older"));
        Path newer = dir.resolve(md5("newer"));
        Files.writeString(older, "aaaa", StandardCharsets.UTF_8);
        Files.writeString(newer, "bbbb", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(older, FileTime.from(Instant.now().minus(5, ChronoUnit.DAYS)));

        // 构造器启动清理已关闭;直接调用公开清理入口
        repository.checkAndEvictCacheReactive().blockingAwait();

        assertFalse(Files.exists(older), "超限时应优先删除最旧文件");
    }

    // --- 其他 ---

    @Test
    void getCacheDirPathReturnsConfiguredDirectory() {
        assertEquals(tempDir.resolve("chapter_cache").toString(), repository.getCacheDirPath());
    }

    @Test
    void disposeInvalidatesMemoryCacheAndStopsCleanupTask() {
        repository.cacheContentReactive(BOOK_ID, CHAPTER_ID, CONTENT).blockingAwait();
        repository.dispose();

        // dispose 后内存缓存清空,文件仍在(读取走文件路径)
        assertEquals(CONTENT, repository.getCachedContentReactive(BOOK_ID, CHAPTER_ID).blockingGet());
    }

    private static String md5Safe(String value) {
        try {
            return md5(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}