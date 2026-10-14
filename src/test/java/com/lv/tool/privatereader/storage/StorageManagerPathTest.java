package com.lv.tool.privatereader.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StorageManager 实例方法测试(V24 可测性改造)。
 *
 * <p>通过新增的包级注入 basePath 构造器,在 {@code @TempDir} 下验证路径解析、
 * 目录创建与删除逻辑,不触碰用户主目录,也不走依赖 IntelliJ 容器的
 * {@code clearAllStorage}/{@code getReactiveChapterCacheRepository}。</p>
 */
class StorageManagerPathTest {

    @TempDir
    Path tempDir;

    private Path base;
    private StorageManager manager;

    @BeforeEach
    void setUp() {
        base = tempDir.resolve("private-reader");
        manager = new StorageManager(base);
    }

    @Test
    void constructorCreatesFullDirectoryLayout() {
        assertTrue(Files.isDirectory(base));
        assertTrue(Files.isDirectory(base.resolve("books")));
        assertTrue(Files.isDirectory(base.resolve("cache")));
        assertTrue(Files.isDirectory(base.resolve("settings")));
        assertTrue(Files.isDirectory(base.resolve("backup")));
    }

    @Test
    void gettersReturnResolvedPaths() {
        assertEquals(base.toString(), manager.getBaseStoragePath());
        assertEquals(base.resolve("books").toString(), manager.getBooksPath());
        assertEquals(base.resolve("cache").toString(), manager.getCachePath());
        assertEquals(base.resolve("settings").toString(), manager.getSettingsPath());
        assertEquals(base.resolve("backup").toString(), manager.getBackupPath());
        assertEquals(base.resolve("books").resolve("index.json").toString(), manager.getBooksFilePath());
    }

    @Test
    void createBookDirectoryCreatesAndReturnsPath() {
        String dir = manager.createBookDirectory("book-1");

        assertEquals(base.resolve("books").resolve("book-1").toString(), dir);
        assertTrue(Files.isDirectory(Path.of(dir)));
    }

    @Test
    void getBookDirectoryCreatesMissingDirectoryOnDemand() {
        Path expected = base.resolve("books").resolve("book-2");
        assertFalse(Files.exists(expected));

        String dir = manager.getBookDirectory("book-2");

        assertEquals(expected.toString(), dir);
        assertTrue(Files.isDirectory(expected), "getBookDirectory 应在缺失时按需创建");
    }

    @Test
    void getBookDirectoryReturnsExistingWithoutRecreation() throws Exception {
        Path existing = base.resolve("books").resolve("book-3");
        Files.createDirectories(existing);

        assertEquals(existing.toString(), manager.getBookDirectory("book-3"));
        assertTrue(Files.isDirectory(existing));
    }

    @Test
    void safeFileNameStaysAvailableFromInstanceContext() {
        assertEquals("a_b", StorageManager.getSafeFileName("a/b"));
    }
}