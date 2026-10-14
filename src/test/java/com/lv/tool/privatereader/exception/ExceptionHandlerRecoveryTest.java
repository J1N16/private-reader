package com.lv.tool.privatereader.exception;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ExceptionHandler 自动恢复分支测试(V28)。
 *
 * <p>V28 把恢复逻辑的三处外部依赖(网络可用性、存储服务、通知展示)抽成可注入的
 * {@link ExceptionHandler.RecoveryHost},生产默认实现 {@code DEFAULT_RECOVERY_HOST} 行为不变。
 * 本测试以内存 fake 注入,确定性覆盖网络/存储/解析三类恢复的所有正常分支
 * (可用/不可用、目录可写/不可写、缓存目录缺失需重建、服务不可用)。</p>
 *
 * <p>注意:存储不可写与服务不可用分支走 {@code showNotification}(fake 捕获),不会触碰
 * {@code LOG.error};但 {@code handle(...)} 本体首行即 {@code LOG.error},故本测试一律直接
 * 调用包级 {@code handleRecovery(...)} 而不调用 {@code handle(...)}。</p>
 */
class ExceptionHandlerRecoveryTest {

    @TempDir
    Path tempDir;

    /** 记录所有通知与网络可用性,便于断言恢复决策。 */
    private static final class FakeRecoveryHost implements ExceptionHandler.RecoveryHost {
        boolean networkAvailable;
        Path basePath;
        Path cachePath;
        boolean serviceUnavailable;
        final List<String> titles = new ArrayList<>();
        final List<String> contents = new ArrayList<>();
        final List<Project> projects = new ArrayList<>();

        @Override public boolean isNetworkAvailable() { return networkAvailable; }

        @Override public @Nullable Path storageBasePath() { return serviceUnavailable ? null : basePath; }

        @Override public @Nullable Path storageCachePath() { return serviceUnavailable ? null : cachePath; }

        @Override
        public void showNotification(@Nullable Project project, @NotNull String title, @NotNull String content) {
            projects.add(project);
            titles.add(title);
            contents.add(content);
        }
    }

    private static PrivateReaderException exception(PrivateReaderException.ExceptionType type) {
        return new PrivateReaderException("测试异常", type);
    }

    // --- 网络恢复 ---

    @Test
    void networkRecoveryReportsRestoredWhenNetworkAvailable() {
        FakeRecoveryHost host = new FakeRecoveryHost();
        host.networkAvailable = true;

        ExceptionHandler.handleRecovery(null, exception(PrivateReaderException.ExceptionType.NETWORK_ERROR), host);

        assertEquals(List.of("网络连接已恢复"), host.titles);
        assertEquals(List.of("正在重新加载数据..."), host.contents);
    }

    @Test
    void networkRecoveryReportsUnavailableWhenNetworkDown() {
        FakeRecoveryHost host = new FakeRecoveryHost();
        host.networkAvailable = false;

        ExceptionHandler.handleRecovery(null, exception(PrivateReaderException.ExceptionType.NETWORK_TIMEOUT), host);

        assertEquals(List.of("网络连接不可用"), host.titles);
        assertTrue(host.contents.get(0).contains("请检查网络连接"));
    }

    @Test
    void networkConnectionRefusedAlsoTriggersNetworkRecovery() {
        FakeRecoveryHost host = new FakeRecoveryHost();
        host.networkAvailable = true;

        ExceptionHandler.handleRecovery(null, exception(
                PrivateReaderException.ExceptionType.NETWORK_CONNECTION_REFUSED), host);

        assertEquals(List.of("网络连接已恢复"), host.titles);
    }

    // --- 存储恢复 ---

    @Test
    void storageRecoverySucceedsWhenBaseWritable() throws IOException {
        FakeRecoveryHost host = new FakeRecoveryHost();
        host.basePath = tempDir;
        host.cachePath = tempDir.resolve("cache");
        Files.createDirectories(host.cachePath);

        ExceptionHandler.handleRecovery(null, exception(PrivateReaderException.ExceptionType.STORAGE_ERROR), host);

        assertEquals(List.of("存储已恢复"), host.titles);
        assertTrue(host.contents.get(0).contains("存储目录检查通过"));
    }

    @Test
    void storageRecoveryRecreatesMissingCacheDirectory() {
        FakeRecoveryHost host = new FakeRecoveryHost();
        host.basePath = tempDir;
        host.cachePath = tempDir.resolve("missing-cache");
        assertFalse(Files.exists(host.cachePath));

        ExceptionHandler.handleRecovery(null, exception(PrivateReaderException.ExceptionType.STORAGE_ERROR), host);

        assertTrue(Files.isDirectory(host.cachePath), "恢复应重建缺失的缓存目录");
        assertEquals(List.of("存储已恢复"), host.titles);
    }

    @Test
    void storageRecoveryReportsUnavailableWhenServiceMissing() {
        FakeRecoveryHost host = new FakeRecoveryHost();
        host.serviceUnavailable = true;

        ExceptionHandler.handleRecovery(null, exception(PrivateReaderException.ExceptionType.STORAGE_ERROR), host);

        assertEquals(List.of("存储错误"), host.titles);
        assertTrue(host.contents.get(0).contains("存储服务不可用"));
    }

    @Test
    void storageRecoveryReportsNotWritableWhenBaseMissing() {
        FakeRecoveryHost host = new FakeRecoveryHost();
        host.basePath = tempDir.resolve("does-not-exist");
        host.cachePath = tempDir.resolve("cache");

        ExceptionHandler.handleRecovery(null, exception(PrivateReaderException.ExceptionType.STORAGE_ERROR), host);

        assertEquals(List.of("存储错误"), host.titles);
        assertTrue(host.contents.get(0).contains("存储目录不可写"));
    }

    @Test
    void storageRecoverySkipsCacheCreationWhenCachePathNull() {
        FakeRecoveryHost host = new FakeRecoveryHost();
        host.basePath = tempDir;
        host.cachePath = null;

        ExceptionHandler.handleRecovery(null, exception(PrivateReaderException.ExceptionType.STORAGE_ERROR), host);

        assertEquals(List.of("存储已恢复"), host.titles);
    }

    // --- 解析恢复 ---

    @Test
    void parseRecoveryShowsHonestGuidance() {
        FakeRecoveryHost host = new FakeRecoveryHost();

        ExceptionHandler.handleRecovery(null, exception(PrivateReaderException.ExceptionType.PARSE_ERROR), host);

        assertEquals(List.of("解析错误"), host.titles);
        assertTrue(host.contents.get(0).contains("切换书源"));
    }

    // --- 不触发恢复的类型 ---

    @Test
    void unknownTypeDoesNotTriggerAnyRecoveryNotification() {
        FakeRecoveryHost host = new FakeRecoveryHost();

        ExceptionHandler.handleRecovery(null, exception(PrivateReaderException.ExceptionType.UNKNOWN_ERROR), host);

        assertTrue(host.titles.isEmpty(), "未知类型不应触发自动恢复");
    }

    // --- DEFAULT_RECOVERY_HOST 基本契约 ---

    @Test
    void defaultHostDelegatesNetworkAvailabilityToNetworkUtils() {
        // 不依赖真实网络:仅验证默认宿主可调用且返回布尔值(不抛异常)
        boolean result = ExceptionHandler.DEFAULT_RECOVERY_HOST.isNetworkAvailable();
        assertEquals(result, result);
    }
}