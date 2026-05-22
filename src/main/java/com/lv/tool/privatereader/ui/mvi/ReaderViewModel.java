package com.lv.tool.privatereader.ui.mvi;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.lv.tool.privatereader.async.ReactiveSchedulers;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.storage.cache.ReactiveChapterPreloader;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.parser.site.UniversalParser;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.service.ChapterService;
import com.lv.tool.privatereader.messaging.CurrentChapterNotifier;
import com.lv.tool.privatereader.service.NotificationService;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.reactivex.rxjava3.subjects.PublishSubject;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.core.Single;


public class ReaderViewModel implements Disposable {
    private static final Logger LOG = Logger.getInstance(ReaderViewModel.class);

    private final BookService bookService;
    private final ChapterService chapterService;
    private final ReactiveChapterPreloader chapterPreloader;
    private final NotificationService notificationService;
    private final CompositeDisposable disposables = new CompositeDisposable();

    private final PublishSubject<IReaderIntent> intentSubject = PublishSubject.create();
    private final BehaviorSubject<ReaderUiState> uiState = BehaviorSubject.createDefault(ReaderUiState.initial());

    public ReaderViewModel(Project project) {
        this.bookService = ApplicationManager.getApplication().getService(BookService.class);
        this.chapterService = ApplicationManager.getApplication().getService(ChapterService.class);
        this.chapterPreloader = ApplicationManager.getApplication().getService(ReactiveChapterPreloader.class);
        this.notificationService = ApplicationManager.getApplication().getService(NotificationService.class);

        disposables.add(
            intentSubject
                .observeOn(Schedulers.io())
                .subscribe(this::handleIntent)
        );
    }

    public Observable<ReaderUiState> getState() {
        return uiState.hide();
    }

    public void processIntent(IReaderIntent intent) {
        intentSubject.onNext(intent);
    }

    private void handleIntent(IReaderIntent intent) {
        if (intent instanceof IReaderIntent.LoadInitialData) {
            loadInitialData();
        } else if (intent instanceof IReaderIntent.SelectBook selectBook) {
            loadChaptersForBook(selectBook.bookId(), null);
        } else if (intent instanceof IReaderIntent.SelectChapter selectChapter) {
            Book currentBook = findBookInCurrentState(uiState.getValue().getSelectedBookId());
            NovelParser.Chapter chapterToLoad = findChapterInCurrentState(selectChapter.chapterId());
            if (currentBook != null && chapterToLoad != null) {
                loadChapterContent(currentBook, chapterToLoad);
            } else {
                LOG.warn("Could not handle SelectChapter intent, book or chapter not found in current state.");
            }
        } else if (intent instanceof IReaderIntent.AddBook addBook) {
            addNewBook(addBook.url());
        } else if (intent instanceof IReaderIntent.DeleteBook deleteBook) {
            deleteBook(deleteBook.bookId());
        } else if (intent instanceof IReaderIntent.SearchBook searchBook) {
            searchBooks(searchBook.keyword());
        } else if (intent instanceof IReaderIntent.RefreshChapters) {
            refreshChapters();
        } else if (intent instanceof IReaderIntent.SaveProgress saveProgress) {
            saveProgress(saveProgress.chapterId(), saveProgress.position());
        } else if (intent instanceof IReaderIntent.HandleExternalChapterChange externalChange) {
            handleExternalChapterChange(externalChange.book(), externalChange.chapter());
        }
    }

    private void loadChapterContent(Book book, NovelParser.Chapter chapter) {
        if (book == null || chapter == null) {
            LOG.warn("Cannot load chapter content, book or chapter is null");
            return;
        }

        if (uiState.getValue().isLoadingContent()) {
            LOG.warn("Already loading chapter content, ignoring new request for " + chapter.title());
            return;
        }

        uiState.onNext(
                uiState.getValue().toBuilder()
                        .selectedChapterId(chapter.url())
                        .isLoadingContent(true)
                        .build()
        );

        disposables.add(
                chapterService.getChapterContent(book, chapter.url())
                        .toObservable()
                        .subscribeOn(Schedulers.io())
                        .subscribe(
                                content -> {
                                    uiState.onNext(
                                            uiState.getValue().toBuilder()
                                                    .isLoadingContent(false)
                                                    .content(content)
                                                    .currentChapterTitle(chapter.title())
                                                    .build()
                                    );
                                    // Notify other parts of the application about the chapter change
                                    ApplicationManager.getApplication().getMessageBus()
                                            .syncPublisher(CurrentChapterNotifier.TOPIC)
                                            .currentChapterChanged(book, chapter);
                                    LOG.debug("Published CurrentChapterNotifier event for: " + chapter.title());

                                    // After successfully loading a chapter, trigger preloading for adjacent chapters.
                                    // 减少预加载的频率或数量，以避免CPU过载
                                    preloadAdjacentChapters(book, chapter);

                                    // Update progress to ensure timestamp is updated in DB (fixes notification bar mode loading old book)
                                    updateAndSaveProgress(book, chapter);
                                },
                                error -> {
                                    LOG.warn("Failed to load content for chapter: " + chapter.url(), error);
                                    notificationService.showError("加载章节内容失败", error.getMessage());
                                    uiState.onNext(
                                            uiState.getValue().toBuilder()
                                                    .isLoadingContent(false)
                                                    .build()
                                    );
                                }
                        )
        );
    }

