package com.lv.tool.privatereader.cache;

import java.util.List;
import java.util.concurrent.TimeUnit;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.diagnostic.Logger;

public class CacheManager {
    private static final Logger LOG = Logger.getInstance(CacheManager.class);
    
    private final Cache<String, List<Book>> bookCache;
    private final Cache<String, List<Chapter>> chapterCache;
    private final Cache<String, String> contentCache;
    
    public CacheManager() {
        // 书籍缓存：最多100本书，10分钟过期
        bookCache = CacheBuilder.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();
            
        // 章节缓存：最多1000章，30分钟过期
        chapterCache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();
            
        // 内容缓存：最多20章内容，1小时过期
        contentCache = CacheBuilder.newBuilder()
            .maximumSize(20)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();
    }
    
    /**
     * 获取缓存的书籍列表
     */
    public List<Book> getBooks(String key) {
        return bookCache.getIfPresent(key);
    }
    
    /**
     * 缓存书籍列表
     */
    public void putBooks(String key, @NotNull List<Book> books) {
        bookCache.put(key, books);
    }
    
    /**
     * 获取缓存的章节列表
     */
    public List<Chapter> getChapters(String key) {
        return chapterCache.getIfPresent(key);
    }
    
    /**
     * 缓存章节列表
     */
    public void putChapters(String key, @NotNull List<Chapter> chapters) {
        chapterCache.put(key, chapters);
    }
    
    /**
     * 获取缓存的章节内容
     */
    public String getContent(String key) {
        return contentCache.getIfPresent(key);
    }
    
    /**
     * 缓存章节内容
     */
    public void putContent(String key, @NotNull String content) {
        contentCache.put(key, content);
    }
    
    /**
     * 清除所有缓存
     */
    public void clearAll() {
        bookCache.invalidateAll();
        chapterCache.invalidateAll();
        contentCache.invalidateAll();
        LOG.info("所有缓存已清除");
    }
    
    /**
     * 检查缓存是否过期
     * 
     * @param key 缓存键
     * @return 是否过期
     */
    public boolean isCacheExpired(String key) {
        return bookCache.getIfPresent(key) == null;
    }
    
    /**
     * 使指定缓存失效
     * 
     * @param key 缓存键
     */
    public void invalidateCache(String key) {
        bookCache.invalidate(key);
        LOG.info("缓存已失效: " + key);
    }
    
    /**
     * 获取缓存统计信息
     */
    public String getStats() {
        return String.format(
            "书籍缓存: %d项, 章节缓存: %d项, 内容缓存: %d项",
            bookCache.size(),
            chapterCache.size(),
            contentCache.size()
        );
    }
} 