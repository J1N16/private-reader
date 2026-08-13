package com.lv.tool.privatereader.parser.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChapterTitleUtils 单元测试
 * 覆盖：各类章节标题格式的识别、非章节标题的过滤
 */
class ChapterTitleUtilsTest {

    // --- 有效章节标题 ---

    @Test
    void recognizesNumberedChapterTitles() {
        assertTrue(ChapterTitleUtils.isChapterTitle("第一章"));
        assertTrue(ChapterTitleUtils.isChapterTitle("第1章 初入江湖"));
        assertTrue(ChapterTitleUtils.isChapterTitle("第100章 决战"));
        assertTrue(ChapterTitleUtils.isChapterTitle("第十章 山雨欲来"));
    }

    @Test
    void recognizesNumericIndexTitles() {
        assertTrue(ChapterTitleUtils.isChapterTitle("1. 楔子"));
        assertTrue(ChapterTitleUtils.isChapterTitle("10、再见"));
        assertTrue(ChapterTitleUtils.isChapterTitle("3."));
    }

    @Test
    void recognizesSpecialChapterFormats() {
        assertTrue(ChapterTitleUtils.isChapterTitle("第三回 风起"));
        assertTrue(ChapterTitleUtils.isChapterTitle("序章"));
        assertTrue(ChapterTitleUtils.isChapterTitle("终章"));
        assertTrue(ChapterTitleUtils.isChapterTitle("前言"));
        assertTrue(ChapterTitleUtils.isChapterTitle("序言"));
        assertTrue(ChapterTitleUtils.isChapterTitle("后记"));
        assertTrue(ChapterTitleUtils.isChapterTitle("番外"));
        assertTrue(ChapterTitleUtils.isChapterTitle("上篇"));
        assertTrue(ChapterTitleUtils.isChapterTitle("特别篇"));
        assertTrue(ChapterTitleUtils.isChapterTitle("外传"));
    }

    @Test
    void rejectsWordingWithoutChapterMarker() {
        // 楔子 不匹配任何预设 pattern（PREFACE 仅匹配"楔言"式），不被识别为章节标题
        assertFalse(ChapterTitleUtils.isChapterTitle("楔子"));
    }

    @Test
    void recognizesVolumeAndTimeTitles() {
        assertTrue(ChapterTitleUtils.isChapterTitle("第一卷 风起云涌"));
        assertTrue(ChapterTitleUtils.isChapterTitle("第二篇"));
        assertTrue(ChapterTitleUtils.isChapterTitle("插曲"));
        assertTrue(ChapterTitleUtils.isChapterTitle("午章"));
        assertTrue(ChapterTitleUtils.isChapterTitle("春章"));
    }

    @Test
    void recognizesPureNumericTitles() {
        assertTrue(ChapterTitleUtils.isChapterTitle("123"));
    }

    // --- 无效标题 ---

    @Test
    void rejectsNullOrBlank() {
        assertFalse(ChapterTitleUtils.isChapterTitle(null));
        assertFalse(ChapterTitleUtils.isChapterTitle(""));
        assertFalse(ChapterTitleUtils.isChapterTitle("   "));
    }

    @Test
    void rejectsUrlsAndOverlongText() {
        assertFalse(ChapterTitleUtils.isChapterTitle("http://example.com/chapter"));
        assertFalse(ChapterTitleUtils.isChapterTitle("www.example.com 第一章"));
        assertFalse(ChapterTitleUtils.isChapterTitle("这是一段非常长的文字，超过了五十个字符的限制，用来验证超长文本不会被视为章节标题，因为通常章节标题都是简短精炼的一句话而已"));
    }

    @Test
    void rejectsPlainTextWithoutChapterMarker() {
        assertFalse(ChapterTitleUtils.isChapterTitle("平凡的一天"));
        assertFalse(ChapterTitleUtils.isChapterTitle("他转身离开"));
    }
}
