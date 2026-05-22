package com.lv.tool.privatereader.storage;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.Disposable;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookProgressData;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import com.lv.tool.privatereader.repository.StorageRepository;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the connection to the shared SQLite database for reading progress.
 * Initializes the database table if it doesn't exist.
 * Implemented as a Application Level Service.
 * Includes one-time migration logic from old JSON format.
 *
 * 优化：使用静态驱动加载 + 缓存连接，避免每次调用都创建新连接。
 * SQLite 是单写入者数据库，单连接 + 同步锁即可满足需求。
 */
@Service(Service.Level.APP)
public final class DatabaseManager implements Disposable {
    private static final Logger LOG = Logger.getInstance(DatabaseManager.class);
    private static final AtomicBoolean databaseInitialized = new AtomicBoolean(false);
    private static final String MIGRATION_FLAG_KEY = "private.reader.migration.sqlite.v1.complete";
    private static final String BOOK_DETAILS_FILENAME = "book_details.json";

    private final String dbUrl;

    // 静态初始化块：只加载一次驱动
    static {
        try {
            Class.forName("org.sqlite.JDBC");
            LOG.info("SQLite JDBC driver loaded successfully.");
        } catch (ClassNotFoundException e) {
            LOG.error("CRITICAL: SQLite JDBC driver class not found. Please ensure the 'org.xerial:sqlite-jdbc' dependency is added to build.gradle.", e);
        }
    }

