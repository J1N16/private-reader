package com.lv.tool.privatereader.service.impl.notification;

import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.model.Book;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 章节导航工具类
 * 提取导航相关的公共逻辑，减少代码重复
 */
public final class ChapterNavigationHelper {
    private static final Logger LOG = Logger.getInstance(ChapterNavigationHelper.class);

    private ChapterNavigationHelper() {}

    /**
     * 导航结果
     */
    public record NavigationResult(
        int targetIndex,
        @NotNull String targetChapterId,
        @NotNull String targetChapterTitle
    ) {}

    /**
     * 查找当前章节在列表中的索引
     *
     * @param book 当前书籍（包含章节索引Map）
     * @param chapterId 当前章节ID
     * @param chapterUrls 章节URL列表
     * @return 章节索引，未找到返回-1
     */
    public static int findChapterIndex(@Nullable Book book, @Nullable String chapterId, @NotNull List<String> chapterUrls) {
        if (chapterId == null || chapterUrls.isEmpty()) {
            return -1;
        }

        // 优先使用Book的章节索引Map
        if (book != null) {
            int index = book.getChapterIndex(chapterId);
            if (index >= 0) {
                LOG.debug("使用章节索引Map查找章节，结果: " + index);
                return index;
            }
        }

        // 回退到线性搜索
        LOG.debug("索引Map中未找到章节，回退到线性搜索");
        for (int i = 0; i < chapterUrls.size(); i++) {
            if (chapterUrls.get(i).equals(chapterId)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * 验证导航目标是否有效
     *
     * @param currentIndex 当前章节索引
     * @param direction 导航方向（-1=上一章，1=下一章）
     * @param totalChapters 章节总数
     * @return 验证结果消息，有效返回null
     */
    @Nullable
    public static String validateNavigation(int currentIndex, int direction, int totalChapters) {
        if (currentIndex < 0) {
            return "当前章节在列表中未找到";
        }

        int targetIndex = currentIndex + direction;
        if (targetIndex < 0) {
            return "已经是第一章了";
        }
        if (targetIndex >= totalChapters) {
            return "已经是最后一章了";
        }

        return null; // 有效
    }

    /**
     * 计算目标章节索引
     */
    public static int calculateTargetIndex(int currentIndex, int direction) {
        return currentIndex + direction;
    }
}
