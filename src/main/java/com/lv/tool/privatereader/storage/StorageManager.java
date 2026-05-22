package com.lv.tool.privatereader.storage;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.repository.ReactiveChapterCacheRepository;
import com.lv.tool.privatereader.repository.BookRepository;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Base64;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

/**
 * 存储管理器
 * 
 * 统一管理应用的各种存储需求，包括：
 * - 书籍数据存储
 * - 章节缓存
 * - 设置存储
 * - 阅读进度
 * 
 * 提供统一的存储路径管理和存储操作接口
 */
@Service(Service.Level.APP)
public final class StorageManager {
    private static final Logger LOG = Logger.getInstance(StorageManager.class);
    private static final int MAX_FILENAME_LENGTH = 255; // 大多数文件系统的限制
    private static final String HASH_ALGORITHM = "SHA-256";
    
    private final Path baseStoragePath;
    private final Path booksPath;
    private final Path cachePath;
    private final Path settingsPath;
    private final Path backupPath;
    
    public StorageManager() {
        LOG.info("初始化应用级别的 StorageManager");
        
        // 使用用户主目录下的.private-reader目录作为基础存储路径
        this.baseStoragePath = Path.of(System.getProperty("user.home"), ".private-reader");
        this.booksPath = baseStoragePath.resolve("books");
        this.cachePath = baseStoragePath.resolve("cache");
        this.settingsPath = baseStoragePath.resolve("settings");
        this.backupPath = baseStoragePath.resolve("backup");
        
        // 创建存储目录结构
        createStorageDirectories();
        
        // 不再在构造函数中执行耗时迁移
        // migrateFromOldLocation();
        // migrateSettings();
    }
    
    /**
     * 执行应用初始化完成后的任务，例如数据迁移。
     * 此方法应在后台线程中调用。
     */
    public void performPostInitializationTasks() {
        LOG.info("开始执行 StorageManager 的初始化后任务...");
        // 从旧位置迁移数据（如果需要）
        migrateFromOldLocation();
        
        LOG.info("StorageManager 的初始化后任务完成。");
    }
    
