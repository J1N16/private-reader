package com.lv.tool.privatereader.ui.dialog;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 章节列表对话框的纯逻辑辅助类(V14 从 {@link ChapterListDialog} 提取)。
 * <p>
 * 承载不依赖 Swing/EDT 的查找、选中、文案构建与加载动效逻辑,便于单元测试;
 * 同时消除了 ChapterListDialog 中 book.getCachedChapters() 分支与网络失败分支
 * 反复出现的「选择上次阅读章节」重复代码(三处几乎相同)。选中的 UI 副作用
 * (setSelectedIndex / ensureIndexIsVisible)由回调函数注入,本类保持纯函数。
 */
public final class ChapterListDialogSupport {

    private ChapterListDialogSupport() {
    }

    /**
     * 在章节列表中查找指定 URL 的章节索引。
     *
     * @param chapters   章节列表(可为空)
     * @param chapterUrl 目标章节 URL(可为空;null/空 → 返回 -1)
     * @return 命中索引;未找到返回 -1
     */
    public static int findChapterIndex(@Nullable List<NovelParser.Chapter> chapters,
                                       @Nullable String chapterUrl) {
        if (chapters == null || chapterUrl == null || chapterUrl.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < chapters.size(); i++) {
            if (chapterUrl.equals(chapters.get(i).url())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 决定应选中的章节索引:优先上次阅读章节,否则第一章。
     *
     * @param chapters      章节列表(可为空)
     * @param lastChapterId 上次阅读章节 URL(可为空)
     * @return 应选中索引;列表为空返回 -1
     */
    public static int resolveSelectIndex(@Nullable List<NovelParser.Chapter> chapters,
                                         @Nullable String lastChapterId) {
        if (chapters == null || chapters.isEmpty()) {
            return -1;
        }
        int lastIndex = findChapterIndex(chapters, lastChapterId);
        if (lastIndex >= 0) {
            return lastIndex;
        }
        return 0;
    }

    /**
     * 构建信息面板 HTML(书名/作者/进度)。
     * 与 ChapterListDialog 中 {@code <html>书名:%s<br>作者:%s<br>进度:%d/%d 章 (%.1f%%)</html>} 格式保持一致。
     */
    @NotNull
    public static String buildInfoLabel(@NotNull Book book) {
        double progress = book.getReadingProgress() * 100;
        return String.format("<html>书名:%s<br>作者:%s<br>进度:%d/%d 章 (%.1f%%)</html>",
                book.getTitle(),
                book.getAuthor(),
                book.getCurrentChapterIndex(),
                book.getTotalChapters(),
                progress);
    }

    /**
     * 生成加载动效的省略号文本:按计数取模 4 产出 "." / ".." / "..." / ""。
     */
    @NotNull
    public static String buildLoadingDots(int count) {
        int dots = Math.floorMod(count, 4);
        return ".".repeat(dots);
    }
}