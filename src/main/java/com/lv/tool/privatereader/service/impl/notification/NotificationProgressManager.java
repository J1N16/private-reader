package com.lv.tool.privatereader.service.impl.notification;

import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.model.BookProgressData;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 通知栏阅读进度的保存与恢复(V19 从 {@code NotificationServiceImpl} 抽出)。
 * <p>
 * 职责:
 * <ul>
 *   <li>{@link #restoreSavedPageNumber(String, String, int)}:从仓库恢复与当前章节匹配的页码</li>
 *   <li>{@link #saveProgress()}:保存当前阅读会话页码(供翻页/加载/事件回调调用)</li>
 * </ul>
 * 通过窄接口 {@link Host} 读取阅读状态与判断会话是否有效,避免直接依赖完整服务 API。
 * <p>
 * 页码语义约定(见服务类头注释):仓库页码为 1 基索引,保存时使用
 * {@code pageIndex + 1},避免 {@code BookService.saveReadingProgress} 的再 +1 问题。
 */
public final class NotificationProgressManager {
    private static final Logger LOG = Logger.getInstance(NotificationProgressManager.class);

    /**
     * 宿主窄接口:提供进度管理器所需的最小能力。
     */
    public interface Host {
        /** 读取当前阅读状态快照 */
        @NotNull
        ReaderViewState getViewState();

        /**
         * 判断当前是否处于有效阅读会话。
         *
         * @param notifyWhenInactive 无活动会话时是否弹出提示通知
         */
        boolean isReadingActive(boolean notifyWhenInactive);
    }

    private final Host host;
    private final ReadingProgressRepository readingProgressRepository;

    public NotificationProgressManager(@NotNull Host host,
                                       @NotNull ReadingProgressRepository readingProgressRepository) {
        this.host = host;
        this.readingProgressRepository = readingProgressRepository;
    }

    /**
     * 从数据库恢复保存的页码。
     *
     * @param bookId      书籍ID
     * @param chapterId   章节ID
     * @param defaultPage 默认页码(未找到或章节不匹配时返回)
     * @return 恢复的页码
     */
    public int restoreSavedPageNumber(@NotNull String bookId, @NotNull String chapterId, int defaultPage) {
        try {
            if (readingProgressRepository != null) {
                Optional<BookProgressData> progressDataOpt = readingProgressRepository.getProgress(bookId);
                if (progressDataOpt.isPresent()) {
                    BookProgressData progressData = progressDataOpt.get();
                    if (chapterId.equals(progressData.lastReadChapterId())) {
                        int savedPage = progressData.lastReadPage();
                        LOG.debug("[页码调试] 从数据库恢复页码: {}", savedPage);
                        return savedPage;
                    } else {
                        LOG.debug("[页码调试] 章节ID不匹配,无法恢复页码");
                    }
                } else {
                    LOG.debug("[页码调试] 未找到书籍的阅读进度记录");
                }
            } else {
                LOG.warn("[页码调试] 无法获取阅读进度仓库,无法恢复页码");
            }
        } catch (Exception e) {
            LOG.error("[页码调试] 恢复页码时出错", e);
        }
        return defaultPage;
    }

    /** 保存当前阅读会话页码(无活动会话时提示) */
    public void saveProgress() {
        saveProgress(true);
    }

    /**
     * 保存当前阅读会话页码。
     *
     * @param notifyWhenInactive 无活动会话时是否弹出提示通知
     */
    public void saveProgress(boolean notifyWhenInactive) {
        if (!host.isReadingActive(notifyWhenInactive)) {
            if (notifyWhenInactive) {
                LOG.warn("[进度保存] 无法保存通知栏模式进度:没有活动的阅读会话。");
            } else {
                LOG.debug("[进度保存] 无法保存通知栏模式进度:没有活动的阅读会话。");
            }
            return;
        }

        try {
            if (readingProgressRepository != null) {
                ReaderViewState state = host.getViewState();
                int pageToSave = state.getPageIndex() + 1;
                readingProgressRepository.updateProgress(
                        state.getBook(), state.getChapterId(), state.getChapterTitle(), 0, pageToSave);
                LOG.info(String.format("[进度保存] 成功保存通知栏模式阅读进度:书籍='%s', 章节='%s', 页码=%d",
                        state.getBook().getTitle(), state.getChapterTitle(), pageToSave));
            } else {
                LOG.warn("[进度保存] SqliteReadingProgressRepository 服务未初始化,跳过保存进度。");
            }
        } catch (Exception e) {
            LOG.error("[进度保存] 保存通知栏模式阅读进度时发生意外错误。", e);
        }
    }
}