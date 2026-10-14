package com.lv.tool.privatereader.repository.impl;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.repository.StorageRepository;
import com.lv.tool.privatereader.service.ChapterService;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * FileBookRepository 「details.json 缺少章节列表」自愈路径测试(V32)。
 *
 * <p>{@code getBook()} 在解析出的书籍章节列表为空时,会尝试通过
 * {@code ApplicationManager.getApplication().getService(ChapterService.class)}
 * 重新抓取章节并回写文件(该段此前未覆盖)。本测试用 mockStatic 提供
 * {@code ChapterService},验证:
 * <ul>
 *   <li>成功抓取 → 章节被补全、回写 details.json、返回的书籍带章节</li>
 *   <li>抓取到空列表 → 返回原(缺章节的)书籍,不崩溃</li>
 * </ul>
 * 存储层直接复用生产实现 {@link FileStorageRepository}(V28 已提供
 * 包级 {@code (Path)} 构造器),无需再写一份 Fake。</p>
 */
class FileBookRepositoryChapterFetchTest {

    @TempDir
    Path tempDir;

    private StorageRepository storage;
    private MockedStatic<ApplicationManager> appMgr;

    @BeforeEach
    void setUp() {
        storage = new FileStorageRepository(tempDir);
        appMgr = mockStatic(ApplicationManager.class);
    }

    @AfterEach
    void tearDown() {
        appMgr.close();
    }

    /** 写入一本 details.json,但不含任何章节列表。 */
    private void writeBookWithoutChapters(String bookId, String title) throws IOException {
        Book bare = new Book(bookId, title, "作者", "https://example.com/" + bookId);
        new BookDetailsIo(storage, new BookJsonCodec()).saveDetails(bare);

        Path detailsFile = Path.of(storage.getBookDirectory(bookId)).resolve("details.json");
        assertTrue(Files.exists(detailsFile), "前置条件:details.json 已写入");
    }

    private void registerChapterService(ChapterService chapterService) {
        Application app = mock(Application.class);
        when(app.getService(ChapterService.class)).thenReturn(chapterService);
        appMgr.when(ApplicationManager::getApplication).thenReturn(app);
    }

    @Test
    void getBookFetchesAndPersistsChaptersWhenDetailsLacksThem() throws IOException {
        String bookId = "book-1";
        writeBookWithoutChapters(bookId, "测试书籍");

        ChapterService chapterService = mock(ChapterService.class);
        List<NovelParser.Chapter> fetched = List.of(
                new NovelParser.Chapter("第一章", "chapter-1"),
                new NovelParser.Chapter("第二章", "chapter-2"));
        when(chapterService.getChapterList(any(Book.class))).thenReturn(Single.just(fetched));
        registerChapterService(chapterService);

        FileBookRepository repository = new FileBookRepository(storage);
        Book loaded = repository.getBook(bookId);

        assertNotNull(loaded);
        assertEquals(2, loaded.getCachedChapters().size(), "应从 ChapterService 补全章节");

        // details.json 已回写章节
        Path detailsFile = Path.of(storage.getBookDirectory(bookId)).resolve("details.json");
        String json = Files.readString(detailsFile);
        assertTrue(json.contains("chapter-1") && json.contains("chapter-2"),
                "应回写章节列表到 details.json,实际: " + json);
    }

    @Test
    void getBookKeepsBookWhenFetchReturnsEmpty() throws IOException {
        String bookId = "book-2";
        writeBookWithoutChapters(bookId, "空章节书籍");

        ChapterService chapterService = mock(ChapterService.class);
        when(chapterService.getChapterList(any(Book.class))).thenReturn(Single.just(List.of()));
        registerChapterService(chapterService);

        FileBookRepository repository = new FileBookRepository(storage);
        Book loaded = repository.getBook(bookId);

        assertNotNull(loaded, "抓取不到章节也应返回原书籍");
        assertEquals("空章节书籍", loaded.getTitle());
    }
}