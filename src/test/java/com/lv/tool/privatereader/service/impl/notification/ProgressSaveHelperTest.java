package com.lv.tool.privatereader.service.impl.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import org.mockito.MockedStatic;

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

    // --- saveProgress(页码与章节名写入) ---

    @Test
    void saveProgressPersistsPageNumberAndChapterTitleViaInterfaceRepository() {
        Book book = new Book("b1", "书", "作者", "https://example.com/b1");
        ReadingProgressRepository repository = mock(ReadingProgressRepository.class);
        try (MockedStatic<ApplicationManager> appManagerMock = mockStatic(ApplicationManager.class)) {
            Application application = mock(Application.class);
            // 插件仅以 ReadingProgressRepository 接口注册,必须能按接口解析到实例
            when(application.getService(ReadingProgressRepository.class)).thenReturn(repository);
            appManagerMock.when(ApplicationManager::getApplication).thenReturn(application);

            // pageIndex=0(新章节第一页)应写为页码 1,并带上章节名
            ProgressSaveHelper.saveProgress(book, "c2", "第二章", 0);
            verify(repository).updateProgress(book, "c2", "第二章", 0, 1);

            // pageIndex=4 应写为页码 5
            ProgressSaveHelper.saveProgress(book, "c2", "第二章", 4);
            verify(repository).updateProgress(book, "c2", "第二章", 0, 5);
        }
    }
}
