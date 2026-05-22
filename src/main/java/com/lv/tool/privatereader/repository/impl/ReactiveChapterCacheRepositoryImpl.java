package com.lv.tool.privatereader.repository.impl;

import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.repository.ReactiveChapterCacheRepository;
import com.lv.tool.privatereader.settings.CacheSettings;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 响应式章节缓存仓库实现类
 * 使用响应式编程处理缓存操作
 */
public class ReactiveChapterCacheRepositoryImpl implements ReactiveChapterCacheRepository, com.intellij.openapi.Disposable {
    private static final Logger LOG = Logger.getInstance(ReactiveChapterCacheRepositoryImpl.class);
    private static final String CACHE_DIR_NAME = "chapter_cache";
    private static final long DEFAULT_CACHE_EXPIRY_HOURS = 24 * 7; // 默认缓存过期时间：7天
    private static final int DEFAULT_MAX_CACHE_SIZE_MB = 100; // 默认最大缓存大小：100MB

    private final CacheSettings cacheSettings;
    private final String cacheDir;
    private final AtomicBoolean cleanupInProgress = new AtomicBoolean(false);
    // 使用 Guava Cache 替代 ConcurrentHashMap，设置容量限制和过期时间
    private final Cache<String, String> memoryCache;
    private Disposable cleanupDisposable;

    public ReactiveChapterCacheRepositoryImpl() {
        this.cacheSettings = com.intellij.openapi.application.ApplicationManager.getApplication().getService(CacheSettings.class);

        // 初始化内存缓存：最多100个章节，写入后1小时过期
        this.memoryCache = CacheBuilder.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();
        this.cacheDir = initCacheDir();

        // 启动定期清理任务
        scheduleCleanupTask();
    }

    private String initCacheDir() {
        String userHome = System.getProperty("user.home");
        String cacheDirPath = Paths.get(userHome, ".privatereader", CACHE_DIR_NAME).toString();

        try {
            Files.createDirectories(Paths.get(cacheDirPath));
            LOG.info("缓存目录初始化成功: " + cacheDirPath);
        } catch (IOException e) {
            LOG.error("创建缓存目录失败: " + cacheDirPath, e);
        }

        return cacheDirPath;
    }

    private void scheduleCleanupTask() {
        // 使用响应式定时器定期清理缓存
        cleanupDisposable = Observable.interval(6, TimeUnit.HOURS)
            .flatMapCompletable(tick -> cleanupCacheReactive())
            .doOnError(e -> LOG.error("缓存清理任务失败", e))
            .subscribe();
    }

    @Override
    public Maybe<String> getCachedContentReactive(String bookId, String chapterId) {
        return Maybe.defer(() -> {
            // 先检查内存缓存
            String cacheKey = getCacheKey(bookId, chapterId);
            String cachedContent = memoryCache.getIfPresent(cacheKey);
            if (cachedContent != null) {
                LOG.debug("从内存缓存获取章节内容: " + cacheKey);
                return Maybe.just(cachedContent);
            }

            // 检查文件缓存
            File cacheFile = getCacheFile(bookId, chapterId);
            if (!cacheFile.exists()) {
                LOG.debug("缓存文件不存在: " + cacheFile.getPath());
                return Maybe.empty();
            }

            // 检查缓存是否过期
            if (isCacheExpired(cacheFile)) {
                LOG.debug("缓存已过期: " + cacheFile.getPath());
                return Maybe.empty();
            }

            // 读取缓存文件
            return Maybe.fromCallable(() -> {
                try {
                    String content = Files.readString(cacheFile.toPath(), StandardCharsets.UTF_8);
                    // 添加到内存缓存
                    memoryCache.put(cacheKey, content);
                    LOG.debug("从文件缓存获取章节内容: " + cacheFile.getPath());
                    return content;
                } catch (IOException e) {
                    LOG.warn("读取缓存文件失败: " + cacheFile.getPath(), e);
                    return null;
                }
            })
            .subscribeOn(Schedulers.io())
            .filter(content -> content != null);
        });
    }

    @Override
    public Maybe<String> getFallbackCachedContentReactive(String bookId, String chapterId) {
        return Maybe.defer(() -> {
            // 先检查内存缓存
            String cacheKey = getCacheKey(bookId, chapterId);
            String cachedContent = memoryCache.getIfPresent(cacheKey);
            if (cachedContent != null) {
                LOG.debug("从内存缓存获取备用章节内容: " + cacheKey);
                return Maybe.just(cachedContent);
            }

            // 检查文件缓存
            File cacheFile = getCacheFile(bookId, chapterId);
            if (!cacheFile.exists()) {
                LOG.debug("备用缓存文件不存在: " + cacheFile.getPath());
                return Maybe.empty();
            }

            // 读取缓存文件，忽略过期检查
            return Maybe.fromCallable(() -> {
                try {
                    String content = Files.readString(cacheFile.toPath(), StandardCharsets.UTF_8);
                    // 添加到内存缓存
                    memoryCache.put(cacheKey, content);
                    LOG.debug("从文件缓存获取备用章节内容: " + cacheFile.getPath());
                    return content;
                } catch (IOException e) {
                    LOG.warn("读取备用缓存文件失败: " + cacheFile.getPath(), e);
                    return null;
                }
            })
            .subscribeOn(Schedulers.io())
            .filter(content -> content != null);
        });
    }