    /**
     * 获取设置存储服务实例
     */
    public SettingsStorage getSettingsStorage() {
        return ApplicationManager.getApplication().getService(SettingsStorage.class);
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
    
    /**
     * 从旧的存储位置迁移数据
     */
    private void migrateFromOldLocation() {
        try {
            // 获取旧的存储路径（IDEA配置目录）
            Path oldBasePath = Path.of(PathManager.getConfigPath(), "private-reader");
            Path oldBooksPath = oldBasePath.resolve("books");
            
            // 如果旧目录存在且新目录为空，执行迁移
            if (Files.exists(oldBooksPath) && !Files.exists(booksPath.resolve("index.json"))) {
                LOG.info("检测到旧版本数据，开始迁移...");
                
                // 创建备份
                String timestamp = String.valueOf(System.currentTimeMillis());
                Path backupDir = backupPath.resolve("migration_backup_" + timestamp);
                Files.createDirectories(backupDir);
                
                // 备份旧数据
                if (Files.exists(oldBasePath)) {
                    copyDirectory(oldBasePath, backupDir);
                    LOG.info("已备份旧数据到: " + backupDir);
                }
                
                // 复制书籍数据
                if (Files.exists(oldBooksPath)) {
                    copyDirectory(oldBooksPath, booksPath);
                    LOG.info("已迁移书籍数据");
                }
                
                // 复制缓存
                Path oldCachePath = oldBasePath.resolve("cache");
                if (Files.exists(oldCachePath)) {
                    copyDirectory(oldCachePath, cachePath);
                    LOG.info("已迁移缓存数据");
                }
                
                // 复制设置
                Path oldSettingsPath = oldBasePath.resolve("settings");
                if (Files.exists(oldSettingsPath)) {
                    copyDirectory(oldSettingsPath, settingsPath);
                    LOG.info("已迁移设置数据");
                }
                
                LOG.info("数据迁移完成");
            }
        } catch (Exception e) {
            LOG.error("数据迁移失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 递归复制目录
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source)
            .forEach(sourcePath -> {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    LOG.error("复制文件失败: " + sourcePath + " -> " + target, e);
                }
            });
    }
    
    /**
     * 获取响应式章节缓存仓库
     * @return 响应式章节缓存仓库
     * @deprecated 请使用 {@link #getReactiveChapterCacheRepository()} 代替
     */
    @Deprecated
    @NotNull
    public ReactiveChapterCacheRepository getChapterCacheManager() {
        return com.intellij.openapi.application.ApplicationManager.getApplication().getService(ReactiveChapterCacheRepository.class);
    }

    /**
     * 获取响应式章节缓存仓库
     * @return 响应式章节缓存仓库
     */
    @NotNull
    public ReactiveChapterCacheRepository getReactiveChapterCacheRepository() {
        return com.intellij.openapi.application.ApplicationManager.getApplication().getService(ReactiveChapterCacheRepository.class);
    }
    
    /**
     * 获取基础存储路径
     * @return 基础存储路径
     */
    @NotNull
    public String getBaseStoragePath() {
        return baseStoragePath.toString();
    }
    
    /**
     * 获取书籍数据存储路径
     * @return 书籍数据存储路径
     */
    @NotNull
    public String getBooksPath() {
        return booksPath.toString();
    }
    
    /**
     * 获取缓存存储路径
     * @return 缓存存储路径
     */
    @NotNull
    public String getCachePath() {
        return cachePath.toString();
    }
    
    /**
     * 获取设置存储路径
     * @return 设置存储路径
     */
    @NotNull
    public String getSettingsPath() {
        return settingsPath.toString();
    }
    
    /**
     * 获取备份存储路径
     * @return 备份存储路径
     */
    @NotNull
    public String getBackupPath() {
        return backupPath.toString();
    }
    
    /**
     * 获取书籍数据文件路径
     * @return 书籍数据文件路径
     */
    @NotNull
    public String getBooksFilePath() {
        return booksPath.resolve("index.json").toString();
    }
    
    /**
     * 创建书籍专属目录
     * @param bookId 书籍ID
     * @return 书籍专属目录路径
     */
    @NotNull
    public String createBookDirectory(String bookId) {
        Path bookDir = booksPath.resolve(bookId);
        try {
            Files.createDirectories(bookDir);
            LOG.info("创建书籍目录: " + bookDir);
        } catch (IOException e) {
            LOG.error("创建书籍目录失败: " + e.getMessage(), e);
        }
        return bookDir.toString();
    }
    
    /**
     * 获取书籍专属目录
     * @param bookId 书籍ID
     * @return 书籍专属目录路径
     */
    @NotNull
    public String getBookDirectory(String bookId) {
        Path bookDir = booksPath.resolve(bookId);
        if (!Files.exists(bookDir)) {
            return createBookDirectory(bookId);
        }
        return bookDir.toString();
    }
    
    /**
     * 清理所有存储数据
     * 谨慎使用，会删除所有数据
     */
    public void clearAllStorage() {
        try {
            // 删除所有书籍数据
            BookRepository bookRepository = com.intellij.openapi.application.ApplicationManager.getApplication().getService(BookRepository.class);
            if (bookRepository != null) {
                bookRepository.clearAllBooks();
            } else {
                LOG.error("无法获取 BookRepository 服务，无法清理书籍数据。");
            }
            
            // 删除所有缓存
            getChapterCacheManager().clearAllCache();
            
            // 删除所有目录内容
            deleteDirectoryContents(baseStoragePath.toFile());
            
            // 重新创建目录结构
            createStorageDirectories();
            
            LOG.info("已清理所有存储数据");
        } catch (Exception e) {
            LOG.error("清理存储数据失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 创建数据备份
     * @return 备份文件路径
     */
    @NotNull
    public String createBackup() {
        String timestamp = String.valueOf(System.currentTimeMillis());
        Path backupFile = backupPath.resolve("backup-" + timestamp + ".zip");
        
        // TODO: 实现备份逻辑
        
        return backupFile.toString();
    }
    
    /**
     * 从备份恢复数据
     * @param backupFilePath 备份文件路径
     * @return 是否恢复成功
     */
    public boolean restoreFromBackup(String backupFilePath) {
        // TODO: 实现恢复逻辑
        return false;
    }
    
    /**
     * 递归删除目录内容
     * @param directory 要删除内容的目录
     */
    private void deleteDirectoryContents(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectoryContents(file);
                    }
                    file.delete();
                }
            }
        }
    }
    
