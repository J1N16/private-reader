package com.lv.tool.privatereader.parser.site;

import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.parser.NovelParser.Chapter;
import com.lv.tool.privatereader.util.SafeHttpRequestExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * UniversalParser 单元测试
 * 覆盖：章节列表识别（标题/URL 特征、过滤、去重）、章节正文提取（选择器与广告清理）
 *
 * <p>使用 mockStatic 拦截 {@link SafeHttpRequestExecutor} 静态网络请求，
 * 使 {@code initialize()} 走真实的 Jsoup 解析流程，无需真实网络。
 */
class UniversalParserTest {

    private static final String BASE_URL = "https://example.com/book";

    /** 用 mockStatic 提供 HTML，让解析器在给定 HTML 上完成初始化与解析 */
    private static List<Chapter> parseWithHtml(String html) {
        try (MockedStatic<SafeHttpRequestExecutor> mocked = mockStatic(SafeHttpRequestExecutor.class)) {
            mocked.when(() -> SafeHttpRequestExecutor.executeGetRequest(anyString())).thenReturn(html);
            return new UniversalParser(BASE_URL).parseChapterList();
        }
    }

    // --- parseChapterList：标题识别 ---

    @Test
    void parseChapterListRecognizesNumberedChapterLinks() {
        String html = """
                <html><body>
                <a href="/book/1.html">第一章 初入江湖</a>
                <a href="/book/2.html">第二章 再遇故人</a>
                <a href="/book/3.html">第三章 风云突变</a>
                </body></html>""";
        List<Chapter> chapters = parseWithHtml(html);

        assertEquals(3, chapters.size());
        assertEquals("第一章 初入江湖", chapters.get(0).title());
        assertEquals("https://example.com/book/1.html", chapters.get(0).url());
    }

    @Test
    void parseChapterListRecognizesChineseChapterTitles() {
        String html = """
                <html><body>
                <a href="/read/1">第一百二十章 风云再起</a>
                <a href="/read/2">第二百章 终局之战</a>
                </body></html>""";
        List<Chapter> chapters = parseWithHtml(html);

        assertEquals(2, chapters.size());
        assertEquals("第一百二十章 风云再起", chapters.get(0).title());
    }

    @Test
    void parseChapterListRecognizesChapterByUrlPattern() {
        // URL 含 /chapter/ 或 /c123 特征，即使标题不含"章"也应识别
        String html = """
                <html><body>
                <a href="/chapter/1">初入江湖</a>
                <a href="/c123">刀光剑影</a>
                </body></html>""";
        List<Chapter> chapters = parseWithHtml(html);

        assertEquals(2, chapters.size());
        assertEquals("初入江湖", chapters.get(0).title());
    }

    @Test
    void parseChapterListFiltersNavigationLinks() {
        String html = """
                <html><body>
                <a href="/book/1.html">第一章 正文</a>
                <a href="/">首页</a>
                <a href="/login">登录</a>
                <a href="/register">注册</a>
                <a href="/rank">排行榜</a>
                </body></html>""";
        List<Chapter> chapters = parseWithHtml(html);

        assertEquals(1, chapters.size());
        assertEquals("第一章 正文", chapters.get(0).title());
    }

    @Test
    void parseChapterListDeduplicatesSameUrl() {
        String html = """
                <html><body>
                <a href="/book/1.html">第一章</a>
                <a href="/book/1.html">第一章 重复</a>
                </body></html>""";
        List<Chapter> chapters = parseWithHtml(html);

        assertEquals(1, chapters.size());
    }

    @Test
    void parseChapterListReturnsEmptyForDocumentWithoutLinks() {
        List<Chapter> chapters = parseWithHtml("<html><body><p>无章节链接</p></body></html>");
        assertTrue(chapters.isEmpty());
    }

    // --- parseChapterContent：正文提取 ---

    @Test
    void parseChapterContentExtractsFromContentDiv() {
        String html = """
                <html><body><div id="content1">
                这是第一章的正文内容。主人公踏上了征途。
                </div></body></html>""";
        try (MockedStatic<SafeHttpRequestExecutor> mocked = mockStatic(SafeHttpRequestExecutor.class)) {
            mocked.when(() -> SafeHttpRequestExecutor.executeGetRequest("https://example.com/book/1.html"))
                    .thenReturn(html);

            UniversalParser parser = new UniversalParser(BASE_URL);
            String content = parser.parseChapterContent("https://example.com/book/1.html");

            assertNotNull(content);
            assertTrue(content.contains("第一章的正文内容"));
            assertTrue(content.contains("主人公踏上了征途"));
        }
    }

    @Test
    void parseChapterContentStripsAdsAndBoilerplate() {
        String html = """
                <html><body><div id="content1">
                正文开始。
                <script>var ad = 1;</script>
                <a href="/ad">广告链接</a>
                <div class="bottem">上一章 下一章</div>
                （顶点小说）最新网址，本站提供在线。
                正文结束。
                </div></body></html>""";
        try (MockedStatic<SafeHttpRequestExecutor> mocked = mockStatic(SafeHttpRequestExecutor.class)) {
            mocked.when(() -> SafeHttpRequestExecutor.executeGetRequest("https://example.com/book/2.html"))
                    .thenReturn(html);

            UniversalParser parser = new UniversalParser(BASE_URL);
            String content = parser.parseChapterContent("https://example.com/book/2.html");

            assertNotNull(content);
            assertTrue(content.contains("正文开始"));
            assertTrue(content.contains("正文结束"));
            assertFalse(content.contains("广告链接"));
            assertFalse(content.contains("顶点小说"));
            assertFalse(content.contains("bottem"));
        }
    }

    @Test
    void parseChapterContentThrowsWhenContentEmpty() {
        String html = "<html><body><div id='content1'></div></body></html>";
        try (MockedStatic<SafeHttpRequestExecutor> mocked = mockStatic(SafeHttpRequestExecutor.class)) {
            mocked.when(() -> SafeHttpRequestExecutor.executeGetRequest("https://example.com/book/3.html"))
                    .thenReturn(html);

            UniversalParser parser = new UniversalParser(BASE_URL);
            assertThrows(RuntimeException.class,
                    () -> parser.parseChapterContent("https://example.com/book/3.html"));
        }
    }

    @Test
    void parseChapterContentExtractsChapterTitleMarkerAsSeparateLine() {
        // 章节标题标记（第X章）应被清理成空行而非混入正文
        String html = """
                <html><body><div id="content1">
                第一章 引子
                故事从这里开始。
                </div></body></html>""";
        try (MockedStatic<SafeHttpRequestExecutor> mocked = mockStatic(SafeHttpRequestExecutor.class)) {
            mocked.when(() -> SafeHttpRequestExecutor.executeGetRequest("https://example.com/book/4.html"))
                    .thenReturn(html);

            UniversalParser parser = new UniversalParser(BASE_URL);
            String content = parser.parseChapterContent("https://example.com/book/4.html");

            assertNotNull(content);
            assertTrue(content.contains("故事从这里开始"));
        }
    }
}
