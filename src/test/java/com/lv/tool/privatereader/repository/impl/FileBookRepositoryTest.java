package com.lv.tool.privatereader.repository.impl;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.repository.StorageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileBookRepositoryTest {

    @TempDir
    private Path tempDir;

    @Test
    void getBookRestoresProgressDefaultsFromDetailsJson() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        writeIndex(storageRepository, "book-1", "测试书籍");
        writeDetails(storageRepository, "book-1", """
                {
                  "id": "book-1",
                  "title": "测试书籍",
                  "author": "作者",
                  "url": "https://example.com/book-1",
                  "lastReadChapterId": "chapter-1",
                  "lastReadPosition": "bad-position",
                  "lastReadPage": "bad-page",
                  "cachedChapters": [
                    {"title": "第一章", "url": "chapter-1"}
                  ]
                }
                """);
        FileBookRepository repository = new FileBookRepository(storageRepository);

        Book book = repository.getBook("book-1");

        assertNotNull(book);
        assertEquals("chapter-1", book.getLastReadChapterId());
        assertEquals(0, book.getLastReadPosition());
        assertEquals(1, book.getLastReadPage());
        assertEquals(1, book.getCurrentChapterIndex());
    }

    @Test
    void getBookFallsBackToEmptyChaptersWhenCachedChaptersIsInvalid() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        writeIndex(storageRepository, "book-1", "测试书籍");
        writeDetails(storageRepository, "book-1", """
                {
                  "id": "book-1",
                  "title": "测试书籍",
                  "author": "作者",
                  "url": "",
                  "cachedChapters": "invalid"
                }
                """);
        FileBookRepository repository = new FileBookRepository(storageRepository);

        Book book = repository.getBook("book-1");

        assertNotNull(book);
        assertNotNull(book.getCachedChapters());
        assertTrue(book.getCachedChapters().isEmpty());
    }

    @Test
    void removeBookDeletesBookDirectoryAndRemovesIndexEntry() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        writeIndex(storageRepository, "book-1", "保留书籍", "book-2", "删除书籍");
        writeDetails(storageRepository, "book-2", """
                {
                  "id": "book-2",
                  "title": "删除书籍",
                  "author": "作者",
                  "url": "https://example.com/book-2",
                  "cachedChapters": []
                }
                """);
        FileBookRepository repository = new FileBookRepository(storageRepository);

        repository.removeBook(new Book("book-2", "删除书籍", "作者", "https://example.com/book-2"));

        assertFalse(Files.exists(Path.of(storageRepository.getBookDirectory("book-2"))));
        String indexJson = Files.readString(Path.of(storageRepository.getBooksFilePath()));
        assertTrue(indexJson.contains("book-1"));
        assertFalse(indexJson.contains("book-2"));
    }

    @Test
    void getAllBooksWithoutDetailsDoesNotRepairBookFiles() throws IOException {
        FakeStorageRepository storageRepository = new FakeStorageRepository(tempDir);
        writeIndex(storageRepository, "book-1", "测试书籍");
        String originalDetails = """
                {
                  "id": "book-1",
                  "title": "测试书籍",
                  "author": "作者",
                  "url": "https://example.com/book-1",
                  "note": "org.jsoup.parser"
                }
                """;
        writeDetails(storageRepository, "book-1", originalDetails);
        FileBookRepository repository = new FileBookRepository(storageRepository);

        List<Book> books = repository.getAllBooks(false);

        assertEquals(1, books.size());
        Path bookDir = Path.of(storageRepository.getBookDirectory("book-1"));
        assertEquals(originalDetails, Files.readString(bookDir.resolve("details.json")));
        try (Stream<Path> files = Files.list(bookDir)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().startsWith("details.json.corrupted.")).count());
        }
    }

    private void writeDetails(FakeStorageRepository storageRepository, String bookId, String json) throws IOException {
        Path bookDir = Path.of(storageRepository.createBookDirectory(bookId));
        Files.writeString(bookDir.resolve("details.json"), json);
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
