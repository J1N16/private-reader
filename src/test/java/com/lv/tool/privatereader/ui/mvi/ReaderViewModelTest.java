package com.lv.tool.privatereader.ui.mvi;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.service.ChapterService;
import com.lv.tool.privatereader.service.NotificationService;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReaderViewModelTest {

    @Mock
    private BookService bookService;

    @Mock
    private ChapterService chapterService;

    @Mock
    private NotificationService notificationService;

    private ReaderViewModel viewModel;

    @AfterEach
    void tearDown() {
        if (viewModel != null) {
            viewModel.dispose();
        }
    }

    @Test
    void loadInitialDataSelectsLastReadBookAndChapter() throws InterruptedException {
        Book firstBook = book("book-1", "第一本", 100L);
        Book lastReadBook = book("book-2", "最近阅读", 200L);
        lastReadBook.updateReadingProgress("chapter-2", 12, 2);
        NovelParser.Chapter chapter1 = chapter("第一章", "chapter-1");
        NovelParser.Chapter chapter2 = chapter("第二章", "chapter-2");
        when(bookService.getAllBooks()).thenReturn(Observable.fromIterable(List.of(firstBook, lastReadBook)));
        when(bookService.getLastReadBook()).thenReturn(Maybe.just(lastReadBook));
        when(bookService.getBookById("book-2")).thenReturn(Single.just(lastReadBook));
        when(chapterService.getChapterList(lastReadBook)).thenReturn(Single.just(List.of(chapter1, chapter2)));
        when(chapterService.getChapterContent(lastReadBook, "chapter-2")).thenReturn(Single.just("第二章正文"));
        when(bookService.saveReadingProgress(any(Book.class), eq("chapter-2"), eq("第二章"), eq(12)))
                .thenReturn(Completable.complete());
        viewModel = new ReaderViewModel(bookService, chapterService, null, notificationService);

        ReaderUiState state = awaitState(stateLatch -> {
            Disposable disposable = viewModel.getState().subscribe(next -> {
                if ("book-2".equals(next.getSelectedBookId())
                        && "chapter-2".equals(next.getSelectedChapterId())
                        && "第二章正文".equals(next.getContent())) {
                    stateLatch.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.LoadInitialData());
            return disposable;
        });

        assertEquals("book-2", state.getSelectedBookId());
        assertEquals("chapter-2", state.getSelectedChapterId());
        assertEquals("第二章", state.getCurrentChapterTitle());
        assertEquals("第二章正文", state.getContent());
        verify(bookService, timeout(1000)).saveReadingProgress(lastReadBook, "chapter-2", "第二章", 12);
    }

    @Test
    void handleExternalChapterChangeUpdatesStateAndSavesProgress() throws InterruptedException {
        Book book = book("book-1", "测试书籍", 100L);
        book.updateReadingProgress("chapter-2", 24, 4);
        NovelParser.Chapter chapter1 = chapter("第一章", "chapter-1");
        NovelParser.Chapter chapter2 = chapter("第二章", "chapter-2");
        when(chapterService.getChapterList(book)).thenReturn(Single.just(List.of(chapter1, chapter2)));
        when(chapterService.getChapterContent(book, "chapter-2")).thenReturn(Single.just("外部切章正文"));
        when(bookService.getBookById("book-1")).thenReturn(Single.just(book));
        when(bookService.saveReadingProgress(book, "chapter-2", "第二章", 24)).thenReturn(Completable.complete());
        viewModel = new ReaderViewModel(bookService, chapterService, null, notificationService);

        ReaderUiState state = awaitState(stateLatch -> {
            Disposable disposable = viewModel.getState().subscribe(next -> {
                if ("book-1".equals(next.getSelectedBookId())
                        && "chapter-2".equals(next.getSelectedChapterId())
                        && "外部切章正文".equals(next.getContent())) {
                    stateLatch.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.HandleExternalChapterChange(book, chapter2));
            return disposable;
        });

        assertEquals("book-1", state.getSelectedBookId());
        assertEquals(List.of(chapter1, chapter2), state.getChapters());
        assertEquals("chapter-2", state.getSelectedChapterId());
        assertEquals("第二章", state.getCurrentChapterTitle());
        verify(bookService, timeout(1000)).saveReadingProgress(book, "chapter-2", "第二章", 24);
    }

    @Test
    void chapterContentFailureClearsLoadingAndShowsError() throws InterruptedException {
        Book book = book("book-1", "测试书籍", 100L);
        book.updateReadingProgress("chapter-1", 0, 1);
        NovelParser.Chapter chapter = chapter("第一章", "chapter-1");
        RuntimeException failure = new RuntimeException("网络错误");
        when(bookService.getAllBooks()).thenReturn(Observable.just(book));
        when(bookService.getLastReadBook()).thenReturn(Maybe.just(book));
        when(bookService.getBookById("book-1")).thenReturn(Single.just(book));
        when(chapterService.getChapterList(book)).thenReturn(Single.just(List.of(chapter)));
        when(chapterService.getChapterContent(book, "chapter-1")).thenReturn(Single.error(failure));
        viewModel = new ReaderViewModel(bookService, chapterService, null, notificationService);

        ReaderUiState state = awaitState(stateLatch -> {
            Disposable disposable = viewModel.getState().subscribe(next -> {
                if ("chapter-1".equals(next.getSelectedChapterId()) && !next.isLoadingContent()) {
                    stateLatch.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.LoadInitialData());
            return disposable;
        });

        assertEquals("chapter-1", state.getSelectedChapterId());
        assertFalse(state.isLoadingContent());
        verify(notificationService).showError("加载章节内容失败", "网络错误");
    }

    @Test
    void selectChapterPublishesChapterChange() throws InterruptedException {
        Book book = book("book-1", "测试书籍", 100L);
        book.updateReadingProgress("chapter-1", 0, 1);
        NovelParser.Chapter chapter1 = chapter("第一章", "chapter-1");
        NovelParser.Chapter chapter2 = chapter("第二章", "chapter-2");
        AtomicReference<Book> publishedBook = new AtomicReference<>();
        AtomicReference<NovelParser.Chapter> publishedChapter = new AtomicReference<>();
        when(bookService.getAllBooks()).thenReturn(Observable.just(book));
        when(bookService.getLastReadBook()).thenReturn(Maybe.just(book));
        when(bookService.getBookById("book-1")).thenReturn(Single.just(book));
        when(chapterService.getChapterList(book)).thenReturn(Single.just(List.of(chapter1, chapter2)));
        when(chapterService.getChapterContent(book, "chapter-1")).thenReturn(Single.just("第一章正文"));
        when(chapterService.getChapterContent(book, "chapter-2")).thenReturn(Single.just("第二章正文"));
        when(bookService.saveReadingProgress(any(Book.class), anyString(), anyString(), anyInt()))
                .thenReturn(Completable.complete());
        viewModel = new ReaderViewModel(bookService, chapterService, null, notificationService, (nextBook, nextChapter) -> {
            publishedBook.set(nextBook);
            publishedChapter.set(nextChapter);
        });

        awaitState(stateLatch -> {
            Disposable disposable = viewModel.getState().subscribe(next -> {
                if ("chapter-1".equals(next.getSelectedChapterId())
                        && "第一章正文".equals(next.getContent())) {
                    stateLatch.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.LoadInitialData());
            return disposable;
        });

        ReaderUiState state = awaitState(stateLatch -> {
            Disposable disposable = viewModel.getState().subscribe(next -> {
                if ("chapter-2".equals(next.getSelectedChapterId())
                        && "第二章正文".equals(next.getContent())) {
                    stateLatch.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.SelectChapter("chapter-2"));
            return disposable;
        });

        assertEquals("chapter-2", state.getSelectedChapterId());
        assertEquals("第二章", state.getCurrentChapterTitle());
        assertEquals(book, publishedBook.get());
        assertEquals(chapter2, publishedChapter.get());
    }

    @Test
    void refreshChaptersKeepsCurrentChapterSelected() throws InterruptedException {
        Book book = book("book-1", "测试书籍", 100L);
        book.updateReadingProgress("chapter-1", 0, 1);
        NovelParser.Chapter chapter1 = chapter("第一章", "chapter-1");
        NovelParser.Chapter chapter2 = chapter("第二章", "chapter-2");
        when(bookService.getAllBooks()).thenReturn(Observable.just(book));
        when(bookService.getLastReadBook()).thenReturn(Maybe.just(book));
        when(bookService.getBookById("book-1")).thenReturn(Single.just(book));
        when(chapterService.getChapterList(book)).thenReturn(Single.just(List.of(chapter1, chapter2)));
        when(chapterService.getChapterContent(book, "chapter-1"))
                .thenReturn(Single.just("第一章正文"))
                .thenReturn(Single.just("刷新后第一章正文"));
        when(bookService.saveReadingProgress(any(Book.class), anyString(), anyString(), anyInt()))
                .thenReturn(Completable.complete());
        viewModel = new ReaderViewModel(bookService, chapterService, null, notificationService);

        awaitState(stateLatch -> {
            Disposable disposable = viewModel.getState().subscribe(next -> {
                if ("chapter-1".equals(next.getSelectedChapterId())
                        && "第一章正文".equals(next.getContent())) {
                    stateLatch.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.LoadInitialData());
            return disposable;
        });

        ReaderUiState state = awaitState(stateLatch -> {
            Disposable disposable = viewModel.getState().subscribe(next -> {
                if ("chapter-1".equals(next.getSelectedChapterId())
                        && "刷新后第一章正文".equals(next.getContent())) {
                    stateLatch.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.RefreshChapters());
            return disposable;
        });

        assertEquals("chapter-1", state.getSelectedChapterId());
        assertEquals("第一章", state.getCurrentChapterTitle());
        assertEquals("刷新后第一章正文", state.getContent());
    }

    @Test
    void deleteBookFailureClearsLoadingAndShowsError() throws InterruptedException {
        Book book = book("book-1", "测试书籍", 100L);
        when(bookService.getAllBooks()).thenReturn(Observable.just(book));
        when(bookService.getLastReadBook()).thenReturn(Maybe.just(book));
        when(bookService.getBookById("book-1")).thenReturn(Single.just(book));
        when(chapterService.getChapterList(book)).thenReturn(Single.just(List.of()));
        when(bookService.removeBook(book)).thenReturn(Single.error(new RuntimeException("删除失败")));
        viewModel = new ReaderViewModel(bookService, chapterService, null, notificationService);

        awaitState(stateLatch -> {
            Disposable disposable = viewModel.getState().subscribe(next -> {
                if ("book-1".equals(next.getSelectedBookId())
                        && next.getBooks().contains(book)) {
                    stateLatch.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.LoadInitialData());
            return disposable;
        });

        AtomicReference<ReaderUiState> latestState = new AtomicReference<>();
        Disposable stateSubscription = viewModel.getState().subscribe(latestState::set);
        try {
            viewModel.processIntent(new IReaderIntent.DeleteBook("book-1"));

            verify(notificationService, timeout(1000)).showError("删除书籍失败", "删除失败");
            assertFalse(latestState.get().isLoadingBooks());
        } finally {
            stateSubscription.dispose();
        }
    }

    private ReaderUiState awaitState(StateSubscription subscription) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ReaderUiState> stateRef = new AtomicReference<>();
        Disposable disposable = subscription.subscribe(state -> {
            stateRef.set(state);
            latch.countDown();
        });
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
            return stateRef.get();
        } finally {
            disposable.dispose();
        }
    }

    private Book book(String id, String title, long createTimeMillis) {
        Book book = new Book(id, title, "作者", "https://example.com/" + id);
        book.setCreateTimeMillis(createTimeMillis);
        return book;
    }

    private NovelParser.Chapter chapter(String title, String url) {
        return new NovelParser.Chapter(title, url);
    }

    private interface StateSubscription {
        Disposable subscribe(StateLatch stateLatch);
    }

    private interface StateLatch {
        void set(ReaderUiState state);
    }
}
