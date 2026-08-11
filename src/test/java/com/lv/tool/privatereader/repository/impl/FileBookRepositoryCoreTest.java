package com.lv.tool.privatereader.repository.impl;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.repository.StorageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileBookRepository 核心读写路径单元测试。
 * 覆盖现有 FileBookRepositoryTest 未触及的 addBook / updateBook / 批量更新 /
 * 缓存命中 / 索引恢复 / 排序 / 清空 等核心路径。
 */
class FileBookRepositoryCoreTest {

    @TempDir
    private Path tempDir;

    @Test
    void addBookCreatesDetailsAndIndexEntry() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        FileBookRepository repository = new FileBookRepository(storageRepository);

        Book book = new Book("book-1", "测试书籍", "作者", "https://example.com/book-1");
        book.setCachedChapters(List.of(new NovelParser.Chapter("第一章", "chapter-1")));
        repository.addBook(book);

        // details.json 已写入
        Path detailsFile = Path.of(storageRepository.getBookDirectory("book-1")).resolve("details.json");
        assertTrue(Files.exists(detailsFile), "addBook 应创建 details.json");
        String detailsJson = Files.readString(detailsFile);
        assertTrue(detailsJson.contains("测试书籍"));
        assertTrue(detailsJson.contains("book-1"));

