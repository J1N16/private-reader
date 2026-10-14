package com.lv.tool.privatereader.repository.impl;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.repository.StorageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileBookRepository 阅读位置恢复 / 修复路径测试(V24)。
 *
 * <p>补齐 {@code FileBookRepository}(51.8%)中未覆盖的分支:
 * <ul>
 *   <li>{@code getBook} 对 null/空 id 短路</li>
 *   <li>{@code restoreLastReadingPosition}:由 lastReadChapter 标题回填章节 id、
 *       由 lastReadChapterId 回填章节索引、负数位置归零、非法页码归 1、缺失时间补全</li>
 *   <li>{@code repairMissingReadingContent}:索引有效但阅读位置需修复的书籍被修复并回写</li>
 *   <li>{@code getAllBooks(true)} 加载详情</li>
 * </ul>
 *
 * <p>约定:所有详情文件的 {@code url} 置空,以避免触发需要 IntelliJ 容器的
 * 章节网络补充分支(该分支会走 LOG.error 导致测试断言失败)。</p>
 */
class FileBookRepositoryRepairTest {

    @TempDir
    Path tempDir;

    private FakeStorageRepository storage() {
        return new FakeStorageRepository(tempDir);
    }

    // --- getBook 短路 ---

    @Test
    void getBookReturnsNullForNullOrEmptyId() {
        FileBookRepository repository = new FileBookRepository(storage());
        assertNull(repository.getBook(null));
        assertNull(repository.getBook(""));
    }

    // --- restoreLastReadingPosition 各分支(经 getBook 触发) ---

    @Test
    void restoresChapterIdFromTitleWhenIdMissing() throws IOException {
        FakeStorageRepository storageRepository = storage();
        writeIndex(storageRepository, "book-1", "测试书籍");
        writeDetails(storageRepository, "book-1", """
                {
                  "id": "book-1",
                  "title": "测试书籍",
                  "author": "作者",
                  "url": "",
                  "lastReadChapter": "第二章",
                  "cachedChapters": [
                    {"title": "第一章", "url": "chapter-1"},
                    {"title": "第二章", "url": "chapter-2"}
                  ]
                }
                """);

        Book book = new FileBookRepository(storageRepository).getBook("book-1");

        assertNotNull(book);
        assertEquals("chapter-2", book.getLastReadChapterId(), "应由标题回填章节 id");
        assertEquals(2, book.getCurrentChapterIndex(), "应由章节 id 回填 1-based 索引");
    }

    @Test
    void resetsNegativePositionAndInvalidPage() throws IOException {
        FakeStorageRepository storageRepository = storage();
        writeIndex(storageRepository, "book-1", "测试书籍");
        writeDetails(storageRepository, "book-1", """
                {
                  "id": "book-1",
                  "title": "测试书籍",
                  "author": "作者",
                  "url": "",
                  "lastReadChapterId": "chapter-1",
                  "lastReadPosition": -5,
                  "lastReadPage": 0,
                  "cachedChapters": [{"title": "第一章", "url": "chapter-1"}]
                }
                """);

        Book book = new FileBookRepository(storageRepository).getBook("book-1");

        assertNotNull(book);
        assertEquals(0, book.getLastReadPosition(), "负数位置应归零");
        assertEquals(1, book.getLastReadPage(), "非法页码应归 1");
    }

    @Test
    void fillsMissingLastReadTime() throws IOException {
        FakeStorageRepository storageRepository = storage();
        writeIndex(storageRepository, "book-1", "测试书籍");
        writeDetails(storageRepository, "book-1", """
                {
                  "id": "book-1",
                  "title": "测试书籍",
                  "author": "作者",
                  "url": "",
                  "lastReadTimeMillis": 0,
                  "cachedChapters": [{"title": "第一章", "url": "chapter-1"}]
                }
                """);

        Book book = new FileBookRepository(storageRepository).getBook("book-1");

        assertNotNull(book);
        assertTrue(book.getLastReadTimeMillis() > 0, "缺失的最后阅读时间应被补全");
    }

