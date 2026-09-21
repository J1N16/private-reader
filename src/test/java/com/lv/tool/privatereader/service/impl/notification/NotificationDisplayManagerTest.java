package com.lv.tool.privatereader.service.impl.notification;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NotificationDisplayManager 单元测试。
 * 覆盖纯逻辑方法:HTML 实体解码、标签剥离、连续换行折叠、空输入。
 */
class NotificationDisplayManagerTest {

    @Test
    void cleanNullOrEmptyReturnsEmpty() {
        assertEquals("", NotificationDisplayManager.cleanHtmlTags(null));
        assertEquals("", NotificationDisplayManager.cleanHtmlTags(""));
    }

    @Test
    void decodesHtmlEntities() {
        // 注意:先解码实体再剥离标签,因此 &lt;c&gt; 解码为 <c> 后会作为标签被剥离(既有行为)
        String result = NotificationDisplayManager.cleanHtmlTags("a&nbsp;b&lt;c&gt;d&amp;e&quot;f&apos;g");
        assertEquals("a bd&e\"f'g", result);
    }

    @Test
    void stripsHtmlTags() {
        String result = NotificationDisplayManager.cleanHtmlTags("<p>第一段</p><div>第二段</div>");
        assertEquals("第一段第二段", result);
    }

    @Test
    void collapsesExcessiveNewlines() {
        String result = NotificationDisplayManager.cleanHtmlTags("第一段\n\n\n\n第二段");
        assertTrue(result.contains("\n\n"), "3 个以上连续换行应折叠为 2 个,实际: " + result.replace("\n", "\\n"));
        assertTrue(!result.contains("\n\n\n"), "不应残留 3 个以上连续换行");
    }

    @Test
    void preservesSingleNewlines() {
        String result = NotificationDisplayManager.cleanHtmlTags("第一段\n第二段");
        assertEquals("第一段\n第二段", result);
    }

    // --- buildDisplayTitle(回调化改造新增纯函数) ---

    @Test
    void buildDisplayTitleKeepsTitleOnFirstPage() {
        assertEquals("书名 - 第一章",
                NotificationDisplayManager.buildDisplayTitle("书名 - 第一章", 0, 10, false));
    }

    @Test
    void buildDisplayTitleShowsReadingInProgressOnLaterPages() {
        assertEquals("阅读中",
                NotificationDisplayManager.buildDisplayTitle("书名 - 第一章", 2, 10, false));
    }

    @Test
    void buildDisplayTitleAppendsPageNumbersWhenEnabled() {
        assertEquals("阅读中 (第3页/共10页)",
                NotificationDisplayManager.buildDisplayTitle("书名 - 第一章", 2, 10, true));
        assertEquals("书名 - 第一章 (第1页/共10页)",
                NotificationDisplayManager.buildDisplayTitle("书名 - 第一章", 0, 10, true));
    }

    @Test
    void buildDisplayTitleHandlesSinglePage() {
        // pageIndex=0 第一页显示原标题
        assertEquals("书 - 章 (第1页/共1页)",
                NotificationDisplayManager.buildDisplayTitle("书 - 章", 0, 1, true));
    }

    // --- 按钮可用性(回调化改造新增纯函数) ---

    @Test
    void prevPageActionEnabledOnlyAfterFirstPage() {
        assertTrue(NotificationDisplayManager.isPrevPageActionEnabled(1));
        assertTrue(NotificationDisplayManager.isPrevPageActionEnabled(5));
        assertFalse(NotificationDisplayManager.isPrevPageActionEnabled(0));
    }

    @Test
    void nextPageActionEnabledOnlyBeforeLastPage() {
        assertTrue(NotificationDisplayManager.isNextPageActionEnabled(0, 3));
        assertFalse(NotificationDisplayManager.isNextPageActionEnabled(2, 3));
        assertFalse(NotificationDisplayManager.isNextPageActionEnabled(0, 1));
    }
}