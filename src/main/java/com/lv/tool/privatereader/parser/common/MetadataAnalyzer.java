package com.lv.tool.privatereader.parser.common;

import com.intellij.openapi.diagnostic.Logger;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 元数据分析器
 * 用于智能识别书籍信息（标题、作者等）
 */
public class MetadataAnalyzer {
    private static final Logger LOG = Logger.getInstance(MetadataAnalyzer.class);
    private static final Pattern AUTHOR_PATTERN = Pattern.compile("作\\s*者[：:](\\s*)([^\\s]+)");
    private static final Set<String> TITLE_SELECTORS = Set.of(
        "h1", "h2.title", "div.title", "div.book-title",
        "span.title", "meta[property=og:title]"
    );
    private static final Set<String> AUTHOR_SELECTORS = Set.of(
        "meta[name=author]", "a.author", "span.author", "div.author",
        "meta[property=og:novel:author]", "meta[property=og:author]"
    );

    /**
     * 智能识别书籍标题
     */
    public static String findTitle(Document doc) {
        LOG.debug("开始查找标题...");
        
        // 1. 尝试从meta标签获取
        String title = findMetaTitle(doc);
        if (isValidTitle(title)) {
            LOG.debug("从meta标签找到标题: " + title);
            return cleanTitle(title);
        }

        // 2. 尝试从常见标题选择器获取
        LOG.debug("尝试从常见标题选择器查找...");
        for (String selector : TITLE_SELECTORS) {
            Element element = doc.selectFirst(selector);
            if (element != null) {
                LOG.debug("找到选择器匹配元素: " + selector);
                title = element.attr("content").isEmpty() ? 
                    element.text() : element.attr("content");
                if (isValidTitle(title)) {
                    LOG.debug("从选择器找到有效标题: " + title);
                    return cleanTitle(title);
                }
            }
        }

        // 3. 尝试从页面标题获取
        LOG.debug("尝试从页面标题获取...");
        title = doc.title();
        if (isValidTitle(title)) {
            LOG.debug("从页面标题找到有效标题: " + title);
            return cleanTitle(title);
        }

        LOG.warn("未能找到有效标题");
        return "未知标题";
    }

    /**
     * 智能识别作者
     */
    public static String findAuthor(Document doc) {
        LOG.debug("开始查找作者...");
        
        // 1. 尝试从meta标签获取
        String author = findMetaAuthor(doc);
        if (isValidAuthor(author)) {
            LOG.debug("从meta标签找到作者: " + author);
            return cleanAuthor(author);
        }

        // 2. 尝试从常见作者选择器获取
        LOG.debug("尝试从常见作者选择器查找...");
        for (String selector : AUTHOR_SELECTORS) {
            Element element = doc.selectFirst(selector);
            if (element != null) {
                LOG.debug("找到选择器匹配元素: " + selector);
                author = element.attr("content").isEmpty() ? 
                    element.text() : element.attr("content");
                if (isValidAuthor(author)) {
                    LOG.debug("从选择器找到有效作者: " + author);
                    return cleanAuthor(author);
                }
            }
        }

        // 3. 尝试从文本内容匹配
        LOG.debug("尝试从文本内容匹配作者信息...");
        String text = doc.text();
        Matcher matcher = AUTHOR_PATTERN.matcher(text);
        if (matcher.find()) {
            author = matcher.group(2);
            if (isValidAuthor(author)) {
                LOG.debug("从文本内容匹配到作者: " + author);
                return cleanAuthor(author);
            }
        }

        LOG.warn("未能找到有效作者信息");
        return "未知作者";
    }

    private static String findMetaTitle(Document doc) {
        LOG.debug("尝试从meta标签查找标题...");
        String[] metaProperties = {
            "og:title", "og:novel:title", "og:book:title",
            "title", "twitter:title"
        };

        for (String property : metaProperties) {
            Element meta = doc.selectFirst("meta[property=" + property + "]," +
                                         "meta[name=" + property + "]");
            if (meta != null) {
                String content = meta.attr("content");
                LOG.debug("找到meta标签 " + property + ": " + content);
                if (isValidTitle(content)) {
                    return content;
                }
            }
        }

        LOG.debug("未在meta标签中找到有效标题");
        return null;
    }

    private static String findMetaAuthor(Document doc) {
        LOG.debug("尝试从meta标签查找作者...");
        String[] metaProperties = {
            "og:author", "og:novel:author", "og:book:author",
            "author", "twitter:creator"
        };

        for (String property : metaProperties) {
            Element meta = doc.selectFirst("meta[property=" + property + "]," +
                                         "meta[name=" + property + "]");
            if (meta != null) {
                String content = meta.attr("content");
                LOG.debug("找到meta标签 " + property + ": " + content);
                if (isValidAuthor(content)) {
                    return content;
                }
            }
        }

        LOG.debug("未在meta标签中找到有效作者");
        return null;
    }

    private static boolean isValidTitle(String title) {
        boolean valid = title != null && !title.trim().isEmpty() && 
                       !title.equalsIgnoreCase("untitled") &&
                       !title.equals("未知标题");
        if (!valid && title != null) {
            LOG.debug("标题无效: " + title);
        }
        return valid;
    }

    private static boolean isValidAuthor(String author) {
        boolean valid = author != null && !author.trim().isEmpty() && 
                       !author.equalsIgnoreCase("unknown") &&
                       !author.equals("未知作者");
        if (!valid && author != null) {
            LOG.debug("作者无效: " + author);
        }
        return valid;
    }

    private static String cleanTitle(String title) {
        String original = title;
        title = title.replaceAll("\\s*[-_|].*$", "")  // 移除分隔符后的内容
                    .replaceAll("\\(.*?\\)", "")      // 移除括号内容
                    .replaceAll("《|》", "")          // 移除书名号
                    .trim();
        if (!title.equals(original)) {
            LOG.debug("清理标题: " + original + " -> " + title);
        }
        return title;
    }

    private static String cleanAuthor(String author) {
        String original = author;
        author = author.replaceAll("作\\s*者[：:](\\s*)", "")  // 移除"作者:"前缀
                      .replaceAll("[\\(（].*?[\\)）]", "")    // 移除括号内容
                      .trim();
        if (!author.equals(original)) {
            LOG.debug("清理作者: " + original + " -> " + author);
        }
        return author;
    }
} 