    private void updateAndSaveProgress(Book book, NovelParser.Chapter chapter) {
        // Fetch latest book from DB to ensure we have the most up-to-date progress (especially lastReadPage)
        // This prevents overwriting DB with stale data from UI state when switching modes
        disposables.add(
            bookService.getBookById(book.getId())
                .flatMap(latestBook -> {
                    int position = 0;
                    int page = 1;

                    // Check against the latest book state from DB
                    if (chapter.url().equals(latestBook.getLastReadChapterId())) {
                        position = latestBook.getLastReadPosition();
                        page = latestBook.getLastReadPageOrDefault(1);
                    }

                    // Update the passed book object in memory (updates UI state reference)
                    book.updateReadingProgress(chapter.url(), position, page);
                    
                    // Update latestBook for saving
                    latestBook.updateReadingProgress(chapter.url(), position, page);

                    return bookService.saveReadingProgress(latestBook, chapter.url(), chapter.title(), position)
                        .andThen(Single.just(true));
                })
                .subscribeOn(Schedulers.io())
                .subscribe(
                    v -> {},
                    error -> LOG.error("Failed to update progress on chapter load", error)
                )
        );
    }

    private NovelParser.Chapter findChapterInCurrentState(String chapterId) {
        if (uiState.getValue().getChapters() == null || chapterId == null) return null;
        return uiState.getValue().getChapters().stream()
            .filter(c -> c.url().equals(chapterId))
            .findFirst()
            .orElse(null);
    }

    private void loadChaptersForBook(String bookId, String chapterIdToRestore) {
        // First update the state to show this book is selected
        uiState.onNext(
            uiState.getValue().toBuilder()
                .selectedBookId(bookId)
                .isLoadingChapters(true)
                .build()
        );
        
        Book book = findBookInCurrentState(bookId);
        if (book == null) {
            uiState.onNext(uiState.getValue().toBuilder().error("Selected book not found in state").isLoadingChapters(false).build());
            return;
        }

        disposables.add(
            bookService.getBookById(bookId) // Fetch the latest book state
                .flatMap(latestBook -> chapterService.getChapterList(latestBook)
                        .map(chapters -> new java.util.AbstractMap.SimpleEntry<>(latestBook, chapters))) // Pair the latest book with its chapters
                .subscribeOn(Schedulers.io())
                .subscribe(
                    pair -> {
                        Book latestBook = pair.getKey();
                        List<NovelParser.Chapter> chapters = pair.getValue();
                        // Determine which chapter to select after loading. Prioritize the explicitly passed one.
                        String chapterIdToSelect = chapterIdToRestore != null ? chapterIdToRestore : latestBook.getLastReadChapterId();

                        uiState.onNext(
                            uiState.getValue().toBuilder()
                                .isLoadingChapters(false)
                                .chapters(chapters)
                                .build()
                        );

                        if (chapterIdToSelect != null && !chapterIdToSelect.isEmpty()) {
                            // Find the chapter object from the newly loaded list
                            chapters.stream()
                                .filter(c -> c.url().equals(chapterIdToSelect))
                                .findFirst()
                                .ifPresent(chapterToLoad -> loadChapterContent(latestBook, chapterToLoad));
                        } else if (!chapters.isEmpty()) {
                            // If no last-read chapter is found, load the first chapter by default
                            loadChapterContent(latestBook, chapters.get(0));
                        }
                    },
                    error -> {
                        LOG.error("Failed to load chapters for book: " + bookId, error);
                        notificationService.showError("加载章节列表失败", error.getMessage());
                        uiState.onNext(
                            uiState.getValue().toBuilder()
                                .isLoadingChapters(false)
                                .build()
                        );
                    }
                )
        );
    }

    private Book findBookInCurrentState(String bookId) {
        return uiState.getValue().getBooks().stream()
            .filter(b -> b.getId().equals(bookId))
            .findFirst()
            .orElse(null);
    }
    
