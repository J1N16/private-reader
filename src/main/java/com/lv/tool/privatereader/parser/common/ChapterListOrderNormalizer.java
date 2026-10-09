package com.lv.tool.privatereader.parser.common;

import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节目录顺序归一化工具。
 *
 * <p>部分小说站点(尤其是移动站 / 完整目录页)按“最新章节在前”的方式输出目录,
 * 解析出的章节列表因此是倒序的,会导致阅读器从最后一章开始、上一章/下一章
 * 行为颠倒。本工具在解析结果落地前检测这种倒序排列并翻转成正序。
 *
 * <p>判定依据是章节标题中的章号(阿拉伯数字或中文数字),而非列表位置,
 * 因此只对“明显倒序”的目录做翻转;无法提取章号或本身已正序的目录保持原样,
 * 不做任何重排,避免破坏卷/篇等特殊结构或调用方刻意设定的顺序。
 */
public final class ChapterListOrderNormalizer {

    /** 匹配“第X章/节/回/卷/集/部/篇”,X 支持阿拉伯数字与中文数字。 */
    private static final Pattern NUMBERED_CHAPTER_PATTERN = Pattern.compile(
            "第\\s*([0-9零一二三四五六七八九十百千万亿两]+)\\s*[章节回卷集部篇]");

    /** 匹配“123、标题”“123. 标题”形式的序号。 */
    private static final Pattern INDEXED_CHAPTER_PATTERN = Pattern.compile("^\\s*([0-9]+)\\s*[、.．]");

    /** 匹配纯数字标题“123”。 */
    private static final Pattern PLAIN_NUMBER_PATTERN = Pattern.compile("^\\s*([0-9]+)\\s*$");

    private static final char[] CHINESE_DIGITS = {'零', '一', '二', '三', '四', '五', '六', '七', '八', '九'};

    private ChapterListOrderNormalizer() {
        // 工具类禁止实例化
    }

    /**
     * 将章节目录规范为正序。
     *
     * <p>若可提取章号的章节整体呈倒序,则返回一个翻转后的新列表;
     * 否则原样返回传入的列表(保持引用,调用方可继续使用)。
     *
     * @param chapters 待规范化的章节列表,可为 null
     * @return 正序章节列表;输入为 null 或无需调整时返回原引用
     */
    @Nullable
    public static List<Chapter> normalize(@Nullable List<Chapter> chapters) {
        if (chapters == null || chapters.size() < 2) {
            return chapters;
        }

        List<Integer> numbers = new ArrayList<>(chapters.size());
        for (Chapter chapter : chapters) {
            numbers.add(chapter == null ? null : extractChapterNumber(chapter.title()));
        }

        if (!isDescending(numbers)) {
            return chapters;
        }

        List<Chapter> reversed = new ArrayList<>(chapters);
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * 从章节标题中提取章号。
     *
     * <p>支持以下形式,均返回其数值:阿拉伯数字、中文数字。
     * 例如“第2273章 …” → 2273,“第一百二十章 …” → 120,
     * “3、楔子” → 3,“123” → 123。无法识别时返回 null。
     *
     * @param title 章节标题,可为 null
     * @return 章号;无法识别返回 null
     */
    @Nullable
    public static Integer extractChapterNumber(@Nullable String title) {
        if (title == null || title.isEmpty()) {
            return null;
        }

        Matcher numbered = NUMBERED_CHAPTER_PATTERN.matcher(title);
        if (numbered.find()) {
            return toInt(numbered.group(1));
        }

        Matcher indexed = INDEXED_CHAPTER_PATTERN.matcher(title);
        if (indexed.find()) {
            return toInt(indexed.group(1));
        }

        Matcher plain = PLAIN_NUMBER_PATTERN.matcher(title);
        if (plain.find()) {
            return toInt(plain.group(1));
        }

        return null;
    }

    /**
     * 判断带章号的章节是否整体呈倒序。
     *
     * <p>统计相邻章号的下降/上升次数:下降明显多于上升时才认定为倒序。
     * 可提取章号少于 2 个时无法判断,直接视为非倒序。
     */
    private static boolean isDescending(List<Integer> numbers) {
        int descents = 0;
        int ascents = 0;
        Integer previous = null;
        for (Integer current : numbers) {
            if (current == null) {
                continue;
            }
            if (previous != null) {
                if (current < previous) {
                    descents++;
                } else if (current > previous) {
                    ascents++;
                }
            }
            previous = current;
        }
        return descents > ascents && descents >= 1;
    }

    /**
     * 将阿拉伯数字或中文数字字符串转换为整数。
     *
     * @return 数值;超出 int 范围或包含无法识别的字符时返回 null
     */
    @Nullable
    private static Integer toInt(@NotNull String raw) {
        long value = raw.chars().allMatch(c -> c >= '0' && c <= '9')
                ? Long.parseLong(raw)
                : parseChineseNumber(raw);
        if (value < 0 || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) value;
    }

    /**
     * 解析中文数字(支持到“亿”),如“二千二百七十三” → 2273。
     * 遇到无法识别的字符时返回 -1。
     */
    private static long parseChineseNumber(@NotNull String raw) {
        long total = 0;   // 已累计的“万/亿”级别结果
        long section = 0; // 当前“万”以内的小节
        long number = 0;  // 当前待处理的个位数字

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            int digit = chineseDigit(c);
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = chineseUnit(c);
            if (unit > 0) {
                section += (number == 0 ? 1 : number) * unit;
                number = 0;
                continue;
            }
            if (c == '万') {
                total += (section + number) * 10000L;
                section = 0;
                number = 0;
                continue;
            }
            if (c == '亿') {
                total = (total + section + number) * 100000000L;
                section = 0;
                number = 0;
                continue;
            }
            return -1;
        }
        return total + section + number;
    }

    private static int chineseDigit(char c) {
        if (c == '两') {
            return 2;
        }
        for (int i = 0; i < CHINESE_DIGITS.length; i++) {
            if (CHINESE_DIGITS[i] == c) {
                return i;
            }
        }
        return -1;
    }

    private static int chineseUnit(char c) {
        return switch (c) {
            case '十' -> 10;
            case '百' -> 100;
            case '千' -> 1000;
            default -> -1;
        };
    }
}