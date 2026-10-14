package com.lv.tool.privatereader.repository.impl;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.repository.StorageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BookDetailsIo 详情文件 IO 测试(V26)。
 *
 * <p>覆盖包级可见的 {@link BookDetailsIo}:读取(存在/缺失)、保存(成功/回退)、
 * 损坏备份、整目录删除。此前该类 64.6% 覆盖。</p>
 */
class BookDetailsIoTest {

    @TempDir
    Path tempDir;

    private BookDetailsIo io() {
        return new BookDetailsIo(new FakeStorageRepository(tempDir), new BookJsonCodec());
    }

    private Book book(String id, String title) {
        return new Book(id, title, "作者", "https://example.com/" + id);
    }

    @Test
    void readDetailsReturnsNullWhenMissing() {
        assertNull(io().readDetails("book-1"));
    }

    @Test
    void saveThenReadRoundTripsDetails() {
        BookDetailsIo io = io();
        Book book = book("book-1", "测试书籍");
        book.setCachedChapters(java.util.List.of(new com.lv.tool.privatereader.parser.NovelParser.Chapter("第一章", "c1")));

        io.saveDetails(book);

        String json = io.readDetails("book-1");
        assertTrue(json != null && json.contains("测试书籍"));
        assertTrue(json.contains("第一章"));
    }

    @Test
    void saveDetailsOnExistingFileReplacesContent() {
        BookDetailsIo io = io();
        io.saveDetails(book("book-1", "旧标题"));
        io.saveDetails(book("book-1", "新标题"));

        String json = io.readDetails("book-1");
        assertTrue(json.contains("新标题"));
        assertFalse(json.contains("旧标题"));
    }

    @Test
    void backupCorruptedCreatesTimestampedCopy() throws IOException {
        BookDetailsIo io = io();
        io.saveDetails(book("book-1", "受损书籍"));

        io.backupCorrupted("book-1");

        Path bookDir = tempDir.resolve("books").resolve("book-1");
        try (var files = Files.list(bookDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("details.json.corrupted.")));
        }
    }

    @Test
    void backupCorruptedOnMissingFileIsNoOp() throws IOException {
        BookDetailsIo io = io();
        Path bookDir = tempDir.resolve("books").resolve("no-such-book");
        Files.createDirectories(bookDir);

        io.backupCorrupted("no-such-book");

        try (var files = Files.list(bookDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void deleteBookDirectoryRemovesEverythingIncludingBackups() throws IOException {
        BookDetailsIo io = io();
        io.saveDetails(book("book-1", "书籍"));
        io.backupCorrupted("book-1");
        Path bookDir = tempDir.resolve("books").resolve("book-1");
        assertTrue(Files.exists(bookDir));

        io.deleteBookDirectory("book-1");

        assertFalse(Files.exists(bookDir), "整本书目录应被递归删除");
    }

    private static class FakeStorageRepository implements StorageRepository {
        private final Path basePath;
        private final Path booksPath;

        private FakeStorageRepository(Path basePath) {
            this.basePath = basePath;
            this.booksPath = basePath.resolve("books");
        }

        @Override public String getBaseStoragePath() { return basePath.toString(); }
        @Override public String getBooksPath() { return booksPath.toString(); }
        @Override public String getCachePath() { return basePath.resolve("cache").toString(); }
        @Override public String getSettingsPath() { return basePath.resolve("settings").toString(); }
        @Override public String getBackupPath() { return basePath.resolve("backup").toString(); }
        @Override public String getBooksFilePath() { return booksPath.resolve("index.json").toString(); }

        @Override
        public String createBookDirectory(String bookId) {
            Path bookDir = booksPath.resolve(bookId);
            try {
                Files.createDirectories(bookDir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return bookDir.toString();
        }

        @Override public String getBookDirectory(String bookId) { return booksPath.resolve(bookId).toString(); }
        @Override public void clearAllStorage() { }
        @Override public String getSafeFileName(String fileName) { return fileName; }
        @Override public String getCacheFileName(String url) { return url; }
    }
}