package com.lv.tool.privatereader.parser.common;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MetadataAnalyzer 单元测试
 * 覆盖：meta 标签、选择器、页面标题、文本匹配等多路径的标题/作者识别与清理
 */
class MetadataAnalyzerTest {

    // --- 标题识别 ---

    @Test
    void findsTitleFromOgMeta() {
        Document doc = Jsoup.parse("<html><head>"
                + "<meta property=\"og:title\" content=\"斗破苍穹\">"
                + "</head><body></body></html>");
        assertEquals("斗破苍穹", MetadataAnalyzer.findTitle(doc));
    }

    @Test
    void findsTitleFromH1() {
        Document doc = Jsoup.parse("<html><head><title>网页标题</title></head>"
                + "<body><h1>吞噬星空</h1></body></html>");
        assertEquals("吞噬星空", MetadataAnalyzer.findTitle(doc));
    }

    @Test
    void fallsBackToPageTitle() {
        Document doc = Jsoup.parse("<html><head><title>遮天</title></head><body><p>正文</p></body></html>");
        assertEquals("遮天", MetadataAnalyzer.findTitle(doc));
    }

    @Test
    void returnsUnknownTitleWhenNothingFound() {
        Document doc = Jsoup.parse("<html><head></head><body><div>empty</div></body></html>");
        assertEquals("未知标题", MetadataAnalyzer.findTitle(doc));
    }

    @Test
    void cleansTitleSuffixAndBrackets() {
        Document doc = Jsoup.parse("<html><head><title>完美世界 - 无弹窗</title></head><body></body></html>");
        assertEquals("完美世界", MetadataAnalyzer.findTitle(doc));
    }

    @Test
    void removesBookTitleBracketsFromTitle() {
        Document doc = Jsoup.parse("<html><head><title>《斗罗大陆》最新章节</title></head><body></body></html>");
        String title = MetadataAnalyzer.findTitle(doc);
        assertTrue(!title.contains("《") && !title.contains("》"), "书名号应被移除，实际: " + title);
    }

    // --- 作者识别 ---

    @Test
    void findsAuthorFromMeta() {
        Document doc = Jsoup.parse("<html><head>"
                + "<meta name=\"author\" content=\"天蚕土豆\">"
                + "</head><body></body></html>");
        assertEquals("天蚕土豆", MetadataAnalyzer.findAuthor(doc));
    }

    @Test
    void findsAuthorFromOgMeta() {
        Document doc = Jsoup.parse("<html><head>"
                + "<meta property=\"og:novel:author\" content=\"我吃西红柿\">"
                + "</head><body></body></html>");
        assertEquals("我吃西红柿", MetadataAnalyzer.findAuthor(doc));
    }

    @Test
    void findsAuthorFromSpan() {
        Document doc = Jsoup.parse("<html><body><span class=\"author\">猫腻</span></body></html>");
        assertEquals("猫腻", MetadataAnalyzer.findAuthor(doc));
    }

    @Test
    void findsAuthorFromTextPattern() {
        Document doc = Jsoup.parse("<html><body><div>作者：辰东</div></body></html>");
        assertEquals("辰东", MetadataAnalyzer.findAuthor(doc));
    }

    @Test
    void cleansAuthorPrefix() {
        Document doc = Jsoup.parse("<html><body><div>作 者：辰东</div></body></html>");
        assertEquals("辰东", MetadataAnalyzer.findAuthor(doc));
    }

    @Test
    void returnsUnknownAuthorWhenNothingFound() {
        Document doc = Jsoup.parse("<html><body>nothing here</body></html>");
        assertEquals("未知作者", MetadataAnalyzer.findAuthor(doc));
    }
}
