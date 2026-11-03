package com.lv.tool.privatereader.events;

import com.intellij.util.messages.Topic;
import com.lv.tool.privatereader.model.Book;
import org.jetbrains.annotations.NotNull;

import java.util.EventListener;

/**
 * Contains events related to book data.
 */
public class BookEvents {

    /**
     * Listener for book data loading events.
     */
    public interface BookDataListener extends EventListener {
        /**
         * Topic for BookDataListener events.
         */
        Topic<BookDataListener> BOOK_DATA_TOPIC = Topic.create("Book Data Loaded", BookDataListener.class);

        /**
         * Fired when initial book data (e.g., from storage) has been loaded.
         */
        void bookDataLoaded();
    }

    /**
     * 书籍从书架删除事件监听器。
     * <p>
     * 用于让通知栏模式等长生命周期状态在「当前阅读的书籍被删除」时及时退出,
     * 避免书籍已不存在但通知栏仍能翻页/显示旧章节内容。
     */
    public interface BookRemovedListener extends EventListener {
        /**
         * Topic for BookRemovedListener events.
         */
        Topic<BookRemovedListener> BOOK_REMOVED_TOPIC = Topic.create("Book Removed", BookRemovedListener.class);

        /**
         * Fired after a book has been removed from the bookshelf.
         *
         * @param book 被删除的书籍
         */
        void bookRemoved(@NotNull Book book);
    }

    // Add other book-related events or listeners here if needed
}