    @Test
    void keepsExistingChapterIndexWhenAlreadySet() throws IOException {
        FakeStorageRepository storageRepository = storage();
        writeIndex(storageRepository, "book-1", "测试书籍");
        writeDetails(storageRepository, "book-1", """
                {
                  "id": "book-1",
                  "title": "测试书籍",
                  "author": "作者",
                  "url": "",
                  "lastReadChapterId": "chapter-2",
                  "currentChapterIndex": 7,
                  "cachedChapters": [
                    {"title": "第一章", "url": "chapter-1"},
                    {"title": "第二章", "url": "chapter-2"}
                  ]
                }
                """);

        Book book = new FileBookRepository(storageRepository).getBook("book-1");

        assertNotNull(book);
        assertEquals(7, book.getCurrentChapterIndex(), "已有有效索引不应被覆盖");
    }

    // --- repairMissingReadingContent ---

    @Test
    void repairMissingReadingContentFixesInvalidBooksAndPersists() throws IOException {
        FakeStorageRepository storageRepository = storage();
        writeIndex(storageRepository, "book-1", "待修复书籍");
        // lastReadChapterId/lastReadChapter 均有值但 currentChapterIndex=0 且无章节 → 需修复
        // url 置空避免触发章节网络补充
        writeDetails(storageRepository, "book-1", """
                {
                  "id": "book-1",
                  "title": "待修复书籍",
                  "author": "作者",
                  "url": "",
                  "lastReadChapterId": "chapter-1",
                  "lastReadChapter": "第一章",
                  "currentChapterIndex": 0,
                  "lastReadPosition": -3,
                  "cachedChapters": []
                }
                """);

        FileBookRepository repository = new FileBookRepository(storageRepository);
        int repaired = repository.repairMissingReadingContent();

        assertEquals(1, repaired, "存在无效阅读位置的书籍应被修复");
        // 修复后应回写 details.json(lastReadPosition 归零)
        String details = Files.readString(
                Path.of(storageRepository.getBookDirectory("book-1")).resolve("details.json"));
        assertTrue(details.contains("\"lastReadPosition\":0") || details.contains("\"lastReadPosition\": 0"),
                "修复后应回写归零的位置,实际: " + details);
    }

    @Test
    void repairMissingReadingContentReturnsZeroForHealthyBooks() throws IOException {
        FakeStorageRepository storageRepository = storage();
        writeIndex(storageRepository, "book-1", "正常书籍");
        writeDetails(storageRepository, "book-1", """
                {
                  "id": "book-1",
                  "title": "正常书籍",
                  "author": "作者",
                  "url": "",
                  "lastReadChapterId": "chapter-1",
                  "lastReadChapter": "第一章",
                  "currentChapterIndex": 1,
                  "lastReadPosition": 10,
                  "cachedChapters": [{"title": "第一章", "url": "chapter-1"}]
                }
                """);

        FileBookRepository repository = new FileBookRepository(storageRepository);

        assertEquals(0, repository.repairMissingReadingContent(), "健康书籍无需修复");
    }

    @Test
    void repairMissingReadingContentReturnsZeroWhenNoBooks() {
        FileBookRepository repository = new FileBookRepository(storage());

        assertEquals(0, repository.repairMissingReadingContent());
    }

    // --- getAllBooks 详情加载 ---

    @Test
    void getAllBooksWithDetailsLoadsCachedChapters() throws IOException {
        FakeStorageRepository storageRepository = storage();
        writeIndex(storageRepository, "book-1", "测试书籍");
        writeDetails(storageRepository, "book-1", """
                {
                  "id": "book-1",
                  "title": "测试书籍",
                  "author": "作者",
                  "url": "",
                  "cachedChapters": [
                    {"title": "第一章", "url": "chapter-1"},
                    {"title": "第二章", "url": "chapter-2"}
                  ]
                }
                """);

        List<Book> books = new FileBookRepository(storageRepository).getAllBooks(true);

        assertEquals(1, books.size());
        assertNotNull(books.get(0).getCachedChapters());
        assertEquals(2, books.get(0).getCachedChapters().size());
    }

    // --- helpers ---

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