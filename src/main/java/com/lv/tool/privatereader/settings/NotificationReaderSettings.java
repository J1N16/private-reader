package com.lv.tool.privatereader.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

/**
 * 通知阅读器设置
 */
@Service(Service.Level.APP)
public class NotificationReaderSettings extends BaseSettings<NotificationReaderSettings> {
    private static final Logger LOG = Logger.getInstance(NotificationReaderSettings.class);

    private boolean markAsReadOnClose = true;
    private int pageSize = 70;
    private boolean showPageNumber = true;
    private boolean enabled = false; // Default to disabled
    private boolean showChapterTitle = true;
    private boolean showReadingProgress = true;
    private boolean showButtons = true; // 是否在通知栏中显示导航按钮

    @Override
    protected void copyFrom(NotificationReaderSettings source) {
        this.markAsReadOnClose = source.markAsReadOnClose;
        this.pageSize = source.pageSize;
        this.showPageNumber = source.showPageNumber;
        this.enabled = source.enabled;
        this.showChapterTitle = source.showChapterTitle;
        this.showReadingProgress = source.showReadingProgress;
        this.showButtons = source.showButtons;
    }

    @Override
    protected NotificationReaderSettings getDefault() {
        NotificationReaderSettings settings = new NotificationReaderSettings();
        settings.markAsReadOnClose = true;
        settings.pageSize = 70;
        settings.showPageNumber = true;
        settings.enabled = false;
        settings.showChapterTitle = true;
        settings.showReadingProgress = true;
        settings.showButtons = true;
        return settings;
    }

    public boolean isMarkAsReadOnClose() {
        ensureSettingsLoaded();
        return markAsReadOnClose;
    }

    public void setMarkAsReadOnClose(boolean markAsReadOnClose) {
        if (this.markAsReadOnClose != markAsReadOnClose) {
            this.markAsReadOnClose = markAsReadOnClose;
            markDirty();
            ApplicationManager.getApplication().getMessageBus()
                            .syncPublisher(NotificationReaderSettingsListener.TOPIC)
                            .settingsChanged();
        }
    }

    public int getPageSize() {
        ensureSettingsLoaded();
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        if (this.pageSize != pageSize) {
            this.pageSize = pageSize;
            markDirty();
            ApplicationManager.getApplication().getMessageBus()
                            .syncPublisher(NotificationReaderSettingsListener.TOPIC)
                            .settingsChanged();
        }
    }

    public boolean isShowPageNumber() {
        ensureSettingsLoaded();
        return showPageNumber;
    }

    public void setShowPageNumber(boolean showPageNumber) {
        if (this.showPageNumber != showPageNumber) {
            this.showPageNumber = showPageNumber;
            markDirty();
            ApplicationManager.getApplication().getMessageBus()
                            .syncPublisher(NotificationReaderSettingsListener.TOPIC)
                            .settingsChanged();
        }
    }

    public boolean isEnabled() {
        ensureSettingsLoaded();
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            markDirty();
            ApplicationManager.getApplication().getMessageBus()
                            .syncPublisher(NotificationReaderSettingsListener.TOPIC)
                            .settingsChanged();
        }
    }

    public boolean isShowChapterTitle() {
        ensureSettingsLoaded();
        return showChapterTitle;
    }

    public void setShowChapterTitle(boolean showChapterTitle) {
        if (this.showChapterTitle != showChapterTitle) {
            this.showChapterTitle = showChapterTitle;
            markDirty();
            ApplicationManager.getApplication().getMessageBus()
                            .syncPublisher(NotificationReaderSettingsListener.TOPIC)
                            .settingsChanged();
        }
    }

    public boolean isShowReadingProgress() {
        ensureSettingsLoaded();
        return showReadingProgress;
    }

    public void setShowReadingProgress(boolean showReadingProgress) {
        if (this.showReadingProgress != showReadingProgress) {
            this.showReadingProgress = showReadingProgress;
            markDirty();
            ApplicationManager.getApplication().getMessageBus()
                            .syncPublisher(NotificationReaderSettingsListener.TOPIC)
                            .settingsChanged();
        }
    }

    public boolean isShowButtons() {
        ensureSettingsLoaded();
        return showButtons;
    }

    public void setShowButtons(boolean showButtons) {
        if (this.showButtons != showButtons) {
            this.showButtons = showButtons;
            markDirty();
            ApplicationManager.getApplication().getMessageBus()
                            .syncPublisher(NotificationReaderSettingsListener.TOPIC)
                            .settingsChanged();
        }
    }

    // Renamed for clarity, as isShowPageNumber is already used
    public boolean isShowPageNumbers() {
        return isShowPageNumber();
    }
}