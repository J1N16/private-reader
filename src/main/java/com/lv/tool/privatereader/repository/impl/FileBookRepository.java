package com.lv.tool.privatereader.repository.impl;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookIndex;
import com.lv.tool.privatereader.repository.BookRepository;
import com.lv.tool.privatereader.repository.StorageRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.lv.tool.privatereader.service.ChapterService;
import com.intellij.openapi.components.Service;

/**
 * 文件书籍仓库实现
 *
 * 基于文件系统实现书籍仓库接口，管理书籍数据的持久化存储。
 * 采用分离存储方案：
 * - 主索引文件：存储所有书籍的基本信息，位于 private-reader/books/index.json
 * - 书籍详情文件：每本书单独存储详细信息，位于 private-reader/books/{bookId}/details.json
 */
@Service(Service.Level.APP)
public final class FileBookRepository implements BookRepository {
    private static final Logger LOG = Logger.getInstance(FileBookRepository.class);
    private static final int MAX_CACHE_SIZE = 100; // 最大内存缓存数量
    private static final int MAX_CHAPTER_FETCH_RETRY = 3; // 最大章节获取重试次数

    // 记录每本书尝试从URL获取章节列表的次数
    private static final Map<String, Integer> chapterFetchRetryCount = new ConcurrentHashMap<>();

    private final StorageRepository storageRepository;
    private final Gson gson;

    // 内存缓存，使用 Guava 统一处理容量和过期淘汰
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
        this.storageRepository = com.intellij.openapi.application.ApplicationManager.getApplication().getService(StorageRepository.class);
        this.gson = createSecureGson();

        // 清空重试计数Map
        chapterFetchRetryCount.clear();

