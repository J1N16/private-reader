package com.lv.tool.privatereader.service.impl;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.exception.PrivateReaderException;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import com.lv.tool.privatereader.repository.BookRepository;
import com.lv.tool.privatereader.repository.ReactiveChapterCacheRepository;
import com.lv.tool.privatereader.service.ChapterService;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ChapterService接口的实现类
 * 使用响应式编程处理章节内容相关操作
 */
@Service(Service.Level.APP)
public final class ChapterServiceImpl implements ChapterService {
    private static final Logger LOG = Logger.getInstance(ChapterServiceImpl.class);
    private ReactiveChapterCacheRepository chapterCacheRepository;
    private BookRepository bookRepository;

    // 缓存相关 - 使用 Guava Cache 替代无限制的 ConcurrentHashMap
    // 书籍章节列表缓存：最多缓存 50 本书的章节列表，访问后 30 分钟过期
    private final Cache<String, List<Chapter>> bookChapterListCache = CacheBuilder.newBuilder()
            .maximumSize(50)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    // 正在进行的请求缓存：最多 50 个并发请求，写入后 5 分钟过期
    private final Cache<String, Single<List<Chapter>>> chapterListSingleCache = CacheBuilder.newBuilder()
            .maximumSize(50)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    // 网络请求频率限制缓存：记录上次网络请求时间，避免频繁刷新
    private final Cache<String, Long> lastNetworkCheckCache = CacheBuilder.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();

    /**
     * 无参构造方法
     */
    public ChapterServiceImpl() {
        LOG.info("初始化ChapterServiceImpl");
        // 服务将在首次需要时异步初始化
    }

