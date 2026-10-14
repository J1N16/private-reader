package com.lv.tool.privatereader.exception;

import com.lv.tool.privatereader.exception.PrivateReaderException.ExceptionType;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ExceptionHandler 纯函数测试(V23)。
 *
 * <p>异常类型推断、错误标题/文案映射此前 0 覆盖。这里只调用
 * {@code determineExceptionType}/{@code getErrorTitle}/{@code getErrorMessage}
 * 三个包级可见的纯函数,不触达通知与日志的错误分支
 * (测试环境 LOG.error 会断言失败)。</p>
 */
class ExceptionHandlerTest {

    // --- determineExceptionType ---

    @Test
    void socketTimeoutMapsToNetworkTimeout() {
        assertEquals(ExceptionType.NETWORK_TIMEOUT,
                ExceptionHandler.determineExceptionType(new SocketTimeoutException()));
    }

    @Test
    void connectExceptionMapsToConnectionRefused() {
        assertEquals(ExceptionType.NETWORK_CONNECTION_REFUSED,
                ExceptionHandler.determineExceptionType(new ConnectException()));
    }

    @Test
    void unknownHostMapsToNetworkError() {
        assertEquals(ExceptionType.NETWORK_ERROR,
                ExceptionHandler.determineExceptionType(new UnknownHostException()));
    }

    @Test
    void sslExceptionMapsToNetworkSslError() {
        assertEquals(ExceptionType.NETWORK_SSL_ERROR,
                ExceptionHandler.determineExceptionType(new SSLException("bad cert")));
    }

    @Test
    void genericIoExceptionMapsToStorageError() {
        assertEquals(ExceptionType.STORAGE_ERROR,
                ExceptionHandler.determineExceptionType(new IOException("disk")));
    }

    @Test
    void otherThrowableMapsToUnknownError() {
        assertEquals(ExceptionType.UNKNOWN_ERROR,
                ExceptionHandler.determineExceptionType(new IllegalStateException("x")));
    }

    // --- getErrorTitle ---

    @Test
    void titlesGroupNetworkTypes() {
        assertEquals("网络错误", ExceptionHandler.getErrorTitle(ExceptionType.NETWORK_ERROR));
        assertEquals("网络错误", ExceptionHandler.getErrorTitle(ExceptionType.NETWORK_TIMEOUT));
        assertEquals("网络错误", ExceptionHandler.getErrorTitle(ExceptionType.NETWORK_CONNECTION_REFUSED));
        assertEquals("网络错误", ExceptionHandler.getErrorTitle(ExceptionType.NETWORK_SSL_ERROR));
    }

    @Test
    void titlesGroupParseAndStorageTypes() {
        assertEquals("解析错误", ExceptionHandler.getErrorTitle(ExceptionType.PARSE_ERROR));
        assertEquals("解析错误", ExceptionHandler.getErrorTitle(ExceptionType.HTML_PARSE_ERROR));
        assertEquals("解析错误", ExceptionHandler.getErrorTitle(ExceptionType.JSON_PARSE_ERROR));
        assertEquals("存储错误", ExceptionHandler.getErrorTitle(ExceptionType.STORAGE_ERROR));
        assertEquals("存储错误", ExceptionHandler.getErrorTitle(ExceptionType.FILE_READ_ERROR));
        assertEquals("存储错误", ExceptionHandler.getErrorTitle(ExceptionType.FILE_WRITE_ERROR));
    }

    @Test
    void titlesCoverRemainingKnownTypes() {
        assertEquals("配置错误", ExceptionHandler.getErrorTitle(ExceptionType.CONFIG_ERROR));
        assertEquals("权限错误", ExceptionHandler.getErrorTitle(ExceptionType.PERMISSION_ERROR));
        assertEquals("资源不存在", ExceptionHandler.getErrorTitle(ExceptionType.RESOURCE_NOT_FOUND));
        assertEquals("并发错误", ExceptionHandler.getErrorTitle(ExceptionType.CONCURRENCY_ERROR));
        assertEquals("未知错误", ExceptionHandler.getErrorTitle(ExceptionType.UNKNOWN_ERROR));
    }

    // --- getErrorMessage ---

    @Test
    void messagesIncludeTypePrefixAndOriginalMessage() {
        PrivateReaderException e = new PrivateReaderException("超时了", ExceptionType.NETWORK_TIMEOUT);
        String message = ExceptionHandler.getErrorMessage(e);
        assertTrue(message.contains("网络请求超时"), message);
        assertTrue(message.contains("超时了"), message);
    }

    @Test
    void messagesCoverAllBranches() {
        assertTrue(ExceptionHandler.getErrorMessage(
                new PrivateReaderException("m", ExceptionType.NETWORK_ERROR)).contains("网络连接失败"));
        assertTrue(ExceptionHandler.getErrorMessage(
                new PrivateReaderException("m", ExceptionType.NETWORK_CONNECTION_REFUSED)).contains("连接被拒绝"));
        assertTrue(ExceptionHandler.getErrorMessage(
                new PrivateReaderException("m", ExceptionType.NETWORK_SSL_ERROR)).contains("SSL/TLS错误"));
        assertTrue(ExceptionHandler.getErrorMessage(
                new PrivateReaderException("m", ExceptionType.PARSE_ERROR)).contains("解析失败"));
        assertTrue(ExceptionHandler.getErrorMessage(
                new PrivateReaderException("m", ExceptionType.STORAGE_ERROR)).contains("存储错误"));
    }

    @Test
    void unknownTypeMessageIsRawMessage() {
        assertEquals("原始信息", ExceptionHandler.getErrorMessage(
                new PrivateReaderException("原始信息", ExceptionType.UNKNOWN_ERROR)));
    }
}