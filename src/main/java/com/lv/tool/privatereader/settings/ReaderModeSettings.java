package com.lv.tool.privatereader.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.messages.Topic;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

/**
 * 阅读模式设置
 * 用于持久化存储阅读模式（通知栏模式或阅读器模式）
 */
@Service(Service.Level.APP)
public class ReaderModeSettings extends BaseSettings<ReaderModeSettings> {
    private static final Logger LOG = Logger.getInstance(ReaderModeSettings.class);

    public enum Mode {
        DEFAULT,
        NOTIFICATION_BAR
    }

    private Mode currentMode = Mode.DEFAULT; // 默认为阅读器模式
    private boolean autoScroll = true;
    private int scrollSpeed = 50;
    private boolean showLineNumbers = true;
    private boolean showPageNumbers = true;
    private int lineSpacing = 1;
    private int paragraphSpacing = 2;
    private int marginLeft = 20;
    private int marginRight = 20;
    private int marginTop = 20;
    private int marginBottom = 20;

    public static final Topic<ReaderModeSettingsListener> TOPIC =
            Topic.create("PrivateReaderModeSettings", ReaderModeSettingsListener.class);

    public boolean isNotificationMode() {
        ensureSettingsLoaded();
        LOG.info("[配置诊断] ReaderModeSettings.isNotificationMode() 返回: " + (currentMode == Mode.NOTIFICATION_BAR));
        return currentMode == Mode.NOTIFICATION_BAR;
    }

    public void setNotificationMode(boolean notificationMode) {
        Mode newMode = notificationMode ? Mode.NOTIFICATION_BAR : Mode.DEFAULT;
        setCurrentMode(newMode);
    }

    public Mode getCurrentMode() {
        ensureSettingsLoaded();
        return currentMode;
    }

    public void setCurrentMode(Mode newMode) {
        boolean changed = this.currentMode != newMode;
        this.currentMode = newMode;
        markDirty();

        // 通知监听器
        if (changed) {
            ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(TOPIC)
                .modeChanged(newMode == Mode.NOTIFICATION_BAR); // Still notify boolean for existing listeners
        }
    }

    @Override
    protected void copyFrom(ReaderModeSettings source) {
        this.currentMode = source.currentMode;
        this.autoScroll = source.autoScroll;
        this.scrollSpeed = source.scrollSpeed;
        this.showLineNumbers = source.showLineNumbers;
        this.showPageNumbers = source.showPageNumbers;
        this.lineSpacing = source.lineSpacing;
        this.paragraphSpacing = source.paragraphSpacing;
        this.marginLeft = source.marginLeft;
        this.marginRight = source.marginRight;
        this.marginTop = source.marginTop;
        this.marginBottom = source.marginBottom;
    }

    @Override
    protected ReaderModeSettings getDefault() {
        ReaderModeSettings settings = new ReaderModeSettings();
        settings.currentMode = Mode.DEFAULT;
        settings.autoScroll = true;
        settings.scrollSpeed = 50;
        settings.showLineNumbers = true;
        settings.showPageNumbers = true;
        settings.lineSpacing = 1;
        settings.paragraphSpacing = 2;
        settings.marginLeft = 20;
        settings.marginRight = 20;
        settings.marginTop = 20;
        settings.marginBottom = 20;
        return settings;
    }

    public boolean isAutoScroll() {
        ensureSettingsLoaded();
        return autoScroll;
    }

    public void setAutoScroll(boolean autoScroll) {
        this.autoScroll = autoScroll;
        markDirty();
    }

    public int getScrollSpeed() {
        ensureSettingsLoaded();
        return scrollSpeed;
    }

    public void setScrollSpeed(int scrollSpeed) {
        this.scrollSpeed = scrollSpeed;
        markDirty();
    }

    public boolean isShowLineNumbers() {
        ensureSettingsLoaded();
        return showLineNumbers;
    }

    public void setShowLineNumbers(boolean showLineNumbers) {
        this.showLineNumbers = showLineNumbers;
        markDirty();
    }

    public boolean isShowPageNumbers() {
        ensureSettingsLoaded();
        return showPageNumbers;
    }

    public void setShowPageNumbers(boolean showPageNumbers) {
        this.showPageNumbers = showPageNumbers;
        markDirty();
    }

    public int getLineSpacing() {
        ensureSettingsLoaded();
        return lineSpacing;
    }

    public void setLineSpacing(int lineSpacing) {
        this.lineSpacing = lineSpacing;
        markDirty();
    }

    public int getParagraphSpacing() {
        ensureSettingsLoaded();
        return paragraphSpacing;
    }

    public void setParagraphSpacing(int paragraphSpacing) {
        this.paragraphSpacing = paragraphSpacing;
        markDirty();
    }

    public int getMarginLeft() {
        ensureSettingsLoaded();
        return marginLeft;
    }

    public void setMarginLeft(int marginLeft) {
        this.marginLeft = marginLeft;
        markDirty();
    }

    public int getMarginRight() {
        ensureSettingsLoaded();
        return marginRight;
    }

    public void setMarginRight(int marginRight) {
        this.marginRight = marginRight;
        markDirty();
    }

    public int getMarginTop() {
        ensureSettingsLoaded();
        return marginTop;
    }

    public void setMarginTop(int marginTop) {
        this.marginTop = marginTop;
        markDirty();
    }

    public int getMarginBottom() {
        ensureSettingsLoaded();
        return marginBottom;
    }

    public void setMarginBottom(int marginBottom) {
        this.marginBottom = marginBottom;
        markDirty();
    }

    public void save() {
        saveSettings();
    }
}