    /**
     * 生成安全的文件名
     * 1. 规范化Unicode字符
     * 2. 移除非法字符
     * 3. 处理长度限制
     * 4. 保持可读性
     * @param fileName 原始文件名
     * @return 安全的文件名
     */
    @NotNull
    public static String getSafeFileName(@NotNull String fileName) {
        // 1. Unicode规范化
        String normalized = Normalizer.normalize(fileName, Normalizer.Form.NFKC);
        
        // 2. 移除非法字符，只保留字母、数字、下划线、中文等安全字符
        String safe = normalized.replaceAll("[^\\w\\u4e00-\\u9fa5.-]", "_");
        
        // 3. 合并多个连续的下划线
        safe = safe.replaceAll("_+", "_");
        
        // 4. 如果文件名过长，使用hash处理
        if (safe.length() > MAX_FILENAME_LENGTH) {
            try {
                // 保留前缀以保持可读性
                String prefix = safe.substring(0, 20);
                // 对剩余部分进行hash
                String remaining = safe.substring(20);
                String hash = hashString(remaining);
                // 组合前缀和hash
                safe = prefix + "_" + hash;
            } catch (NoSuchAlgorithmException e) {
                LOG.warn("Hash算法不可用，使用截断方式处理长文件名");
                safe = safe.substring(0, MAX_FILENAME_LENGTH);
            }
        }
        
        // 5. 移除首尾的点和下划线
        safe = safe.replaceAll("^[._]+|[._]+$", "");
        
        // 6. 如果文件名为空，使用默认名称
        if (safe.isEmpty()) {
            safe = "unnamed_" + System.currentTimeMillis();
        }
        
        return safe;
    }
    
    /**
     * 对字符串进行hash处理
     * @param input 输入字符串
     * @return hash结果（16进制）
     */
    private static String hashString(String input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        // 只使用前8个字节，足够区分
        byte[] shortened = new byte[8];
        System.arraycopy(hash, 0, shortened, 0, 8);
        return bytesToHex(shortened);
    }
    
    /**
     * 将字节数组转换为16进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
    
    /**
     * 生成章节缓存文件名
     * @param url 章节URL
     * @return 安全的缓存文件名
     */
    @NotNull
    public static String getCacheFileName(@NotNull String url) {
        try {
            // 1. 移除协议前缀
            String simplified = url.replaceAll("^https?://", "");
            
            // 2. 如果URL较短，直接使用安全文件名
            if (simplified.length() <= MAX_FILENAME_LENGTH) {
                return getSafeFileName(simplified) + ".txt";
            }
            
            // 3. 对长URL进行hash处理
            String hash = hashString(url);
            
            // 4. 保留URL的一部分以提高可读性
            String prefix = getSafeFileName(simplified.substring(0, 30));
            
            return prefix + "_" + hash + ".txt";
        } catch (NoSuchAlgorithmException e) {
            LOG.warn("Hash算法不可用，使用Base64编码");
            // 降级方案：使用Base64编码
            String encoded = Base64.getUrlEncoder().encodeToString(url.getBytes());
            return getSafeFileName(encoded) + ".txt";
        }
    }
    
} 