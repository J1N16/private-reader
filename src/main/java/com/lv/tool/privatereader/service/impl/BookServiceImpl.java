package com.lv.tool.privatereader.service.impl;

import com.intellij.openapi.application.ApplicationManager;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.repository.BookRepository;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import com.lv.tool.privatereader.service.BookService;
import com.lv.tool.privatereader.service.ChapterService;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public class BookServiceImpl implements BookService {

    private static final com.intellij.openapi.diagnostic.Logger LOG = com.intellij.openapi.diagnostic.Logger.getInstance(BookServiceImpl.class);

    private final BookRepository bookRepository;
    private final ReadingProgressRepository readingProgressRepository;

    /**
     * 无参构造函数，用于IntelliJ服务系统
     * 通过ApplicationManager获取服务实例
     */
    public BookServiceImpl() {
        this.bookRepository = ApplicationManager.getApplication().getService(BookRepository.class);
        this.readingProgressRepository = ApplicationManager.getApplication().getService(ReadingProgressRepository.class);
    }

    /**
     * 构造器注入，用于测试和依赖注入框架
     * @param bookRepository 书籍仓库
     * @param readingProgressRepository 阅读进度仓库
     */
    public BookServiceImpl(BookRepository bookRepository, ReadingProgressRepository readingProgressRepository) {
        this.bookRepository = bookRepository;
        this.readingProgressRepository = readingProgressRepository;
    }

    @Override
    public Observable<Book> getAllBooks() {
        return Single.fromCallable(() -> bookRepository.getAllBooks())
                .subscribeOn(Schedulers.io())
                .flatMapObservable(Observable::fromIterable);
    }

    @Override
    public Single<Book> getBookById(@NotNull String bookId) {
        return Single.fromCallable(() -> Optional.ofNullable(bookRepository.getBook(bookId)))
                .subscribeOn(Schedulers.io())
                .flatMap(optionalBook -> optionalBook.map(Single::just).orElseGet(() -> Single.error(new RuntimeException("Book not found: " + bookId))))
                .flatMap(this::loadProgressForBook);
    }

    @Override
    public Single<Boolean> addBook(@NotNull Book book) {
        return Single.<Boolean>create(emitter -> {
            try {
                bookRepository.addBook(book);
                emitter.onSuccess(true);
            } catch (Exception e) {
                LOG.warn("添加书籍失败: " + book.getTitle(), e);
                emitter.onError(e);
            }
        }).subscribeOn(Schedulers.io());
    }

    @Override
    public Single<Boolean> removeBook(@NotNull Book book) {
        return Single.<Boolean>create(emitter -> {
            try {
                readingProgressRepository.resetProgress(book);
                bookRepository.removeBook(book);
                emitter.onSuccess(true);
            } catch (Exception e) {
                LOG.warn("移除书籍失败: " + book.getTitle(), e);
                emitter.onError(e);
            }
        }).subscribeOn(Schedulers.io());
    }

    @Override
    public Single<Boolean> updateBook(@NotNull Book book) {
        return Single.<Boolean>create(emitter -> {
            try {
                bookRepository.updateBook(book);
                emitter.onSuccess(true);
            } catch (Exception e) {
                LOG.warn("更新书籍失败: " + book.getTitle(), e);
                emitter.onError(e);
            }
        }).subscribeOn(Schedulers.io());
    }

    @Override
    public Single<Book> getLastReadBook() {
        return Single.fromCallable(() -> readingProgressRepository.getLastReadProgressData())
                .subscribeOn(Schedulers.io())
                .flatMap(optionalProgress ->
                        optionalProgress.map(progress -> getBookById(progress.bookId()))
                                .orElse(Single.error(new RuntimeException("No last read book found")))
                );
    }

    @Override
    public Completable saveReadingProgress(@NotNull Book book, @NotNull String chapterId, String chapterTitle, int position) {
        return Completable.fromRunnable(() ->
                        readingProgressRepository.updateProgress(book, chapterId, chapterTitle, position))
                .subscribeOn(Schedulers.io());
    }

    private Single<Book> loadProgressForBook(Book book) {
        return Single.fromCallable(() -> readingProgressRepository.getProgress(book.getId()))
                .subscribeOn(Schedulers.io())
                .map(optionalProgress -> {
                    optionalProgress.ifPresent(progress -> book.updateReadingProgress(
                            progress.lastReadChapterId(),
                            progress.lastReadPosition(),
                            progress.lastReadPage()
                    ));
                    return book;
                });
    }

    @Override
    public java.util.List<ChapterService.EnhancedChapter> getChaptersSync(@NotNull String bookId) {
        return null; // Not implemented
    }

    @Override
    public void clearChaptersCache(String bookId) {
        // TODO: Implement cache clearing logic in the repository layer
    }
}
