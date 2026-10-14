package com.lv.tool.privatereader.repository.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileStorageRepository 路径与文件名逻辑测试(V28)。
 *
 * <p>V28 为 {@link FileStorageRepository} 增加包级 {@code (Path)} 构造函数,默认构造器委托,
 * 生产行为不变。此前该类 0/78 覆盖(构造器硬编码 {@code user.home}),现可在 {@code @TempDir}
 * 下确定性验证目录结构、文件名净化、缓存文件名编码与全量清理。</p>
 *
 * <p>全部用例只走正常/容错分支(无 {@code LOG.error}),故不触发测试环境的错误日志断言。</p>
 */
class FileStorageRepositoryTest {

    @TempDir
    Path tempDir;

    private FileStorageRepository repository() {
        return new FileStorageRepository(tempDir);
    }

    // --- 目录结构创建 ---

    @Test
    void constructorCreatesFullDirectoryStructure() {
        repository();

        assertTrue(Files.isDirectory(tempDir));
        assertTrue(Files.isDirectory(tempDir.resolve("books")));
        assertTrue(Files.isDirectory(tempDir.resolve("cache")));
        assertTrue(Files.isDirectory(tempDir.resolve("settings")));
        assertTrue(Files.isDirectory(tempDir.resolve("backup")));
    }

    @Test
    void pathGettersReflectBaseDirectory() {
        FileStorageRepository repo = repository();

        assertEquals(tempDir.toString(), repo.getBaseStoragePath());
        assertEquals(tempDir.resolve("books").toString(), repo.getBooksPath());
        assertEquals(tempDir.resolve("cache").toString(), repo.getCachePath());
        assertEquals(tempDir.resolve("settings").toString(), repo.getSettingsPath());
        assertEquals(tempDir.resolve("backup").toString(), repo.getBackupPath());
        assertEquals(tempDir.resolve("books").resolve("index.json").toString(), repo.getBooksFilePath());
    }

    // --- 书籍目录 ---

    @Test
    void createBookDirectoryCreatesAndReturnsPath() {
        FileStorageRepository repo = repository();

        String dir = repo.createBookDirectory("book-1");

        assertEquals(tempDir.resolve("books").resolve("book-1").toString(), dir);
        assertTrue(Files.isDirectory(Path.of(dir)));
    }

    @Test
    void createBookDirectoryIsIdempotent() {
        FileStorageRepository repo = repository();

        String first = repo.createBookDirectory("book-1");
        String second = repo.createBookDirectory("book-1");

        assertEquals(first, second);
        assertTrue(Files.isDirectory(Path.of(second)));
    }

    @Test
    void getBookDirectorySanitizesUnsafeId() {
        FileStorageRepository repo = repository();

        String dir = repo.getBookDirectory("a/b:c*?");

        // 路径分隔符与非法字符被替换为下划线,不应逃逸出 books 目录
        Path resolved = Path.of(dir);
        assertEquals("a_b_c__", resolved.getFileName().toString());
        assertEquals(tempDir.resolve("books"), resolved.getParent());
    }

    // --- getSafeFileName ---

    @Test
    void getSafeFileNameHandlesNullAndEmpty() {
        FileStorageRepository repo = repository();

        assertEquals("unknown", repo.getSafeFileName(null));
        assertEquals("unknown", repo.getSafeFileName(""));
    }

    @Test
    void getSafeFileNameKeepsAllowedCharacters() {
        FileStorageRepository repo = repository();

        assertEquals("book_1.2-3", repo.getSafeFileName("book_1.2-3"));
    }

    @Test
    void getSafeFileNameReplacesUnicodeAndSpecialCharacters() {
        FileStorageRepository repo = repository();

        String safe = repo.getSafeFileName("第一章 测试/书:名*?");

        // 非 [a-zA-Z0-9._-] 一律替换为下划线
        assertTrue(safe.matches("[a-zA-Z0-9._-]+"), safe);
        assertEquals("_______", safe.substring(0, 7));
    }

    @Test
    void getSafeFileNameHashesOverlongNames() {
        FileStorageRepository repo = repository();

        String longName = "a".repeat(300);
        String safe = repo.getSafeFileName(longName);

        // SHA-256 十六进制长度为 64,不超过文件系统上限
        assertEquals(64, safe.length());
        assertTrue(safe.matches("[0-9a-f]{64}"), safe);
        assertNotEquals(longName, safe);
    }

    // --- getCacheFileName ---

    @Test
    void getCacheFileNameHandlesNullAndEmpty() {
        FileStorageRepository repo = repository();

        assertEquals("unknown", repo.getCacheFileName(null));
        assertEquals("unknown", repo.getCacheFileName(""));
    }

    @Test
    void getCacheFileNameEncodesUrlIntoFilesystemSafeName() {
        FileStorageRepository repo = repository();

        String name = repo.getCacheFileName("https://example.com/path?q=1");

        assertTrue(name.matches("[a-zA-Z0-9._-]+"), name);
        // 同一 URL 稳定映射
        assertEquals(name, repo.getCacheFileName("https://example.com/path?q=1"));
        // 不同 URL 映射不同
        assertNotEquals(name, repo.getCacheFileName("https://example.com/other"));
    }

    // --- clearAllStorage ---

    @Test
    void clearAllStorageEmptiesBooksCacheAndSettingsButKeepsBackup() throws Exception {
        FileStorageRepository repo = repository();
        Files.writeString(tempDir.resolve("books").resolve("index.json"), "{}");
        Files.createDirectories(tempDir.resolve("books").resolve("book-1"));
        Files.writeString(tempDir.resolve("books").resolve("book-1").resolve("details.json"), "{}");
        Files.writeString(tempDir.resolve("cache").resolve("c1"), "cached");
        Files.writeString(tempDir.resolve("settings").resolve("PluginSettings.json"), "{}");
        Files.writeString(tempDir.resolve("backup").resolve("keep.txt"), "keep");

        repo.clearAllStorage();

        assertEquals(0, countFiles(tempDir.resolve("books")));
        assertEquals(0, countFiles(tempDir.resolve("cache")));
        assertEquals(0, countFiles(tempDir.resolve("settings")));
        // 备份目录不在清理范围内
        assertTrue(Files.exists(tempDir.resolve("backup").resolve("keep.txt")));
    }

    @Test
    void clearAllStorageIsNoOpWhenDirectoriesEmpty() {
        FileStorageRepository repo = repository();

        repo.clearAllStorage();

        assertTrue(Files.isDirectory(tempDir.resolve("books")));
        assertEquals(0, countFiles(tempDir.resolve("books")));
    }

    private static long countFiles(Path dir) {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).count();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}