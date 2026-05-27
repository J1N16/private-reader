package com.lv.tool.privatereader.settings;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;


/**
 * 缓存设置
 *
 * 管理应用的缓存相关设置，包括：
 * - 是否启用缓存
 * - 最大缓存大小
 * - 缓存过期时间
 * - 章节预加载设置
 */
@Service(Service.Level.APP)
public class CacheSettings extends BaseSettings<CacheSettings> {
    private static final Logger LOG = Logger.getInstance(CacheSettings.class);

    private long cacheExpiryHours = 24 * 7; // 默认缓存过期时间：7天
    private int maxCacheSizeMB = 100; // 默认最大缓存大小：100MB
    private boolean enableCache = true; // 默认启用缓存
    private boolean cleanupOnStartup = true; // 默认启动时清理过期缓存
    private boolean preloadNextChapter = true; // 默认预加载下一章
    private int preloadCount = 1; // 默认预加载数量
    private int maxCacheSize = 100; // 默认最大缓存大小（单位：MB）
    private int maxCacheAge = 7; // 默认最大缓存时间（单位：天）
    private boolean enablePreload = true; // 默认启用预加载
    private int preloadDelay = 500; // 默认预加载延迟（单位：毫秒）

    /**
     * 默认构造函数
     */
    public CacheSettings() {
        super();
    }

    /**
     * 获取缓存过期时间（小时）
     *
     * @return 缓存过期时间
     */
    public long getCacheExpiryHours() {
        ensureSettingsLoaded();
        return cacheExpiryHours;
    }

    /**
     * 设置缓存过期时间（小时）
     *
     * @param cacheExpiryHours 缓存过期时间
     */
    public void setCacheExpiryHours(long cacheExpiryHours) {
        this.cacheExpiryHours = cacheExpiryHours;
        markDirty();
    }

    /**
     * 获取最大缓存大小（MB）
     *
     * @return 最大缓存大小
     */
    public int getMaxCacheSizeMB() {
        ensureSettingsLoaded();
        return maxCacheSizeMB;
    }

    /**
     * 设置最大缓存大小（MB）
     *
     * @param maxCacheSizeMB 最大缓存大小
     */
    public void setMaxCacheSizeMB(int maxCacheSizeMB) {
        this.maxCacheSizeMB = maxCacheSizeMB;
        markDirty();
    }

    /**
     * 是否启用缓存
     *
     * @return 是否启用缓存
     */
    public boolean isEnableCache() {
        ensureSettingsLoaded();
        return enableCache;
    }

    /**
     * 设置是否启用缓存
     *
     * @param enableCache 是否启用缓存
     */
    public void setEnableCache(boolean enableCache) {
        this.enableCache = enableCache;
        markDirty();
    }

    /**
     * 是否在启动时清理过期缓存
     *
     * @return 是否在启动时清理过期缓存
     */
    public boolean isCleanupOnStartup() {
        ensureSettingsLoaded();
        return cleanupOnStartup;
    }

    /**
     * 设置是否在启动时清理过期缓存
     *
     * @param cleanupOnStartup 是否在启动时清理过期缓存
     */
    public void setCleanupOnStartup(boolean cleanupOnStartup) {
        this.cleanupOnStartup = cleanupOnStartup;
        markDirty();
    }

    /**
     * 是否预加载下一章
     *
     * @return 是否预加载下一章
     */
    public boolean isPreloadNextChapter() {
        ensureSettingsLoaded();
        return preloadNextChapter;
    }

    /**
     * 设置是否预加载下一章
     *
     * @param preloadNextChapter 是否预加载下一章
     */
    public void setPreloadNextChapter(boolean preloadNextChapter) {
        this.preloadNextChapter = preloadNextChapter;
        markDirty();
    }

    /**
     * 获取预加载数量
     *
     * @return 预加载数量
     */
    public int getPreloadCount() {
        ensureSettingsLoaded();
        return preloadCount;
    }

    /**
     * 设置预加载数量
     *
     * @param preloadCount 预加载数量
     */
    public void setPreloadCount(int preloadCount) {
        this.preloadCount = preloadCount;
        markDirty();
    }

    /**
     * 获取最大缓存大小
     *
     * @return 最大缓存大小
     */
    public int getMaxCacheSize() {
        ensureSettingsLoaded();
        return maxCacheSize;
    }

    /**
     * 设置最大缓存大小
     *
     * @param maxCacheSize 最大缓存大小
     */
    public void setMaxCacheSize(int maxCacheSize) {
        this.maxCacheSize = maxCacheSize;
        markDirty();
    }

    /**
     * 获取最大缓存时间（天）
     *
     * @return 最大缓存时间
     */
    public int getMaxCacheAge() {
        ensureSettingsLoaded();
        return maxCacheAge;
    }

    /**
     * 设置最大缓存时间（天）
     *
     * @param maxCacheAge 最大缓存时间
     */
    public void setMaxCacheAge(int maxCacheAge) {
        this.maxCacheAge = maxCacheAge;
        markDirty();
    }

    /**
     * 是否启用预加载
     *
     * @return 是否启用预加载
     */
    public boolean isEnablePreload() {
        ensureSettingsLoaded();
        return enablePreload;
    }

    /**
     * 设置是否启用预加载
     *
     * @param enablePreload 是否启用预加载
     */
    public void setEnablePreload(boolean enablePreload) {
        this.enablePreload = enablePreload;
        markDirty();
    }

    /**
     * 获取预加载延迟（毫秒）
     *
     * @return 预加载延迟
     */
    public int getPreloadDelay() {
        ensureSettingsLoaded();
        return preloadDelay;
    }

    /**
     * 设置预加载延迟（毫秒）
     *
     * @param preloadDelay 预加载延迟
     */
    public void setPreloadDelay(int preloadDelay) {
        this.preloadDelay = preloadDelay;
        markDirty();
    }

    /**
     * 获取缓存目录路径
     *
     * @return 缓存目录路径
     */
    @NotNull
    public String getCacheDirectoryPath() {
        ensureSettingsLoaded();
        return PathManager.getSystemPath() + "/private-reader/cache";
    }

    @Override
    protected void copyFrom(CacheSettings source) {
        this.cacheExpiryHours = source.cacheExpiryHours;
        this.maxCacheSizeMB = source.maxCacheSizeMB;
        this.enableCache = source.enableCache;
        this.cleanupOnStartup = source.cleanupOnStartup;
        this.preloadNextChapter = source.preloadNextChapter;
        this.preloadCount = source.preloadCount;
        this.maxCacheSize = source.maxCacheSize;
        this.maxCacheAge = source.maxCacheAge;
        this.enablePreload = source.enablePreload;
        this.preloadDelay = source.preloadDelay;
    }

    @Override
    protected CacheSettings getDefault() {
        // 直接创建新实例并设置默认值
        // BaseSettings.ensureSettingsLoaded 会处理 isInitializingDefaults 标志
        CacheSettings settings = new CacheSettings();
        settings.cacheExpiryHours = 24 * 7; // 默认7天
        settings.maxCacheSizeMB = 100; // 默认100MB
        settings.enableCache = true;
        settings.cleanupOnStartup = true;
        settings.preloadNextChapter = true;
        settings.preloadCount = 1;
        settings.maxCacheSize = 100;
        settings.maxCacheAge = 7;
        settings.enablePreload = true;
        settings.preloadDelay = 500;
        return settings;
    }
} 