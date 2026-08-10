package com.lv.tool.privatereader.service.impl.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProgressSaveHelper 单元测试
 * 覆盖：通知内容/标题构建（纯函数）
 */
class ProgressSaveHelperTest {

    // --- buildNotificationContent ---

    @Test
    void buildNotificationContentReturnsPlainContentWhenProgressDisabled() {
        String content = "这是一页小说内容";
        assertEquals(content, ProgressSaveHelper.buildNotificationContent(content, 0, 10, false));
    }

    @Test
    void buildNotificationContentAppendsProgressWhenEnabled() {
        String content = "这是一页小说内容";
        String result = ProgressSaveHelper.buildNotificationContent(content, 2, 10, true);
        assertTrue(result.startsWith(content));
        assertTrue(result.contains("进度: 第 3 页，共 10 页"));
    }

    @Test
    void buildNotificationContentUsesOneBasedPageIndex() {
        // pageIndex=0 是第一页
        String result = ProgressSaveHelper.buildNotificationContent("内容", 0, 5, true);
        assertTrue(result.contains("第 1 页"));
    }

    @Test
    void buildNotificationContentHandlesEmptyContent() {
        String result = ProgressSaveHelper.buildNotificationContent("", 0, 1, true);
        assertTrue(result.contains("第 1 页，共 1 页"));
    }

    @Test
    void buildNotificationContentProgressDisabledDoesNotAppend() {
        String content = "正文内容";
        String result = ProgressSaveHelper.buildNotificationContent(content, 0, 10, false);
        assertFalse(result.contains("进度"));
        assertEquals(content, result);
    }

    // --- buildNotificationTitle ---

    @Test
    void buildNotificationTitleCombinesBookAndChapter() {
        assertEquals("斗破苍穹 - 第一章 陨落的天才",
                ProgressSaveHelper.buildNotificationTitle("斗破苍穹", "第一章 陨落的天才"));
    }

    @Test
    void buildNotificationTitleHandlesEmptyParts() {
        assertEquals(" - ", ProgressSaveHelper.buildNotificationTitle("", ""));
        assertEquals("书 - ", ProgressSaveHelper.buildNotificationTitle("书", ""));
        assertEquals(" - 章", ProgressSaveHelper.buildNotificationTitle("", "章"));
    }
}
