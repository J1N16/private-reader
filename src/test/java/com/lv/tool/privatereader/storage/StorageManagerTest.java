package com.lv.tool.privatereader.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StorageManager 静态工具方法测试(V23)。
 *
 * <p>{@code getSafeFileName}/{@code getCacheFileName} 为纯函数,长期 0 覆盖,
 * 却直接影响磁盘文件名合法性。这里覆盖规范化的关键分支:非法字符替换、
 * 连续下划线合并、首尾点/下划线裁剪、超长截断+hash、空名回退、
 * 协议前缀剥离、长 URL hash。构造器依赖 IntelliJ 容器,故不实例化。</p>
 */
class StorageManagerTest {

    @Test
    void safeFileNameReplacesIllegalCharacters() {
        assertEquals("a_b_c", StorageManager.getSafeFileName("a/b\\c"));
    }

    @Test
    void safeFileNameCollapsesRepeatedUnderscores() {
        assertEquals("a_b", StorageManager.getSafeFileName("a///b"));
    }

    @Test
    void safeFileNameKeepsChineseAndDots() {
        assertEquals("第一章.txt", StorageManager.getSafeFileName("第一章.txt"));
    }

    @Test
    void safeFileNameTrimsLeadingAndTrailingDotsAndUnderscores() {
        assertEquals("abc", StorageManager.getSafeFileName("._abc_."));
    }

    @Test
    void safeFileNameFallsBackToGeneratedNameWhenEmpty() {
        String result = StorageManager.getSafeFileName("...");
        assertTrue(result.startsWith("unnamed_"), "空名应回退为 unnamed_ 前缀,实际: " + result);
    }

    @Test
    void safeFileNameShortensOverlongNameWithHashSuffix() {
        String longName = "a".repeat(600);
        String result = StorageManager.getSafeFileName(longName);

        assertTrue(result.length() <= 255, "结果不应超过文件系统上限,实际长度: " + result.length());
        assertTrue(result.startsWith("aaaaaaaaaaaaaaaaaaaa_"), "应保留可读前缀 + 下划线 + hash");
    }

    @Test
    void cacheFileNameStripsHttpPrefixAndAppendsTxt() {
        assertEquals("example.com_book_chapter.txt",
                StorageManager.getCacheFileName("https://example.com/book/chapter"));
    }

    @Test
    void cacheFileNameHandlesLongUrlWithHash() {
        String longUrl = "https://example.com/" + "x".repeat(400);
        String result = StorageManager.getCacheFileName(longUrl);

        assertTrue(result.endsWith(".txt"));
        assertTrue(result.length() <= 255, "长 URL 结果应受控,实际长度: " + result.length());
        // 与短 URL 结果不同(含 hash),且两次调用稳定
        assertEquals(result, StorageManager.getCacheFileName(longUrl));
    }

    @Test
    void cacheFileNameProducesDifferentNamesForDifferentLongUrls() {
        String urlA = "https://example.com/" + "a".repeat(400);
        String urlB = "https://example.com/" + "b".repeat(400);
        assertNotEquals(StorageManager.getCacheFileName(urlA), StorageManager.getCacheFileName(urlB));
    }

    @Test
    void cacheFileNameDoesNotContainProtocolSeparators() {
        String result = StorageManager.getCacheFileName("http://example.com/a/b?q=1");
        assertFalse(result.contains("//"));
        assertTrue(result.endsWith(".txt"));
    }
}