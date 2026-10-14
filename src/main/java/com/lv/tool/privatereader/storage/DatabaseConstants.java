package com.lv.tool.privatereader.storage;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.repository.StorageRepository;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Constants related to database storage, specifically the shared database file path.
 */
public final class DatabaseConstants {

    private static final Logger LOG = Logger.getInstance(DatabaseConstants.class);
    private static final String FALLBACK_DB_FILENAME = "progress_fallback.db"; // Fallback filename

    private DatabaseConstants() {
        // Private constructor to prevent instantiation
    }

    /**
     * Gets the path to the shared SQLite database file.
     * The file is stored within the base storage directory managed by StorageRepository
     * (typically ~/.private-reader/).
     *
     * @return The absolute Path object for the database file.
     */
    @NotNull
    public static Path getDatabasePath() {
        StorageRepository storageRepository = null;
        try {
            storageRepository = ApplicationManager.getApplication().getService(StorageRepository.class);
        } catch (Exception e) {
            LOG.error("Failed to get StorageRepository service while determining database path.", e);
        }

        if (storageRepository != null) {
            try {
                Path baseStoragePath = Paths.get(storageRepository.getBaseStoragePath());
                // Ensure the directory exists (handled by StorageRepository creation usually)
                return baseStoragePath.resolve("progress.db");
            } catch (Exception e) {
                 LOG.error("Error resolving database path using StorageRepository. Using fallback path.", e);
                 // Fallback logic if getBaseStoragePath fails unexpectedly
                 return getFallbackDatabasePath();
            }
        } else {
            // Fallback logic if StorageRepository service is unavailable
             LOG.warn("StorageRepository service not available. Using fallback database path.");
             return getFallbackDatabasePath();
        }
    }

    /**
     * Provides a fallback database path in the system temp directory if the primary storage is unavailable.
     */
    @NotNull
    private static Path getFallbackDatabasePath() {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
        Path fallbackPath = tempDir.resolve("private-reader-db").resolve(FALLBACK_DB_FILENAME);
        try {
            // Attempt to create the fallback directory
             Files.createDirectories(fallbackPath.getParent());
        } catch (IOException e) {
             LOG.error("Failed to create fallback database directory: " + fallbackPath.getParent(), e);
        }
        LOG.warn("Using fallback database location: " + fallbackPath);
        return fallbackPath;
    }

    /**
     * 从 JDBC URL 推导数据库文件路径(用于目录创建与诊断)。
     *
     * @param dbUrl 形如 {@code jdbc:sqlite:/path/to/x.db} 的 URL
     * @return 对应的文件 Path
     */
    @NotNull
    public static Path pathFromUrl(@NotNull String dbUrl) {
        String prefix = "jdbc:sqlite:";
        String filePath = dbUrl.startsWith(prefix) ? dbUrl.substring(prefix.length()) : dbUrl;
        return Paths.get(filePath);
    }

    /**
     * Gets the JDBC URL for the shared SQLite database.
     *
     * @return The JDBC URL string.
     */
    @NotNull
    public static String getDatabaseUrl() {
        // SQLite JDBC URL format: jdbc:sqlite:/path/to/database/file
        return "jdbc:sqlite:" + getDatabasePath().toAbsolutePath().toString();
    }
} 