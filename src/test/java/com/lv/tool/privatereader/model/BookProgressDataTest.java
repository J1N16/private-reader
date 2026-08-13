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
                "b1", "ch1", "第一章", 123, 3, true, 9999L);

        assertEquals("b1", data.bookId());
        assertEquals("ch1", data.lastReadChapterId());
        assertEquals("第一章", data.lastReadChapterTitle());
        assertEquals(123, data.lastReadPosition());
        assertEquals(3, data.lastReadPage());
        assertTrue(data.isFinished());
        assertEquals(9999L, data.lastReadTimestamp());
    }

    @Test
    void nullFieldsAllowed() {
        BookProgressData data = new BookProgressData(
                "b1", null, null, 0, 0, false, 0L);
        assertEquals("b1", data.bookId());
        assertEquals(null, data.lastReadChapterId());
        assertEquals(null, data.lastReadChapterTitle());
        assertFalse(data.isFinished());
    }

    @Test
    void equalityByAllComponents() {
        BookProgressData a = new BookProgressData("b1", "ch1", "第一章", 1, 1, true, 100L);
        BookProgressData b = new BookProgressData("b1", "ch1", "第一章", 1, 1, true, 100L);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentComponentBreaksEquality() {
        BookProgressData a = new BookProgressData("b1", "ch1", "第一章", 1, 1, true, 100L);
        BookProgressData b = new BookProgressData("b1", "ch1", "第一章", 2, 1, true, 100L);
        assertNotEquals(a, b);
    }
}
