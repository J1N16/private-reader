package com.lv.tool.privatereader.events;

import com.intellij.openapi.components.Service;
import java.util.concurrent.atomic.AtomicReference;

@Service(Service.Level.APP)
public final class ChapterChangeManager {
    private final AtomicReference<ChapterChangeEventSource> lastEventSource = new AtomicReference<>();

    public void setEventSource(ChapterChangeEventSource source) {
        lastEventSource.set(source);
    }

    public ChapterChangeEventSource getLastEventSource() {
        return lastEventSource.get();
    }
}