    private void loadInitialData() {
        uiState.onNext(uiState.getValue().toBuilder().isLoadingBooks(true).build());
        disposables.add(
            bookService.getAllBooks().toList()
                .subscribeOn(Schedulers.io())
                .subscribe(books -> {
                    disposables.add(
                        bookService.getLastReadBook().toObservable().firstElement()
                            .subscribe(
                                lastReadBook -> {
                                    books.sort(Comparator.comparingLong(Book::getCreateTimeMillis).reversed());
                                    String selectedBookId = lastReadBook.getId() != null ? lastReadBook.getId() : (books.isEmpty() ? null : books.get(0).getId());
                                    updateInitialState(books, selectedBookId);
                                },
                                error -> {
                                    LOG.error("Could not get last read book, selecting first.", error);
                                    books.sort(Comparator.comparingLong(Book::getCreateTimeMillis).reversed());
                                    String selectedBookId = books.isEmpty() ? null : books.get(0).getId();
                                    updateInitialState(books, selectedBookId);
                                },
                                () -> { // Empty Maybe
                                    books.sort(Comparator.comparingLong(Book::getCreateTimeMillis).reversed());
                                    String selectedBookId = books.isEmpty() ? null : books.get(0).getId();
                                    updateInitialState(books, selectedBookId);
                                }
                            )
                    );
                }, error -> {
                    LOG.error("Failed to load books", error);
                    notificationService.showError("加载书籍失败", error.getMessage());
                    uiState.onNext(uiState.getValue().toBuilder().isLoadingBooks(false).build());
                })
        );
    }
    
    private void updateInitialState(List<Book> books, String selectedBookId) {
        uiState.onNext(
            uiState.getValue().toBuilder()
                .isLoadingBooks(false)
                .books(books)
                .selectedBookId(selectedBookId)
                .build()
        );
        if (selectedBookId != null) {
            loadChaptersForBook(selectedBookId, null);
        }
    }

    private void searchBooks(String keyword) {
        LOG.info("Searching for books with keyword: " + keyword);
        uiState.onNext(uiState.getValue().toBuilder().isLoadingBooks(true).build());
        disposables.add(
            bookService.getAllBooks()
                .filter(b -> matchesKeyword(b, keyword))
                .toList()
                .subscribeOn(Schedulers.io())
                .subscribe(
                    books -> uiState.onNext(uiState.getValue().toBuilder().isLoadingBooks(false).books(books).build()),
                    error -> {
                        LOG.error("Failed to search books", error);
                        notificationService.showError("搜索书籍失败", error.getMessage());
                        uiState.onNext(uiState.getValue().toBuilder().isLoadingBooks(false).build());
                    }
                )
        );
    }
    
