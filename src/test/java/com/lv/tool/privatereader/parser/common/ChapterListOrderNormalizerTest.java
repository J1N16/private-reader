package com.lv.tool.privatereader.parser.common;

import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * ChapterListOrderNormalizer 单元测试
 * 覆盖:倒序目录翻转、正序/无法判定时保持原样、阿拉伯与中文章号解析
 */
class ChapterListOrderNormalizerTest {

    private static Chapter ch(String title) {
        return new Chapter(title, "url-" + title);
    }

    // --- extractChapterNumber:阿拉伯数字 ---

    @Test
    void extractsArabicChapterNumbers() {
        assertEquals(2273, ChapterListOrderNormalizer.extractChapterNumber("第2273章 最新"));
        assertEquals(1, ChapterListOrderNormalizer.extractChapterNumber("第1节 开始"));
        assertEquals(12, ChapterListOrderNormalizer.extractChapterNumber("第12回"));
        assertEquals(3, ChapterListOrderNormalizer.extractChapterNumber("3、楔子"));
        assertEquals(10, ChapterListOrderNormalizer.extractChapterNumber("10. 再见"));
        assertEquals(123, ChapterListOrderNormalizer.extractChapterNumber("123"));
    }

    // --- extractChapterNumber:中文数字 ---

    @Test
    void extractsChineseChapterNumbers() {
        assertEquals(1, ChapterListOrderNormalizer.extractChapterNumber("第一章 初入江湖"));
        assertEquals(10, ChapterListOrderNormalizer.extractChapterNumber("第十章"));
        assertEquals(12, ChapterListOrderNormalizer.extractChapterNumber("第十二章"));
        assertEquals(20, ChapterListOrderNormalizer.extractChapterNumber("第二十章"));
        assertEquals(120, ChapterListOrderNormalizer.extractChapterNumber("第一百二十章 风云再起"));
        assertEquals(2273, ChapterListOrderNormalizer.extractChapterNumber("第两千二百七十三章"));
        assertEquals(2, ChapterListOrderNormalizer.extractChapterNumber("第两章"));
    }

    @Test
    void returnsNullWhenNoChapterNumber() {
        assertNull(ChapterListOrderNormalizer.extractChapterNumber(null));
        assertNull(ChapterListOrderNormalizer.extractChapterNumber(""));
        assertNull(ChapterListOrderNormalizer.extractChapterNumber("序章"));
        assertNull(ChapterListOrderNormalizer.extractChapterNumber("番外 特别篇"));
    }

    // --- normalize:倒序 → 正序 ---

    @Test
    void normalizesDescendingArabicCatalogToAscending() {
        List<Chapter> descending = List.of(
                ch("第3章 三"),
                ch("第2章 二"),
                ch("第1章 一")
        );

        List<Chapter> result = ChapterListOrderNormalizer.normalize(descending);

        assertEquals(List.of(ch("第1章 一"), ch("第2章 二"), ch("第3章 三")), result);
    }

    @Test
    void normalizesDescendingChineseCatalogToAscending() {
        List<Chapter> descending = List.of(
                ch("第三章"),
                ch("第二章"),
                ch("第一章")
        );

        List<Chapter> result = ChapterListOrderNormalizer.normalize(descending);

        assertEquals(List.of(ch("第一章"), ch("第二章"), ch("第三章")), result);
    }

    // --- normalize:正序/无法判定 → 原样返回 ---

    @Test
    void keepsAscendingCatalogUnchanged() {
        List<Chapter> ascending = List.of(ch("第1章"), ch("第2章"), ch("第3章"));

        assertSame(ascending, ChapterListOrderNormalizer.normalize(ascending));
    }

    @Test
    void keepsCatalogWithoutNumbersUnchanged() {
        List<Chapter> noNumbers = List.of(ch("序章"), ch("番外"), ch("后记"));

        assertSame(noNumbers, ChapterListOrderNormalizer.normalize(noNumbers));
    }

    @Test
    void keepsSingleChapterUnchanged() {
        List<Chapter> single = List.of(ch("第1章"));
        assertSame(single, ChapterListOrderNormalizer.normalize(single));
    }

    @Test
    void handlesNullAndEmpty() {
        assertNull(ChapterListOrderNormalizer.normalize(null));
        List<Chapter> empty = List.of();
        assertSame(empty, ChapterListOrderNormalizer.normalize(empty));
    }

    // --- normalize:非数字项穿插时按可提取章号判定 ---

    @Test
    void normalizesDescendingCatalogWithNonNumberedTitles() {
        List<Chapter> descending = List.of(
                ch("第3章 三"),
                ch("番外"),
                ch("第2章 二"),
                ch("第1章 一")
        );

        List<Chapter> result = ChapterListOrderNormalizer.normalize(descending);

        assertEquals(List.of(ch("第1章 一"), ch("第2章 二"), ch("番外"), ch("第3章 三")), result);
    }
}