    @Override
    public Completable cacheContentReactive(String bookId, String chapterId, String content) {
        return Completable.defer(() -> {
            if (content == null || content.isEmpty()) {
                LOG.debug("内容为空，不进行缓存");
                return Completable.complete();
            }

            String cacheKey = getCacheKey(bookId, chapterId);

            // 添加到内存缓存
            memoryCache.put(cacheKey, content);

            // 写入文件缓存
            File cacheFile = getCacheFile(bookId, chapterId);
            File parentDir = cacheFile.getParentFile();

            return Completable.fromRunnable(() -> {
                try {
                    if (!parentDir.exists() && !parentDir.mkdirs()) {
                        LOG.error("创建缓存目录失败: " + parentDir.getPath());
                        return;
                    }

                    Files.writeString(cacheFile.toPath(), content, StandardCharsets.UTF_8);
                    LOG.debug("缓存章节内容成功: " + cacheFile.getPath());
                } catch (IOException e) {
                    LOG.error("写入缓存文件失败: " + cacheFile.getPath(), e);
                }
            })
            .subscribeOn(Schedulers.io());
        });
    }

    @Override
    public Completable clearCacheReactive(String bookId) {
        return Completable.defer(() -> {
            // 清除内存缓存 (Guava Cache 不支持直接按前缀清除，这里简单地清除所有或遍历清除)
            // 由于 Guava Cache 的 asMap() 返回的视图支持移除操作
            memoryCache.asMap().keySet().removeIf(key -> key.startsWith(bookId + ":"));

            // 清除文件缓存
            File bookCacheDir = new File(cacheDir, bookId);
            if (!bookCacheDir.exists()) {
                return Completable.complete();
            }

            return Completable.fromRunnable(() -> {
                try {
                    deleteDirectory(bookCacheDir);
                    LOG.info("清除书籍缓存成功: " + bookId);
                } catch (IOException e) {
                    LOG.error("清除书籍缓存失败: " + bookId, e);
                }
            })
            .subscribeOn(Schedulers.io());
        });
    }

    @Override
    public Completable checkAndEvictCacheReactive() {
        return cleanupCacheReactive();
    }

    @Override
    public Completable clearAllCacheReactive() {
        return Completable.defer(() -> {
            // 清除内存缓存
            memoryCache.invalidateAll();

            // 清除文件缓存
            File cacheDirFile = new File(cacheDir);
            if (!cacheDirFile.exists()) {
                return Completable.complete();
            }

            return Completable.fromRunnable(() -> {
                try {
                    deleteDirectory(cacheDirFile);
                    Files.createDirectories(cacheDirFile.toPath());
                    LOG.info("清除所有缓存成功");
                } catch (IOException e) {
                    LOG.error("清除所有缓存失败", e);
                }
            })
            .subscribeOn(Schedulers.io());
        });
    }

    @NotNull
    @Override
    public String getCacheDirPath() {
        return cacheDir;
    }

    @Override
    public Completable cleanupCacheReactive() {
        return Completable.defer(() -> {
            if (!cleanupInProgress.compareAndSet(false, true)) {
                LOG.debug("缓存清理已在进行中，跳过本次清理");
                return Completable.complete();
            }

            return Completable.fromRunnable(() -> {
                try {
                    LOG.info("开始清理缓存");

                    // 清理过期缓存
                    cleanupExpiredCache();

                    // 清理过大的缓存
                    cleanupOversizedCache();

                    LOG.info("缓存清理完成");
                } catch (Exception e) {
                    LOG.error("缓存清理失败", e);
                } finally {
                    cleanupInProgress.set(false);
                }
            })
            .subscribeOn(Schedulers.io());
        });
    }

    @Override
    public Completable cleanupBookCacheReactive(String bookId) {
        return Completable.defer(() -> {
            File bookCacheDir = new File(cacheDir, bookId);
            if (!bookCacheDir.exists()) {
                return Completable.complete();
            }

            return Completable.fromRunnable(() -> {
                try {
                    LOG.info("开始清理书籍缓存: " + bookId);

                    // 清理过期缓存
                    cleanupExpiredBookCache(bookCacheDir);

                    LOG.info("书籍缓存清理完成: " + bookId);
                } catch (Exception e) {
                    LOG.error("书籍缓存清理失败: " + bookId, e);
                }
            })
            .subscribeOn(Schedulers.io());
        });
    }

    private void cleanupExpiredCache() throws IOException {
        File cacheDirFile = new File(cacheDir);
        if (!cacheDirFile.exists()) {
            return;
        }

        File[] bookDirs = cacheDirFile.listFiles();
        if (bookDirs == null) {
            return;
        }

        for (File bookDir : bookDirs) {
            if (bookDir.isDirectory()) {
                cleanupExpiredBookCache(bookDir);
            }
        }
    }

