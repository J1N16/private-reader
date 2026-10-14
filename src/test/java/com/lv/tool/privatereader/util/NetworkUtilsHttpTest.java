package com.lv.tool.privatereader.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NetworkUtils 真实 HTTP 路径测试(V22)。
 *
 * <p>此前的 {@link NetworkUtilsTest} 只覆盖非法/不可达输入的快速失败分支,
 * {@code isUrlAccessible} 与 {@code checkNetworkSpeed} 的成功路径零覆盖。
 * 这里用 JDK 内置 HttpServer 起本地服务,确定性覆盖真实网络返回码分支,
 * 不依赖外网。</p>
 */
class NetworkUtilsHttpTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        hits.set(0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            int status;
            if (path.startsWith("/ok")) {
                status = 200;
            } else if (path.startsWith("/redirect")) {
                status = 302;
            } else if (path.startsWith("/server-error")) {
                status = 500;
            } else {
                status = 404;
            }
            boolean isHead = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
            byte[] body = isHead ? new byte[0] : "body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, isHead ? -1 : body.length);
            if (!isHead) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // --- isUrlAccessible:2xx 成功 ---

    @Test
    void isUrlAccessibleReturnsTrueFor200() {
        assertTrue(NetworkUtils.isUrlAccessible(baseUrl + "/ok"));
        assertTrue(hits.get() > 0);
    }

    // --- 3xx:仍在 [200,400) 区间,视为可访问 ---

    @Test
    void isUrlAccessibleTreatsRedirectAsAccessible() {
        assertTrue(NetworkUtils.isUrlAccessible(baseUrl + "/redirect"));
    }

    // --- 4xx/5xx:不可访问 ---

    @Test
    void isUrlAccessibleReturnsFalseFor404() {
        assertFalse(NetworkUtils.isUrlAccessible(baseUrl + "/missing"));
    }

    @Test
    void isUrlAccessibleReturnsFalseFor500() {
        assertFalse(NetworkUtils.isUrlAccessible(baseUrl + "/server-error"));
    }

    @Test
    void isUrlAccessibleHonorsExplicitTimeout() {
        assertTrue(NetworkUtils.isUrlAccessible(baseUrl + "/ok", 2000));
    }

    // --- checkNetworkSpeed:成功返回耗时,非法 URL 返回 -1 ---

    @Test
    void checkNetworkSpeedReturnsNonNegativeForReachableUrl() {
        long speed = NetworkUtils.checkNetworkSpeed(baseUrl + "/ok");
        assertTrue(speed >= 0, "可达地址应返回非负耗时,实际: " + speed);
    }

    @Test
    void checkNetworkSpeedReturnsMinusOneForInvalidUrl() {
        assertTrue(NetworkUtils.checkNetworkSpeed("not a url") == -1);
    }
}