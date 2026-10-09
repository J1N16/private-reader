package com.lv.tool.privatereader.model;

import org.jetbrains.annotations.Nullable;

/**
 * Represents the reading progress data retrieved from or saved to the persistent store (e.g., SQLite).
 *
 * @param bookId               The unique identifier of the book.
 * @param bookTitle            The title of the book. Can be null for legacy records.
 * @param lastReadChapterId    The ID (e.g., URL) of the last read chapter. Can be null if no progress.
 * @param lastReadChapterTitle The title of the last read chapter. Can be null.
 * @param lastReadPosition     The scroll position within the last read chapter.
 * @param lastReadPage         The page number within the last read chapter (if applicable).
 * @param isFinished           Whether the book is marked as finished.
 * @param lastReadTime         The human-readable local time (yyyy-MM-dd HH:mm:ss.SSS) when the progress was last updated.
 */
public record BookProgressData(
        String bookId,
        @Nullable String bookTitle,
        @Nullable String lastReadChapterId,
        @Nullable String lastReadChapterTitle,
        int lastReadPosition,
        int lastReadPage,
        boolean isFinished, // Mapped from INTEGER (0/1) in DB
        @Nullable String lastReadTime // Mapped from TEXT in DB, e.g. "2026-01-01 12:30:45.123"
) {
    // No additional methods needed for a simple data carrier record
}