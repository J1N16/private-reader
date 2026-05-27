package com.lv.tool.privatereader.repository.impl;

import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.repository.StorageRepository;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * 文件存储仓库实现
 *
 * 基于文件系统实现存储仓库接口，管理应用的各种存储需求。
 */
public final class FileStorageRepository implements StorageRepository {
    private static final Logger LOG = Logger.getInstance(FileStorageRepository.class);
    private static final int MAX_FILENAME_LENGTH = 255; // 大多数文件系统的限制
    private static final String HASH_ALGORITHM = "SHA-256";
    
    private final Path baseStoragePath;
    private final Path booksPath;
    private final Path cachePath;
    private final Path settingsPath;
    private final Path backupPath;
    
    public FileStorageRepository() {
        LOG.info("初始化应用级别的 FileStorageRepository");
        
        // 使用用户主目录下的.private-reader目录作为基础存储路径
        this.baseStoragePath = Path.of(System.getProperty("user.home"), ".private-reader");
        this.booksPath = baseStoragePath.resolve("books");
        this.cachePath = baseStoragePath.resolve("cache");
        this.settingsPath = baseStoragePath.resolve("settings");
        this.backupPath = baseStoragePath.resolve("backup");
        
        // 创建存储目录结构
        createStorageDirectories();
    }
    
    /**
     * 创建存储目录结构
     */
    private void createStorageDirectories() {
        try {
            Files.createDirectories(baseStoragePath);
            Files.createDirectories(booksPath);
            Files.createDirectories(cachePath);
            Files.createDirectories(settingsPath);
            Files.createDirectories(backupPath);
            LOG.info("创建存储目录结构: " + baseStoragePath);
        } catch (IOException e) {
            LOG.error("创建存储目录结构失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    @NotNull
    public String getBaseStoragePath() {
        return baseStoragePath.toString();
    }
    
    @Override
    @NotNull
    public String getBooksPath() {
        return booksPath.toString();
    }
    
    @Override
    @NotNull
    public String getCachePath() {
        return cachePath.toString();
    }
    
    @Override
    @NotNull
    public String getSettingsPath() {
        return settingsPath.toString();
    }
    
    @Override
    @NotNull
    public String getBackupPath() {
        return backupPath.toString();
    }
    
    @Override
    @NotNull
    public String getBooksFilePath() {
        return Path.of(getBooksPath(), "index.json").toString();
    }
    
    @Override
    @NotNull
    public String createBookDirectory(String bookId) {
        String dirPath = getBookDirectory(bookId);
        try {
            Files.createDirectories(Path.of(dirPath));
            LOG.info("创建书籍目录: " + dirPath);
        } catch (IOException e) {
            LOG.error("创建书籍目录失败: " + e.getMessage(), e);
        }
        return dirPath;
    }
    
    @Override
    @NotNull
    public String getBookDirectory(String bookId) {
        return Path.of(getBooksPath(), getSafeFileName(bookId)).toString();
    }
    
    @Override
    public void clearAllStorage() {
        try {
            // 删除所有存储目录内容
            deleteDirectoryContents(new File(getBooksPath()));
            deleteDirectoryContents(new File(getCachePath()));
            deleteDirectoryContents(new File(getSettingsPath()));
            LOG.info("清空所有存储");
        } catch (Exception e) {
            LOG.error("清空存储失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    @NotNull
    public String createBackup() {
        String backupFileName = "backup_" + System.currentTimeMillis() + ".zip";
        String backupFilePath = Path.of(getBackupPath(), backupFileName).toString();
        
        // TODO: 实现备份逻辑
        
        return backupFilePath;
    }
    
    @Override
    public boolean restoreFromBackup(String backupFilePath) {
        // TODO: 实现恢复逻辑
        return false;
    }
    
    /**
     * 删除目录内容
     */
    private void deleteDirectoryContents(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return;
        }
        
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectoryContents(file);
                }
                if (!file.delete()) {
                    LOG.warn("无法删除文件: " + file.getAbsolutePath());
                }
            }
        }
    }
    
    @Override
    @NotNull
    public String getSafeFileName(@NotNull String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unknown";
        }
        
        // 规范化字符串，处理特殊字符
        String normalized = Normalizer.normalize(fileName, Normalizer.Form.NFD);
        
        // 替换不安全的文件名字符
        String safe = normalized.replaceAll("[^a-zA-Z0-9\\._\\-]", "_");
        
        // 如果文件名过长，使用哈希值
        if (safe.length() > MAX_FILENAME_LENGTH) {
            try {
                return hashString(fileName);
            } catch (NoSuchAlgorithmException e) {
                // 如果哈希失败，截断文件名
                return safe.substring(0, MAX_FILENAME_LENGTH);
            }
        }
        
        return safe;
    }
    
    /**
     * 对字符串进行哈希处理
     */
    private static String hashString(String input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hash);
    }
    
    /**
     * 将字节数组转换为十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
    
    @Override
    @NotNull
    public String getCacheFileName(@NotNull String url) {
        if (url == null || url.isEmpty()) {
            return "unknown";
        }
        
        try {
            // 使用Base64编码URL，然后进行安全文件名处理
            String encoded = Base64.getEncoder().encodeToString(url.getBytes());
            return getSafeFileName(encoded);
        } catch (Exception e) {
            LOG.warn("生成缓存文件名失败: " + e.getMessage());
            return getSafeFileName(url);
        }
    }
} 