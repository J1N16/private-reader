package com.lv.tool.privatereader.storage;

import com.intellij.ide.util.PropertiesComponent;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import com.lv.tool.privatereader.repository.StorageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DatabaseManager 核心行为测试(V22 可测性改造)。
 *
 * <p>覆盖此前无法测的路径:
 * <ul>
 *   <li>构造器按注入 URL 建库并初始化表结构;{@code getConnection} 返回可用连接</li>
 *   <li>{@code dispose} 为空实现(短连接方案),调用不抛异常</li>
 *   <li>JSON → SQLite 一次性迁移:有效条目写入、无时间戳跳过、目录不存在早退、
 *       已完成标记短路、目录名与 JSON id 不一致时以 JSON 为准</li>
 * </ul>
 *
 * <p>隔离:包级构造器注入 {@code @TempDir} 下的独立 dbUrl(不再依赖 IntelliJ 容器与
 * 全局静态 flag);迁移逻辑走包级重载,依赖(StorageRepository/ReadingProgressRepository/
 * PropertiesComponent)以 mock 注入。仅覆盖非 {@code LOG.error} 分支,避免测试环境
 * 断言失败(见踩坑记录)。</p>
 */
class DatabaseManagerCoreTest {

    private static final String MIGRATION_KEY = "private.reader.migration.sqlite.v1.complete";

    @TempDir
    Path tempDir;

    private String dbUrl(String name) {
        return "jdbc:sqlite:" + tempDir.resolve(name).toAbsolutePath();
    }

    // --- 构造 / 连接 / 释放 ---

    @Test
    void constructorInitializesTableForInjectedUrl() throws Exception {
        DatabaseManager manager = new DatabaseManager(dbUrl("init.db"));

        try (Connection connection = manager.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(reading_progress)")) {

            boolean sawBookId = false;
            while (rs.next()) {
                if ("book_id".equalsIgnoreCase(rs.getString("name"))) {
                    sawBookId = true;
                }
            }
            assertTrue(sawBookId, "构造器应初始化 reading_progress 表");
        }
    }

