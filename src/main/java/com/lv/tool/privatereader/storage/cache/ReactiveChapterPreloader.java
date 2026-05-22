package com.lv.tool.privatereader.storage.cache;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.repository.ReactiveChapterCacheRepository;
import com.lv.tool.privatereader.settings.CacheSettings;
import com.lv.tool.privatereader.settings.PluginSettings;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 响应式章节预加载服务
 * 使用响应式编程在后台同时预加载当前章节前后的章节，提升连续阅读体验
 * 通过并行预加载前后章节，减少阅读等待时间
 */
@Service(Service.Level.APP)
public final class ReactiveChapterPreloader {
    private static final Logger LOG = Logger.getInstance(ReactiveChapterPreloader.class);

    private final AtomicBoolean isPreloading = new AtomicBoolean(false);
    private final AtomicReference<String> currentPreloadingBookId = new AtomicReference<>(null);

    public ReactiveChapterPreloader() {
        LOG.info("初始化响应式章节预加载器");
    }

    /**
     * 停止指定书籍的预加载任务
     * @param bookId 书籍ID
     */
    public void stopPreload(String bookId) {
        if (bookId != null && bookId.equals(currentPreloadingBookId.get())) {
            LOG.info("停止书籍预加载任务: " + bookId);
            isPreloading.set(false);
            currentPreloadingBookId.set(null);
        }
    }

    /**
     * 响应式预加载指定书籍前后章节
     * 同时预加载当前章节前后的章节，提高阅读体验
     * @param book 当前阅读的书籍
     * @param currentChapterIndex 当前章节索引
     * @return 预加载操作的 Completable
     */
    public Completable preloadChaptersReactive(Book book, int currentChapterIndex) {
        return Completable.defer(() -> {
            // 检查插件是否启用
            PluginSettings pluginSettings = ApplicationManager.getApplication().getService(PluginSettings.class);
            if (!pluginSettings.isEnabled()) {
                LOG.debug("插件已禁用，不执行预加载");
                return Completable.complete();
            }

            // 获取缓存设置
            CacheSettings cacheSettings = ApplicationManager.getApplication().getService(CacheSettings.class);

            // 检查预加载是否启用
            if (!cacheSettings.isEnablePreload()) {
                LOG.debug("预加载功能已禁用，不执行预加载");
                return Completable.complete();
            }

            // 避免重复预加载
            if (isPreloading.get()) {
                LOG.debug("已有预加载任务在执行，跳过本次预加载");
                return Completable.complete();
            }

            // 设置预加载状态
            if (!isPreloading.compareAndSet(false, true)) {
                return Completable.complete();
            }
            currentPreloadingBookId.set(book.getId());

            LOG.info("开始响应式预加载前后章节，书籍: " + book.getTitle() + "，当前章节索引: " + currentChapterIndex);

            if (book == null || book.getParser() == null) {
                LOG.warn("书籍或解析器为空，无法预加载");
                resetPreloadingState();
                return Completable.complete();
            }

            List<NovelParser.Chapter> chapters = book.getCachedChapters();
            if (chapters == null || chapters.isEmpty()) {
                LOG.warn("章节列表为空，无法预加载");
                resetPreloadingState();
                return Completable.complete();
            }

            // 获取预加载配置
            int preloadCount = cacheSettings.getPreloadCount();
            int preloadDelay = cacheSettings.getPreloadDelay();

            int totalChapters = chapters.size();

            // 计算后续章节的预加载范围
            int endIndex = Math.min(currentChapterIndex + preloadCount, totalChapters - 1);

            // 计算前面章节的预加载范围
            int startIndex = Math.max(0, currentChapterIndex - preloadCount);

            // 创建优先级队列，按照与当前章节的距离排序
            List<Integer> prioritizedIndices = new ArrayList<>();

            // 首先添加当前章节（如果需要预加载）
            ReactiveChapterCacheRepository cacheRepository = ApplicationManager.getApplication().getService(ReactiveChapterCacheRepository.class);
            NovelParser.Chapter currentChapter = chapters.get(currentChapterIndex);
            String cachedCurrentContent = cacheRepository.getCachedContent(book.getId(), currentChapter.url());
            if (cachedCurrentContent == null) {
                prioritizedIndices.add(currentChapterIndex);
            }

            // 然后添加后续章节
            for (int i = currentChapterIndex + 1; i <= endIndex; i++) {
                prioritizedIndices.add(i);
            }

            // 最后添加前面章节
            for (int i = currentChapterIndex - 1; i >= startIndex; i--) {
                prioritizedIndices.add(i);
            }

            // 创建预加载流
            return Observable.fromIterable(prioritizedIndices)
                // 检查是否仍在预加载状态
                .takeWhile(i -> isPreloading.get())
                // 获取章节对象
                .map(i -> chapters.get(i))
                // 过滤掉null章节
                .filter(chapter -> chapter != null)
                // 对每个章节进行预加载处理
                .concatMapCompletable(chapter -> preloadChapter(book, chapter, preloadDelay))
                // 使用IO线程池执行
                .subscribeOn(Schedulers.io())
                // 完成后记录日志
                .doOnComplete(() -> LOG.info("章节预加载完成，书籍: " + book.getTitle() + "，预加载范围: 前面(" + startIndex + " - " + (currentChapterIndex - 1) + "), 后面(" + (currentChapterIndex + 1) + " - " + endIndex + ")"))
                // 错误处理
                .doOnError(e -> LOG.error("章节预加载过程发生错误: " + e.getMessage(), e))
                // 无论成功失败都重置状态
                .doFinally(this::resetPreloadingState);
        });
    }

