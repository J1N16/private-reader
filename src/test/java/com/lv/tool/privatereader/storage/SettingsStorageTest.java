package com.lv.tool.privatereader.storage;

import com.lv.tool.privatereader.settings.CacheSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SettingsStorage 文件读写单元测试
 *
 * <p>将 user.home 临时指向 JUnit @TempDir 目录，验证 saveSettings/loadSettings
 * 的真实文件 IO 往返：目录创建、JSON 序列化、反序列化、文件缺失/空文件处理。</p>
 *
 * <p>注意：反序列化返回的实例 loaded=false，调用 getter 会触发 ensureSettingsLoaded()
 * 走 SettingsStorage.getInstance() 静态门面，故用反射读取私有字段验证往返。</p>
 */
class SettingsStorageTest {

    private static final String EXPECTED_SUBDIR = ".private-reader/settings";

    @TempDir
    Path tempDir;

    private SettingsStorage storage;
    private String originalUserHome;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        storage = new SettingsStorage();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalUserHome);
    }

    private Path settingsFilePath(Class<?> settingsClass) {
        return Paths.get(tempDir.toString(), EXPECTED_SUBDIR, settingsClass.getSimpleName() + ".json");
    }

    private Object fieldValue(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    // --- 保存 ---

    @Test
    void saveCreatesSettingsFileAtExpectedPath() {
        CacheSettings settings = new CacheSettings();
        settings.setCacheExpiryHours(48);

        storage.saveSettings(settings);

        Path expected = settingsFilePath(CacheSettings.class);
        assertTrue(Files.exists(expected), "保存后应生成文件: " + expected);
        assertTrue(Files.isRegularFile(expected));
    }

    @Test
    void saveCreatesParentDirectoriesAutomatically() {
        // 父目录 .private-reader/settings 尚不存在（@TempDir 初始为空）
        CacheSettings settings = new CacheSettings();
        settings.setEnableCache(false);

        storage.saveSettings(settings);

        assertTrue(Files.exists(settingsFilePath(CacheSettings.class).getParent()),
                "保存时应自动创建父目录");
    }

    @Test
    void saveWritesSerializedJsonContainingFieldValues() throws Exception {
        CacheSettings settings = new CacheSettings();
        settings.setCacheExpiryHours(48);
        settings.setEnablePreload(false);

        storage.saveSettings(settings);

        String json = Files.readString(settingsFilePath(CacheSettings.class));
        assertTrue(json.contains("\"cacheExpiryHours\": 48"), "JSON 应包含设置的字段值，实际: " + json);
        assertTrue(json.contains("\"enablePreload\": false"), "JSON 应包含布尔字段，实际: " + json);
    }

    // --- 加载 ---

    @Test
    void loadReturnsNullWhenFileAbsent() {
        CacheSettings loaded = storage.loadSettings(CacheSettings.class);
        assertNull(loaded, "文件不存在时应返回 null");
    }

    @Test
    void loadReturnsNullWhenFileEmpty() throws IOException {
        Files.createDirectories(settingsFilePath(CacheSettings.class).getParent());
        Files.writeString(settingsFilePath(CacheSettings.class), "");

        CacheSettings loaded = storage.loadSettings(CacheSettings.class);
        assertNull(loaded, "空文件应返回 null");
    }

    @Test
    void loadRestoresFieldsFromWrittenJson() throws Exception {
        // 模拟之前版本写入的 JSON（只包含部分字段）
        Files.createDirectories(settingsFilePath(CacheSettings.class).getParent());
        Files.writeString(settingsFilePath(CacheSettings.class),
                "{\n  \"cacheExpiryHours\": 48,\n  \"maxCacheSizeMB\": 200,\n  \"enableCache\": false\n}");

        CacheSettings loaded = storage.loadSettings(CacheSettings.class);

        assertNotNull(loaded);
        assertEquals(48L, fieldValue(loaded, "cacheExpiryHours"));
        assertEquals(200, fieldValue(loaded, "maxCacheSizeMB"));
        assertFalse((Boolean) fieldValue(loaded, "enableCache"));
    }

    // --- 往返 ---

    @Test
    void saveAndLoadRoundTripPersistsAllFields() throws Exception {
        CacheSettings original = new CacheSettings();
        original.setCacheExpiryHours(72);
        original.setMaxCacheSizeMB(300);
        original.setEnableCache(false);
        original.setCleanupOnStartup(false);
        original.setPreloadNextChapter(false);
        original.setPreloadCount(3);
        original.setMaxCacheSize(500);
        original.setMaxCacheAge(10);
        original.setEnablePreload(false);
        original.setPreloadDelay(1000);

        storage.saveSettings(original);

        CacheSettings loaded = storage.loadSettings(CacheSettings.class);
        assertNotNull(loaded);
        assertEquals(72L, fieldValue(loaded, "cacheExpiryHours"));
        assertEquals(300, fieldValue(loaded, "maxCacheSizeMB"));
        assertFalse((Boolean) fieldValue(loaded, "enableCache"));
        assertFalse((Boolean) fieldValue(loaded, "cleanupOnStartup"));
        assertFalse((Boolean) fieldValue(loaded, "preloadNextChapter"));
        assertEquals(3, fieldValue(loaded, "preloadCount"));
        assertEquals(500, fieldValue(loaded, "maxCacheSize"));
        assertEquals(10, fieldValue(loaded, "maxCacheAge"));
        assertFalse((Boolean) fieldValue(loaded, "enablePreload"));
        assertEquals(1000, fieldValue(loaded, "preloadDelay"));
    }

    @Test
    void saveOverwritesExistingFile() throws Exception {
        CacheSettings first = new CacheSettings();
        first.setCacheExpiryHours(24);
        storage.saveSettings(first);

        CacheSettings second = new CacheSettings();
        second.setCacheExpiryHours(96);
        storage.saveSettings(second);

        CacheSettings loaded = storage.loadSettings(CacheSettings.class);
        assertNotNull(loaded);
        assertEquals(96L, fieldValue(loaded, "cacheExpiryHours"), "再次保存应覆盖旧值");
    }
}
