package com.lv.tool.privatereader.service.impl;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookProgressData;
import com.lv.tool.privatereader.repository.BookRepository;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookServiceImplTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private ReadingProgressRepository readingProgressRepository;

    @Test
    void testConstructorInjection() {
        BookServiceImpl bookService = new BookServiceImpl(bookRepository, readingProgressRepository);
        assertNotNull(bookService);
    }

    @Test
    void testConstructorInjectionWithMocks() {
        assertDoesNotThrow(() -> new BookServiceImpl(bookRepository, readingProgressRepository));
    }

    @Test
    void getBookByIdLoadsReadingProgress() {
        Book book = new Book("book-1", "测试书籍", "作者", "https://example.com/book");
        BookProgressData progress = new BookProgressData(
                "book-1",
                "chapter-2",
                "第二章",
                128,
                3,
                false,
                1000L
        );
        when(bookRepository.getBook("book-1")).thenReturn(book);
        when(readingProgressRepository.getProgress("book-1")).thenReturn(Optional.of(progress));
        BookServiceImpl bookService = new BookServiceImpl(bookRepository, readingProgressRepository);

        Book result = bookService.getBookById("book-1").blockingGet();

        assertSame(book, result);
        assertEquals("chapter-2", result.getLastReadChapterId());
        assertEquals(128, result.getLastReadPosition());
        assertEquals(3, result.getLastReadPage());
    }

    @Test
    void getBookByIdFailsWhenBookDoesNotExist() {
        when(bookRepository.getBook("missing-book")).thenReturn(null);
        BookServiceImpl bookService = new BookServiceImpl(bookRepository, readingProgressRepository);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> bookService.getBookById("missing-book").blockingGet());

        assertEquals("Book not found: missing-book", error.getMessage());
    }

    @Test
    void removeBookResetsProgressAndRemovesBook() {
        Book book = new Book("book-1", "测试书籍", "作者", "https://example.com/book");
        BookServiceImpl bookService = new BookServiceImpl(bookRepository, readingProgressRepository);

        assertTrue(bookService.removeBook(book).blockingGet());

        verify(readingProgressRepository).resetProgress(book);
        verify(bookRepository).removeBook(book);
    }

    @Test
    void removeBookDoesNotRemoveBookWhenResetProgressFails() {
        Book book = new Book("book-1", "测试书籍", "作者", "https://example.com/book");
        doThrow(new RuntimeException("reset failed")).when(readingProgressRepository).resetProgress(book);
        BookServiceImpl bookService = new BookServiceImpl(bookRepository, readingProgressRepository);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> bookService.removeBook(book).blockingGet());

        assertEquals("reset failed", error.getMessage());
        verify(readingProgressRepository).resetProgress(book);
        verify(bookRepository, never()).removeBook(book);
    }
}
