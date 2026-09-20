package com.lv.tool.privatereader.repository.impl;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookIndex;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.repository.BookRepository;
import com.lv.tool.privatereader.repository.StorageRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件书籍仓库实现
 *
 * 基于文件系统实现书籍仓库接口,管理书籍数据的持久化存储。
 * 采用分离存储方案:
 * - 主索引文件:存储所有书籍的基本信息,位于 private-reader/books/index.json
 * - 书籍详情文件:每本书单独存储详细信息,位于 private-reader/books/{bookId}/details.json
 *
 * 职责拆分(V13):
 * - {@link BookJsonCodec}:安全 Gson 构建与 Book/BookIndex JSON 互转(纯逻辑)
 * - {@link BookIndexStore}:索引文件 IO(读/写/增删改)
 * - {@link BookDetailsIo}:详情文件 IO(原子写入/备份/目录清理)
 * 本类仅保留:内存缓存、章节缺失时的 URL 补充、损坏文件清理/阅读位置修复的调度,以及
 * 各公开方法对应外围入口(保持 API 不变)。
 */
@Service(Service.Level.APP)
public final class FileBookRepository implements BookRepository {
    private static final Logger LOG = Logger.getInstance(FileBookRepository.class);
    private static final int MAX_CACHE_SIZE = 100; // 最大内存缓存数量
    private static final int MAX_CHAPTER_FETCH_RETRY = 3; // 最大章节获取重试次数

    // 记录每本书尝试从URL获取章节列表的次数
    private static final Map<String, Integer> chapterFetchRetryCount = new ConcurrentHashMap<>();

    private final StorageRepository storageRepository;
    private final BookJsonCodec codec;
    private final BookIndexStore indexStore;
    private final BookDetailsIo detailsIo;

    // 内存缓存,使用 Guava 统一处理容量和过期淘汰
    private final Cache<String, CacheEntry> bookCache = CacheBuilder.newBuilder()
            .maximumSize(MAX_CACHE_SIZE)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    // 缓存条目
    private static class CacheEntry {
        final Book book;

        CacheEntry(Book book) {
            this.book = book;
        }
    }

