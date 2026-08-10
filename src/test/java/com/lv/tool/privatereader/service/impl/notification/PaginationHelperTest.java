package com.lv.tool.privatereader.service.impl.notification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PaginationHelper 单元测试
 * 覆盖：分页边界、换行断点、句末断点、退化输入
 */
class PaginationHelperTest {

    // --- 退化输入 ---

    @Test
    void paginateReturnsEmptyForNullContent() {
        assertTrue(PaginationHelper.paginate(null, 70).isEmpty());
    }

    @Test
    void paginateReturnsEmptyForEmptyContent() {
        assertTrue(PaginationHelper.paginate("", 70).isEmpty());
    }

    @Test
    void paginateReturnsEmptyForInvalidPageSize() {
        assertTrue(PaginationHelper.paginate("内容", 0).isEmpty());
        assertTrue(PaginationHelper.paginate("内容", -1).isEmpty());
    }

    // --- 基础分页 ---

    @Test
    void paginateSplitsLongTextIntoMultiplePages() {
        // 100 字符、每页 30 -> 4 页（30+30+30+10）
        String content = "字".repeat(100);
        List<String> pages = PaginationHelper.paginate(content, 30);
        assertEquals(4, pages.size());
        assertEquals(30, pages.get(0).length());
        assertEquals(10, pages.get(3).length());
    }

    @Test
    void paginateKeepsShortTextAsSinglePage() {
        String content = "短内容";
        List<String> pages = PaginationHelper.paginate(content, 70);
        assertEquals(1, pages.size());
        assertEquals("短内容", pages.get(0));
    }

    @Test
    void paginateExactMultipleHasNoTrailingEmptyPage() {
        String content = "字".repeat(60);
        List<String> pages = PaginationHelper.paginate(content, 30);
        assertEquals(2, pages.size());
        assertEquals(30, pages.get(1).length());
    }

    // --- 换行断点 ---

    @Test
    void paginateBreaksAtNewline() {
        // 第 1 页：20 字符+换行后 20 字符；第 2 页：其余
        String content = "12345678901234567890\n1234567890123456789012345678901234567890";
        List<String> pages = PaginationHelper.paginate(content, 30);
        // 第一页应断在换行处（index 20 之后），即 21 字符
        assertEquals("12345678901234567890\n", pages.get(0));
        assertTrue(pages.get(0).endsWith("\n"));
    }

    // --- 句末断点 ---

    @Test
    void paginateBreaksAtSentenceEndingWhenNoNewline() {
        // 无换行，pageSize 恰好落在句号后，应断在句号后
        String content = "第一句话。第二句话。第三句话。第四句话。";
        List<String> pages = PaginationHelper.paginate(content, 5);
        // pageSize=5：第一页候选是 "第一句话"（5 字符），其前无换行，回看找句号在 index 4（。），断点在 5
        assertEquals("第一句话。", pages.get(0));
    }

    // --- 内容完整性 ---

    @Test
    void paginatePreservesAllContent() {
        String content = "第一节。\n第二节。\n第三节。\n" + "字".repeat(200);
        List<String> pages = PaginationHelper.paginate(content, 40);
        StringBuilder rebuilt = new StringBuilder();
        for (String page : pages) {
            rebuilt.append(page);
        }
        assertEquals(content, rebuilt.toString());
    }

    @Test
    void paginateNeverExceedsPageSizePerPage() {
        String content = "字".repeat(1000);
        for (String page : PaginationHelper.paginate(content, 100)) {
            assertTrue(page.length() <= 100);
        }
    }
}
