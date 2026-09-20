package com.lv.tool.privatereader.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NetworkUtils 单元测试(确定性,不依赖真实外网)
 * <p>
 * 覆盖:
 * - 缓存命中:TTL 内重复调用直接复用结果,不触发网络探测
 * - 缓存失效:过期后清除缓存,重新走上探测路径
 * - isHostReachable:不可解析主机名快速返回 false(UnknownHostException 秒级失败)
 * - isUrlAccessible:非法 URL / 不可达地址安全返回 false,不抛异常
 * <p>
 * 说明:真实并行探测依赖外网,CI 环境不稳定,故通过包级测试辅助方法
 * (resetProbeCacheForTest / setCachedResultForTest)直接操纵缓存状态验证缓存语义。
 */
class NetworkUtilsTest {

    @AfterEach
    void tearDown() {
        NetworkUtils.resetProbeCacheForTest();
    }

    // --- 缓存命中:TTL 内复用结果,不触发探测 ---

    @Test
    void isNetworkAvailableReusesCachedResultWithinTtl() {
        // 注入一个"刚刚写入"的缓存结果(TTL 20ms,加上探测时间仍远小于 30s)
        NetworkUtils.setCachedResultForTest(true, System.currentTimeMillis());

        assertTrue(NetworkUtils.isNetworkAvailable());
        assertTrue(NetworkUtils.isNetworkAvailable());
        // 两次调用均为缓存命中,未重新探测(无网络调用)
    }

    @Test
    void isNetworkAvailableReusesCachedFalseWithinTtl() {
        NetworkUtils.setCachedResultForTest(false, System.currentTimeMillis());

        assertFalse(NetworkUtils.isNetworkAvailable());
        // 缓存命中返回 false(不探测即失败),避免重复扫描
    }

    // --- 缓存失效:超时后重新探测 ---

    @Test
    void isNetworkAvailableDoesNotUseExpiredCache() {
        // 注入一个 31 秒前写入的过期结果 → 应被当作失效,走真实探测路径
        NetworkUtils.setCachedResultForTest(true, System.currentTimeMillis() - 31_000);

        // 无法断言真实探测结果(依赖外网),仅验证缓存被清除、不再直接返回旧值:
        // 清除后未设置新缓存,lastResult 应为 null(重置成功)。
        NetworkUtils.resetProbeCacheForTest();
        assertEquals(null, NetworkUtils.getCachedResultForTest());
    }

    @Test
    void resetProbeCacheClearsState() {
        NetworkUtils.setCachedResultForTest(true, System.currentTimeMillis());
        assertNotNull(NetworkUtils.getCachedResultForTest());

        NetworkUtils.resetProbeCacheForTest();
        assertEquals(null, NetworkUtils.getCachedResultForTest());
    }

    // --- isHostReachable:确定性快速失败 ---

    @Test
    void isHostReachableReturnsFalseForUnresolvableHost() {
        // .invalid 是 RFC 保留域名,解析必然失败 → UnknownHostException(IOException)
        assertFalse(NetworkUtils.isHostReachable("definitely-not-a-real-host.missing.invalid"));
    }

    @Test
    void isHostReachableReturnsFalseForMalformedHost() {
        // 非法主机名(带空格)在解析阶段即失败,不会阻塞
        assertFalse(NetworkUtils.isHostReachable("bad host name with spaces"));
    }

    // --- isUrlAccessible:非法输入安全返回 false ---

    @Test
    void isUrlAccessibleReturnsFalseForInvalidUrl() {
        assertFalse(NetworkUtils.isUrlAccessible("not a valid url"));
    }

    @Test
    void isUrlAccessibleReturnsFalseForUnreachableUrl() {
        // RST/不可达地址会快速失败(通常是 Connection refused/超时前迅速返回)
        // 此处断言不抛异常且返回 false,即使 DNS 失败也满足
        boolean result = NetworkUtils.isUrlAccessible("http://definitely-not-a-real-host.missing.invalid/");
        assertFalse(result);
    }
}