package com.lv.tool.privatereader.repository;

import org.jetbrains.annotations.NotNull;

/**
 * 存储仓库接口
 * 
 * 定义存储管理的基本操作，提供统一的存储路径和备份管理接口。
 * 实现类负责具体的存储实现细节。
 */
public interface StorageRepository {
    
    /**
     * 获取基础存储路径
     * 
     * @return 基础存储路径
     */
    @NotNull
    String getBaseStoragePath();
    
    /**
     * 获取书籍存储路径
     * 
     * @return 书籍存储路径
     */
    @NotNull
    String getBooksPath();
    
    /**
     * 获取缓存存储路径
     * 
     * @return 缓存存储路径
     */
    @NotNull
    String getCachePath();
    
    /**
     * 获取设置存储路径
     * 
     * @return 设置存储路径
     */
    @NotNull
    String getSettingsPath();
    
    /**
     * 获取备份存储路径
     * 
     * @return 备份存储路径
     */
    @NotNull
    String getBackupPath();
    
    /**
     * 获取书籍文件路径
     * 
     * @return 书籍文件路径
     */
    @NotNull
    String getBooksFilePath();
    
    /**
     * 创建书籍目录
     * 
     * @param bookId 书籍ID
     * @return 创建的目录路径
     */
    @NotNull
    String createBookDirectory(String bookId);
    
    /**
     * 获取书籍目录
     * 
     * @param bookId 书籍ID
     * @return 书籍目录路径
     */
    @NotNull
    String getBookDirectory(String bookId);
    
    /**
     * 清空所有存储
     */
    void clearAllStorage();

    /**
     * 获取安全的文件名
     * 
     * @param fileName 原始文件名
     * @return 安全的文件名
     */
    @NotNull
    String getSafeFileName(@NotNull String fileName);
    
    /**
     * 获取缓存文件名
     * 
     * @param url URL
     * @return 缓存文件名
     */
    @NotNull
    String getCacheFileName(@NotNull String url);
} 