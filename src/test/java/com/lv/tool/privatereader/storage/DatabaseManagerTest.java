package com.lv.tool.privatereader.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerTest {

    @TempDir
    private Path tempDir;

    @Test
    void initializeCreatesReadingProgressTable() throws SQLException {
        try (Connection connection = openDatabase("new.db")) {
            DatabaseManager.initializeDatabaseTableStructure(connection);

            assertTrue(tableExists(connection, "reading_progress"));
            assertTrue(columnExists(connection, "reading_progress", "is_finished"));
            assertTrue(columnExists(connection, "reading_progress", "book_title"));
            assertTrue(columnExists(connection, "reading_progress", "last_read_time"));
        }
    }

    @Test
    void initializeAddsFinishedColumnToLegacyTable() throws SQLException {
        try (Connection connection = openDatabase("legacy.db");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE reading_progress (
                        book_id TEXT PRIMARY KEY NOT NULL,
                        last_read_timestamp INTEGER NOT NULL
                    )
                    """);

            DatabaseManager.initializeDatabaseTableStructure(connection);

            assertTrue(columnExists(connection, "reading_progress", "is_finished"));
            assertTrue(columnExists(connection, "reading_progress", "book_title"));
            assertTrue(columnExists(connection, "reading_progress", "last_read_time"));
            // 旧列已迁移并删除,避免其 NOT NULL 约束阻断新写入
            assertFalse(columnExists(connection, "reading_progress", "last_read_timestamp"));
        }
    }

    @Test
    void initializeBackfillsLegacyTimestampIntoReadableTime() throws SQLException {
        try (Connection connection = openDatabase("legacy-data.db");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE reading_progress (
                        book_id TEXT PRIMARY KEY NOT NULL,
                        last_read_timestamp INTEGER NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO reading_progress (book_id, last_read_timestamp) VALUES ('b1', 1000)");

            DatabaseManager.initializeDatabaseTableStructure(connection);

            try (ResultSet rs = statement.executeQuery("SELECT last_read_time FROM reading_progress WHERE book_id = 'b1'")) {
                assertTrue(rs.next());
                String migrated = rs.getString("last_read_time");
                assertTrue(migrated != null && migrated.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"),
                        "应回填为 yyyy-MM-dd HH:mm:ss.SSS 格式,实际: " + migrated);
            }
        }
    }

    @Test
    void initializeIsIdempotent() throws SQLException {
        try (Connection connection = openDatabase("existing.db")) {
            DatabaseManager.initializeDatabaseTableStructure(connection);
            DatabaseManager.initializeDatabaseTableStructure(connection);

            assertEquals(1, columnCount(connection, "reading_progress", "is_finished"));
        }
    }

    private Connection openDatabase(String fileName) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve(fileName));
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        return columnCount(connection, tableName, columnName) > 0;
    }

    private int columnCount(Connection connection, String tableName, String columnName) throws SQLException {
        int count = 0;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    count++;
                }
            }
        }
        return count;
    }
}
