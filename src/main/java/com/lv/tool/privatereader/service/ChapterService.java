package com.lv.tool.privatereader.service;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 章节服务接口
 * 提供响应式API获取章节内容和管理章节缓存
 */
public interface ChapterService {
    /**
     * 获取章节对象
     *
     * @param book      书籍
     * @param chapterId 章节ID
     * @return 章节对象
     */
    Single<Chapter> getChapter(@NotNull Book book, @NotNull String chapterId);

    /**
     * 获取章节对象，如果获取失败则尝试使用缓存中的过期内容
     *
     * @param book      书籍
     * @param chapterId 章节ID
     * @return 章节对象
     */
    Single<Chapter> getChapterWithFallback(@NotNull Book book, @NotNull String chapterId);

    /**
     * 获取章节列表
     *
     * @param book 书籍
     * @return 章节列表
     */
    Single<List<Chapter>> getChapterList(@NotNull Book book);

    /**
     * 清除书籍缓存
     *
     * @param book 书籍
     * @return 完成信号
     */
    Completable clearBookCache(@NotNull Book book);

    /**
     * 清除所有缓存
     *
     * @return 完成信号
     */
    Completable clearAllCache();

    /**
     * 获取章节内容 (同步)
     *
     * @param bookId    书籍ID
     * @param chapterId 章节ID
     * @return 章节内容
     */
    String getChapterContentSync(@NotNull String bookId, @NotNull String chapterId);

    /**
     * 获取章节标题 (同步)
     *
     * @param bookId    书籍ID
     * @param chapterId 章节ID
     * @return 章节标题
     */
    Single<String> getChapterTitle(@NotNull String bookId, @NotNull String chapterId);

    /**
     * 获取章节内容 (异步)
     *
     * @param book      书籍
     * @param chapterId 章节ID
     * @return 章节内容的 Single
     */
    Single<String> getChapterContent(@NotNull Book book, @NotNull String chapterId);

    /**
     * 扩展章节类，包含内容
     */
    class EnhancedChapter extends Chapter {
        private final String content;

        public EnhancedChapter(String title, String url, String content) {
            super(title, url);
            this.content = content != null ? content : "";
        }

        public String getContent() {
            return content;
        }
    }
}