    /**
     * 预加载单个章节
     * 检查缓存是否存在，如不存在则获取内容并缓存
     * @param book 书籍
     * @param chapter 章节
     * @param delayMs 延迟毫秒数，避免请求过于频繁
     * @return 预加载操作的 Completable
     */
    private Completable preloadChapter(Book book, NovelParser.Chapter chapter, int delayMs) {
        return Completable.defer(() -> {
            // 检查缓存是否已存在
            ReactiveChapterCacheRepository cacheRepository = ApplicationManager.getApplication().getService(ReactiveChapterCacheRepository.class);
            String cachedContent = cacheRepository.getCachedContent(book.getId(), chapter.url());

            // 如果缓存已存在，跳过预加载
            if (cachedContent != null) {
                LOG.debug("章节已缓存，跳过预加载: " + chapter.title());
                return Completable.complete();
            }

            // 预加载章节内容
            return Single.fromCallable(() -> {
                LOG.info("预加载章节: " + chapter.title() + "，书籍: " + book.getTitle());
                return book.getParser().parseChapterContent(chapter.url());
            })
            .subscribeOn(Schedulers.io())
            // 过滤掉空内容
            .filter(content -> content != null && !content.isEmpty())
            // 缓存内容
            .doOnSuccess(content -> {
                cacheRepository.cacheContent(book.getId(), chapter.url(), content);
                LOG.info("成功预加载并缓存章节: " + chapter.title() + "，书籍: " + book.getTitle() + "，内容长度: " + content.length());
            })
            // 添加延迟，避免请求过于频繁
            .delaySubscription(delayMs, TimeUnit.MILLISECONDS)
            // 错误处理
            .onErrorResumeNext(e -> {
                LOG.warn("预加载章节失败: " + chapter.title() + "，书籍: " + book.getTitle() + ", 错误: " + e.getMessage());
                return Maybe.empty();
            })
            // 转换为 Completable
            .ignoreElement();
        });
    }

    /**
     * 重置预加载状态
     */
    private void resetPreloadingState() {
        isPreloading.set(false);
        currentPreloadingBookId.set(null);
    }

    /**
     * 预加载指定书籍前后章节（兼容旧API）
     * 同时预加载当前章节前后的章节，提高阅读体验
     * @param book 当前阅读的书籍
     * @param currentChapterIndex 当前章节索引
     */
    public void preloadChapters(Book book, int currentChapterIndex) {
        preloadChaptersReactive(book, currentChapterIndex)
            .subscribe();
    }
}
