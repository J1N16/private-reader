package com.lv.tool.privatereader.service.impl.notification;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节分页结果缓存工具类。
 * <p>
 * 解决 V3 遗留的 P3-3 分页重复计算：阅读流程中同一章节内容会被多次传入
 * （showChapterContent 双路径、导航展示流水线），每次触发整章 O(n) 重分页。
 * 该工具按"内容 + pageSize"缓存分页结果，内容未变化时直接复用。
 * 独立静态类便于单元测试，与 {@link PaginationHelper} 模式一致。
 */
public final class ChapterPaginationCache {
    /** 上次分页的原始内容；null 表示缓存未初始化 */
    private String cachedContent;
    /** 上次分页的 pageSize */
    private int cachedPageSize = -1;
    /** 缓存的分页结果 */
    private List<String> cachedPages;

    /**
     * 按 pageSize 分页，内容与 pageSize 均未变化时复用上次结果。
     *
     * @param content  待分页内容
     * @param pageSize 每页字符数上限
     * @return 分页后的内容列表；content 为空或 pageSize 非法时返回空列表
     */
    @NotNull
    public List<String> paginate(String content, int pageSize) {
        if (content == null) {
            content = "";
        }
        // 缓存命中：内容与 pageSize 均未变化，直接复用上次分页结果
        if (content.equals(cachedContent) && pageSize == cachedPageSize && cachedPages != null) {
            return cachedPages;
        }
        cachedContent = content;
        cachedPageSize = pageSize;
        cachedPages = PaginationHelper.paginate(content, pageSize);
        return cachedPages;
    }

    /**
     * 清除缓存。当前章节切换为不同内容时由调用方触发，避免旧缓存残留。
     */
    public void clear() {
        cachedContent = null;
        cachedPageSize = -1;
        cachedPages = null;
    }

    /** 仅用于单元测试：读取当前缓存的内容 */
    String cachedContent() {
        return cachedContent;
    }

    /** 仅用于单元测试：读取当前缓存的 pageSize */
    int cachedPageSize() {
        return cachedPageSize;
    }
}
