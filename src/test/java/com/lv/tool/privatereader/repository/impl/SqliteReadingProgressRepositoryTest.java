package com.lv.tool.privatereader.repository.impl;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookProgressData;
import com.lv.tool.privatereader.storage.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SqliteReadingProgressRepository 单元测试
 * 覆盖：进度保存/读取 round-trip、UPSERT 覆盖、重置、完成标记、最近阅读查询
 *
 * <p>使用 @TempDir 创建真实 SQLite 文件，mock DatabaseManager 返回对应连接。
 */
class SqliteReadingProgressRepositoryTest {

    @TempDir
    private Path tempDir;

    /** 跟踪所有打开的连接，@AfterEach 统一关闭，避免 Windows 上锁住数据库文件 */
    private final List<Connection> openConnections = new ArrayList<>();

    private SqliteReadingProgressRepository repository;
    private DatabaseManager databaseManager;

    @BeforeEach
    void setUp() throws Exception {
        // 建表：与 DatabaseManager.initializeDatabaseTableStructure 的结构一致
        try (Connection connection = openNewConnection()) {
            connection.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS reading_progress (
                        book_id TEXT PRIMARY KEY NOT NULL,
                        last_read_chapter_id TEXT,
                        last_read_chapter_title TEXT,
                        last_read_position INTEGER DEFAULT 0,
                        last_read_page INTEGER DEFAULT 1,
                        is_finished INTEGER DEFAULT 0,
                        last_read_timestamp INTEGER NOT NULL
                    );
                    """);
        }

        databaseManager = mock(DatabaseManager.class);
        when(databaseManager.getConnection()).thenAnswer(invocation -> openNewConnection());

        repository = new SqliteReadingProgressRepository(databaseManager);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Connection conn : openConnections) {
            try {
                conn.close();
            } catch (Exception ignored) {
                // 已关闭则忽略
            }
        }
        openConnections.clear();
    }

    private Connection openNewConnection() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("progress.db"));
        openConnections.add(conn);
        return conn;
    }

    private Book book(String id, String title) {
        Book book = new Book(id, title, "作者", "https://example.com/" + id);
        return book;
    }

    // --- 保存 / 读取 round-trip ---

    @Test
    void updateProgressThenGetProgressReturnsSavedData() {
        Book book = book("book-1", "测试书籍");
        repository.updateProgress(book, "chapter-2", "第二章", 128, 3);

        Optional<BookProgressData> result = repository.getProgress("book-1");

        assertTrue(result.isPresent());
        BookProgressData data = result.get();
        assertEquals("book-1", data.bookId());
        assertEquals("chapter-2", data.lastReadChapterId());
        assertEquals("第二章", data.lastReadChapterTitle());
        assertEquals(128, data.lastReadPosition());
        assertEquals(3, data.lastReadPage());
        assertFalse(data.isFinished());
        assertTrue(data.lastReadTimestamp() > 0);
    }

    @Test
    void updateProgressPersistsFinishedFlag() {
        Book book = book("book-1", "测试书籍");
        book.setFinished(true);
        repository.updateProgress(book, "chapter-9", "第九章", 50, 1);

        Optional<BookProgressData> result = repository.getProgress("book-1");

        assertTrue(result.isPresent());
        assertTrue(result.get().isFinished());
    }

    @Test
    void updateProgressAllowsNullChapterFields() {
        Book book = book("book-1", "测试书籍");
        repository.updateProgress(book, null, null, 10, 1);

        Optional<BookProgressData> result = repository.getProgress("book-1");

        assertTrue(result.isPresent());
        assertEquals(null, result.get().lastReadChapterId());
        assertEquals(null, result.get().lastReadChapterTitle());
    }

    @Test
    void updateProgressUpsertsSameBookInsteadOfDuplicating() {
        Book book = book("book-1", "测试书籍");
        repository.updateProgress(book, "chapter-1", "第一章", 10, 1);
        repository.updateProgress(book, "chapter-2", "第二章", 20, 2);

        Optional<BookProgressData> result = repository.getProgress("book-1");

        assertTrue(result.isPresent());
        assertEquals("chapter-2", result.get().lastReadChapterId());
        assertEquals(20, result.get().lastReadPosition());
        assertEquals(2, result.get().lastReadPage());
    }

    // --- 查询 ---

    @Test
    void getProgressReturnsEmptyForUnknownBook() {
        assertTrue(repository.getProgress("missing-book").isEmpty());
    }

    @Test
    void getLastReadProgressDataReturnsMostRecentlyUpdated() throws Exception {
        Book book1 = book("book-1", "书籍一");
        repository.updateProgress(book1, "chapter-1", "第一章", 10, 1);
        Thread.sleep(5); // 确保时间戳递增
        Book book2 = book("book-2", "书籍二");
        repository.updateProgress(book2, "chapter-5", "第五章", 99, 2);

        Optional<BookProgressData> result = repository.getLastReadProgressData();

        assertTrue(result.isPresent());
        assertEquals("book-2", result.get().bookId());
    }

    // --- 重置 ---

    @Test
    void resetProgressDeletesBookRow() {
        Book book = book("book-1", "测试书籍");
        repository.updateProgress(book, "chapter-1", "第一章", 10, 1);

        repository.resetProgress(book);

        assertTrue(repository.getProgress("book-1").isEmpty());
    }

    // --- 完成标记 ---

    @Test
    void markAsFinishedSetsAndPersistsFinished() {
        Book book = book("book-1", "测试书籍");
        repository.updateProgress(book, "chapter-1", "第一章", 10, 1);

        repository.markAsFinished(book);

        assertTrue(book.isFinished());
        Optional<BookProgressData> result = repository.getProgress("book-1");
        assertTrue(result.isPresent());
        assertTrue(result.get().isFinished());
    }

    @Test
    void markAsUnfinishedClearsFinishedFlag() {
        Book book = book("book-1", "测试书籍");
        book.setFinished(true);
        repository.updateProgress(book, "chapter-1", "第一章", 10, 1);

        repository.markAsUnfinished(book);

        assertFalse(book.isFinished());
        Optional<BookProgressData> result = repository.getProgress("book-1");
        assertTrue(result.isPresent());
        assertFalse(result.get().isFinished());
    }
}
