package com.lv.tool.privatereader.repository.impl;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookProgressData;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import com.lv.tool.privatereader.storage.DatabaseManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Implementation of ReadingProgressRepository using SQLite for persistent storage.
 * Handles reading and writing progress data to a shared database file.
 */
@Service(Service.Level.APP)
public final class SqliteReadingProgressRepository implements ReadingProgressRepository {
    private static final Logger LOG = Logger.getInstance(SqliteReadingProgressRepository.class);
    private final DatabaseManager databaseManager;

    /**
     * 上次阅读时间的人类可读格式(本地时间),例如 "2026-01-01 12:30:45.123"。
     * 不使用 epoch 毫秒时间戳,便于直接查看数据库。
     */
    private static final DateTimeFormatter LAST_READ_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // Using UPSERT (ON CONFLICT DO UPDATE) for SQLite
    // Ensure book_id is the primary key or has a unique index for this to work correctly.
    private static final String UPSERT_PROGRESS_SQL = """
            INSERT INTO reading_progress (
                book_id, book_title, last_read_chapter_id, last_read_chapter_title,
                last_read_position, last_read_page, is_finished, last_read_time
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(book_id) DO UPDATE SET
                book_title = excluded.book_title,
                last_read_chapter_id = excluded.last_read_chapter_id,
                last_read_chapter_title = excluded.last_read_chapter_title,
                last_read_position = excluded.last_read_position,
                last_read_page = excluded.last_read_page,
                is_finished = excluded.is_finished,
                last_read_time = excluded.last_read_time;
            """;

    private static final String GET_PROGRESS_SQL = """
            SELECT book_id, book_title, last_read_chapter_id, last_read_chapter_title,
                   last_read_position, last_read_page, is_finished, last_read_time
            FROM reading_progress
            WHERE book_id = ?;
            """;

    private static final String GET_LAST_READ_SQL = """
            SELECT book_id, book_title, last_read_chapter_id, last_read_chapter_title,
                   last_read_position, last_read_page, is_finished, last_read_time
            FROM reading_progress
            ORDER BY last_read_time DESC
            LIMIT 1;
            """;

    private static final String DELETE_PROGRESS_SQL = """
            DELETE FROM reading_progress WHERE book_id = ?;
            """;

    public SqliteReadingProgressRepository() {
        // Obtain the application-level service instance
        this(DatabaseManager.getInstance());
    }

    /**
     * 包级私有构造:允许测试注入 mock 的 DatabaseManager。
     */
    SqliteReadingProgressRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    // --- Implementation of ReadingProgressRepository --- //

