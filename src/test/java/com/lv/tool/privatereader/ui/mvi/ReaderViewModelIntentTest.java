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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ReaderViewModel 意图分发补测(V26)。
 *
 * <p>补齐现有 {@link ReaderViewModelTest} 未覆盖的确定性意图:{@code SelectBook}
 * 重载目录并定位章节,以及 {@code SearchBook} 的关键字过滤(空关键字/标题命中/作者命中)。
 * 全部通过内存 mock 驱动,不依赖真实网络。</p>
 *
 * <p>说明:{@code AddBook} 会经 {@code fetchBookInfo} 走 {@code UniversalParser} 的真实网络
 * 抓取,难以确定性测试,故本类不覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class ReaderViewModelIntentTest {

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

    private Book book(String id, String title, long createTimeMillis) {
        Book book = new Book(id, title, "作者", "https://example.com/" + id);
        book.setCreateTimeMillis(createTimeMillis);
        return book;
    }

    private NovelParser.Chapter chapter(String title, String url) {
        return new NovelParser.Chapter(title, url);
    }

    private ReaderUiState awaitState(StateSubscription subscription) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ReaderUiState> ref = new AtomicReference<>();
        Disposable disposable = subscription.subscribe(state -> {
            ref.set(state);
            latch.countDown();
        });
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS), "等待状态超时");
            return ref.get();
        } finally {
            disposable.dispose();
        }
    }

    // --- SelectBook:重新加载目录并定位到指定书籍 ---

    @Test
    void selectBookReloadsItsChapters() throws InterruptedException {
        Book book = book("book-1", "测试书籍", 100L);
        book.updateReadingProgress("chapter-1", 0, 1);
        NovelParser.Chapter c1 = chapter("第一章", "chapter-1");
        when(bookService.getAllBooks()).thenReturn(Observable.just(book));
        when(bookService.getLastReadBook()).thenReturn(Maybe.just(book));
        when(bookService.getBookById("book-1")).thenReturn(Single.just(book));
        when(chapterService.getChapterList(book)).thenReturn(Single.just(List.of(c1)));
        when(chapterService.getChapterContent(book, "chapter-1")).thenReturn(Single.just("正文"));
        when(bookService.saveReadingProgress(any(Book.class), anyString(), anyString(), anyInt()))
                .thenReturn(Completable.complete());
        viewModel = new ReaderViewModel(bookService, chapterService, null, notificationService);

        awaitState(l -> {
            Disposable d = viewModel.getState().subscribe(next -> {
                if ("book-1".equals(next.getSelectedBookId()) && "正文".equals(next.getContent())) {
                    l.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.LoadInitialData());
            return d;
        });

        ReaderUiState state = awaitState(l -> {
            Disposable d = viewModel.getState().subscribe(next -> {
                if (!next.isLoadingChapters()
                        && "book-1".equals(next.getSelectedBookId())
                        && next.getChapters().size() == 1) {
                    l.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.SelectBook("book-1"));
            return d;
        });

        assertEquals("book-1", state.getSelectedBookId());
        assertEquals("chapter-1", state.getSelectedChapterId());
        assertEquals("第一章", state.getCurrentChapterTitle());
        assertEquals(List.of(c1), state.getChapters());
    }

    // --- SearchBook:关键字过滤 ---

    @Test
    void searchBookFiltersByTitle() throws InterruptedException {
        Book match = book("book-1", "剑来", 100L);
        Book other = book("book-2", "其他书", 200L);
        when(bookService.getAllBooks()).thenReturn(Observable.just(match, other));
        viewModel = new ReaderViewModel(bookService, chapterService, null, notificationService);

        ReaderUiState state = awaitState(l -> {
            Disposable d = viewModel.getState().subscribe(next -> {
                if (!next.isLoadingBooks() && next.getBooks().size() == 1) {
                    l.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.SearchBook("剑来"));
            return d;
        });

        assertEquals(1, state.getBooks().size());
        assertEquals("剑来", state.getBooks().get(0).getTitle());
    }

    @Test
    void searchBookMatchesByAuthorWhenTitleDiffers() throws InterruptedException {
        Book byAuthor = new Book("book-1", "无关书名", "金庸", "https://example.com/a");
        byAuthor.setCreateTimeMillis(100L);
        when(bookService.getAllBooks()).thenReturn(Observable.just(byAuthor));
        viewModel = new ReaderViewModel(bookService, chapterService, null, notificationService);

        ReaderUiState state = awaitState(l -> {
            Disposable d = viewModel.getState().subscribe(next -> {
                if (!next.isLoadingBooks() && next.getBooks().size() == 1) {
                    l.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.SearchBook("金庸"));
            return d;
        });

        assertEquals("无关书名", state.getBooks().get(0).getTitle());
    }

    @Test
    void searchBookWithBlankKeywordKeepsAllBooks() throws InterruptedException {
        Book a = book("book-1", "书A", 100L);
        Book b = book("book-2", "书B", 200L);
        when(bookService.getAllBooks()).thenReturn(Observable.just(a, b));
        viewModel = new ReaderViewModel(bookService, chapterService, null, notificationService);

        ReaderUiState state = awaitState(l -> {
            Disposable d = viewModel.getState().subscribe(next -> {
                if (!next.isLoadingBooks() && next.getBooks().size() == 2) {
                    l.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.SearchBook("  "));
            return d;
        });

        assertEquals(2, state.getBooks().size());
    }

    // --- NavigateChapter:在已加载目录中前进/后退 ---

    private ReaderViewModel setUpBookWithThreeChapters(Book book, List<NovelParser.Chapter> chapters)
            throws InterruptedException {
        when(bookService.getAllBooks()).thenReturn(Observable.just(book));
        when(bookService.getLastReadBook()).thenReturn(Maybe.just(book));
        when(chapterService.getChapterList(book)).thenReturn(Single.just(chapters));
        for (NovelParser.Chapter c : chapters) {
            lenient().when(chapterService.getChapterContent(book, c.url()))
                    .thenReturn(Single.just(c.title() + "正文"));
        }
        when(bookService.getBookById("book-1")).thenReturn(Single.just(book));
        lenient().when(bookService.saveReadingProgress(any(Book.class), anyString(), anyString(), anyInt()))
                .thenReturn(Completable.complete());
        ReaderViewModel vm = new ReaderViewModel(bookService, chapterService, null, notificationService);
        awaitState(l -> {
            Disposable d = vm.getState().subscribe(next -> {
                if ("第一章正文".equals(next.getContent())) {
                    l.set(next);
                }
            });
            vm.processIntent(new IReaderIntent.LoadInitialData());
            return d;
        });
        return vm;
    }

    @Test
    void navigateChapterForwardLoadsNextChapter() throws InterruptedException {
        Book book = book("book-1", "测试书籍", 100L);
        book.updateReadingProgress("chapter-1", 0, 1);
        List<NovelParser.Chapter> chapters = List.of(
                chapter("第一章", "chapter-1"),
                chapter("第二章", "chapter-2"),
                chapter("第三章", "chapter-3"));
        viewModel = setUpBookWithThreeChapters(book, chapters);

        ReaderUiState state = awaitState(l -> {
            Disposable d = viewModel.getState().subscribe(next -> {
                if ("第二章正文".equals(next.getContent())) {
                    l.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.NavigateChapter(1));
            return d;
        });

        assertEquals("chapter-2", state.getSelectedChapterId());
        assertEquals("第二章", state.getCurrentChapterTitle());
    }

    @Test
    void navigateChapterBackwardFromSecondChapterLoadsFirst() throws InterruptedException {
        Book book = book("book-1", "测试书籍", 100L);
        book.updateReadingProgress("chapter-2", 0, 1);
        List<NovelParser.Chapter> chapters = List.of(
                chapter("第一章", "chapter-1"),
                chapter("第二章", "chapter-2"),
                chapter("第三章", "chapter-3"));
        when(bookService.getAllBooks()).thenReturn(Observable.just(book));
        when(bookService.getLastReadBook()).thenReturn(Maybe.just(book));
        when(bookService.getBookById("book-1")).thenReturn(Single.just(book));
        when(chapterService.getChapterList(book)).thenReturn(Single.just(chapters));
        when(chapterService.getChapterContent(book, "chapter-2")).thenReturn(Single.just("第二章正文"));
        when(chapterService.getChapterContent(book, "chapter-1")).thenReturn(Single.just("第一章正文"));
        when(bookService.saveReadingProgress(any(Book.class), anyString(), anyString(), anyInt()))
                .thenReturn(Completable.complete());
        viewModel = new ReaderViewModel(bookService, chapterService, null, notificationService);
        awaitState(l -> {
            Disposable d = viewModel.getState().subscribe(next -> {
                if ("第二章正文".equals(next.getContent())) {
                    l.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.LoadInitialData());
            return d;
        });

        ReaderUiState state = awaitState(l -> {
            Disposable d = viewModel.getState().subscribe(next -> {
                if ("第一章正文".equals(next.getContent())) {
                    l.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.NavigateChapter(-1));
            return d;
        });

        assertEquals("chapter-1", state.getSelectedChapterId());
        assertEquals("第一章", state.getCurrentChapterTitle());
    }

    @Test
    void navigateChapterBackwardAtFirstChapterIsNoOp() throws InterruptedException {
        Book book = book("book-1", "测试书籍", 100L);
        book.updateReadingProgress("chapter-1", 0, 1);
        List<NovelParser.Chapter> chapters = List.of(
                chapter("第一章", "chapter-1"),
                chapter("第二章", "chapter-2"),
                chapter("第三章", "chapter-3"));
        viewModel = setUpBookWithThreeChapters(book, chapters);

        viewModel.processIntent(new IReaderIntent.NavigateChapter(-1));

        Thread.sleep(200);
        assertEquals("chapter-1", viewModel.getState().blockingFirst().getSelectedChapterId());
        assertEquals("第一章正文", viewModel.getState().blockingFirst().getContent());
    }

    @Test
    void navigateChapterForwardAtLastChapterIsNoOp() throws InterruptedException {
        Book book = book("book-1", "测试书籍", 100L);
        book.updateReadingProgress("chapter-3", 0, 1);
        List<NovelParser.Chapter> chapters = List.of(
                chapter("第一章", "chapter-1"),
                chapter("第二章", "chapter-2"),
                chapter("第三章", "chapter-3"));
        when(bookService.getAllBooks()).thenReturn(Observable.just(book));
        when(bookService.getLastReadBook()).thenReturn(Maybe.just(book));
        when(bookService.getBookById("book-1")).thenReturn(Single.just(book));
        when(chapterService.getChapterList(book)).thenReturn(Single.just(chapters));
        when(chapterService.getChapterContent(book, "chapter-3")).thenReturn(Single.just("第三章正文"));
        when(bookService.saveReadingProgress(any(Book.class), anyString(), anyString(), anyInt()))
                .thenReturn(Completable.complete());
        viewModel = new ReaderViewModel(bookService, chapterService, null, notificationService);
        awaitState(l -> {
            Disposable d = viewModel.getState().subscribe(next -> {
                if ("第三章正文".equals(next.getContent())) {
                    l.set(next);
                }
            });
            viewModel.processIntent(new IReaderIntent.LoadInitialData());
            return d;
        });

        viewModel.processIntent(new IReaderIntent.NavigateChapter(1));

        Thread.sleep(200);
        assertEquals("chapter-3", viewModel.getState().blockingFirst().getSelectedChapterId());
    }

    private interface StateSubscription {
        Disposable subscribe(StateLatch latch);
    }

    private interface StateLatch {
        void set(ReaderUiState state);
    }
}