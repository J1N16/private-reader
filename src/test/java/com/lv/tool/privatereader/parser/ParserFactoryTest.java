package com.lv.tool.privatereader.parser;

import com.lv.tool.privatereader.parser.site.UniversalParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ParserFactory 单元测试
 * 覆盖：创建通用解析器、空 URL 抛异常
 */
class ParserFactoryTest {

    @Test
    void createParserReturnsUniversalParser() {
        NovelParser parser = ParserFactory.createParser("https://example.com/book/123");
        assertInstanceOf(UniversalParser.class, parser);
    }

    @Test
    void createParserRejectsNullUrl() {
        assertThrows(IllegalArgumentException.class, () -> ParserFactory.createParser(null));
    }

    @Test
    void createParserRejectsEmptyUrl() {
        assertThrows(IllegalArgumentException.class, () -> ParserFactory.createParser(""));
    }
}
