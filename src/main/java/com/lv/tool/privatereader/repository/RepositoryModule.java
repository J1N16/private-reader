package com.lv.tool.privatereader.repository;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 存储仓库模块
 *
 * 负责从 IntelliJ 服务容器获取各种 Repository 实例，提供统一的访问点。
 */
@Service(Service.Level.APP)
public final class RepositoryModule {
    private static final Logger LOG = Logger.getInstance(RepositoryModule.class);

    private StorageRepository storageRepository;
    private BookRepository bookRepository;
    private ReadingProgressRepository readingProgressRepository;
    private ReactiveChapterCacheRepository chapterCacheRepository;

    public RepositoryModule() {
        LOG.info("RepositoryModule 初始化");
    }

    private void ensureInitialized() {
        if (storageRepository == null) {
            storageRepository = getService(StorageRepository.class);
        }
        if (bookRepository == null) {
            bookRepository = getService(BookRepository.class);
        }
        if (readingProgressRepository == null) {
            readingProgressRepository = getService(ReadingProgressRepository.class);
        }
        if (chapterCacheRepository == null) {
            chapterCacheRepository = getService(ReactiveChapterCacheRepository.class);
        }
    }

    @Nullable
    private <T> T getService(@NotNull Class<T> serviceClass) {
        try {
            T service = ApplicationManager.getApplication().getService(serviceClass);
            if (service == null) {
                LOG.warn("未能从服务容器获取 " + serviceClass.getSimpleName());
            }
            return service;
        } catch (Exception e) {
            LOG.error("获取 " + serviceClass.getSimpleName() + " 服务时出错", e);
            return null;
        }
    }

    @Nullable
    public StorageRepository getStorageRepository() {
        ensureInitialized();
        return storageRepository;
    }

    @Nullable
    public BookRepository getBookRepository() {
        ensureInitialized();
        return bookRepository;
    }

    @Nullable
    public ReadingProgressRepository getReadingProgressRepository() {
        ensureInitialized();
        return readingProgressRepository;
    }

    @Nullable
    public ReactiveChapterCacheRepository getChapterCacheRepository() {
        ensureInitialized();
        return chapterCacheRepository;
    }

    @NotNull
    public static RepositoryModule getInstance() {
        RepositoryModule module = ApplicationManager.getApplication().getService(RepositoryModule.class);
        if (module == null) {
            LOG.warn("无法从服务容器获取 RepositoryModule，创建新实例");
            module = new RepositoryModule();
        }
        return module;
    }
}
