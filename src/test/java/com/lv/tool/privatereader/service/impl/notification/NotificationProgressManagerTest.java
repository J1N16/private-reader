package com.lv.tool.privatereader.service.impl.notification;

import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.model.BookProgressData;
import com.lv.tool.privatereader.repository.ReadingProgressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotificationProgressManager 单元测试(V19 从 NotificationServiceImpl 拆分出的进度保存/恢复)。
 * <p>
 * 覆盖:
 * - {@link NotificationProgressManager#restoreSavedPageNumber}:同章节恢复、章节不匹配回落默认值、
 *   无记录回落默认值、仓库为 null 回落默认值
 * - {@link NotificationProgressManager#saveProgress}:活动会话写入 1 基页码、无会话短路(不写库)
 * <p>
 * 隔离方式:窄接口 {@link NotificationProgressManager.Host} 用可断言内存实现
 * (复用真实 {@link ReaderViewState});仓库为 mock,验证写入参数。
 */
class NotificationProgressManagerTest {

    private NotificationProgressManager manager;
    private ReadingProgressRepository repository;
    private TestHost host;

    @BeforeEach
    void setUp() {
        repository = mock(ReadingProgressRepository.class);
        host = new TestHost(repository != null);
        manager = new NotificationProgressManager(host, repository);
    }

    private Book newBook() {
        return new Book("b1", "测试书", "作者", "https://x.com/b1");
    }

    private void startReading(Book book, String chapterId, String chapterTitle, int pageIndex) {
        host.ref.set(ReaderViewState.empty()
                .withChapter(book, chapterId, chapterTitle)
                .withPages(java.util.List.of("p1", "p2", "p3"), true)
                .withPageIndex(pageIndex));
    }

    // --- restoreSavedPageNumber ---

    @Test
    void restoreReturnsSavedPageWhenChapterMatches() {
        when(repository.getProgress("b1")).thenReturn(Optional.of(
                new BookProgressData("b1", "测试书", "c2", "第二章", 0, 7, false, null)));

        int restored = manager.restoreSavedPageNumber("b1", "c2", 1);

        assertEquals(7, restored, "同章节应恢复已保存页码");
    }

    @Test
    void restoreFallsBackWhenChapterDiffers() {
        when(repository.getProgress("b1")).thenReturn(Optional.of(
                new BookProgressData("b1", "测试书", "c9", "第九章", 0, 7, false, null)));

        int restored = manager.restoreSavedPageNumber("b1", "c2", 3);

        assertEquals(3, restored, "章节不匹配应回落默认页码");
    }

    @Test
    void restoreFallsBackWhenNoRecord() {
        when(repository.getProgress("b1")).thenReturn(Optional.empty());

        assertEquals(5, manager.restoreSavedPageNumber("b1", "c2", 5));
    }

    @Test
    void restoreFallsBackWhenRepositoryNull() {
        NotificationProgressManager noRepo = new NotificationProgressManager(host, null);

        assertEquals(2, noRepo.restoreSavedPageNumber("b1", "c2", 2),
                "仓库为 null 时应安全回落默认页码");
    }

    // --- saveProgress ---

    @Test
    void saveWritesOneBasedPageForActiveSession() {
        Book book = newBook();
        startReading(book, "c2", "第二章", 4); // pageIndex=4 → 页码 5

        manager.saveProgress();

        verify(repository).updateProgress(book, "c2", "第二章", 0, 5);
    }

    @Test
    void saveSkipsWhenNoActiveSession() {
        // host 保持空状态
        manager.saveProgress();

        verify(repository, never()).updateProgress(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void saveSkipsWhenRepositoryNull() {
        Book book = newBook();
        startReading(book, "c2", "第二章", 0);
        NotificationProgressManager noRepo = new NotificationProgressManager(host, null);

        // 不抛异常即通过(仓库为 null 时安全短路)
        noRepo.saveProgress();
    }

    /** 可断言的内存 Host:复用真实 ReaderViewState;可配置会话是否被视为活动 */
    private static final class TestHost implements NotificationProgressManager.Host {
        private final java.util.concurrent.atomic.AtomicReference<ReaderViewState> ref =
                new java.util.concurrent.atomic.AtomicReference<>(ReaderViewState.empty());
        private final boolean active;

        TestHost(boolean active) {
            this.active = active;
        }

        @Override
        public ReaderViewState getViewState() {
            return ref.get();
        }

        @Override
        public boolean isReadingActive(boolean notifyWhenInactive) {
            return active && ref.get().isReadingActive();
        }
    }
}