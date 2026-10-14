package com.lv.tool.privatereader.storage;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DatabaseConstants 单元测试(V22)。
 * 覆盖为可测性改造新增的 {@link DatabaseConstants#pathFromUrl(String)}(纯函数,无外部依赖)。
 */
class DatabaseConstantsTest {

    @Test
    void pathFromUrlStripsSqlitePrefix() {
        String path = Paths.get(System.getProperty("java.io.tmpdir"), "pr", "progress.db").toString();
        assertEquals(Paths.get(path), DatabaseConstants.pathFromUrl("jdbc:sqlite:" + path));
    }

    @Test
    void pathFromUrlHandlesBarePath() {
        String path = Paths.get("relative", "progress.db").toString();
        assertEquals(Paths.get(path), DatabaseConstants.pathFromUrl(path));
    }
}