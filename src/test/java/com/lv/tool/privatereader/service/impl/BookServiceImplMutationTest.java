package com.lv.tool.privatereader.service.impl;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.repository.BookRepository;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BookServiceImpl 增删改与进度保存路径测试(V25)。
 *
 * <p>补齐现有 {@link BookServiceImplTest} 未覆盖的分支:
 * {@code getAllBooks}、{@code addBook}/{@code updateBook} 成功与失败、
 * {@code saveReadingProgress}、{@code getChaptersSync} 未实现占位。</p>
 */
@ExtendWith(MockitoExtension.class)
class BookServiceImplMutationTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private ReadingProgressRepository readingProgressRepository;

    private BookServiceImpl service() {
        return new BookServiceImpl(bookRepository, readingProgressRepository);
    }

    @Test
    void getAllBooksEmitsEachBook() {
        Book a = new Book("a", "书A", "作者", "https://example.com/a");
        Book b = new Book("b", "书B", "作者", "https://example.com/b");
        when(bookRepository.getAllBooks()).thenReturn(List.of(a, b));

        List<Book> books = service().getAllBooks().toList().blockingGet();

        assertEquals(2, books.size());
        assertEquals("a", books.get(0).getId());
        assertEquals("b", books.get(1).getId());
    }

    @Test
    void getAllBooksEmitsNothingWhenRepositoryEmpty() {
        when(bookRepository.getAllBooks()).thenReturn(List.of());

        assertEquals(0, service().getAllBooks().count().blockingGet().intValue());
    }

    @Test
    void addBookReturnsTrueAndDelegates() {
        Book book = new Book("book-1", "测试书籍", "作者", "https://example.com/book");

        assertTrue(service().addBook(book).blockingGet());
        verify(bookRepository).addBook(book);
    }

    @Test
    void addBookPropagatesRepositoryFailure() {
        Book book = new Book("book-1", "测试书籍", "作者", "https://example.com/book");
        doThrow(new RuntimeException("写入失败")).when(bookRepository).addBook(book);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service().addBook(book).blockingGet());
        assertEquals("写入失败", error.getMessage());
    }

    @Test
    void updateBookReturnsTrueAndDelegates() {
        Book book = new Book("book-1", "新标题", "作者", "https://example.com/book");

        assertTrue(service().updateBook(book).blockingGet());
        verify(bookRepository).updateBook(book);
    }

    @Test
    void updateBookPropagatesRepositoryFailure() {
        Book book = new Book("book-1", "新标题", "作者", "https://example.com/book");
        doThrow(new IllegalStateException("更新失败")).when(bookRepository).updateBook(book);

        assertThrows(IllegalStateException.class, () -> service().updateBook(book).blockingGet());
    }

    @Test
    void removeBookPropagatesFailure() {
        Book book = new Book("book-1", "测试书籍", "作者", "https://example.com/book");
        doThrow(new RuntimeException("删除失败")).when(readingProgressRepository).resetProgress(book);

        assertThrows(RuntimeException.class, () -> service().removeBook(book).blockingGet());
    }

    @Test
    void saveReadingProgressDelegatesToRepository() {
        Book book = new Book("book-1", "测试书籍", "作者", "https://example.com/book");

        Completable completable = service().saveReadingProgress(book, "chapter-1", "第一章", 42);
        completable.blockingAwait();

        verify(readingProgressRepository).updateProgress(book, "chapter-1", "第一章", 42);
    }

    @Test
    void getChaptersSyncIsNotImplementedPlaceholder() {
        assertEquals(null, service().getChaptersSync("book-1"));
    }
}