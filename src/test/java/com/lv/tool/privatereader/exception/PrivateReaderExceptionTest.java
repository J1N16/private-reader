package com.lv.tool.privatereader.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PrivateReaderException 单元测试
 * 覆盖：消息/类型构造、cause 传递、异常类型枚举
 */
class PrivateReaderExceptionTest {

    @Test
    void preservesMessageAndType() {
        PrivateReaderException ex = new PrivateReaderException("网络错误",
                PrivateReaderException.ExceptionType.NETWORK_ERROR);
        assertEquals("网络错误", ex.getMessage());
        assertSame(PrivateReaderException.ExceptionType.NETWORK_ERROR, ex.getType());
    }

    @Test
    void preservesCause() {
        RuntimeException cause = new RuntimeException("底层原因");
        PrivateReaderException ex = new PrivateReaderException("解析失败", cause,
                PrivateReaderException.ExceptionType.PARSE_ERROR);
        assertSame(cause, ex.getCause());
        assertSame(PrivateReaderException.ExceptionType.PARSE_ERROR, ex.getType());
    }

    @Test
    void isRuntimeException() {
        assertThrows(PrivateReaderException.class, () -> {
            throw new PrivateReaderException("任意错误", PrivateReaderException.ExceptionType.UNKNOWN_ERROR);
        });
    }

    @Test
    void exposesExceptionTypeEnum() {
        PrivateReaderException.ExceptionType[] types = PrivateReaderException.ExceptionType.values();
        assertNotNull(types);
        assertEquals(15, types.length);
        assertEquals(PrivateReaderException.ExceptionType.NETWORK_ERROR,
                PrivateReaderException.ExceptionType.valueOf("NETWORK_ERROR"));
    }
}