    public FileBookRepository() {
        this(com.intellij.openapi.application.ApplicationManager.getApplication().getService(StorageRepository.class));
        // 启动后台线程,在应用启动后清理和修复损坏的书籍文件
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                LOG.info("开始自动检查和修复书籍文件...");
                // 首先清理损坏的文件
                int cleanedCount = cleanupCorruptedBooks();
                LOG.info("已清理 " + cleanedCount + " 个损坏的书籍文件");

                // 然后修复阅读位置和内容
                int repairedCount = repairMissingReadingContent();
                LOG.info("已修复 " + repairedCount + " 本书籍的阅读位置");
            } catch (Exception e) {
                LOG.error("自动修复过程中出错: " + e.getMessage(), e);
            }
        });
    }

    FileBookRepository(StorageRepository storageRepository) {
        this.storageRepository = storageRepository;
        this.codec = new BookJsonCodec();
        this.indexStore = new BookIndexStore(storageRepository, codec);
        this.detailsIo = new BookDetailsIo(storageRepository, codec);
        chapterFetchRetryCount.clear();
    }

    @Override
    @NotNull
    public List<Book> getAllBooks(boolean loadDetails) {
        List<Book> books = new ArrayList<>();

        try {
            // 读取所有书籍索引
            List<BookIndex> indices = indexStore.read();

            for (BookIndex index : indices) {
                try {
                    if (loadDetails) {
                        // 加载完整书籍信息
                        Book book = null;
                        try {
                            book = getBook(index.getId());
                        } catch (Exception e) {
                            LOG.error("获取书籍详情失败 [" + index.getId() + "]: " + e.getMessage(), e);
                        }

                        if (book != null) {
                            books.add(book);
                        } else {
                            // 如果详情获取失败,尝试从索引创建简化版本
                            LOG.warn("无法加载书籍详情: " + index.getId() + ",创建简化版本");
                            books.add(simpleBookFromIndex(index));
                        }
                    } else {
                        // 只使用索引信息创建简化版本
                        books.add(simpleBookFromIndex(index));
                    }
                } catch (Exception e) {
                    LOG.error("加载书籍失败 [" + index.getId() + "]: " + e.getMessage(), e);
                    // 继续处理下一本书
                }
            }
        } catch (Exception e) {
            LOG.error("获取书籍列表失败: " + e.getMessage(), e);
        }

        // 按最后阅读时间排序,最近阅读的排在前面
        books.sort((b1, b2) -> Long.compare(b2.getLastReadTimeMillis(), b1.getLastReadTimeMillis()));

        return books;
    }

    @Override
    @NotNull
    public List<Book> getAllBooks() {
        return getAllBooks(true);
    }

    @Override
    @Nullable
    public Book getBook(String bookId) {
        if (bookId == null || bookId.isEmpty()) {
            return null;
        }

        // 1. 尝试从缓存获取
        CacheEntry cachedEntry = bookCache.getIfPresent(bookId);
        if (cachedEntry != null) {
            // Add Debug Log for cache hit
            LOG.debug(String.format("Cache hit for Book ID: %s. Checking completeness...", bookId));

            // === Check Cache Completeness Start ===
            Book cachedBook = cachedEntry.book;
            if (cachedBook.getCachedChapters() == null || cachedBook.getCachedChapters().isEmpty()) {
                // 检查重试次数
                int retryCount = chapterFetchRetryCount.getOrDefault(bookId, 0);
                if (retryCount >= MAX_CHAPTER_FETCH_RETRY) {
                    LOG.warn("书籍 [" + bookId + "] 已达到最大重试次数 (" + MAX_CHAPTER_FETCH_RETRY + "),不再尝试从文件重新加载章节列表");
                    // 返回当前可能不完整的缓存版本
                    return cachedBook;
                }
                // 增加重试计数
                chapterFetchRetryCount.put(bookId, retryCount + 1);

                LOG.warn("缓存中的书籍 [" + bookId + "] 缺少章节列表,尝试从文件重新加载... (重试次数: " + (retryCount + 1) + "/" + MAX_CHAPTER_FETCH_RETRY + ")");
                try {
                    String jsonContent = detailsIo.readDetails(bookId);
                    if (jsonContent != null) {
                        Book fileBook = codec.parseBook(jsonContent, bookId); // Use parsing method
                        if (fileBook != null) {
                            //修复数据不一致
                            fileBook = restoreLastReadingPosition(fileBook);
                            if (fileBook.getCachedChapters() != null && !fileBook.getCachedChapters().isEmpty()) {
                                LOG.info("成功从文件为 [" + bookId + "] 加载了章节列表,更新缓存。");
                                bookCache.put(bookId, new CacheEntry(fileBook));

                                // 重置重试计数
                                chapterFetchRetryCount.remove(bookId);
                                LOG.debug("重置书籍 [" + bookId + "] 的重试计数");

                                return fileBook; // 返回从文件加载的完整对象
                            } else {
                                LOG.warn("从文件重新加载 [" + bookId + "] 仍未获取到章节列表。");
                            }
                        } else {
                            LOG.warn("从文件解析书籍失败,无法更新缓存中的章节列表: " + bookId);
                        }
                    }
                } catch (Exception e) {
                    LOG.error("尝试从文件为 [" + bookId + "] 重新加载章节列表时出错: " + e.getMessage(), e);
                }
                // 如果重新加载失败或文件版也没有章节,仍然返回(可能不完整的)缓存版本
                LOG.debug("Returning potentially incomplete cached book after failed reload attempt: " + bookId);
                return cachedBook;
            } else {
                // 缓存中的书籍已有章节,直接返回
                LOG.debug(String.format("Cache hit for Book ID: %s. Returning complete cached book: Title='%s', ChapterID=%s, Pos=%d, Page=%d",
                    bookId, cachedBook.getTitle(), cachedBook.getLastReadChapterId(),
                    cachedBook.getLastReadPosition(), cachedBook.getLastReadPage()));
                return cachedBook;
            }
            // === Check Cache Completeness End ===
        }

        // 2. 如果缓存未命中或已过期,从文件加载
        try {
            String jsonContent = detailsIo.readDetails(bookId);
            if (jsonContent == null) {
                LOG.warn("书籍详情文件不存在: " + bookId);
                //尝试从索引恢复
                Book recoveredBook = recoverBookFromIndex(bookId);
                if (recoveredBook != null) {
                    LOG.info("从索引恢复书籍成功: " + bookId);
                    detailsIo.saveDetails(recoveredBook); // 保存恢复的数据
                    bookCache.put(bookId, new CacheEntry(recoveredBook));
                    return recoveredBook;
                }
                return null;
            }

            Book fileBook = null;
            try {
                fileBook = codec.parseBook(jsonContent, bookId); //解析书籍

                // === Fetch Missing Chapters Logic Start ===
                if (fileBook != null && (fileBook.getCachedChapters() == null || fileBook.getCachedChapters().isEmpty()) &&
                    fileBook.getUrl() != null && !fileBook.getUrl().isEmpty()) {

                    // 检查重试次数
                    int retryCount = chapterFetchRetryCount.getOrDefault(bookId, 0);
                    if (retryCount >= MAX_CHAPTER_FETCH_RETRY) {
                        LOG.warn("书籍 [" + bookId + "] 已达到最大重试次数 (" + MAX_CHAPTER_FETCH_RETRY + "),不再尝试从 URL 获取章节列表");
                        // 返回当前可能不完整的书籍对象
                        return fileBook;
                    }
                    // 增加重试计数
                    chapterFetchRetryCount.put(bookId, retryCount + 1);

                    LOG.warn("书籍 [" + bookId + "] details.json 文件缺少章节列表,尝试从 URL 重新获取... (重试次数: " + (retryCount + 1) + "/" + MAX_CHAPTER_FETCH_RETRY + ")");
                    try {
                        // 获取章节服务实例
                        com.lv.tool.privatereader.service.ChapterService chapterService =
                            com.intellij.openapi.application.ApplicationManager.getApplication().getService(com.lv.tool.privatereader.service.ChapterService.class);

                        if (chapterService != null) {
                            // 调用服务获取章节 (使用阻塞方式获取,注意潜在性能影响)
                            LOG.debug("调用 chapterService.getChapterList for " + bookId);

                            io.reactivex.rxjava3.core.Single<List<NovelParser.Chapter>> chapterListSingle =
                                chapterService.getChapterList(fileBook);
                            List<NovelParser.Chapter> fetchedChapters = chapterListSingle.blockingGet();

                            if (fetchedChapters != null && !fetchedChapters.isEmpty()) {
                                LOG.info("成功从 URL 为书籍 [" + bookId + "] 获取到 " + fetchedChapters.size() + " 个章节。");
                                fileBook.setCachedChapters(fetchedChapters);

                                // 将补充了章节的书籍信息保存回文件
                                LOG.warn("将获取到的章节列表保存回 details.json 文件: " + bookId);
                                detailsIo.saveDetails(fileBook);

                                // 重置重试计数
                                chapterFetchRetryCount.remove(bookId);
                                LOG.debug("重置书籍 [" + bookId + "] 的重试计数");
                                // 保存后,缓存会在下面更新
                            } else {
                                LOG.warn("从 URL 未能获取到书籍 [" + bookId + "] 的章节列表。");
                            }
                        } else {
                            LOG.error("无法获取 ReactiveChapterService 实例,无法为 [" + bookId + "] 获取章节。");
                        }
                    } catch (Exception fetchEx) {
                        LOG.error("尝试为书籍 [" + bookId + "] 从 URL 获取章节列表时出错: " + fetchEx.getMessage(), fetchEx);
                        // 即使获取失败,也继续使用从文件解析出的(缺少章节的)book 对象
                    }
                }
                // === Fetch Missing Chapters Logic End ===

                if (fileBook != null) {
                    // 修复可能的阅读位置数据不一致
                    fileBook = restoreLastReadingPosition(fileBook);

                    if (fileBook != null) {
                        LOG.debug(String.format("Cache miss for Book ID: %s. Loaded from file: Title='%s', ChapterID=%s, Pos=%d, Page=%d",
                                bookId, fileBook.getTitle(), fileBook.getLastReadChapterId(),
                                fileBook.getLastReadPosition(), fileBook.getLastReadPage()));
                        // 更新缓存 (使用可能已补充章节的 fileBook)
                        bookCache.put(bookId, new CacheEntry(fileBook));
                    }
                }
            } catch (Exception parseEx) {
                // Handle parsing error (potentially recover from index)
                LOG.error("解析文件获取书籍失败,无法处理损坏的文件: " + parseEx.getMessage(), parseEx);
                Book recoveredBook = recoverBookFromIndex(bookId);
                if (recoveredBook != null) {
                    LOG.info("从索引恢复书籍成功 (after parse error): " + bookId);
                    detailsIo.saveDetails(recoveredBook);
                    bookCache.put(bookId, new CacheEntry(recoveredBook));
                    return recoveredBook;
                }
            }
            return fileBook; // 返回从文件加载(并可能已补充章节)的书籍

        } catch (Exception e) {
            LOG.error("获取书籍失败: " + e.getMessage(), e);
        }

        return null;
    }

    /**
     * 确保阅读位置数据的完整性
     * 如果主要字段丢失,尝试从备用字段恢复
     *
     * @param book 需要检查的书籍对象
     * @return 修复后的书籍对象
     */
    private Book restoreLastReadingPosition(Book book) {
        if (book == null) {
            return null;
        }

        LOG.debug("检查并修复书籍阅读位置数据: " + book.getTitle());

        // 1. 确保lastReadChapterId不为空
        if (book.getLastReadChapterId() == null && book.getLastReadChapter() != null) {
            LOG.info("发现lastReadChapterId为空但lastReadChapter不为空,尝试恢复章节ID");

            // 如果有缓存的章节,尝试从标题匹配章节ID
            if (book.getCachedChapters() != null && !book.getCachedChapters().isEmpty()) {
                for (NovelParser.Chapter chapter : book.getCachedChapters()) {
                    if (book.getLastReadChapter().equals(chapter.title())) {
                        book.setLastReadChapterId(chapter.url());
                        LOG.info("成功恢复章节ID: " + chapter.url());
                        break;
                    }
                }
            }
        }

        // 2. 确保currentChapterIndex与lastReadChapterId一致
        if (book.getLastReadChapterId() != null && book.getCachedChapters() != null &&
            !book.getCachedChapters().isEmpty() && book.getCurrentChapterIndex() <= 0) {

            LOG.info("尝试根据lastReadChapterId恢复章节索引");

            for (int i = 0; i < book.getCachedChapters().size(); i++) {
                if (book.getLastReadChapterId().equals(book.getCachedChapters().get(i).url())) {
                    // currentChapterIndex是1-based索引
                    book.setCurrentChapterIndex(i + 1);
                    LOG.info("成功恢复章节索引: " + (i + 1));
                    break;
                }
            }
        }

        // 3. 确保lastReadPosition和lastReadPage有合理的值
        if (book.getLastReadPosition() < 0) {
            book.setLastReadPosition(0);
            LOG.info("重置无效的lastReadPosition");
        }

        if (book.getLastReadPage() <= 0) {
            book.setLastReadPage(1);
            LOG.info("重置无效的lastReadPage");
        }

        // 4. 确保最后阅读时间有值
        if (book.getLastReadTimeMillis() <= 0) {
            book.setLastReadTimeMillis(System.currentTimeMillis());
            LOG.info("设置缺失的lastReadTimeMillis");
        }

        return book;
    }

    /**
     * 从索引中恢复书籍信息
     * 当书籍详情文件损坏时,尝试从索引中获取基本信息
     */
    private Book recoverBookFromIndex(String bookId) {
        try {
            List<BookIndex> indices = indexStore.read();
            for (BookIndex index : indices) {
                if (index.getId().equals(bookId)) {
                    return bookFromIndex(index);
                }
            }
        } catch (Exception e) {
            LOG.error("从索引恢复书籍信息失败: " + e.getMessage(), e);
        }

        return null;
    }

    /**
     * 用索引信息创建简化书籍对象
     */
    private static Book bookFromIndex(BookIndex index) {
        Book book = new Book(index.getId(), index.getTitle(), index.getAuthor(), index.getUrl());
        book.setCreateTimeMillis(index.getCreateTimeMillis());
        book.setLastChapter(index.getLastChapter());
        book.setLastReadTimeMillis(index.getLastReadTimeMillis());
        book.setTotalChapters(index.getTotalChapters());
        book.setFinished(index.isFinished());
        return book;
    }

    private static Book simpleBookFromIndex(BookIndex index) {
        return bookFromIndex(index);
    }

    @Override
    public void addBook(@NotNull Book book) {
        if (book == null || book.getId() == null || book.getId().isEmpty()) {
            LOG.warn("无法添加书籍:book 或 bookId 为空");
            return;
        }

        try {
            // 创建书籍目录
            storageRepository.createBookDirectory(book.getId());

            // 保存书籍详情
            detailsIo.saveDetails(book);

            // 更新索引文件
            indexStore.updateAndSave(book);

            // 添加到缓存
            bookCache.put(book.getId(), new CacheEntry(book));

            LOG.info("添加书籍成功: " + book.getTitle());
        } catch (Exception e) {
            LOG.error("添加书籍失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void updateBook(@NotNull Book book) {
        if (book == null || book.getId() == null || book.getId().isEmpty()) {
            LOG.warn("无法更新书籍:book 或 bookId 为空");
            return;
        }

        try {
            // Add log at start
            LOG.debug("[SAVE_TRACE] FBR.updateBook: Starting update for book: " + book.getId());
            // === Preserve Chapters Logic Start ===
            // Load existing book data to preserve chapter list if the input book doesn't have it
            Book existingBook = getBook(book.getId()); // Use getBook which handles cache/file loading
            if (existingBook != null) {
                if ((book.getCachedChapters() == null || book.getCachedChapters().isEmpty()) &&
                    (existingBook.getCachedChapters() != null && !existingBook.getCachedChapters().isEmpty())) {

                    LOG.debug("Preserving existing chapter list for book: " + book.getId() + " Size: " + existingBook.getCachedChapters().size());
                    book.setCachedChapters(existingBook.getCachedChapters());
                }
            } else {
                LOG.warn("Update called for book ID not found in storage: " + book.getId() + ". Saving as new/overwriting.");
            }
            // === Preserve Chapters Logic End ===

            // 保存书籍详情 (now potentially with chapters preserved)
            LOG.debug("[SAVE_TRACE] FBR.updateBook: Calling saveBookDetails for book: " + book.getId());
            detailsIo.saveDetails(book);
            LOG.debug("[SAVE_TRACE] FBR.updateBook: Returned from saveBookDetails for book: " + book.getId());

            // 更新索引文件
            LOG.debug("[SAVE_TRACE] FBR.updateBook: Calling updateBookIndex for book: " + book.getId());
            indexStore.updateAndSave(book);
            LOG.debug("[SAVE_TRACE] FBR.updateBook: Returned from updateBookIndex for book: " + book.getId());

            // 更新缓存
            bookCache.put(book.getId(), new CacheEntry(book));

            LOG.info("更新书籍成功: " + book.getTitle());
        } catch (Exception e) {
            LOG.error("[SAVE_TRACE] FBR.updateBook: Exception during update for book: " + book.getId(), e);
        }
    }

    @Override
    public void updateBooks(@NotNull List<Book> books) {
        if (books == null || books.isEmpty()) {
            return;
        }

        for (Book book : books) {
            updateBook(book);
        }
    }

    @Override
    public void removeBook(@NotNull Book book) {
        if (book == null || book.getId() == null || book.getId().isEmpty()) {
            LOG.warn("无法删除书籍:book 或 bookId 为空");
            return;
        }

        try {
            // 删除书籍目录
            detailsIo.deleteBookDirectory(book.getId());

            // 从索引文件中移除
            indexStore.removeAndSave(book.getId());

            // 从缓存中移除
            bookCache.invalidate(book.getId());

            LOG.info("删除书籍成功: " + book.getTitle());
        } catch (Exception e) {
            LOG.error("删除书籍失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void clearAllBooks() {
        try {
            // 清空书籍目录
            File booksDir = new File(storageRepository.getBooksPath());
            if (booksDir.exists() && booksDir.isDirectory()) {
                File[] files = booksDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isDirectory()) {
                            detailsIo.deleteBookDirectory(file.getName());
                        } else if (!file.getName().equals("index.json")) {
                            file.delete();
                        }
                    }
                }
            }

            // 清空索引文件
            indexStore.clear();

            // 清空缓存
            bookCache.invalidateAll();

            LOG.info("清空所有书籍成功");
        } catch (Exception e) {
            LOG.error("清空所有书籍失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getIndexFilePath() {
        return storageRepository.getBooksFilePath();
    }

    /**
     * 保存书籍详情到文件(保持公开 API 不变,内部委托 BookDetailsIo)。
     *
     * @param book 书籍对象
     */
    public void saveBookDetails(Book book) {
        detailsIo.saveDetails(book);
    }

    /**
     * 更新书籍索引(委托 BookIndexStore)。
     */
    private void updateBookIndex(Book book) {
        indexStore.updateAndSave(book);
    }

    /**
     * 从索引中移除书籍(委托 BookIndexStore)。
     */
    private void removeBookFromIndex(String bookId) {
        indexStore.removeAndSave(bookId);
    }

    /**
     * 读取书籍索引列表(委托 BookIndexStore)。
     */
    private List<BookIndex> readBookIndices() {
        return indexStore.read();
    }

    /**
     * 保存书籍索引列表(委托 BookIndexStore)。
     */
    private void saveBookIndices(List<BookIndex> indices) {
        indexStore.save(indices);
    }

    /**
     * 标记并备份损坏的书籍文件
     * @param bookId 书籍ID
     */
    private void markCorruptedBookFile(String bookId) {
        try {
            // 备份损坏的 details.json
            detailsIo.backupCorrupted(bookId);

            // 创建最小化书籍对象并保存
            try {
                Book minimalBook = recoverBookFromIndex(bookId);
                if (minimalBook == null) {
                    minimalBook = new Book(bookId, "恢复的书籍 " + bookId, "未知", "");
                    minimalBook.setCreateTimeMillis(System.currentTimeMillis());
                }

                // 保存最小化书籍对象,使用手动JSON构建
                detailsIo.saveDetails(minimalBook);
                LOG.info("已创建替代书籍对象: " + minimalBook.getTitle());

                // 添加到缓存
                bookCache.put(bookId, new CacheEntry(minimalBook));
            } catch (Exception e) {
                LOG.error("创建替代书籍对象失败: " + e.getMessage(), e);

                // 如果恢复失败,则删除原始文件以避免后续再次触发相同错误
                try {
                    File detailsFile = new File(storageRepository.getBookDirectory(bookId), "details.json");
                    detailsFile.delete();
                    LOG.info("已删除无法修复的损坏文件: " + detailsFile.getAbsolutePath());
                } catch (Exception ex) {
                    LOG.error("删除损坏文件失败: " + ex.getMessage(), ex);
                }
            }
        } catch (Exception e) {
            LOG.error("处理损坏书籍文件失败 [" + bookId + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 清理损坏的书籍文件
     *
     * @return 已清理的文件数量
     */
    public int cleanupCorruptedBooks() {
        int cleanedCount = 0;

        try {
            File booksDir = new File(storageRepository.getBooksPath());
            if (!booksDir.exists() || !booksDir.isDirectory()) {
                return 0;
            }

            File[] bookDirs = booksDir.listFiles(File::isDirectory);
            if (bookDirs == null || bookDirs.length == 0) {
                return 0;
            }

            for (File bookDir : bookDirs) {
                String bookId = bookDir.getName();
                if (bookId == null || bookId.isEmpty()) {
                    continue;
                }

                // 读取详情内容,内含 jsoup 残留检测
                String content = detailsIo.readDetails(bookId);
                if (content == null) {
                    continue;
                }

                try {
                    if (content.contains("org.jsoup.parser") ||
                        content.contains("org.jsoup.nodes") ||
                        content.contains("parentNode\":{") ||
                        content.contains("childNodes") ||
                        content.contains("HtmlTreeBuilder")) {

                        // 备份并修复这个文件
                        markCorruptedBookFile(bookId);
                        cleanedCount++;
                    }
                } catch (Exception e) {
                    LOG.warn("检查书籍文件时出错: " + bookId + " - " + e.getMessage());
                }
            }

            LOG.info("已清理 " + cleanedCount + " 个损坏的书籍文件");

            // 清理重试计数Map
            chapterFetchRetryCount.clear();
            LOG.debug("已清理章节获取重试计数");
        } catch (Exception e) {
            LOG.error("清理损坏书籍文件失败: " + e.getMessage(), e);
        }

        return cleanedCount;
    }

    /**
     * 修复丢失的阅读内容和位置
     * 尝试检查并修复Books中可能丢失的阅读位置和内容信息
     *
     * @return 已修复的书籍数量
     */
    public int repairMissingReadingContent() {
        int repairedCount = 0;

        try {
            // 读取书籍索引列表,避免递归调用getAllBooks
            List<BookIndex> indices = indexStore.read();
            List<Book> books = new ArrayList<>();

            // 直接从索引加载简化的书籍对象
            for (BookIndex index : indices) {
                try {
                    Book book = getBook(index.getId());
                    if (book != null) {
                        books.add(book);
                    }
                } catch (Exception e) {
                    LOG.error("加载书籍失败 [" + index.getId() + "]: " + e.getMessage(), e);
                }
            }

            if (books.isEmpty()) {
                return 0;
            }

            for (Book book : books) {
                boolean needsRepair = false;

                // 检查是否需要修复
                if (book.getLastReadChapterId() != null && book.getLastReadChapter() != null) {
                    if (book.getCurrentChapterIndex() <= 0) {
                        // 有上次阅读章节但章节索引无效
                        needsRepair = true;
                    }

                    // 检查阅读位置是否有效
                    if (book.getLastReadPosition() < 0) {
                        needsRepair = true;
                    }
                }

                if (needsRepair) {
                    LOG.info("修复书籍阅读位置: " + book.getTitle());

                    // 应用修复
                    book = restoreLastReadingPosition(book);

                    // 保存修复后的书籍
                    detailsIo.saveDetails(book);

                    // 更新书籍索引
                    indexStore.updateAndSave(book);

                    repairedCount++;
                }
            }

            LOG.info("已修复 " + repairedCount + " 本书籍的阅读位置");
        } catch (Exception e) {
            LOG.error("修复阅读位置失败: " + e.getMessage(), e);
        }

        return repairedCount;
    }
}