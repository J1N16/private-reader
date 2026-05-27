package com.lv.tool.privatereader.events;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChapterChangeManagerTest {

    @Test
    void storesLastEventSource() {
        ChapterChangeManager manager = new ChapterChangeManager();

        assertNull(manager.getLastEventSource());

        manager.setEventSource(ChapterChangeEventSource.NOTIFICATION_SERVICE);
        assertEquals(ChapterChangeEventSource.NOTIFICATION_SERVICE, manager.getLastEventSource());

        manager.setEventSource(ChapterChangeEventSource.READER_PANEL);
        assertEquals(ChapterChangeEventSource.READER_PANEL, manager.getLastEventSource());
    }
}
