package com.lv.tool.privatereader.parser.common;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TextDensityAnalyzer 单元测试
 * 覆盖：文本密度计算、常见内容选择器识别、文本密度兜底
 */
class TextDensityAnalyzerTest {

    @Test
    void textDensityIsBetweenZeroAndOne() {
        Element div = Jsoup.parse("<html><body><div>纯文本内容</div></body></html>")
                .selectFirst("div");
        double density = TextDensityAnalyzer.getTextDensity(div);
        assertTrue(density >= 0.0 && density <= 1.0);
    }

    @Test
    void emptyElementHasZeroDensity() {
        Element empty = Jsoup.parse("<html><body><div></div></body></html>").selectFirst("div");
        assertEquals(0.0, TextDensityAnalyzer.getTextDensity(empty));
    }

    @Test
    void findsContentByCommonSelector() {
        Document doc = Jsoup.parse("<html><body>"
                + "<div class=\"content\">" + "正".repeat(100) + "</div>"
                + "<div class=\"footer\">版权信息</div>"
                + "</body></html>");
        Element content = TextDensityAnalyzer.findContentElement(doc.body());
        assertNotNull(content);
        assertEquals("content", content.className());
    }

    @Test
    void findsContentById() {
        Document doc = Jsoup.parse("<html><body>"
                + "<div id=\"content\">" + "正".repeat(100) + "</div>"
                + "</body></html>");
        Element content = TextDensityAnalyzer.findContentElement(doc.body());
        assertNotNull(content);
        assertEquals("content", content.id());
    }

    @Test
    void fallsBackToDensityAnalysis() {
        // 无常见选择器匹配，但有一个高密度长文本 div
        Document doc = Jsoup.parse("<html><body>"
                + "<div class=\"noise nav\">导航菜单</div>"
                + "<div class=\"random\">" + "正".repeat(200) + "</div>"
                + "</body></html>");
        Element content = TextDensityAnalyzer.findContentElement(doc.body());
        assertNotNull(content);
        assertEquals("random", content.className());
    }

    @Test
    void ignoresNoiseKeywordsWhenScoring() {
        // 噪音 div 含 nav/ad 关键词，即使文本长也不应被选中
        Document doc = Jsoup.parse("<html><body>"
                + "<div class=\"ad-container\">" + "正".repeat(500) + "</div>"
                + "<div class=\"main-text\">" + "正".repeat(100) + "</div>"
                + "</body></html>");
        Element content = TextDensityAnalyzer.findContentElement(doc.body());
        assertNotNull(content);
        assertEquals("main-text", content.className());
    }

    @Test
    void returnsNullWhenNoValidContent() {
        Document doc = Jsoup.parse("<html><body><div>短</div></body></html>");
        assertNull(TextDensityAnalyzer.findContentElement(doc.body()));
    }
}