    // SQL statement to create the progress table
    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS reading_progress (
                book_id TEXT PRIMARY KEY NOT NULL,
                last_read_chapter_id TEXT,
                last_read_chapter_title TEXT,
                last_read_position INTEGER DEFAULT 0,
                last_read_page INTEGER DEFAULT 1,
                is_finished INTEGER DEFAULT 0, -- 0 for false, 1 for true
                last_read_timestamp INTEGER NOT NULL
            );
            """;

    // SQL statement to add the 'is_finished' column if it doesn't exist (for migration)
    private static final String ADD_FINISHED_COLUMN_SQL = """
            ALTER TABLE reading_progress ADD COLUMN is_finished INTEGER DEFAULT 0;
            """;

    public DatabaseManager() {
        this.dbUrl = DatabaseConstants.getDatabaseUrl();
        LOG.info("DatabaseManager initialized. DB URL: " + dbUrl);
        ensureDatabaseInitialized();
    }

    public static DatabaseManager getInstance() {
        return ApplicationManager.getApplication().getService(DatabaseManager.class);
    }

    /**
     * 获取 SQLite 数据库连接。
     * 调用方负责关闭返回的连接。
     *
     * @return A valid Connection object.
     * @throws SQLException if a database access error occurs.
     */
    @NotNull
    public Connection getConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(dbUrl);
        connection.setAutoCommit(true);
        return connection;
    }

    @Override
    public void dispose() {
    }

    /**
     * Ensures the database directory exists and the table is initialized.
     * Should be called once during application startup (e.g., in the constructor).
     */
    private void ensureDatabaseInitialized() {
        if (databaseInitialized.compareAndSet(false, true)) {
            LOG.info("Performing one-time database initialization...");
            try {
                // Ensure database directory exists
                Path dbPath = DatabaseConstants.getDatabasePath();
                Path parentDir = dbPath.getParent();
                if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                    LOG.info("Created database directory: " + parentDir);
                }
                // Initialize table structure
                initializeDatabaseTableStructure();
                LOG.info("One-time database initialization completed.");
            } catch (Exception e) {
                LOG.error("Failed during one-time database initialization. Database might be unusable.", e);
                databaseInitialized.set(false); // Reset flag to allow retry on next startup?
            }
        }
    }

    /**
     * 初始化数据库表结构（CREATE TABLE, ALTER TABLE）。
     */
    private void initializeDatabaseTableStructure() {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {

            stmt.execute(CREATE_TABLE_SQL);
            LOG.info("Ensured 'reading_progress' table exists.");

            try {
                stmt.execute(ADD_FINISHED_COLUMN_SQL);
                LOG.info("Added 'is_finished' column to 'reading_progress' table (or it already existed).");
            } catch (SQLiteException e) {
                if (e.getErrorCode() == SQLiteErrorCode.SQLITE_ERROR.code && e.getMessage().contains("duplicate column name: is_finished")) {
                    LOG.debug("'is_finished' column already exists.");
                } else {
                    throw e;
                }
            }

        } catch (SQLException e) {
            LOG.error("Failed to initialize database table 'reading_progress'.", e);
        }
    }

    /**
     * Simple record to hold data parsed from the old book_details.json format.
     * Adjust fields based on the actual JSON structure.
     */
    private record OldBookDetails(
            String id,
            @Nullable String lastReadChapterId,
            @Nullable String lastReadChapterTitle, // Added this field based on schema
            @Nullable Integer lastReadPosition, // Using Integer for null checks
            @Nullable Integer lastReadPage,     // Using Integer for null checks
            @Nullable Boolean isFinished,       // JSON might use boolean
            @Nullable Long lastReadTimestamp // JSON might use different name like lastReadTimeMillis
    ) {}

    public void runMigrationIfNeeded() {
        PropertiesComponent propertiesComponent = PropertiesComponent.getInstance();
        if (propertiesComponent.isValueSet(MIGRATION_FLAG_KEY)) {
            LOG.info("Migration to SQLite already completed. Skipping.");
            return;
        }

        LOG.info("Starting one-time migration of reading progress from JSON to SQLite...");

        StorageRepository storageRepository = null;
        ReadingProgressRepository progressRepository = null;
        try {
            storageRepository = ApplicationManager.getApplication().getService(StorageRepository.class);
            // Get the specific implementation, likely SqliteReadingProgressRepository
            progressRepository = ApplicationManager.getApplication().getService(ReadingProgressRepository.class);

            if (storageRepository == null || progressRepository == null) {
                LOG.error("Migration failed: Could not obtain required services (StorageRepository or ReadingProgressRepository).");
                return;
            }

            Path booksBasePath = Paths.get(storageRepository.getBooksPath());
            if (!Files.isDirectory(booksBasePath)) {
                LOG.warn("Migration skipped: Books base path does not exist or is not a directory: " + booksBasePath);
                // Set the flag anyway if the source path doesn't exist? Or let it retry? Let's retry next time.
                return;
            }

            Gson gson = new Gson();
            int migratedCount = 0;
            int errorCount = 0;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(booksBasePath)) {
                for (Path bookDir : stream) {
                    if (Files.isDirectory(bookDir)) {
                        Path detailsJsonPath = bookDir.resolve(BOOK_DETAILS_FILENAME);
                        if (Files.isRegularFile(detailsJsonPath)) {
                            String bookId = bookDir.getFileName().toString(); // Assuming directory name is bookId
                            try (BufferedReader reader = Files.newBufferedReader(detailsJsonPath)) {
                                OldBookDetails details = gson.fromJson(reader, OldBookDetails.class);

                                if (details != null && details.id != null && !details.id.isEmpty()) {
                                    // Ensure ID consistency (optional, but good practice)
                                    if (!details.id.equals(bookId)) {
                                         LOG.warn("Migration warning for potential book ID mismatch: Directory '" + bookId + "', JSON ID '" + details.id + "'. Using JSON ID.");
                                         bookId = details.id; // Prefer ID from JSON
                                    }

                                    // Only migrate if there's some progress data
                                    if (details.lastReadTimestamp != null && details.lastReadTimestamp > 0) {
                                        // Prepare data for the repository call
                                        String finalBookId = bookId;
                                        boolean isBookFinished = details.isFinished != null && details.isFinished;
                                        int position = details.lastReadPosition != null ? details.lastReadPosition : 0;
                                        int page = details.lastReadPage != null ? details.lastReadPage : 1;

                                        // Create a temporary Book object just for the updateProgress call
                                        Book tempBook = new Book(); // Use the default constructor
                                        tempBook.setId(finalBookId); // Set the ID manually
                                        tempBook.setFinished(isBookFinished); // Set the finished status

                                        // Call the correct updateProgress method
                                        progressRepository.updateProgress(
                                                tempBook, // Pass the temporary book
                                                details.lastReadChapterId,
                                                details.lastReadChapterTitle,
                                                position,
                                                page // Use the specific overload with page
                                        );

                                        // Original BookProgressData creation (optional, can be removed if not used elsewhere)
                                        /*
                                        BookProgressData progressData = new BookProgressData(
                                                bookId,
                                                details.lastReadChapterId,
                                                details.lastReadChapterTitle, // Use field from record
                                                details.lastReadPosition != null ? details.lastReadPosition : 0,
                                                details.lastReadPage != null ? details.lastReadPage : 1,
                                                details.isFinished != null && details.isFinished, // Keep as boolean for record
                                                details.lastReadTimestamp // Use the timestamp directly
                                        );
                                        */

                                        LOG.debug("Migrated progress for book ID: " + bookId);
                                        migratedCount++;
                                    } else {
                                        LOG.debug("Skipping migration for book ID " + bookId + ": No valid lastReadTimestamp found in JSON.");
                                    }
                                } else {
                                    LOG.warn("Skipping migration for directory " + bookDir.getFileName() + ": Invalid or missing 'id' in " + BOOK_DETAILS_FILENAME);
                                    errorCount++;
                                }
                            } catch (JsonSyntaxException e) {
                                LOG.error("Migration error: Failed to parse JSON " + detailsJsonPath, e);
                                errorCount++;
                            } catch (IOException e) {
                                LOG.error("Migration error: Failed to read file " + detailsJsonPath, e);
                                errorCount++;
                            } catch (Exception e) { // Catch errors during progress update
                                LOG.error("Migration error: Failed to save progress to DB for book ID: " + bookId, e);
                                errorCount++;
                            }
                        }
                    }
                }
            } // DirectoryStream try-with-resources

            if (errorCount == 0) {
                LOG.info("Migration from JSON to SQLite completed successfully. Migrated " + migratedCount + " progress entries.");
                propertiesComponent.setValue(MIGRATION_FLAG_KEY, true); // Set flag only on full success
            } else {
                LOG.warn("Migration from JSON to SQLite finished with " + errorCount + " errors. Migrated " + migratedCount + " entries. The process will retry on next startup.");
            }

        } catch (Exception e) { // Catch errors getting services or listing directories
            LOG.error("Critical migration error. The process will retry on next startup.", e);
        }
    }

} 