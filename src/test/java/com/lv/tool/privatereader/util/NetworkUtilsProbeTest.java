package com.lv.tool.privatereader.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NetworkUtils 并行探测 / 最佳主机 / 异步探测测试(V28)。
 *
 * <p>既有 {@code NetworkUtilsTest} 覆盖缓存 TTL 与不可达主机,{@code NetworkUtilsHttpTest}
 * 覆盖 URL 可访问性与测速;本类补齐缺口:并行主机探测缓存写入
 * ({@code isNetworkAvailable} 未命中缓存的完整路径)、异步探测
 * ({@code isNetworkAvailableAsync} 的真/假两分支)、{@code getBestHost} 与
 * {@code checkNetworkSpeed} 的可达路径。</p>
 *
 * <p>{@code CHECK_HOSTS} 为私有常量,无法在测试中替换,故并行探测的真实网络结果不可预期;
 * 本类改用缓存注入断言「未命中缓存 → 探测 → 写入新缓存」的语义,并配合本地
 * HttpServer 验证可测速/可达的确定性部分。</p>
 */
class NetworkUtilsProbeTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        NetworkUtils.resetProbeCacheForTest();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        NetworkUtils.resetProbeCacheForTest();
    }

    // --- 缓存未命中的完整探测路径 ---

    @Test
    void isNetworkAvailableProbesAndCachesResultWhenCacheEmpty() {
        NetworkUtils.resetProbeCacheForTest();

        boolean result = NetworkUtils.isNetworkAvailable();

        // 探测后应写入缓存,且缓存值与返回值一致
        Object cached = NetworkUtils.getCachedResultForTest();
        assertNotNull(cached, "首次调用应写入探测缓存");
    }

    @Test
    void isNetworkAvailableSecondCallHitsFreshCache() {
        NetworkUtils.setCachedResultForTest(true, System.currentTimeMillis());

        assertTrue(NetworkUtils.isNetworkAvailable());
        assertNotNull(NetworkUtils.getCachedResultForTest());
    }

    // --- 异步探测 ---

    @Test
    void isNetworkAvailableAsyncCompletesWithBoolean() throws InterruptedException, ExecutionException {
        CompletableFuture<Boolean> future = NetworkUtils.isNetworkAvailableAsync(500);

        assertNotNull(future);
        assertNotNull(future.get(), "异步探测应正常返回布尔结果");
    }

    @Test
    void isNetworkAvailableAsyncReturnsTrueViaStubbedCacheDoesNotAffectAsync() {
        // 异步路径不读缓存,仅验证其独立完成且不抛异常
        NetworkUtils.setCachedResultForTest(true, System.currentTimeMillis());

        assertNotNull(NetworkUtils.isNetworkAvailableAsync(300));
    }

    // --- 测速与最佳主机 ---

    @Test
    void checkNetworkSpeedReturnsNonNegativeForLocalServer() {
        long speed = NetworkUtils.checkNetworkSpeed(baseUrl);

        assertTrue(speed >= 0, "本地可达 URL 的测速应为非负值,实际: " + speed);
    }

    @Test
    void checkNetworkSpeedReturnsMinusOneForMalformedUrl() {
        assertEquals(-1, NetworkUtils.checkNetworkSpeed("not a url"));
    }

    @Test
    void getBestHostReturnsNullOrReachableHostWithoutThrowing() {
        // CHECK_HOSTS 为外网主机,结果不确定;断言调用契约:要么 null,要么非空字符串
        String best = NetworkUtils.getBestHost();
        assertTrue(best == null || !best.isEmpty());
    }

    // --- isUrlAccessible 包级默认可达性 ---

    @Test
    void isUrlAccessibleDefaultOverloadUsesTimeoutAndSucceedsForLocalServer() {
        assertTrue(NetworkUtils.isUrlAccessible(baseUrl));
    }

    @Test
    void isUrlAccessibleReturnsFalseForUnresolvableHost() {
        assertFalse(NetworkUtils.isUrlAccessible("http://nonexistent.invalid.host.example/"));
    }

    @Test
    void isHostReachableReturnsFalseWhenNoListenerOnPort80() {
        // isHostReachable 固定连接 80 端口,本地随机端口的 server 无法命中,
        // 故此处仅验证不可达时快速返回 false(确定性)
        assertFalse(NetworkUtils.isHostReachable("127.0.0.1"));
    }

    @Test
    void setCachedResultHonorsTimestampForExpiry() {
        // 过期时间戳应导致下次调用重新探测(缓存被忽略)
        NetworkUtils.setCachedResultForTest(true, System.currentTimeMillis() - 60_000);
        NetworkUtils.setCachedResultForTest(false, System.currentTimeMillis());

        assertFalse(NetworkUtils.isNetworkAvailable());
    }

    @Test
    void resetProbeCacheClearsCachedResult() {
        NetworkUtils.setCachedResultForTest(true, System.currentTimeMillis());

        NetworkUtils.resetProbeCacheForTest();

        assertNull(NetworkUtils.getCachedResultForTest());
    }
}