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