    /**
     * 确保服务已初始化
     *
     * @throws IllegalStateException 如果服务初始化失败
     */
    private void ensureServicesInitialized() {
        // Initialize ChapterCacheRepository if needed
        if (chapterCacheRepository == null) {
            LOG.debug("尝试初始化 ChapterCacheRepository 服务");
            try {
                chapterCacheRepository = ApplicationManager.getApplication().getService(ReactiveChapterCacheRepository.class);
                if (chapterCacheRepository != null) {
                    LOG.debug("ChapterCacheRepository 服务初始化成功");
                } else {
                    LOG.error("ChapterCacheRepository 服务无法初始化!");
                }
            } catch (Exception e) {
                LOG.error("初始化 ChapterCacheRepository 服务时出错: " + e.getMessage(), e);
            }
        }

        // Initialize BookRepository directly if needed
        if (bookRepository == null) {
            LOG.debug("尝试初始化 BookRepository 服务");
            try {
                bookRepository = ApplicationManager.getApplication().getService(BookRepository.class);
                if (bookRepository == null) {
                    String errorMsg = "BookRepository 服务无法初始化!";
                    LOG.error(errorMsg, new IllegalStateException(errorMsg));
                } else {
                    LOG.debug("BookRepository 服务初始化成功");
                }
            } catch (Exception e) {
                LOG.error("初始化 BookRepository 服务时出错: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public Single<String> getChapterContent(@NotNull Book book, @NotNull String chapterId) {
        ensureServicesInitialized();
        return Single.defer(() -> {
            // 首先检查有效缓存
            String cachedContent = chapterCacheRepository.getCachedContent(book.getId(), chapterId);
            if (cachedContent != null) {
                return Single.just(cachedContent);
            }
            // 从网络获取
            return Single.fromCallable(() -> {
                NovelParser parser = book.getParser();
                if (parser == null) {
                    throw new IllegalStateException("Parser not available for book: " + book.getTitle());
                }
                String content = parser.parseChapterContent(chapterId);
                // 成功后缓存内容
                if (content != null && !content.isEmpty()) {
                    chapterCacheRepository.cacheContent(book.getId(), chapterId, content);
                }
                return content;
            }).subscribeOn(Schedulers.io())
            // 网络失败后回退到备用缓存
            .onErrorResumeNext(e -> {
                LOG.warn("从网络获取章节内容失败，回退到缓存: " + e.getMessage());
                String fallbackContent = chapterCacheRepository.getFallbackCachedContent(book.getId(), chapterId);
                if (fallbackContent != null) {
                    return Single.just(fallbackContent);
                }
                return Single.error(e); // 如果备用缓存也没有，则传递原始错误
            });
        });
    }

    @Override
    public String getChapterContentSync(@NotNull String bookId, @NotNull String chapterId) {
        ensureServicesInitialized();
        try {
            Book book = bookRepository.getBook(bookId);
            if (book == null) {
                return "错误: 未找到书籍。";
            }
            return getChapterContent(book, chapterId).blockingGet();
        } catch (Exception e) {
            return "获取章节内容失败: " + e.getMessage();
        }
    }

    @Override
    public Single<Chapter> getChapter(@NotNull Book book, @NotNull String chapterId) {
        ensureServicesInitialized();
        LOG.info("获取章节元数据: 书籍='" + book.getTitle() + "', 章节ID=" + chapterId);

        return getChapterList(book)
            .flatMap(chapters -> {
                return Observable.fromIterable(chapters)
                    .filter(chapter -> chapterId.equals(chapter.url()))
                    .firstElement()
                    .toSingle();
            })
            .doOnError(e -> {
                LOG.error("获取章节元数据失败: 书籍='" + book.getTitle() + "', 章节ID=" + chapterId, e);
            });
    }

    @Override
    public Single<Chapter> getChapterWithFallback(@NotNull Book book, @NotNull String chapterId) {
        ensureServicesInitialized();
        LOG.info("获取章节元数据(带回退): 书籍='" + book.getTitle() + "', 章节ID=" + chapterId);

        return getChapter(book, chapterId)
            .onErrorResumeNext(e -> {
                LOG.warn("在 getChapterWithFallback 中未找到章节: " + chapterId);
                return Single.error(new PrivateReaderException("无法找到章节: " + chapterId,
                    PrivateReaderException.ExceptionType.RESOURCE_NOT_FOUND));
            })
            .doOnError(e -> {
                LOG.error("获取章节元数据(带回退)失败: 书籍='" + book.getTitle() + "', 章节ID=" + chapterId, e);
            });
    }

    @Override
    public Single<List<Chapter>> getChapterList(@NotNull Book book) {
        ensureServicesInitialized();
        String bookId = book.getId();
        LOG.info("获取章节列表: 书籍='" + book.getTitle() + "' (缓存优先策略)");

        // 1. 缓存优先：立即从缓存返回数据
        Single<List<Chapter>> cachedChaptersSingle = fallbackToCache(book)
                .filter(chapters -> !chapters.isEmpty())
                .toSingle();

        // 2. 后台异步从网络获取最新数据
        Single<List<Chapter>> networkChaptersSingle = Single.fromCallable(() -> {
                    NovelParser parser = book.getParser();
                    if (parser == null) {
                        throw new IllegalStateException("Parser not initialized for book: " + book.getTitle());
                    }
                    return parser.parseChapterList();
                })
                .subscribeOn(Schedulers.io())
                .doOnSuccess(chaptersFromNetwork -> {
                    if (chaptersFromNetwork != null && !chaptersFromNetwork.isEmpty()) {
                        LOG.info("后台网络请求成功获取 " + chaptersFromNetwork.size() + " 个章节 for '" + book.getTitle() + "'. 更新缓存.");
                        // 检查与缓存是否相同，避免不必要的更新
                        List<Chapter> cachedList = bookChapterListCache.getIfPresent(bookId);
                        if (!chaptersFromNetwork.equals(cachedList)) {
                             // 更新内存缓存
                            bookChapterListCache.put(bookId, chaptersFromNetwork);
                            // 更新 Book 对象自身的持久化缓存
                            book.setCachedChapters(chaptersFromNetwork);
                            LOG.info("缓存已更新 for '" + book.getTitle() + "'.");
                        } else {
                            LOG.info("网络章节列表与缓存一致，无需更新 for '" + book.getTitle() + "'.");
                        }
                    } else {
                        LOG.warn("后台网络获取的章节列表为空 for '" + book.getTitle() + "'.");
                    }
                })
                .doOnError(error -> LOG.warn("后台网络获取章节列表失败 for '" + book.getTitle() + "': " + error.getMessage()));

        // 3. 合并缓存和网络请求
        // a. 立即返回缓存的结果
        // b. 触发后台网络请求，但不等待其完成（带频率限制和并发控制）
        // c. 如果缓存为空，则等待网络请求的结果
        return cachedChaptersSingle
                .doOnSuccess(chapters -> {
                    // 缓存命中时，检查是否需要后台刷新
                    if (lastNetworkCheckCache.getIfPresent(bookId) == null) {
                        try {
                            // 使用 chapterListSingleCache 确保同一时间只有一个后台更新任务在运行
                            chapterListSingleCache.get(bookId, () -> {
                                LOG.info("触发后台章节列表更新 for '" + book.getTitle() + "'");
                                lastNetworkCheckCache.put(bookId, System.currentTimeMillis());

                                // 创建并启动任务，任务完成后自动清理并发锁
                                Single<List<Chapter>> task = networkChaptersSingle
                                    .doFinally(() -> chapterListSingleCache.invalidate(bookId));

                                task.subscribe();
                                return task;
                            });
                        } catch (Exception e) {
                            LOG.warn("触发后台更新时发生异常: " + e.getMessage());
                        }
                    } else {
                        LOG.debug("跳过后台章节列表更新 (最近已更新) for '" + book.getTitle() + "'");
                    }
                })
                .onErrorResumeNext(e -> {
                    // 缓存未命中或出错，执行网络请求并记录时间
                    LOG.info("缓存未命中或出错，执行网络请求 for '" + book.getTitle() + "'");
                    lastNetworkCheckCache.put(bookId, System.currentTimeMillis());
                    return networkChaptersSingle
                        .doFinally(() -> chapterListSingleCache.invalidate(bookId));
                });
    }

    /**
     * 回退到缓存的辅助方法
     */
    private Single<List<Chapter>> fallbackToCache(Book book) {
        // 优先检查内存缓存
        List<Chapter> cachedChapters = bookChapterListCache.getIfPresent(book.getId());
        if (cachedChapters != null && !cachedChapters.isEmpty()) {
            LOG.info("从内存缓存回退成功 for book: '" + book.getTitle() + "'");
            // 同时，确保Book对象自身的缓存也是最新的
            if (book.getCachedChapters() == null || book.getCachedChapters().size() != cachedChapters.size()) {
                book.setCachedChapters(cachedChapters);
            }
            return Single.just(cachedChapters);
        }

        // 其次检查Book对象自身的持久化缓存
        List<Chapter> bookCachedChapters = book.getCachedChapters();
        if (bookCachedChapters != null && !bookCachedChapters.isEmpty()) {
            LOG.info("从Book对象持久化缓存回退成功 for book: '" + book.getTitle() + "'");
            // 将其放入内存缓存以备后用
            bookChapterListCache.put(book.getId(), bookCachedChapters);
            return Single.just(bookCachedChapters);
        }

        LOG.error("网络和所有缓存都获取章节列表失败 for book: '" + book.getTitle() + "'");
        return Single.just(List.of()); // 返回空列表作为最终的失败结果
    }

    @Override
    public Completable clearBookCache(@NotNull Book book) {
        ensureServicesInitialized();
        String bookId = book.getId();
        LOG.info("清除书籍缓存: " + book.getTitle() + " (ID: " + bookId + ")");
        // 将 Runnable 包装在 Completable 中，并在 io 线程执行
        return Completable.fromRunnable(() -> {
            bookChapterListCache.invalidate(bookId);
            chapterListSingleCache.invalidate(bookId);
            lastNetworkCheckCache.invalidate(bookId);
            chapterCacheRepository.clearCache(bookId);
        })
        .subscribeOn(Schedulers.io()); // 文件操作在 io 线程
    }

    @Override
    public Completable clearAllCache() {
        ensureServicesInitialized();
        LOG.info("清除所有章节缓存");
        // 将 Runnable 包装在 Completable 中，并在 io 线程执行
        return Completable.fromRunnable(() -> {
            bookChapterListCache.invalidateAll();
            chapterListSingleCache.invalidateAll();
            lastNetworkCheckCache.invalidateAll();
            chapterCacheRepository.clearAllCache();
        })
        .subscribeOn(Schedulers.io()); // 文件操作在 io 线程
    }

    @Override
    public Single<String> getChapterTitle(@NotNull String bookId, @NotNull String chapterId) {
        ensureServicesInitialized();
        LOG.info("异步获取章节标题: 书籍ID='" + bookId + "', 章节ID=" + chapterId);

        return Single.fromCallable(() -> bookRepository.getBook(bookId))
            .subscribeOn(Schedulers.io())
            .flatMap(book -> {
                if (book == null) {
                    LOG.warn("异步获取章节标题失败: 未找到书籍ID='" + bookId + "'");
                    return Single.just("Error: Book not found.");
                }
                return getChapterList(book)
                    .map(chapters -> chapters.stream()
                        .filter(c -> chapterId.equals(c.url()))
                        .findFirst()
                        .map(Chapter::title)
                        .orElse("Error: Chapter not found in list."));
            })
            .doOnError(e -> LOG.error("异步获取章节标题时发生错误: 书籍ID='" + bookId + "', 章节ID=" + chapterId, e));
    }
}