    private void cleanupExpiredBookCache(File bookDir) throws IOException {
        File[] chapterFiles = bookDir.listFiles();
        if (chapterFiles == null) {
            return;
        }

        long expiryHours = cacheSettings.getCacheExpiryHours();
        if (expiryHours <= 0) {
            expiryHours = DEFAULT_CACHE_EXPIRY_HOURS;
        }

        long expiryTimeMillis = System.currentTimeMillis() - expiryHours * 3600 * 1000;

        for (File chapterFile : chapterFiles) {
            if (chapterFile.isFile() && chapterFile.lastModified() < expiryTimeMillis) {
                Files.delete(chapterFile.toPath());
                LOG.debug("删除过期缓存文件: " + chapterFile.getPath());
            }
        }

        // 如果目录为空，删除目录
        if (bookDir.list() != null && bookDir.list().length == 0) {
            Files.delete(bookDir.toPath());
            LOG.debug("删除空缓存目录: " + bookDir.getPath());
        }
    }

    private void cleanupOversizedCache() throws IOException {
        Path cachePath = Paths.get(cacheDir);
        if (!Files.isDirectory(cachePath)) {
            return;
        }

        long maxCacheSizeBytes = cacheSettings.getMaxCacheSizeMB() * 1024L * 1024L;
        java.util.List<CacheFile> cacheFiles;
        try (Stream<Path> paths = Files.walk(cachePath)) {
            cacheFiles = paths
                .filter(Files::isRegularFile)
                .map(this::toCacheFile)
                .filter(cacheFile -> cacheFile != null)
                .sorted(Comparator.comparingLong(CacheFile::lastModified))
                .toList();
        }

        long currentCacheSize = cacheFiles.stream().mapToLong(CacheFile::size).sum();
        if (currentCacheSize <= maxCacheSizeBytes) {
            LOG.debug("缓存大小在限制范围内，无需清理");
            return;
        }

        LOG.info(String.format("缓存大小超过限制，开始清理。当前大小: %.2f MB, 最大限制: %d MB",
            currentCacheSize / (1024.0 * 1024.0), cacheSettings.getMaxCacheSizeMB()));

        long targetMaxSize = (long) (maxCacheSizeBytes * 0.8);
        long remainingCacheSize = currentCacheSize;
        for (CacheFile cacheFile : cacheFiles) {
            if (remainingCacheSize <= targetMaxSize) {
                break;
            }
            try {
                Files.deleteIfExists(cacheFile.path());
                remainingCacheSize -= cacheFile.size();
                LOG.debug("删除缓存文件以减小缓存大小: " + cacheFile.path());
            } catch (IOException e) {
                LOG.warn("删除缓存文件失败: " + cacheFile.path(), e);
            }
        }

        deleteEmptyDirectories(cachePath);
    }

    private CacheFile toCacheFile(Path path) {
        try {
            return new CacheFile(path, Files.size(path), path.toFile().lastModified());
        } catch (IOException e) {
            LOG.warn("读取缓存文件信息失败: " + path, e);
            return null;
        }
    }

    private void deleteEmptyDirectories(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths
                .filter(Files::isDirectory)
                .sorted(Comparator.reverseOrder())
                .toList()) {
                if (!path.equals(root) && isEmptyDirectory(path)) {
                    Files.deleteIfExists(path);
                    LOG.debug("删除空缓存目录: " + path);
                }
            }
        }
    }

    private boolean isEmptyDirectory(Path path) throws IOException {
        try (Stream<Path> entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        }
    }

    private record CacheFile(Path path, long size, long lastModified) {}

    private void deleteDirectory(File directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory.toPath())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.warn("删除文件失败: " + path, e);
                }
            }
        }
    }

    private boolean isCacheExpired(File cacheFile) {
        long expiryHours = cacheSettings.getCacheExpiryHours();
        if (expiryHours <= 0) {
            expiryHours = DEFAULT_CACHE_EXPIRY_HOURS;
        }

        long expiryTimeMillis = System.currentTimeMillis() - expiryHours * 3600 * 1000;
        return cacheFile.lastModified() < expiryTimeMillis;
    }

    @Override
    public void dispose() {
        if (cleanupDisposable != null && !cleanupDisposable.isDisposed()) {
            cleanupDisposable.dispose();
        }
        memoryCache.invalidateAll();
    }

    private File getCacheFile(String bookId, String chapterId) {
        // 对章节ID进行MD5编码，避免文件名过长或包含非法字符
        String encodedChapterId = encodeChapterId(chapterId);
        return new File(new File(cacheDir, bookId), encodedChapterId);
    }

    private String getCacheKey(String bookId, String chapterId) {
        return bookId + ":" + chapterId;
    }

    private String encodeChapterId(String chapterId) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(chapterId.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            LOG.warn("MD5编码失败，使用原始章节ID", e);
            // 如果MD5编码失败，使用原始章节ID的哈希码
            return String.valueOf(chapterId.hashCode());
        }
    }
}