    private boolean matchesKeyword(Book book, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return true;
        }
        String lowerKeyword = keyword.toLowerCase();
        return book.getTitle().toLowerCase().contains(lowerKeyword) ||
               (book.getAuthor() != null && book.getAuthor().toLowerCase().contains(lowerKeyword));
    }

    private void refreshChapters() {
        ReaderUiState currentState = uiState.getValue();
        String bookId = currentState.getSelectedBookId();
        String chapterId = currentState.getSelectedChapterId(); // Get current chapter before refresh
        if (bookId == null) {
            LOG.warn("Cannot refresh chapters, no book selected");
            return;
        }
        // Pass the current chapter ID to be restored after the list is reloaded
        loadChaptersForBook(bookId, chapterId);
    }

    private void addNewBook(String url) {
        LOG.info("Adding new book from url: " + url);
        uiState.onNext(uiState.getValue().toBuilder().isLoadingBooks(true).build());
        disposables.add(
            fetchBookInfo(url)
                .subscribe(book -> {
                    disposables.add(
                        bookService.addBook(book)
                            .toObservable()
                            .subscribe(success -> {
                                if (success) {
                                    loadInitialData(); // Just reload everything for simplicity
                                } else {
                                    notificationService.showError("添加书籍失败", "无法添加书籍，请稍后再试。");
                                    uiState.onNext(uiState.getValue().toBuilder().isLoadingBooks(false).build());
                                }
                            }, error -> {
                                LOG.error("Failed to add book", error);
                                notificationService.showError("添加书籍失败", error.getMessage());
                                uiState.onNext(uiState.getValue().toBuilder().isLoadingBooks(false).build());
                            })
                    );
                }, error -> {
                    LOG.error("Failed to fetch book info", error);
                    notificationService.showError("获取书籍信息失败", error.getMessage());
                    uiState.onNext(uiState.getValue().toBuilder().isLoadingBooks(false).build());
                })
        );
    }
    
    private Single<Book> fetchBookInfo(String url) {
        return Single.fromCallable(() -> {
            try {
                NovelParser parser = new UniversalParser(url);
                String title = parser.getTitle();
                String author = parser.getAuthor();
                return new Book("book_" + url.hashCode(), title, author, url);
            } catch (Exception e) {
                LOG.warn("获取书籍信息失败，将使用临时标题: " + e.getMessage(), e);
                return new Book("book_" + url.hashCode(), url, "", url);
            }
        }).subscribeOn(Schedulers.io());
    }

    private void deleteBook(String bookId) {
        LOG.info("Deleting book with id: " + bookId);
        Book bookToDelete = findBookInCurrentState(bookId);
        if (bookToDelete == null) {
            uiState.onNext(uiState.getValue().toBuilder().error("Cannot delete: book not found").build());
            return;
        }
        uiState.onNext(uiState.getValue().toBuilder().isLoadingBooks(true).build());
        disposables.add(
            bookService.removeBook(bookToDelete)
                .toObservable()
                .subscribe(success -> {
                    if(success) {
                        loadInitialData(); // Just reload everything
                    } else {
                        notificationService.showError("删除书籍失败", "无法删除书籍，请稍后再试。");
                        uiState.onNext(uiState.getValue().toBuilder().isLoadingBooks(false).build());
                    }
                }, error -> {
                    LOG.error("Failed to delete book", error);
                    notificationService.showError("删除书籍失败", error.getMessage());
                    uiState.onNext(uiState.getValue().toBuilder().isLoadingBooks(false).build());
                })
        );
    }
    
    private void saveProgress(String chapterId, int position) {
        String bookId = uiState.getValue().getSelectedBookId();
        if (bookId == null) return;

        // Fetch the latest book state from the service to ensure we have the correct lastReadPage
        // The book in uiState might be stale (from getAllBooks) and have default page 1
        disposables.add(
            bookService.getBookById(bookId)
                .flatMap(book -> bookService.saveReadingProgress(book, chapterId, "", position)
                    .andThen(Single.just(true)))
                .subscribeOn(Schedulers.io())
                .subscribe(
                    v -> {},
                    error -> LOG.error("Failed to save progress for chapter: " + chapterId, error)
                )
        );
    }

   private void handleExternalChapterChange(Book book, NovelParser.Chapter chapter) {
       if (book == null || chapter == null) {
           LOG.warn("Cannot handle external chapter change, book or chapter is null");
           return;
       }
       LOG.debug("Handling external chapter change for book: " + book.getTitle() + ", chapter: " + chapter.title());

       // First, ensure we have the latest chapter list for the book.
       disposables.add(
           chapterService.getChapterList(book)
               .toObservable()
               .subscribeOn(Schedulers.io())
               .subscribe(
                   chapters -> {
                       // With the latest chapter list, we can now safely update the state and load the content.
                       uiState.onNext(
                           uiState.getValue().toBuilder()
                               .selectedBookId(book.getId())
                               .chapters(chapters) // Update the chapter list in the state
                               .build()
                       );
                       // Now, trigger the content load for the specific chapter.
                       loadChapterContent(book, chapter);
                   },
                   error -> {
                       LOG.error("Failed to load chapters during external change for book: " + book.getId(), error);
                       uiState.onNext(
                           uiState.getValue().toBuilder()
                               .isLoadingChapters(false)
                               .build()
                       );
                       notificationService.showError("加载章节列表失败", error.getMessage());
                   }
               )
       );
   }

   private void preloadAdjacentChapters(Book book, NovelParser.Chapter currentChapter) {
       if (chapterPreloader == null || book == null || currentChapter == null) {
           return;
       }
       List<NovelParser.Chapter> chapters = uiState.getValue().getChapters();
       if (chapters == null || chapters.isEmpty()) {
           return;
       }

       int currentIndex = -1;
       for (int i = 0; i < chapters.size(); i++) {
           if (chapters.get(i).url().equals(currentChapter.url())) {
               currentIndex = i;
               break;
           }
       }

       if (currentIndex != -1) {
           final int indexToPreload = currentIndex;
           // The preloader runs asynchronously, so we just subscribe to it.
           // It's a service, so its lifecycle is managed by the application.
           // 优化：使用 subscribeOn(Schedulers.single()) 避免占用过多IO线程
           // 并且可以考虑限制预加载的数量，比如只预加载下一章
           // 使用 Completable.toObservable() 转换以兼容 subscribe
           chapterPreloader.preloadChaptersReactive(book, indexToPreload)
               .toObservable()
               .subscribeOn(Schedulers.single())
               .subscribe(
                   v -> { /* onNext is not called for Mono<Void>, do nothing */ },
                   error -> LOG.error("Error initiating chapter preloading", error),
                   () -> LOG.debug("Preloading initiated for chapters around index: " + indexToPreload)
               );
       }
   }

    @Override
    public void dispose() {
        disposables.dispose();
        if (!intentSubject.hasThrowable() && !intentSubject.hasComplete()) {
            intentSubject.onComplete();
        }
        if (!uiState.hasThrowable() && !uiState.hasComplete()) {
            uiState.onComplete();
        }
    }
} 