package com.lv.tool.privatereader.storage;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.lv.tool.privatereader.repository.BookRepository;
import com.lv.tool.privatereader.repository.ReactiveChapterCacheRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StorageManager 初始化后任务 / 迁移 / 清理 / 服务查询测试(V31)。
 *
 * <p>V31 为 {@code StorageManager} 新增包级 {@code (Path base, Path legacy)} 构造器:
 * 原先 {@code migrateFromOldLocation()} 内联调用 {@code PathManager.getConfigPath()},
 * 容器外不可达(该文件 44.2% 覆盖,缺口集中在迁移与清理)。注入旧目录后,迁移的
 * 「备份 → 复制 books/cache/settings」全流程可在 {@code @TempDir} 下确定性验证。</p>
 *
 * <p>所有用例只走正常分支;服务缺失/IO 失败等会 {@code LOG.error} 的路径不覆盖
 * (测试环境断言错误日志)。</p>
 */
class StorageManagerLifecycleTest {

    @TempDir
    Path baseDir;

    @TempDir
    Path legacyDir;

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

    private StorageManager manager() {
        return new StorageManager(baseDir, legacyDir);
    }

    private long countFiles(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    // --- 目录结构与路径 ---

    @Test
    void constructorCreatesAllSubDirectories() {
        manager();

        assertTrue(Files.isDirectory(baseDir.resolve("books")));
        assertTrue(Files.isDirectory(baseDir.resolve("cache")));
        assertTrue(Files.isDirectory(baseDir.resolve("settings")));
        assertTrue(Files.isDirectory(baseDir.resolve("backup")));
    }

    @Test
    void twoArgConstructorCreatesBookDirectoryOnDemand() {
        StorageManager m = manager();

        String dir = m.getBookDirectory("book-1");

        assertEquals(baseDir.resolve("books").resolve("book-1").toString(), dir);
        assertTrue(Files.isDirectory(Path.of(dir)));
    }

    @Test
    void getBookDirectoryIsIdempotent() {
        StorageManager m = manager();

        assertEquals(m.getBookDirectory("book-1"), m.getBookDirectory("book-1"));
    }

    // --- 迁移:无旧数据时不动作 ---

    @Test
    void migrationIsSkippedWhenLegacyBooksMissing() {
        manager().performPostInitializationTasks();

        // 未创建任何迁移备份
        assertEquals(0, countFilesQuietly(baseDir.resolve("backup")));
    }

    @Test
    void migrationIsSkippedWhenTargetIndexAlreadyExists() throws Exception {
        Files.createDirectories(legacyDir.resolve("books"));
        Files.writeString(legacyDir.resolve("books").resolve("legacy.json"), "{}", StandardCharsets.UTF_8);
        Files.createDirectories(baseDir.resolve("books"));
        Files.writeString(baseDir.resolve("books").resolve("index.json"), "{}", StandardCharsets.UTF_8);

        manager().performPostInitializationTasks();

        // 目标已有 index.json → 不迁移,旧文件不应出现在新目录
        assertFalse(Files.exists(baseDir.resolve("books").resolve("legacy.json")));
    }

    // --- 迁移:完整流程 ---

    @Test
    void migrationCopiesBooksCacheAndSettingsAndCreatesBackup() throws Exception {
        // 旧数据布局
        Files.createDirectories(legacyDir.resolve("books").resolve("book-1"));
        Files.writeString(legacyDir.resolve("books").resolve("book-1").resolve("details.json"), "{}", StandardCharsets.UTF_8);
        Files.createDirectories(legacyDir.resolve("cache"));
        Files.writeString(legacyDir.resolve("cache").resolve("c1"), "cached", StandardCharsets.UTF_8);
        Files.createDirectories(legacyDir.resolve("settings"));
        Files.writeString(legacyDir.resolve("settings").resolve("PluginSettings.json"), "{}", StandardCharsets.UTF_8);

        manager().performPostInitializationTasks();

        // 迁移到位
        assertTrue(Files.exists(baseDir.resolve("books").resolve("book-1").resolve("details.json")));
        assertTrue(Files.exists(baseDir.resolve("cache").resolve("c1")));
        assertTrue(Files.exists(baseDir.resolve("settings").resolve("PluginSettings.json")));
    }

    @Test
    void migrationCreatesTimestampedBackupDirectory() throws Exception {
        Files.createDirectories(legacyDir.resolve("books"));
        Files.writeString(legacyDir.resolve("books").resolve("a.json"), "{}", StandardCharsets.UTF_8);

        manager().performPostInitializationTasks();

        try (Stream<Path> s = Files.list(baseDir.resolve("backup"))) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("migration_backup_")),
                    "应创建时间戳备份目录");
        }
    }

    @Test
    void migrationBackupContainsOriginalData() throws Exception {
        Files.createDirectories(legacyDir.resolve("books"));
        Files.writeString(legacyDir.resolve("books").resolve("a.json"), "{\"k\":1}", StandardCharsets.UTF_8);

        manager().performPostInitializationTasks();

        Path backup = null;
        try (Stream<Path> s = Files.list(baseDir.resolve("backup"))) {
            backup = s.findFirst().orElse(null);
        }
        assertNotNull(backup);
        assertTrue(Files.exists(backup.resolve("books").resolve("a.json")), "备份应保留原始数据");
    }

    @Test
    void migrationSkipsCacheAndSettingsWhenAbsentLegacy() throws Exception {
        // 仅旧 books 存在,无 cache/settings
        Files.createDirectories(legacyDir.resolve("books"));
        Files.writeString(legacyDir.resolve("books").resolve("a.json"), "{}", StandardCharsets.UTF_8);

        manager().performPostInitializationTasks();

        assertEquals(0, countFiles(baseDir.resolve("cache")));
        assertEquals(0, countFiles(baseDir.resolve("settings")));
        assertTrue(Files.exists(baseDir.resolve("books").resolve("a.json")));
    }

    // --- 服务查询委派 ---

    @Test
    void getSettingsStorageDelegatesToApplicationService() {
        SettingsStorage storage = mock(SettingsStorage.class);
        when(app.getService(SettingsStorage.class)).thenReturn(storage);

        assertEquals(storage, manager().getSettingsStorage());
    }

    @Test
    void getReactiveChapterCacheRepositoryDelegatesToApplicationService() {
        ReactiveChapterCacheRepository repo = mock(ReactiveChapterCacheRepository.class);
        when(app.getService(ReactiveChapterCacheRepository.class)).thenReturn(repo);

        assertEquals(repo, manager().getReactiveChapterCacheRepository());
    }

    @Test
    void clearAllStorageClearsBookRepositoryAndCacheThenRecreatesDirectories() throws Exception {
        BookRepository bookRepo = mock(BookRepository.class);
        ReactiveChapterCacheRepository cacheRepo = mock(ReactiveChapterCacheRepository.class);
        lenient().when(app.getService(BookRepository.class)).thenReturn(bookRepo);
        lenient().when(app.getService(ReactiveChapterCacheRepository.class)).thenReturn(cacheRepo);

        StorageManager m = manager();
        // 放入一些数据,验证会被清空
        Files.writeString(baseDir.resolve("books").resolve("index.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(baseDir.resolve("cache").resolve("c1"), "c", StandardCharsets.UTF_8);

        m.clearAllStorage();

        verify(bookRepo).clearAllBooks();
        verify(cacheRepo).clearAllCache();
        // 目录结构被重建且内容已清空
        assertTrue(Files.isDirectory(baseDir.resolve("books")));
        assertEquals(0, countFiles(baseDir.resolve("books")));
        assertEquals(0, countFiles(baseDir.resolve("cache")));
    }

    private long countFilesQuietly(Path dir) {
        try {
            return countFiles(dir);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}