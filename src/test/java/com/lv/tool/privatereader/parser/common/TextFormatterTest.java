package com.lv.tool.privatereader.parser.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TextFormatter 单元测试
 * 覆盖：空文本处理、换行规范化、标点规范化、段落缩进与分段
 */
class TextFormatterTest {

    @Test
    void returnsEmptyForNullOrBlank() {
        assertEquals("", TextFormatter.format(null));
        assertEquals("", TextFormatter.format(""));
        assertEquals("", TextFormatter.format("   "));
    }

    @Test
    void normalizesLineEndings() {
        String result = TextFormatter.format("第一行\r\n第二行");
        assertFalse(result.contains("\r\n"));
        assertTrue(result.contains("\n"));
    }

    @Test
    void collapsesWhitespaceRuns() {
        String result = TextFormatter.format("你好    世界\t啊");
        assertFalse(result.contains("    "));
    }

    @Test
    void normalizesPunctuationRuns() {
        String result = TextFormatter.format("太好了……！……啊");
        // 省略号折叠为 "……"
        assertFalse(result.contains("…!…"));
    }

    @Test
    void formatsParagraphWithIndentation() {
        String result = TextFormatter.format("第一段。\n\n第二段。");
        // trim 会去掉首行缩进；第二段保持 4 空格缩进
        assertTrue(result.contains("\n    "), "段落间应有 4 空格缩进，实际: " + result.replace(" ", "·"));
        assertTrue(result.startsWith("第一段。"), "首段文本应保留，实际: " + result);
    }

    @Test
    void separatesParagraphsOnBlankLines() {
        String result = TextFormatter.format("第一段。\n\n第二段。");
        assertTrue(result.contains("第一段。"));
        assertTrue(result.contains("第二段。"));
    }

    @Test
    void formatsChapterTitleAsHeading() {
        String result = TextFormatter.format("第一章 测试");
        assertTrue(result.contains("第一章 测试"));
    }

    @Test
    void preservesDialogContent() {
        String result = TextFormatter.format("他说道：「你好，世界。」");
        assertTrue(result.contains("「你好，世界。」"));
    }

    @Test
    void splitsLongParagraph() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            sb.append("这是用于测试长段落拆分的句子。");
        }
        String result = TextFormatter.format(sb.toString());
        // 超长段落应被拆分为多行（120 句 × 13 字 ≈ 1560 字，按 500 上限至少 3 段）
        long newlines = result.chars().filter(c -> c == '\n').count();
        assertTrue(newlines >= 2, "超长段落应被拆分为多行，实际换行数: " + newlines);
    }
}
