package com.lv.tool.privatereader.service.impl.notification;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 分页工具类
 * 提取章节内容分页的纯逻辑，独立可测
 */
public final class PaginationHelper {
    private PaginationHelper() {}

    /**
     * 将内容按页分页。
     * <p>
     * 分页策略：
     * <ul>
     *   <li>优先在换行符处断开（保持段落完整）</li>
     *   <li>若无换行，在最近的中文/英文句末标点处断开（回看最多 50 字符）</li>
     *   <li>若均不可行，按 pageSize 硬切</li>
     * </ul>
     *
     * @param content 待分页内容
     * @param pageSize 每页字符数上限
     * @return 分页后的内容列表；content 为空或 pageSize 非法时返回空列表
     */
    @NotNull
    public static List<String> paginate(String content, int pageSize) {
        List<String> pages = new ArrayList<>();
        if (content == null || content.isEmpty() || pageSize <= 0) {
            return pages;
        }

        int textLength = content.length();
        int startIndex = 0;

        while (startIndex < textLength) {
            int endIndex = Math.min(startIndex + pageSize, textLength);

            // If this is not the last chunk of text, try to find a natural break point.
            if (endIndex < textLength) {
                int breakPoint = -1;
                // Look for the last newline character within the current page candidate.
                for (int i = endIndex - 1; i >= startIndex; i--) {
                    if (content.charAt(i) == '\n') {
                        breakPoint = i + 1; // Break after the newline
                        break;
                    }
                }

                // If no newline, look for a sentence break, but don't look back too far.
                if (breakPoint == -1) {
                    for (int i = endIndex - 1; i >= startIndex && i > endIndex - 50; i--) { // Look back max 50 chars
                        char c = content.charAt(i);
                        if ("。！？.?!".indexOf(c) != -1) {
                            breakPoint = i + 1;
                            break;
                        }
                    }
                }

                // If we found a good break point, adjust the end index.
                if (breakPoint > startIndex) { // Ensure we are making progress
                    endIndex = breakPoint;
                }
            }

            pages.add(content.substring(startIndex, endIndex));
            startIndex = endIndex;
        }

        return pages;
    }
}
