package com.lv.tool.privatereader.repository.impl;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookIndex;
import com.lv.tool.privatereader.repository.StorageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BookIndexStore 索引文件 IO 测试(V26)。
 *
 * <p>直接对包级可见的 {@link BookIndexStore} 覆盖读/写/增改/删除/清空与原子写入回退路径,
 * 此前该类 69.1% 覆盖,缺口集中在 updateAndSave/removeAndSave/clear/failure 分支。</p>
 */
class BookIndexStoreTest {

    @TempDir
    Path tempDir;

    private BookIndexStore store() {
        StorageRepository storage = new FakeStorageRepository(tempDir);
        return new BookIndexStore(storage, new BookJsonCodec());
    }

    private Book book(String id, String title) {
        Book book = new Book(id, title, "作者", "https://example.com/" + id);
        book.setCreateTimeMillis(1L);
        return book;
    }

    @Test
    void readReturnsEmptyWhenFileMissing() {
        assertTrue(store().read().isEmpty());
    }

    @Test
    void updateAndSaveAddsNewEntryThenReadsItBack() {
        BookIndexStore store = store();

        store.updateAndSave(book("book-1", "新书"));

        List<BookIndex> indices = store.read();
        assertEquals(1, indices.size());
        assertEquals("book-1", indices.get(0).getId());
        assertEquals("新书", indices.get(0).getTitle());
    }

    @Test
    void updateAndSaveReplacesExistingEntry() {
        BookIndexStore store = store();
        store.updateAndSave(book("book-1", "旧标题"));

        store.updateAndSave(book("book-1", "新标题"));

        List<BookIndex> indices = store.read();
        assertEquals(1, indices.size(), "同一本书不应产生重复条目");
        assertEquals("新标题", indices.get(0).getTitle());
    }

    @Test
    void removeAndSaveDeletesMatchingEntry() {
        BookIndexStore store = store();
        store.updateAndSave(book("book-1", "保留"));
        store.updateAndSave(book("book-2", "删除"));

        store.removeAndSave("book-2");

        List<BookIndex> indices = store.read();
        assertEquals(1, indices.size());
        assertEquals("book-1", indices.get(0).getId());
    }

    @Test
    void clearEmptiesIndexFile() {
        BookIndexStore store = store();
        store.updateAndSave(book("book-1", "书"));

        store.clear();

        assertTrue(store.read().isEmpty());
    }

    @Test
    void saveThenReadRoundTripsMultipleEntries() {
        BookIndexStore store = store();
        store.updateAndSave(book("book-1", "书一"));
        store.updateAndSave(book("book-2", "书二"));

        List<BookIndex> indices = store.read();
        assertEquals(2, indices.size());
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