    @Test
    void getConnectionReturnsUsableAutoCommitConnection() throws Exception {
        DatabaseManager manager = new DatabaseManager(dbUrl("conn.db"));

        try (Connection connection = manager.getConnection()) {
            assertNotNull(connection);
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test
    void disposeIsNoOpForShortConnectionScheme() {
        DatabaseManager manager = new DatabaseManager(dbUrl("dispose.db"));
        manager.dispose();
        manager.dispose();
    }

    // --- 迁移:核心逻辑(包级重载) ---

    private StorageRepository storageRepository(Path booksPath) {
        StorageRepository storageRepository = mock(StorageRepository.class);
        when(storageRepository.getBooksPath()).thenReturn(booksPath.toString());
        return storageRepository;
    }

    private PropertiesComponent freshProperties() {
        PropertiesComponent properties = mock(PropertiesComponent.class);
        when(properties.isValueSet(MIGRATION_KEY)).thenReturn(false);
        return properties;
    }

    private Path writeDetails(String bookId, String json) throws Exception {
        Path bookDir = tempDir.resolve("books").resolve(bookId);
        Files.createDirectories(bookDir);
        Files.writeString(bookDir.resolve("book_details.json"), json, StandardCharsets.UTF_8);
        return bookDir;
    }

    @Test
    void migrationMigratesValidEntryAndSetsCompletionFlag() throws Exception {
        writeDetails("book-1", """
                {
                  "id": "book-1",
                  "lastReadChapterId": "chapter-3",
                  "lastReadChapterTitle": "第三章",
                  "lastReadPosition": 64,
                  "lastReadPage": 2,
                  "isFinished": false,
                  "lastReadTimestamp": 1700000000000
                }
                """);

        StorageRepository storageRepository = storageRepository(tempDir.resolve("books"));
        ReadingProgressRepository progressRepository = mock(ReadingProgressRepository.class);
        PropertiesComponent properties = freshProperties();

        DatabaseManager manager = new DatabaseManager(dbUrl("migrate.db"));
        manager.runMigrationIfNeeded(properties, storageRepository, progressRepository);

        ArgumentCaptor<Book> bookCaptor = ArgumentCaptor.forClass(Book.class);
        verify(progressRepository).updateProgress(bookCaptor.capture(), eq("chapter-3"),
                eq("第三章"), eq(64), eq(2));
        assertEquals("book-1", bookCaptor.getValue().getId());
        verify(properties).setValue(MIGRATION_KEY, true);
    }

    @Test
    void migrationUsesJsonIdWhenDirectoryNameDiffers() throws Exception {
        writeDetails("dir-name", """
                {
                  "id": "json-id",
                  "lastReadChapterId": "c1",
                  "lastReadTimestamp": 1700000000000
                }
                """);

        ReadingProgressRepository progressRepository = mock(ReadingProgressRepository.class);
        PropertiesComponent properties = freshProperties();

        DatabaseManager manager = new DatabaseManager(dbUrl("migrate-id.db"));
        manager.runMigrationIfNeeded(properties, storageRepository(tempDir.resolve("books")),
                progressRepository);

        ArgumentCaptor<Book> bookCaptor = ArgumentCaptor.forClass(Book.class);
        verify(progressRepository).updateProgress(bookCaptor.capture(), eq("c1"),
                isNull(), anyInt(), anyInt());
        assertEquals("json-id", bookCaptor.getValue().getId(), "应以 JSON 中的 id 为准");
    }

    @Test
    void migrationSkipsEntryWithoutTimestampButStillCompletes() throws Exception {
        writeDetails("book-1", """
                {"id": "book-1", "lastReadChapterId": "chapter-1"}
                """);

        ReadingProgressRepository progressRepository = mock(ReadingProgressRepository.class);
        PropertiesComponent properties = freshProperties();

        DatabaseManager manager = new DatabaseManager(dbUrl("migrate-skip.db"));
        manager.runMigrationIfNeeded(properties, storageRepository(tempDir.resolve("books")),
                progressRepository);

        verify(progressRepository, never()).updateProgress(org.mockito.ArgumentMatchers.any(),
                anyString(), anyString(), anyInt(), anyInt());
        // 无错误 → 视为迁移完成
        verify(properties).setValue(MIGRATION_KEY, true);
    }

    @Test
    void migrationSkipsWhenCompletionFlagAlreadySet() {
        ReadingProgressRepository progressRepository = mock(ReadingProgressRepository.class);
        PropertiesComponent properties = mock(PropertiesComponent.class);
        when(properties.isValueSet(MIGRATION_KEY)).thenReturn(true);

        DatabaseManager manager = new DatabaseManager(dbUrl("migrate-done.db"));
        manager.runMigrationIfNeeded(properties, storageRepository(tempDir.resolve("books")),
                progressRepository);

        verify(progressRepository, never()).updateProgress(org.mockito.ArgumentMatchers.any(),
                anyString(), anyString(), anyInt(), anyInt());
        verify(properties, never()).setValue(anyString(), eq(true));
    }

    @Test
    void migrationReturnsEarlyWhenBooksPathMissing() {
        ReadingProgressRepository progressRepository = mock(ReadingProgressRepository.class);
        PropertiesComponent properties = freshProperties();

        DatabaseManager manager = new DatabaseManager(dbUrl("migrate-nopath.db"));
        manager.runMigrationIfNeeded(properties, storageRepository(tempDir.resolve("does-not-exist")),
                progressRepository);

        verify(progressRepository, never()).updateProgress(org.mockito.ArgumentMatchers.any(),
                anyString(), anyString(), anyInt(), anyInt());
        // 源路径不存在时设置标记的行为保持原状(不设置,下次重试)
        verify(properties, never()).setValue(anyString(), eq(true));
    }
}