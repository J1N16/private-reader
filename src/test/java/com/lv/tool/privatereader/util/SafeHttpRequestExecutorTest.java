package com.lv.tool.privatereader.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * SafeHttpRequestExecutor 单元测试
 * 覆盖：请求成功、网络异常重试、达到最大重试次数抛错、零次重试
 *
 * <p>使用 JDK 内置 HttpServer 起本地 mock 服务器测真实网络路径
 * （executeGetRequest 内部在子线程调用 HttpRequests，mockStatic 跨线程不生效，
 * 因此采用真实本地服务器而非 mock HttpRequests）。
 * NetworkPerformanceMonitor 在主线程调用，用 mockStatic 避免 ApplicationManager 依赖。
 */
class SafeHttpRequestExecutorTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requestCount = new AtomicInteger();
    /** 前 failUntilCount 次请求返回 500，之后返回 200 */
    private int failUntilCount;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        requestCount.set(0);
        failUntilCount = 0;

        server.createContext("/book", exchange -> {
            requestCount.incrementAndGet();
            byte[] body;
            int status;
            if (requestCount.get() <= failUntilCount) {
                status = 500;
                body = "error".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = "请求内容".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
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
    }

    private NetworkPerformanceMonitor mockMonitor() {
        return mock(NetworkPerformanceMonitor.class);
    }

    // --- 请求成功 ---

    @Test
    void executeGetRequestReturnsContentOnSuccess() throws IOException {
        try (MockedStatic<NetworkPerformanceMonitor> monitors = mockStatic(NetworkPerformanceMonitor.class)) {
            monitors.when(NetworkPerformanceMonitor::getInstance).thenReturn(mockMonitor());

            String result = SafeHttpRequestExecutor.executeGetRequest(baseUrl + "/book", 2, 0);

            assertEquals("请求内容", result);
            assertEquals(1, requestCount.get());
        }
    }

    // --- 网络异常重试 ---

    @Test
    void executeGetRequestRetriesOnServerErrorThenSucceeds() throws IOException {
        failUntilCount = 2; // 前两次 500，第三次 200

        try (MockedStatic<NetworkPerformanceMonitor> monitors = mockStatic(NetworkPerformanceMonitor.class)) {
            monitors.when(NetworkPerformanceMonitor::getInstance).thenReturn(mockMonitor());

            String result = SafeHttpRequestExecutor.executeGetRequest(baseUrl + "/book", 3, 0);

            assertEquals("请求内容", result);
            assertEquals(3, requestCount.get()); // 两次失败 + 一次成功
        }
    }

    @Test
    void executeGetRequestThrowsAfterMaxRetries() throws IOException {
        failUntilCount = Integer.MAX_VALUE; // 一直失败

        try (MockedStatic<NetworkPerformanceMonitor> monitors = mockStatic(NetworkPerformanceMonitor.class)) {
            monitors.when(NetworkPerformanceMonitor::getInstance).thenReturn(mockMonitor());

            IOException error = assertThrows(IOException.class,
                    () -> SafeHttpRequestExecutor.executeGetRequest(baseUrl + "/book", 2, 0));

            assertTrue(error.getMessage().contains("已重试 2 次"));
            assertEquals(3, requestCount.get()); // 初次 + 2 次重试
        }
    }

    @Test
    void executeGetRequestWithZeroRetriesFailsImmediately() throws IOException {
        failUntilCount = Integer.MAX_VALUE;

        try (MockedStatic<NetworkPerformanceMonitor> monitors = mockStatic(NetworkPerformanceMonitor.class)) {
            monitors.when(NetworkPerformanceMonitor::getInstance).thenReturn(mockMonitor());

            assertThrows(IOException.class,
                    () -> SafeHttpRequestExecutor.executeGetRequest(baseUrl + "/book", 0, 0));

            assertEquals(1, requestCount.get()); // 只尝试一次，不重试
        }
    }

    // --- 重试后成功（前 N 次失败）的边界 ---

    @Test
    void executeGetRequestSucceedsAfterSingleRetry() throws IOException {
        failUntilCount = 1; // 第一次 500，第二次 200

        try (MockedStatic<NetworkPerformanceMonitor> monitors = mockStatic(NetworkPerformanceMonitor.class)) {
            monitors.when(NetworkPerformanceMonitor::getInstance).thenReturn(mockMonitor());

            String result = SafeHttpRequestExecutor.executeGetRequest(baseUrl + "/book", 1, 0);

            assertEquals("请求内容", result);
            assertEquals(2, requestCount.get());
        }
    }
}