        // index.json 已包含该书
        String indexJson = Files.readString(Path.of(storageRepository.getBooksFilePath()));
        assertTrue(indexJson.contains("book-1"));
        assertTrue(indexJson.contains("测试书籍"));
    }

    @Test
    void addBookReturnsFromCacheOnSecondGet() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        FileBookRepository repository = new FileBookRepository(storageRepository);

        Book book = new Book("book-1", "测试书籍", "作者", "https://example.com/book-1");
        book.setCachedChapters(List.of(new NovelParser.Chapter("第一章", "chapter-1")));
        repository.addBook(book);

        // 首次 getBook 从文件加载并入缓存
        Book first = repository.getBook("book-1");
        assertNotNull(first);
        assertEquals("测试书籍", first.getTitle());

        // 删除详情文件后再次 getBook：应命中缓存，不依赖文件
        Files.deleteIfExists(Path.of(storageRepository.getBookDirectory("book-1")).resolve("details.json"));
        Book cached = repository.getBook("book-1");
        assertNotNull(cached);
        assertEquals("测试书籍", cached.getTitle());
        assertEquals(1, cached.getCachedChapters().size());
    }

    @Test
    void getBookRecoversFromIndexWhenDetailsMissing() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        writeIndex(storageRepository, "book-1", "测试书籍");

        FileBookRepository repository = new FileBookRepository(storageRepository);

        // details.json 不存在，索引有记录 → 应从索引恢复
        Book recovered = repository.getBook("book-1");
        assertNotNull(recovered, "缺少 details.json 时应从索引恢复");
        assertEquals("测试书籍", recovered.getTitle());
        assertEquals("作者", recovered.getAuthor());

        // 恢复后应回写 details.json，后续可直接从文件读取
        Path detailsFile = Path.of(storageRepository.getBookDirectory("book-1")).resolve("details.json");
        assertTrue(Files.exists(detailsFile), "从索引恢复后应保存 details.json");
    }

    @Test
    void updateBookPreservesChapterListWhenInputMissing() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        FileBookRepository repository = new FileBookRepository(storageRepository);

        Book original = new Book("book-1", "测试书籍", "作者", "https://example.com/book-1");
        original.setCachedChapters(List.of(new NovelParser.Chapter("第一章", "chapter-1"),
                new NovelParser.Chapter("第二章", "chapter-2")));
        repository.addBook(original);

        // 用无章节列表的同 id 书籍更新
        Book update = new Book("book-1", "新标题", "作者", "https://example.com/book-1");
        repository.updateBook(update);

        Book updated = repository.getBook("book-1");
        assertNotNull(updated);
        assertEquals("新标题", updated.getTitle());
        assertNotNull(updated.getCachedChapters());
        assertEquals(2, updated.getCachedChapters().size(), "输入缺少章节列表时应保留已有章节");
    }

    @Test
    void getAllBooksSortedByLastReadTimeDesc() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        writeIndex(storageRepository,
                "book-old", "旧书", 100L,
                "book-new", "新书", 200L);

        FileBookRepository repository = new FileBookRepository(storageRepository);
        List<Book> books = repository.getAllBooks(false);

        assertEquals(2, books.size());
        assertEquals("book-new", books.get(0).getId(), "最近阅读的书籍应排在前面");
        assertEquals("book-old", books.get(1).getId());
    }

    @Test
    void clearAllBooksRemovesAllBooks() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        FileBookRepository repository = new FileBookRepository(storageRepository);

        repository.addBook(new Book("book-1", "测试书籍", "作者", "https://example.com/book-1"));
        repository.addBook(new Book("book-2", "测试书籍二", "作者", "https://example.com/book-2"));

        repository.clearAllBooks();

        List<Book> books = repository.getAllBooks(false);
        assertTrue(books.isEmpty(), "清空后应无书籍");
        String indexJson = Files.readString(Path.of(storageRepository.getBooksFilePath()));
        assertEquals("[]", indexJson.trim(), "清空后索引应为空数组");
        assertFalse(Files.exists(Path.of(storageRepository.getBookDirectory("book-1"))));
        assertFalse(Files.exists(Path.of(storageRepository.getBookDirectory("book-2"))));
    }

    @Test
    void updateBooksBulkUpdatesAllBooks() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        FileBookRepository repository = new FileBookRepository(storageRepository);

        repository.addBook(new Book("book-1", "测试书籍", "作者", "https://example.com/book-1"));
        repository.addBook(new Book("book-2", "测试书籍二", "作者", "https://example.com/book-2"));

        List<Book> updates = Arrays.asList(
                new Book("book-1", "新标题一", "作者", "https://example.com/book-1"),
                new Book("book-2", "新标题二", "作者", "https://example.com/book-2"));
        repository.updateBooks(updates);

        assertEquals("新标题一", repository.getBook("book-1").getTitle());
        assertEquals("新标题二", repository.getBook("book-2").getTitle());
    }

    @Test
    void addBookWithEmptyIdIsIgnored() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        FileBookRepository repository = new FileBookRepository(storageRepository);

        repository.addBook(new Book("", "空ID书籍", "作者", "https://example.com/empty"));

        assertTrue(repository.getAllBooks(false).isEmpty(), "空 ID 书籍不应被添加");
    }

    @Test
    void cleanupCorruptedBooksBacksUpJsoupSerializationGarbage() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        writeIndex(storageRepository, "corrupt-1", "损坏书籍", "clean-1", "正常书籍");
        // 损坏书籍的 details.json 包含 jsoup 序列化残留
        writeDetails(storageRepository, "corrupt-1", """
                {
                  "id": "corrupt-1",
                  "title": "损坏书籍",
                  "note": "org.jsoup.nodes.Document nodeName elementTagName parentNode"
                }
                """);
        writeDetails(storageRepository, "clean-1", """
                {
                  "id": "clean-1",
                  "title": "正常书籍",
                  "note": "normal data"
                }
                """);
        FileBookRepository repository = new FileBookRepository(storageRepository);

        int cleaned = repository.cleanupCorruptedBooks();

        assertEquals(1, cleaned, "仅损坏的书籍文件应被清理");
        Path corruptDir = Path.of(storageRepository.getBookDirectory("corrupt-1"));
        // 备份文件应已创建
        try (Stream<Path> files = Files.list(corruptDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("details.json.corrupted.")),
                    "损坏文件应被备份为 details.json.corrupted.*");
        }
    }

    @Test
    void cleanupCorruptedBooksSkipsHealthyBooks() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        writeIndex(storageRepository, "clean-1", "正常书籍");
        writeDetails(storageRepository, "clean-1", """
                {
                  "id": "clean-1",
                  "title": "正常书籍",
                  "note": "clean content"
                }
                """);
        FileBookRepository repository = new FileBookRepository(storageRepository);

        int cleaned = repository.cleanupCorruptedBooks();

        assertEquals(0, cleaned, "正常书籍不应被清理");
        Path cleanDir = Path.of(storageRepository.getBookDirectory("clean-1"));
        try (Stream<Path> files = Files.list(cleanDir)) {
            assertEquals(1, files.count(), "不应产生备份文件");
        }
    }

    private void writeIndex(FakeStorageRepository storageRepository, String... idAndTitles) throws IOException {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < idAndTitles.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"").append(idAndTitles[i]).append("\",")
                    .append("\"title\":\"").append(idAndTitles[i + 1]).append("\",")
                    .append("\"author\":\"作者\",")
                    .append("\"url\":\"https://example.com/").append(idAndTitles[i]).append("\",")
                    .append("\"createTimeMillis\":1,")
                    .append("\"lastReadTimeMillis\":0,")
                    .append("\"totalChapters\":0,")
                    .append("\"finished\":false}");
        }
        json.append(']');
        Files.createDirectories(Path.of(storageRepository.getBooksPath()));
        Files.writeString(Path.of(storageRepository.getBooksFilePath()), json.toString());
    }

    private void writeIndex(FakeStorageRepository storageRepository,
                            String id1, String title1, long time1,
                            String id2, String title2, long time2) throws IOException {
        String json = "["
                + "{\"id\":\"" + id1 + "\",\"title\":\"" + title1 + "\","
                + "\"author\":\"作者\",\"url\":\"https://example.com/" + id1 + "\","
                + "\"createTimeMillis\":1,\"lastReadTimeMillis\":" + time1 + ","
                + "\"totalChapters\":0,\"finished\":false}"
                + ",{\"id\":\"" + id2 + "\",\"title\":\"" + title2 + "\","
                + "\"author\":\"作者\",\"url\":\"https://example.com/" + id2 + "\","
                + "\"createTimeMillis\":1,\"lastReadTimeMillis\":" + time2 + ","
                + "\"totalChapters\":0,\"finished\":false}"
                + "]";
        Files.createDirectories(Path.of(storageRepository.getBooksPath()));
        Files.writeString(Path.of(storageRepository.getBooksFilePath()), json);
    }

    private void writeDetails(FakeStorageRepository storageRepository, String bookId, String json) throws IOException {
        Path bookDir = Path.of(storageRepository.createBookDirectory(bookId));
        Files.writeString(bookDir.resolve("details.json"), json);
    }

    private static class FakeStorageRepository implements StorageRepository {
        private final Path basePath;
        private final Path booksPath;

        private FakeStorageRepository(Path basePath) {
            this.basePath = basePath;
            this.booksPath = basePath.resolve("books");
        }

        @Override
        public String getBaseStoragePath() {
            return basePath.toString();
        }

        @Override
        public String getBooksPath() {
            return booksPath.toString();
        }

        @Override
        public String getCachePath() {
            return basePath.resolve("cache").toString();
        }

        @Override
        public String getSettingsPath() {
            return basePath.resolve("settings").toString();
        }

        @Override
        public String getBackupPath() {
            return basePath.resolve("backup").toString();
        }

        @Override
        public String getBooksFilePath() {
            return booksPath.resolve("index.json").toString();
        }

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

        @Override
        public String getBookDirectory(String bookId) {
            return booksPath.resolve(bookId).toString();
        }

        @Override
        public void clearAllStorage() {
        }

        @Override
        public String getSafeFileName(String fileName) {
            return fileName;
        }

        @Override
        public String getCacheFileName(String url) {
            return url;
        }
    }
}
