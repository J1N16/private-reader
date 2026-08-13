package com.lv.tool.privatereader.settings;

import com.lv.tool.privatereader.storage.SettingsStorage;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BaseSettings 单元测试
 * 覆盖：无配置文件/有配置文件时的加载策略、加载幂等、脏标记保存逻辑
 *
 * <p>注意：IntelliJ Logger.error 在此测试环境抛 AssertionError，因此
 * 刻意避开「存储为 null」与「加载抛异常」两条错误分支，统一走 debug 路径。</p>
 */
class BaseSettingsTest {

    /** 最小测试设置子类 */
    static class TestSettings extends BaseSettings<TestSettings> {
        private String value = "default";
        private int count = 1;

        public String getValue() {
            ensureSettingsLoaded();
            return value;
        }

        public void setValue(String value) {
            this.value = value;
            markDirty();
        }

        public int getCount() {
            ensureSettingsLoaded();
            return count;
        }

        public void setCount(int count) {
            this.count = count;
            markDirty();
        }

        @Override
        protected void copyFrom(TestSettings other) {
            this.value = other.value;
            this.count = other.count;
        }

        @Override
        protected TestSettings getDefault() {
            TestSettings defaults = new TestSettings();
            defaults.value = "default";
            defaults.count = 1;
            return defaults;
        }
    }

    /** 返回存储 mock：loadSettings 未打桩 → 返回 null（无配置文件 → 走默认值） */
    private static MockedStatic<SettingsStorage> mockStorageEmpty() {
        MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class);
        storage.when(SettingsStorage::getInstance).thenReturn(mock(SettingsStorage.class));
        return storage;
    }

    // --- 加载策略 ---

    @Test
    void usesDefaultsWhenNoConfigFile() {
        try (MockedStatic<SettingsStorage> storage = mockStorageEmpty()) {
            TestSettings settings = new TestSettings();
            assertEquals("default", settings.getValue());
            assertEquals(1, settings.getCount());
        }
    }

    @Test
    void loadsFromStorageWhenAvailable() {
        TestSettings persisted = new TestSettings();
        persisted.value = "loaded";
        persisted.count = 42;

        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            SettingsStorage storageMock = mock(SettingsStorage.class);
            storage.when(SettingsStorage::getInstance).thenReturn(storageMock);
            when(storageMock.loadSettings(TestSettings.class)).thenReturn(persisted);

            TestSettings settings = new TestSettings();
            assertEquals("loaded", settings.getValue());
            assertEquals(42, settings.getCount());
        }
    }

    @Test
    void loadIsIdempotentAfterFirstAccess() {
        TestSettings first = new TestSettings();
        first.value = "v1";
        TestSettings second = new TestSettings();
        second.value = "v2";

        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            SettingsStorage storageMock = mock(SettingsStorage.class);
            storage.when(SettingsStorage::getInstance).thenReturn(storageMock);
            // 第一次返回 v1，之后返回 v2（用于验证不会重复加载）
            when(storageMock.loadSettings(TestSettings.class)).thenReturn(first, second);

            TestSettings settings = new TestSettings();
            assertEquals("v1", settings.getValue()); // 首次触发加载
            assertEquals("v1", settings.getValue()); // 已加载，不重新加载
        }
    }

    // --- 保存逻辑 ---

    @Test
    void savesWhenDirtyAfterLoad() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            SettingsStorage storageMock = mock(SettingsStorage.class);
            storage.when(SettingsStorage::getInstance).thenReturn(storageMock);

            TestSettings settings = new TestSettings();
            settings.getValue(); // 触发加载
            settings.setValue("x"); // 标记 dirty
            settings.saveSettings();

            verify(storageMock).saveSettings(settings);
        }
    }

    @Test
    void skipsSaveWhenClean() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            SettingsStorage storageMock = mock(SettingsStorage.class);
            storage.when(SettingsStorage::getInstance).thenReturn(storageMock);

            TestSettings settings = new TestSettings();
            settings.getValue(); // 触发加载，无修改
            settings.saveSettings();

            verify(storageMock, never()).saveSettings(settings);
        }
    }

    @Test
    void saveAfterLoadWithoutSetterChangeDoesNotOverwrite() {
        try (MockedStatic<SettingsStorage> storage = mockStatic(SettingsStorage.class)) {
            SettingsStorage storageMock = mock(SettingsStorage.class);
            storage.when(SettingsStorage::getInstance).thenReturn(storageMock);

            TestSettings settings = new TestSettings();
            settings.getValue();
            settings.saveSettings();
            settings.saveSettings();

            verify(storageMock, never()).saveSettings(settings);
        }
    }
}