    @Override
    public void updateProgress(@NotNull Book book, @Nullable String chapterId, @Nullable String chapterTitle, int position, int page) {
        String currentTime = LocalDateTime.now().format(LAST_READ_TIME_FORMATTER);
        LOG.debug(String.format("Updating progress in SQLite: Book='%s'(ID:%s), ChapterID=%s, Title='%s', Pos=%d, Page=%d, Finished=%b, Time=%s",
                book.getTitle(), book.getId(), chapterId, chapterTitle, position, page, book.isFinished(), currentTime));

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(UPSERT_PROGRESS_SQL)) {

            pstmt.setString(1, book.getId());
            pstmt.setString(2, book.getTitle());
            pstmt.setString(3, chapterId);
            pstmt.setString(4, chapterTitle);
            pstmt.setInt(5, position);
            pstmt.setInt(6, page);
            pstmt.setInt(7, book.isFinished() ? 1 : 0); // Store boolean as integer
            pstmt.setString(8, currentTime); // Store human-readable time as TEXT

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                LOG.debug("Successfully upserted progress for book: " + book.getId());
            } else {
                // This might happen if the insert/update didn't change anything, which is unlikely with timestamp
                LOG.warn("Upsert progress command affected 0 rows for book: " + book.getId());
            }
        } catch (SQLException e) {
            LOG.error("Failed to update reading progress in SQLite for book: " + book.getId(), e);
            // Consider re-throwing as a custom exception or handling it
        }
    }

    @Override
    public void updateProgress(@NotNull Book book, @Nullable String chapterId, @Nullable String chapterTitle, int position) {
        // Default page to 1 if not provided, maintain existing isFinished state
        updateProgress(book, chapterId, chapterTitle, position, book.getLastReadPageOrDefault(1));
    }

    @Override
    public void markAsFinished(@NotNull Book book) {
        LOG.debug("Marking book as finished in SQLite: " + book.getId());
        book.setFinished(true);
        // Persist the change along with existing progress or default values
        BookProgressData progress = getProgress(book.getId()).orElse(null);
        updateProgress(
            book, // Pass the book object which now has finished=true
            progress != null ? progress.lastReadChapterId() : book.getLastReadChapterId(),
            progress != null ? progress.lastReadChapterTitle() : book.getLastReadChapter(),
            progress != null ? progress.lastReadPosition() : book.getLastReadPosition(),
            progress != null ? progress.lastReadPage() : book.getLastReadPageOrDefault(1)
        );
    }

    @Override
    public void markAsUnfinished(@NotNull Book book) {
        LOG.debug("Marking book as unfinished in SQLite: " + book.getId());
        book.setFinished(false);
        // Persist the change along with existing progress or default values
        BookProgressData progress = getProgress(book.getId()).orElse(null);
        updateProgress(
            book, // Pass the book object which now has finished=false
            progress != null ? progress.lastReadChapterId() : book.getLastReadChapterId(),
            progress != null ? progress.lastReadChapterTitle() : book.getLastReadChapter(),
            progress != null ? progress.lastReadPosition() : book.getLastReadPosition(),
            progress != null ? progress.lastReadPage() : book.getLastReadPageOrDefault(1)
        );
    }

    @Override
    public void resetProgress(@NotNull Book book) {
        LOG.debug("Resetting progress in SQLite for book: " + book.getId());
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(DELETE_PROGRESS_SQL)) {
            pstmt.setString(1, book.getId());
            int affectedRows = pstmt.executeUpdate();
            LOG.debug("Reset progress deleted " + affectedRows + " row(s) for book: " + book.getId());
        } catch (SQLException e) {
            LOG.error("Failed to reset reading progress in SQLite for book: " + book.getId(), e);
        }
    }

    // --- Additional methods for querying progress --- //

    /**
     * Retrieves the latest reading progress for a specific book.
     *
     * @param bookId The ID of the book.
     * @return An Optional containing BookProgressData if found, otherwise empty.
     */
    @NotNull
    public Optional<BookProgressData> getProgress(@NotNull String bookId) {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(GET_PROGRESS_SQL)) {

            pstmt.setString(1, bookId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readProgressData(rs));
                }
            }
        } catch (SQLException e) {
            LOG.error("Failed to get reading progress from SQLite for book: " + bookId, e);
        }
        return Optional.empty();
    }

    /**
     * Retrieves the progress data for the most recently read book across all books.
     *
     * @return An Optional containing BookProgressData for the last read book, otherwise empty.
     */
    @NotNull
    public Optional<BookProgressData> getLastReadProgressData() {
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(GET_LAST_READ_SQL);
             ResultSet rs = pstmt.executeQuery()) {

            if (rs.next()) {
                return Optional.of(readProgressData(rs));
            }
        } catch (SQLException e) {
            LOG.error("Failed to get last read progress data from SQLite", e);
        }
        return Optional.empty();
    }

    /**
     * 从结果集读取一行进度记录。列顺序与各 SELECT 语句保持一致。
     */
    private static BookProgressData readProgressData(ResultSet rs) throws SQLException {
        return new BookProgressData(
                rs.getString("book_id"),
                rs.getString("book_title"),
                rs.getString("last_read_chapter_id"),
                rs.getString("last_read_chapter_title"),
                rs.getInt("last_read_position"),
                rs.getInt("last_read_page"),
                rs.getInt("is_finished") == 1, // Convert integer back to boolean
                rs.getString("last_read_time")
        );
    }
}