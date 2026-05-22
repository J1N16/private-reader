package com.lv.tool.privatereader.service.impl;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.repository.BookRepository;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

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
    void removeBookResetsProgressAndRemovesBook() {
        Book book = new Book("book-1", "测试书籍", "作者", "https://example.com/book");
        BookServiceImpl bookService = new BookServiceImpl(bookRepository, readingProgressRepository);

        assertTrue(bookService.removeBook(book).blockingGet());

        verify(readingProgressRepository).resetProgress(book);
        verify(bookRepository).removeBook(book);
    }
}