        // 启动后台线程，在应用启动后清理和修复损坏的书籍文件
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
        this.gson = createSecureGson();
        chapterFetchRetryCount.clear();
    }

    // 共享的排除策略实例，用于序列化和反序列化
    private static final com.google.gson.ExclusionStrategy PROBLEMATIC_TYPE_EXCLUSION_STRATEGY = new com.google.gson.ExclusionStrategy() {
        @Override
        public boolean shouldSkipField(com.google.gson.FieldAttributes f) {
            String className = f.getDeclaringClass().getName();
            String fieldName = f.getName();

            // 排除所有可能导致问题的字段
            return className.startsWith("java.lang.invoke.") ||
                   className.startsWith("java.lang.reflect.") ||
                   className.contains("MethodType") ||
                   className.contains("com.intellij.") ||
                   className.contains("com.jetbrains.") ||
                   fieldName.equals("rtype") ||
                   fieldName.equals("ptypes") ||
                   fieldName.equals("supportedSignaturesOfLightServiceConstructors") ||
                   fieldName.equals("myContainer") ||
                   fieldName.equals("myDisposed") ||
                   fieldName.equals("myParentComponentManager") ||
                   fieldName.startsWith("my") || // 排除所有my开头的字段，这是IntelliJ常用的命名
                   fieldName.startsWith("_");   // 排除所有_开头的字段
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            String className = clazz.getName();
            // 排除所有可能导致问题的类
            return className.startsWith("java.lang.invoke.") ||
                   className.startsWith("java.lang.reflect.") ||
                   className.contains("MethodType") ||
                   className.contains("com.intellij.") ||
                   className.contains("com.jetbrains.");
        }
    };

    /**
     * 创建安全配置的Gson实例，避免访问JDK内部类引起的模块系统限制
     * 并解决IntelliJ平台特有的序列化冲突问题
     *
     * @return 安全配置的Gson实例
     */
    private Gson createSecureGson() {
        // 创建排除TypeAdapter工厂，处理可能导致反射错误的类型
        com.google.gson.TypeAdapterFactory excludeProblematicTypesFactory = new com.google.gson.TypeAdapterFactory() {
            @Override
            public <T> com.google.gson.TypeAdapter<T> create(com.google.gson.Gson gson, com.google.gson.reflect.TypeToken<T> type) {
                Class<? super T> rawType = type.getRawType();
                // 排除 MethodType、反射类以及IntelliJ平台特定的类
                if (rawType.getName().startsWith("java.lang.invoke.") ||
                    rawType.getName().startsWith("java.lang.reflect.") ||
                    rawType.getName().contains("MethodType") ||
                    rawType.getName().contains("com.intellij.openapi.project.impl.") ||
                    rawType.getName().contains("com.intellij.serviceContainer.") ||
                    rawType.getName().contains("com.intellij.") ||
                    rawType.getName().contains("com.jetbrains.")) {

                    // 返回一个更简单的适配器，直接写入null而不尝试遍历字段
                    @SuppressWarnings("unchecked")
                    com.google.gson.TypeAdapter<T> adapter = (com.google.gson.TypeAdapter<T>) new com.google.gson.TypeAdapter<Object>() {
                        @Override
                        public void write(com.google.gson.stream.JsonWriter out, Object value) throws IOException {
                            // 简单地写null，避免遍历复杂对象的字段导致的递归问题
                            out.nullValue();
                        }

                        @Override
                        public Object read(com.google.gson.stream.JsonReader in) throws IOException {
                            // 简单跳过读取
                            in.skipValue();
                            return null;
                        }
                    };
                    return adapter;
                }
                return null; // 让Gson处理其他类型
            }
        };

        // 更安全的禁止序列化策略
        return new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping() // 禁用HTML转义
                .disableJdkUnsafe() // 禁用不安全的JDK访问
                .excludeFieldsWithModifiers(java.lang.reflect.Modifier.TRANSIENT,
                                           java.lang.reflect.Modifier.STATIC) // 排除transient和static字段
                .registerTypeAdapterFactory(excludeProblematicTypesFactory) // 注册我们自定义的类型适配器工厂
                // 使用共享的排除策略
                .addSerializationExclusionStrategy(PROBLEMATIC_TYPE_EXCLUSION_STRATEGY)
                .addDeserializationExclusionStrategy(PROBLEMATIC_TYPE_EXCLUSION_STRATEGY)
                .serializeNulls() // 序列化 null 值
                .create();
    }

    @Override
    @NotNull
    public List<Book> getAllBooks(boolean loadDetails) {
        List<Book> books = new ArrayList<>();

        try {
            // 读取所有书籍索引
            List<BookIndex> indices = readBookIndices();

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
                            // 如果详情获取失败，尝试从索引创建简化版本
                            LOG.warn("无法加载书籍详情: " + index.getId() + "，创建简化版本");
                            Book simpleBook = new Book(index.getId(), index.getTitle(), index.getAuthor(), index.getUrl());
                            simpleBook.setCreateTimeMillis(index.getCreateTimeMillis());
                            simpleBook.setLastChapter(index.getLastChapter());
                            simpleBook.setLastReadTimeMillis(index.getLastReadTimeMillis());
                            simpleBook.setTotalChapters(index.getTotalChapters());
                            simpleBook.setFinished(index.isFinished());
                            books.add(simpleBook);
                        }
                    } else {
                        // 只使用索引信息创建简化版本
                        Book simpleBook = new Book(index.getId(), index.getTitle(), index.getAuthor(), index.getUrl());
                        simpleBook.setCreateTimeMillis(index.getCreateTimeMillis());
                        simpleBook.setLastChapter(index.getLastChapter());
                        simpleBook.setLastReadTimeMillis(index.getLastReadTimeMillis());
                        simpleBook.setTotalChapters(index.getTotalChapters());
                        simpleBook.setFinished(index.isFinished());
                        books.add(simpleBook);
                    }
                } catch (Exception e) {
                    LOG.error("加载书籍失败 [" + index.getId() + "]: " + e.getMessage(), e);
                    // 继续处理下一本书
                }
            }
        } catch (Exception e) {
            LOG.error("获取书籍列表失败: " + e.getMessage(), e);
        }

        // 按最后阅读时间排序，最近阅读的排在前面
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
                    LOG.warn("书籍 [" + bookId + "] 已达到最大重试次数 (" + MAX_CHAPTER_FETCH_RETRY + ")，不再尝试从文件重新加载章节列表");
                    // 返回当前可能不完整的缓存版本
                    return cachedBook;
                }
                // 增加重试计数
                chapterFetchRetryCount.put(bookId, retryCount + 1);

                LOG.warn("缓存中的书籍 [" + bookId + "] 缺少章节列表，尝试从文件重新加载... (重试次数: " + (retryCount + 1) + "/" + MAX_CHAPTER_FETCH_RETRY + ")");
                try {
                     String bookDir = storageRepository.getBookDirectory(bookId);
                     File detailsFile = new File(bookDir, "details.json");
                     if (detailsFile.exists()) {
                         String jsonContent = new String(java.nio.file.Files.readAllBytes(detailsFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                         Book fileBook = parseBookFromJson(jsonContent, bookId); // Use parsing method
                         if (fileBook != null) {
                              //修复数据不一致
                              fileBook = restoreLastReadingPosition(fileBook);
                              if (fileBook.getCachedChapters() != null && !fileBook.getCachedChapters().isEmpty()) {
                                  LOG.info("成功从文件为 [" + bookId + "] 加载了章节列表，更新缓存。");
                                  bookCache.put(bookId, new CacheEntry(fileBook));

                                  // 重置重试计数
                                  chapterFetchRetryCount.remove(bookId);
                                  LOG.debug("重置书籍 [" + bookId + "] 的重试计数");

                                  return fileBook; // 返回从文件加载的完整对象
                              } else {
                                   LOG.warn("从文件重新加载 [" + bookId + "] 仍未获取到章节列表。");
                              }
                         } else {
                              LOG.warn("从文件解析书籍失败，无法更新缓存中的章节列表: " + bookId);
                         }
                     }
                } catch (Exception e) {
                     LOG.error("尝试从文件为 [" + bookId + "] 重新加载章节列表时出错: " + e.getMessage(), e);
                }
                // 如果重新加载失败或文件版也没有章节，仍然返回（可能不完整的）缓存版本
                LOG.debug("Returning potentially incomplete cached book after failed reload attempt: " + bookId);
                return cachedBook;
            } else {
                // 缓存中的书籍已有章节，直接返回
                 LOG.debug(String.format("Cache hit for Book ID: %s. Returning complete cached book: Title='%s', ChapterID=%s, Pos=%d, Page=%d",
                    bookId, cachedBook.getTitle(), cachedBook.getLastReadChapterId(),
                    cachedBook.getLastReadPosition(), cachedBook.getLastReadPage()));
                return cachedBook;
            }
            // === Check Cache Completeness End ===
        }

        // 2. 如果缓存未命中或已过期，从文件加载
        try {
            // 读取书籍详情文件
            String bookDir = storageRepository.getBookDirectory(bookId);
            File detailsFile = new File(bookDir, "details.json");
            if (!detailsFile.exists()) {
                LOG.warn("书籍详情文件不存在: " + detailsFile.getAbsolutePath());
                //尝试从索引恢复
                Book recoveredBook = recoverBookFromIndex(bookId);
                if (recoveredBook != null) {
                     LOG.info("从索引恢复书籍成功: " + bookId);
                     saveBookDetails(recoveredBook); // 保存恢复的数据
                     bookCache.put(bookId, new CacheEntry(recoveredBook));
                     return recoveredBook;
                }
                return null;
            }

            Book fileBook = null;
            try {
                String jsonContent = new String(java.nio.file.Files.readAllBytes(detailsFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                fileBook = parseBookFromJson(jsonContent, bookId); //解析书籍

                // === Fetch Missing Chapters Logic Start ===
                if (fileBook != null && (fileBook.getCachedChapters() == null || fileBook.getCachedChapters().isEmpty()) &&
                    fileBook.getUrl() != null && !fileBook.getUrl().isEmpty()) {

                    // 检查重试次数
                    int retryCount = chapterFetchRetryCount.getOrDefault(bookId, 0);
                    if (retryCount >= MAX_CHAPTER_FETCH_RETRY) {
                        LOG.warn("书籍 [" + bookId + "] 已达到最大重试次数 (" + MAX_CHAPTER_FETCH_RETRY + ")，不再尝试从 URL 获取章节列表");
                        // 返回当前可能不完整的书籍对象
                        return fileBook;
                    }
                    // 增加重试计数
                    chapterFetchRetryCount.put(bookId, retryCount + 1);

                    LOG.warn("书籍 [" + bookId + "] details.json 文件缺少章节列表，尝试从 URL 重新获取... (重试次数: " + (retryCount + 1) + "/" + MAX_CHAPTER_FETCH_RETRY + ")");
                    try {
                        // 获取章节服务实例 (假定服务接口为 ReactiveChapterService)
                        com.lv.tool.privatereader.service.ChapterService chapterService =
                            com.intellij.openapi.application.ApplicationManager.getApplication().getService(com.lv.tool.privatereader.service.ChapterService.class);

                        if (chapterService != null) {
                            // 调用服务获取章节 (使用阻塞方式获取，注意潜在性能影响)
                            LOG.debug("调用 chapterService.getChapterList for " + bookId);

                            // Correctly call getChapterList which returns Single<List<Chapter>>
                            io.reactivex.rxjava3.core.Single<List<com.lv.tool.privatereader.parser.NovelParser.Chapter>> chapterListSingle =
                                chapterService.getChapterList(fileBook);
                            List<com.lv.tool.privatereader.parser.NovelParser.Chapter> fetchedChapters =
                                chapterListSingle.blockingGet(); // .blockingGet() on Single<List<T>> returns List<T>

                            if (fetchedChapters != null && !fetchedChapters.isEmpty()) {
                                LOG.info("成功从 URL 为书籍 [" + bookId + "] 获取到 " + fetchedChapters.size() + " 个章节。");
                                fileBook.setCachedChapters(fetchedChapters);

                                // 将补充了章节的书籍信息保存回文件
                                LOG.warn("将获取到的章节列表保存回 details.json 文件: " + bookId);
                                saveBookDetails(fileBook);

                                // 重置重试计数
                                chapterFetchRetryCount.remove(bookId);
                                LOG.debug("重置书籍 [" + bookId + "] 的重试计数");
                                // 保存后，缓存会在下面更新
                            } else {
                                LOG.warn("从 URL 未能获取到书籍 [" + bookId + "] 的章节列表。");
                            }
                        } else {
                             LOG.error("无法获取 ReactiveChapterService 实例，无法为 [" + bookId + "] 获取章节。");
                        }
                    } catch (Exception fetchEx) {
                        LOG.error("尝试为书籍 [" + bookId + "] 从 URL 获取章节列表时出错: " + fetchEx.getMessage(), fetchEx);
                        // 即使获取失败，也继续使用从文件解析出的（缺少章节的）book 对象
                    }
                }
                // === Fetch Missing Chapters Logic End ===

                 if (fileBook != null) {
                     // 修复可能的阅读位置数据不一致
                     fileBook = restoreLastReadingPosition(fileBook);

                     if (fileBook != null) {
                         // Add Debug Log for file load
                         LOG.debug(String.format("Cache miss for Book ID: %s. Loaded from file: Title='%s', ChapterID=%s, Pos=%d, Page=%d",
                                 bookId, fileBook.getTitle(), fileBook.getLastReadChapterId(),
                                 fileBook.getLastReadPosition(), fileBook.getLastReadPage()));
                         // 更新缓存 (使用可能已补充章节的 fileBook)
                         bookCache.put(bookId, new CacheEntry(fileBook));
                         }
                 }
            } catch (Exception parseEx) {
                 // Handle parsing error (potentially recover from index)
                  LOG.error("解析文件获取书籍失败，无法处理损坏的文件: " + parseEx.getMessage(), parseEx);
                  // (Recovery logic from index might be called here if parseBookFromJson didn't handle it)
                  Book recoveredBook = recoverBookFromIndex(bookId);
                  if (recoveredBook != null) {
                       LOG.info("从索引恢复书籍成功 (after parse error): " + bookId);
                       saveBookDetails(recoveredBook);
                       bookCache.put(bookId, new CacheEntry(recoveredBook));
                         return recoveredBook;
                  }
            }
             return fileBook; // 返回从文件加载（并可能已补充章节）的书籍

        } catch (Exception e) {
            LOG.error("获取书籍失败: " + e.getMessage(), e);
        }

        return null;
    }

    /**
     * 确保阅读位置数据的完整性
     * 如果主要字段丢失，尝试从备用字段恢复
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
            LOG.info("发现lastReadChapterId为空但lastReadChapter不为空，尝试恢复章节ID");

            // 如果有缓存的章节，尝试从标题匹配章节ID
            if (book.getCachedChapters() != null && !book.getCachedChapters().isEmpty()) {
                for (com.lv.tool.privatereader.parser.NovelParser.Chapter chapter : book.getCachedChapters()) {
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
     * 解析 JSON 字符串为 Book 对象
     *
     * @param jsonContent JSON 内容
     * @param bookId 书籍 ID
     * @return 解析后的 Book 对象，如果解析失败则返回 null
     */
    private Book parseBookFromJson(String jsonContent, String bookId) {
        if (jsonContent == null || jsonContent.isEmpty()) {
            LOG.warn("JSON content is null or empty for book: " + bookId);
            return null;
        }

        try {
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(jsonContent).getAsJsonObject();
            Book book = new Book(
                getStringFromJson(json, "id", bookId), // Ensure ID is correct
                getStringFromJson(json, "title", "未知标题"),
                getStringFromJson(json, "author", "未知作者"),
                getStringFromJson(json, "url", null)
            );

            // --- Metadata Only --- Keep these fields
            book.setSourceId(getStringFromJson(json, "sourceId", null));
            book.setCreateTimeMillis(getLongFromJson(json, "createTimeMillis", System.currentTimeMillis()));
            book.setLastChapter(getStringFromJson(json, "lastChapter", null));
            book.setTotalChapters(getIntFromJson(json, "totalChapters", 0));

            // --- Progress Data --- Restore these fields from JSON parsing
            book.setLastReadChapter(getStringFromJson(json, "lastReadChapter", null));
            book.setLastReadChapterId(getStringFromJson(json, "lastReadChapterId", null));
            book.setLastReadPosition(getIntFromJson(json, "lastReadPosition", 0));
            book.setLastReadTimeMillis(getLongFromJson(json, "lastReadTimeMillis", 0));
            book.setCurrentChapterIndex(getIntFromJson(json, "currentChapterIndex", 0));
            book.setFinished(getBooleanFromJson(json, "finished", false));
            book.setLastReadPage(getIntFromJson(json, "lastReadPage", 1));

            // --- Cached Chapters (Optional - decide if this belongs here or separate cache)
            // If cachedChapters are stored in details.json, keep this part.
            // If they are large and stored separately, remove this.
            // Assuming they are small enough to keep in details.json for now:
            if (json.has("cachedChapters") && json.get("cachedChapters").isJsonArray()) {
                try {
                    // Use Gson to parse the Chapter list
                    java.lang.reflect.Type chapterListType = new com.google.gson.reflect.TypeToken<List<com.lv.tool.privatereader.parser.NovelParser.Chapter>>(){}.getType();
                    List<com.lv.tool.privatereader.parser.NovelParser.Chapter> chapters = gson.fromJson(json.get("cachedChapters"), chapterListType);
                    book.setCachedChapters(chapters);
                } catch (Exception e) {
                    LOG.warn("Failed to parse cachedChapters for book: " + bookId, e);
                    book.setCachedChapters(new ArrayList<>()); // Set empty list on error
                }
            } else {
                book.setCachedChapters(new ArrayList<>());
            }

            LOG.debug("Successfully parsed book from JSON: " + book.getId());
            return book;
        } catch (com.google.gson.JsonSyntaxException | IllegalStateException | NullPointerException e) {
            LOG.error("Failed to parse JSON content for book: " + bookId + ". Content: " + jsonContent.substring(0, Math.min(jsonContent.length(), 200)) + "...", e);
            return null;
        }
    }

    // 辅助方法，从JsonObject安全获取各种类型的值
    private String getStringFromJson(com.google.gson.JsonObject json, String key, String defaultValue) {
        try {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                return json.get(key).getAsString();
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return defaultValue;
    }

    private int getIntFromJson(com.google.gson.JsonObject json, String key, int defaultValue) {
        try {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                return json.get(key).getAsInt();
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return defaultValue;
    }

    private long getLongFromJson(com.google.gson.JsonObject json, String key, long defaultValue) {
        try {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                return json.get(key).getAsLong();
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return defaultValue;
    }

    private boolean getBooleanFromJson(com.google.gson.JsonObject json, String key, boolean defaultValue) {
        try {
            if (json.has(key) && !json.get(key).isJsonNull()) {
                return json.get(key).getAsBoolean();
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return defaultValue;
    }

    /**
     * 从索引中恢复书籍信息
     * 当书籍详情文件损坏时，尝试从索引中获取基本信息
     */
    private Book recoverBookFromIndex(String bookId) {
        try {
            File indexFile = new File(storageRepository.getBooksFilePath());
            if (!indexFile.exists()) {
                return null;
            }

            // 直接读取文件内容
            String jsonContent = new String(java.nio.file.Files.readAllBytes(indexFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);

            // 手动解析JSON数组
            try {
                com.google.gson.JsonArray jsonArray = com.google.gson.JsonParser.parseString(jsonContent).getAsJsonArray();

                for (int i = 0; i < jsonArray.size(); i++) {
                    com.google.gson.JsonObject indexObject = jsonArray.get(i).getAsJsonObject();
                    String id = getStringFromJson(indexObject, "id", "");

                    if (id.equals(bookId)) {
                        // 找到匹配的索引项
                        String title = getStringFromJson(indexObject, "title", "未知标题");
                        String author = getStringFromJson(indexObject, "author", "未知作者");
                        String url = getStringFromJson(indexObject, "url", "");

                        Book book = new Book(bookId, title, author, url);
                        book.setCreateTimeMillis(getLongFromJson(indexObject, "createTimeMillis", System.currentTimeMillis()));
                        book.setLastChapter(getStringFromJson(indexObject, "lastChapter", null));
                        book.setLastReadTimeMillis(getLongFromJson(indexObject, "lastReadTimeMillis", 0L));
                        book.setTotalChapters(getIntFromJson(indexObject, "totalChapters", 0));
                        book.setFinished(getBooleanFromJson(indexObject, "finished", false));
                        return book;
                    }
                }
            } catch (Exception e) {
                LOG.error("解析书籍索引JSON失败: " + e.getMessage(), e);

                // 回退到使用GSON解析
                try (FileReader reader = new FileReader(indexFile)) {
                    List<BookIndex> indices = gson.fromJson(reader, new TypeToken<List<BookIndex>>(){}.getType());
                    if (indices == null) {
                        return null;
                    }

                    for (BookIndex index : indices) {
                        if (index.getId().equals(bookId)) {
                            Book book = new Book(bookId, index.getTitle(), index.getAuthor(), index.getUrl());
                            book.setCreateTimeMillis(index.getCreateTimeMillis());
                            book.setLastChapter(index.getLastChapter());
                            book.setLastReadTimeMillis(index.getLastReadTimeMillis());
                            book.setTotalChapters(index.getTotalChapters());
                            book.setFinished(index.isFinished());
                            return book;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("从索引恢复书籍信息失败: " + e.getMessage(), e);
        }

        return null;
    }

    @Override
    public void addBook(@NotNull Book book) {
        if (book == null || book.getId() == null || book.getId().isEmpty()) {
            LOG.warn("无法添加书籍：book 或 bookId 为空");
            return;
        }

        try {
            // 创建书籍目录
            storageRepository.createBookDirectory(book.getId());

            // 保存书籍详情
            saveBookDetails(book);

            // 更新索引文件
            updateBookIndex(book);

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
            LOG.warn("无法更新书籍：book 或 bookId 为空");
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
                // Optionally, ensure other essential fields aren't accidentally overwritten if they exist in existingBook but not in book
                // Example: if (book.getUrl() == null && existingBook.getUrl() != null) book.setUrl(existingBook.getUrl());
                // Add similar checks if necessary based on how 'book' is constructed by the caller.
            } else {
                 LOG.warn("Update called for book ID not found in storage: " + book.getId() + ". Saving as new/overwriting.");
            }
             // === Preserve Chapters Logic End ===

            // 保存书籍详情 (now potentially with chapters preserved)
            // Add log before saveBookDetails
            LOG.debug("[SAVE_TRACE] FBR.updateBook: Calling saveBookDetails for book: " + book.getId());
            saveBookDetails(book);
            // Add log after saveBookDetails
            LOG.debug("[SAVE_TRACE] FBR.updateBook: Returned from saveBookDetails for book: " + book.getId());

            // 更新索引文件
            // Add log before updateBookIndex
            LOG.debug("[SAVE_TRACE] FBR.updateBook: Calling updateBookIndex for book: " + book.getId());
            updateBookIndex(book);
            // Add log after updateBookIndex
            LOG.debug("[SAVE_TRACE] FBR.updateBook: Returned from updateBookIndex for book: " + book.getId());

            // 更新缓存
            bookCache.put(book.getId(), new CacheEntry(book));

            LOG.info("更新书籍成功: " + book.getTitle());
        } catch (Exception e) {
            // Modify log in catch block
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
            LOG.warn("无法删除书籍：book 或 bookId 为空");
            return;
        }

        try {
            // 删除书籍目录
            String bookDir = storageRepository.getBookDirectory(book.getId());
            deleteDirectory(new File(bookDir));

            // 从索引文件中移除
            removeBookFromIndex(book.getId());

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
                            deleteDirectory(file);
                        } else if (!file.getName().equals("index.json")) {
                            file.delete();
                        }
                    }
                }
            }

            // 清空索引文件
            File indexFile = new File(storageRepository.getBooksFilePath());
            if (indexFile.exists()) {
                try (FileWriter writer = new FileWriter(indexFile)) {
                    writer.write("[]");
                }
            }

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
     * 保存书籍详情到文件
     *
     * @param book 书籍对象
     */
    public void saveBookDetails(Book book) {
        if (book == null || book.getId() == null) {
            LOG.error("无法保存书籍详情：book 或 bookId 为空");
            return;
        }

        // 获取书籍目录路径
        String bookDirPath = storageRepository.getBookDirectory(book.getId());
        if (bookDirPath == null) {
            LOG.error("无法获取书籍目录路径，无法保存: " + book.getId());
            return;
        }

        // 创建临时文件和目标文件
        File detailsFile = new File(bookDirPath, "details.json");
        File tempFile = new File(bookDirPath, "details.json.tmp");

        // 确保父目录存在
        File parentDir = detailsFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                LOG.error("无法创建书籍详情目录: " + parentDir.getAbsolutePath());
                return;
            }
        }

        try {
            LOG.debug("开始保存书籍详情: " + book.getTitle());

            // 创建一个简化的书籍对象，只包含需要保存的字段
            Map<String, Object> bookData = new HashMap<>();
            bookData.put("id", book.getId());
            bookData.put("title", book.getTitle());
            bookData.put("author", book.getAuthor());
            bookData.put("url", book.getUrl());
            bookData.put("sourceId", book.getSourceId());
            bookData.put("createTimeMillis", book.getCreateTimeMillis());
            bookData.put("lastChapter", book.getLastChapter());
            bookData.put("totalChapters", book.getTotalChapters());
            
            // Add progress data to details.json to ensure persistence across sessions
            bookData.put("lastReadChapter", book.getLastReadChapter());
            bookData.put("lastReadChapterId", book.getLastReadChapterId());
            bookData.put("lastReadPosition", book.getLastReadPosition());
            bookData.put("lastReadTimeMillis", book.getLastReadTimeMillis());
            bookData.put("currentChapterIndex", book.getCurrentChapterIndex());
            bookData.put("finished", book.isFinished());
            bookData.put("lastReadPage", book.getLastReadPage());

            bookData.put("cachedChapters", book.getCachedChapters() != null ? book.getCachedChapters() : new ArrayList<>());

            // 使用Gson序列化为JSON
            String json = gson.toJson(bookData);

            // 先写入临时文件
            try (FileWriter writer = new FileWriter(tempFile)) {
                writer.write(json);
                writer.flush();
            }

            // 如果临时文件写入成功，则重命名为目标文件
            if (tempFile.exists() && tempFile.length() > 0) {
                // 如果目标文件已存在，先删除
                if (detailsFile.exists()) {
                    if (!detailsFile.delete()) {
                        LOG.warn("无法删除已存在的书籍详情文件: " + detailsFile.getAbsolutePath());
                    }
                }

                // 重命名临时文件为目标文件
                if (tempFile.renameTo(detailsFile)) {
                    LOG.debug("已保存书籍详情: " + detailsFile.getAbsolutePath());
                } else {
                    LOG.error("重命名临时文件失败: " + tempFile.getAbsolutePath() + " -> " + detailsFile.getAbsolutePath());
                    // 尝试复制文件内容
                    try {
                        Files.copy(tempFile.toPath(), detailsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        tempFile.delete(); // 删除临时文件
                        LOG.debug("通过复制保存书籍详情: " + detailsFile.getAbsolutePath());
                    } catch (IOException copyEx) {
                        LOG.error("复制临时文件失败: " + copyEx.getMessage(), copyEx);
                    }
                }
            } else {
                LOG.error("临时文件写入失败或为空: " + tempFile.getAbsolutePath());
            }
        } catch (Exception e) {
            LOG.error("保存书籍详情失败: " + book.getId(), e);
            // 清理临时文件
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * 更新书籍索引
     */
    private void updateBookIndex(Book book) {
        try {
            // Add log at start
            LOG.debug("[SAVE_TRACE] FBR.updateBookIndex: Starting update for book: " + book.getId());
            // 读取当前索引
            List<BookIndex> indices = readBookIndices();

            // 查找并更新或添加
            boolean found = false;
            for (int i = 0; i < indices.size(); i++) {
                if (indices.get(i).getId().equals(book.getId())) {
                    indices.set(i, BookIndex.fromBook(book));
                    found = true;
                    break;
                }
            }

            if (!found) {
                indices.add(BookIndex.fromBook(book));
            }

            // 保存更新的索引
            // Add log before saveBookIndices
            LOG.debug("[SAVE_TRACE] FBR.updateBookIndex: Calling saveBookIndices");
            saveBookIndices(indices);
            // Add log after saveBookIndices
            LOG.debug("[SAVE_TRACE] FBR.updateBookIndex: Returned from saveBookIndices");
        } catch (Exception e) {
            // Modify log in catch block
            LOG.error("[SAVE_TRACE] FBR.updateBookIndex: Exception during update for book: " + book.getId(), e);
        }
    }

    /**
     * 从索引中移除书籍
     */
    private void removeBookFromIndex(String bookId) {
        try {
            // 读取当前索引
            List<BookIndex> indices = readBookIndices();

            // 移除指定ID的书籍
            indices.removeIf(index -> index.getId().equals(bookId));

            // 保存更新的索引
            saveBookIndices(indices);
        } catch (Exception e) {
            LOG.error("从索引中移除书籍失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取书籍索引列表
     */
    private List<BookIndex> readBookIndices() {
        File indexFile = new File(storageRepository.getBooksFilePath());
        if (!indexFile.exists()) {
            return new ArrayList<>();
        }

        try {
            // 先尝试直接读取文件内容进行手动解析
            try {
                String jsonContent = new String(java.nio.file.Files.readAllBytes(indexFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                com.google.gson.JsonArray jsonArray = com.google.gson.JsonParser.parseString(jsonContent).getAsJsonArray();

                List<BookIndex> indices = new ArrayList<>();
                for (int i = 0; i < jsonArray.size(); i++) {
                    com.google.gson.JsonObject indexObject = jsonArray.get(i).getAsJsonObject();

                    BookIndex index = new BookIndex();
                    index.setId(getStringFromJson(indexObject, "id", ""));
                    index.setTitle(getStringFromJson(indexObject, "title", ""));
                    index.setAuthor(getStringFromJson(indexObject, "author", ""));
                    index.setUrl(getStringFromJson(indexObject, "url", ""));
                    index.setCreateTimeMillis(getLongFromJson(indexObject, "createTimeMillis", 0L));
                    index.setLastChapter(getStringFromJson(indexObject, "lastChapter", null));
                    index.setLastReadTimeMillis(getLongFromJson(indexObject, "lastReadTimeMillis", 0L));
                    index.setTotalChapters(getIntFromJson(indexObject, "totalChapters", 0));
                    index.setFinished(getBooleanFromJson(indexObject, "finished", false));

                    indices.add(index);
                }

                return indices;
            } catch (Exception e) {
                LOG.warn("手动解析JSON索引文件失败，尝试使用GSON: " + e.getMessage());

                // 作为备选，使用GSON解析
                try (FileReader reader = new FileReader(indexFile)) {
                    List<BookIndex> indices = gson.fromJson(reader, new TypeToken<List<BookIndex>>(){}.getType());
                    return indices != null ? indices : new ArrayList<>();
                }
            }
        } catch (Exception e) {
            LOG.error("读取书籍索引文件失败: " + e.getMessage(), e);
        }

        return new ArrayList<>();
    }

    /**
     * 保存书籍索引列表
     */
    private void saveBookIndices(List<BookIndex> indices) {
        try {
            File indexFile = new File(storageRepository.getBooksFilePath());
            File tempFile = new File(storageRepository.getBooksFilePath() + ".tmp");

            // 确保父目录存在
            indexFile.getParentFile().mkdirs();

            LOG.debug("[SAVE_TRACE] FBR.saveBookIndices: Preparing to write index file");

            // 使用Gson序列化为JSON
            String json = gson.toJson(indices);

            // 先写入临时文件
            try (FileWriter writer = new FileWriter(tempFile)) {
                writer.write(json);
                writer.flush();
            }

            // 如果临时文件写入成功，则重命名为目标文件
            if (tempFile.exists() && tempFile.length() > 0) {
                // 如果目标文件已存在，先删除
                if (indexFile.exists()) {
                    if (!indexFile.delete()) {
                        LOG.warn("无法删除已存在的索引文件: " + indexFile.getAbsolutePath());
                    }
                }

                // 重命名临时文件为目标文件
                if (tempFile.renameTo(indexFile)) {
                    LOG.debug("[SAVE_TRACE] FBR.saveBookIndices: Finished writing index file");
                    LOG.info("索引文件保存成功: " + indexFile.getAbsolutePath() + " (" + indices.size() + " 条目)");
                } else {
                    LOG.error("重命名临时文件失败: " + tempFile.getAbsolutePath() + " -> " + indexFile.getAbsolutePath());
                    // 尝试复制文件内容
                    try {
                        Files.copy(tempFile.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        tempFile.delete(); // 删除临时文件
                        LOG.debug("通过复制保存索引文件: " + indexFile.getAbsolutePath());
                        LOG.info("索引文件保存成功: " + indexFile.getAbsolutePath() + " (" + indices.size() + " 条目)");
                    } catch (IOException copyEx) {
                        LOG.error("复制临时文件失败: " + copyEx.getMessage(), copyEx);
                    }
                }
            } else {
                LOG.error("临时文件写入失败或为空: " + tempFile.getAbsolutePath());
            }
        } catch (Exception e) {
            LOG.error("[SAVE_TRACE] FBR.saveBookIndices: Exception writing index file", e);
            // 清理临时文件
            File tempFile = new File(storageRepository.getBooksFilePath() + ".tmp");
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * 删除目录及其内容
     */
    private void deleteDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }

        directory.delete();
    }

    /**
     * 标记并备份损坏的书籍文件
     * @param bookId 书籍ID
     */
    private void markCorruptedBookFile(String bookId) {
        try {
            // 获取书籍目录
            String bookDir = storageRepository.getBookDirectory(bookId);
            File detailsFile = new File(bookDir, "details.json");

            if (detailsFile.exists()) {
                // 创建备份
                File backupFile = new File(bookDir, "details.json.corrupted." + System.currentTimeMillis());
                try {
                    java.nio.file.Files.copy(detailsFile.toPath(), backupFile.toPath());
                    LOG.info("已将损坏的文件备份到: " + backupFile.getAbsolutePath());
                } catch (Exception e) {
                    LOG.error("备份损坏文件失败: " + e.getMessage(), e);
                }

                // 创建最小化书籍对象并保存
                try {
                    Book minimalBook = recoverBookFromIndex(bookId);
                    if (minimalBook == null) {
                        minimalBook = new Book(bookId, "恢复的书籍 " + bookId, "未知", "");
                        minimalBook.setCreateTimeMillis(System.currentTimeMillis());
                    }

                    // 保存最小化书籍对象，使用手动JSON构建
                    saveBookDetails(minimalBook);
                    LOG.info("已创建替代书籍对象: " + minimalBook.getTitle());

                    // 添加到缓存
                    bookCache.put(bookId, new CacheEntry(minimalBook));
                        } catch (Exception e) {
                    LOG.error("创建替代书籍对象失败: " + e.getMessage(), e);

                    // 如果恢复失败，则删除原始文件以避免后续再次触发相同错误
                    try {
                        detailsFile.delete();
                        LOG.info("已删除无法修复的损坏文件: " + detailsFile.getAbsolutePath());
                    } catch (Exception ex) {
                        LOG.error("删除损坏文件失败: " + ex.getMessage(), ex);
                    }
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

                File detailsFile = new File(bookDir, "details.json");
                if (!detailsFile.exists() || !detailsFile.isFile()) {
                    continue;
                }

                try {
                    // 检查文件内容是否包含潜在的问题模式
                    String content = new String(java.nio.file.Files.readAllBytes(detailsFile.toPath()),
                                              java.nio.charset.StandardCharsets.UTF_8);

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
            // 读取书籍索引列表，避免递归调用getAllBooks
            List<BookIndex> indices = readBookIndices();
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
                    saveBookDetails(book);

                    // 更新书籍索引
                    updateBookIndex(book);

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