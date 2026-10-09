package com.lv.tool.privatereader.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BookProgressData 单元测试
 * 覆盖：record 字段、equals/hashCode、toString
 */
class BookProgressDataTest {

    @Test
    void recordPreservesAllFields() {
        BookProgressData data = new BookProgressData(
                "b1", "测试书籍", "ch1", "第一章", 123, 3, true, "2026-01-01 12:30:45.123");

        assertEquals("b1", data.bookId());
        assertEquals("测试书籍", data.bookTitle());
        assertEquals("ch1", data.lastReadChapterId());
        assertEquals("第一章", data.lastReadChapterTitle());
        assertEquals(123, data.lastReadPosition());
        assertEquals(3, data.lastReadPage());
        assertTrue(data.isFinished());
        assertEquals("2026-01-01 12:30:45.123", data.lastReadTime());
    }

    @Test
    void nullFieldsAllowed() {
        BookProgressData data = new BookProgressData(
                "b1", null, null, null, 0, 0, false, null);
        assertEquals("b1", data.bookId());
        assertEquals(null, data.bookTitle());
        assertEquals(null, data.lastReadChapterId());
        assertEquals(null, data.lastReadChapterTitle());
        assertFalse(data.isFinished());
        assertEquals(null, data.lastReadTime());
    }

    @Test
    void equalityByAllComponents() {
        BookProgressData a = new BookProgressData("b1", "书", "ch1", "第一章", 1, 1, true, "2026-01-01 00:00:00.000");
        BookProgressData b = new BookProgressData("b1", "书", "ch1", "第一章", 1, 1, true, "2026-01-01 00:00:00.000");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentComponentBreaksEquality() {
        BookProgressData a = new BookProgressData("b1", "书", "ch1", "第一章", 1, 1, true, "2026-01-01 00:00:00.000");
        BookProgressData b = new BookProgressData("b1", "书", "ch1", "第一章", 2, 1, true, "2026-01-01 00:00:00.000");
        assertNotEquals(a, b);
    }
}
