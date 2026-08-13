package com.lv.tool.privatereader.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BookIndex 单元测试
 * 覆盖：fromBook 转换、字段访问器、equals/hashCode
 */
class BookIndexTest {

    private Book sampleBook() {
        Book book = new Book("b1", "斗破苍穹", "天蚕土豆", "https://example.com/book/1");
        book.setCreateTimeMillis(1000L);
        book.setLastReadTimeMillis(2000L);
        book.setLastChapter("第十章");
        book.setTotalChapters(100);
        book.setFinished(true);
        return book;
    }

    @Test
    void fromBookCopiesAllFields() {
        BookIndex index = BookIndex.fromBook(sampleBook());

        assertEquals("b1", index.getId());
        assertEquals("斗破苍穹", index.getTitle());
        assertEquals("天蚕土豆", index.getAuthor());
        assertEquals("https://example.com/book/1", index.getUrl());
        assertEquals(1000L, index.getCreateTimeMillis());
        assertEquals(2000L, index.getLastReadTimeMillis());
        assertEquals("第十章", index.getLastChapter());
        assertEquals(100, index.getTotalChapters());
        assertTrue(index.isFinished());
    }

    @Test
    void setterAndGetterRoundTrip() {
        BookIndex index = new BookIndex();
        index.setId("x");
        index.setTitle("书名");
        index.setAuthor("作者");
        index.setUrl("http://u");
        index.setCreateTimeMillis(111L);
        index.setLastReadTimeMillis(222L);
        index.setLastChapter("第1章");
        index.setTotalChapters(10);
        index.setFinished(false);

        assertEquals("x", index.getId());
        assertEquals("书名", index.getTitle());
        assertEquals("作者", index.getAuthor());
        assertEquals("http://u", index.getUrl());
        assertEquals(111L, index.getCreateTimeMillis());
        assertEquals(222L, index.getLastReadTimeMillis());
        assertEquals("第1章", index.getLastChapter());
        assertEquals(10, index.getTotalChapters());
        assertFalse(index.isFinished());
    }

    @Test
    void equalityIsBasedOnId() {
        BookIndex a = BookIndex.fromBook(sampleBook());
        BookIndex b = BookIndex.fromBook(sampleBook());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        b.setId("different");
        assertFalse(a.equals(b));
    }

    @Test
    void newIndexInitializesTimestamps() {
        BookIndex index = new BookIndex();
        assertTrue(index.getCreateTimeMillis() > 0);
        assertTrue(index.getLastReadTimeMillis() > 0);
        assertNull(index.getId());
        assertNull(index.getLastChapter());
    }
}
