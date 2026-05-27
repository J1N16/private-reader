package com.lv.tool.privatereader.settings;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import java.awt.Font;
import javax.swing.UIManager;
import java.util.ArrayList;
import java.util.List;

/**
 * 阅读器设置
 */
@Service(Service.Level.APP)
public final class ReaderSettings extends BaseSettings<ReaderSettings> {
    private static final Logger LOG = Logger.getInstance(ReaderSettings.class);
    
    private static final String DEFAULT_FONT_FAMILY = "Microsoft YaHei";
    private static final int DEFAULT_FONT_SIZE = 16;
    private static final int LIGHT_THEME_COLOR = -14013910; // RGB: 43, 43, 43
    private static final int DARK_THEME_COLOR = -4473925;   // RGB: 187, 187, 187
    private static final String EDITOR_FONT_KEY = "Label.font";
    private static final String EDITOR_COLOR_KEY = "Editor.foreground";
    private static final String DARK_THEME_KEY = "ui.is.dark";
    private static final int LIGHT_BG_COLOR = -1;           // RGB: 255, 255, 255
    private static final int DARK_BG_COLOR = -16777216;    // RGB: 0, 0, 0

    private String fontFamily = getSystemFontFamily();
    private int fontSize = getSystemFontSize();
    private boolean isBold = false;
    private List<ThemePreset> themePresets = new ArrayList<>();
    private String currentPresetName = "Default";
    private boolean isDarkTheme = false;
    private boolean useAnimation = true;
    private int animationDuration = 300;
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

    public ReaderSettings() {
        // 初始化默认主题预设
        themePresets.add(ThemePreset.defaultPreset());
    }

    private static String getSystemFontFamily() {
        Font font = UIManager.getFont("Label.font");
        return font != null ? font.getFamily() : DEFAULT_FONT_FAMILY;
    }

    private static int getSystemFontSize() {
        Font font = UIManager.getFont("Label.font");
        return font != null ? font.getSize() : DEFAULT_FONT_SIZE;
    }

    public String getFontFamily() {
        ensureSettingsLoaded();
        return fontFamily;
    }

    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
        markDirty();
    }

    public int getFontSize() {
        ensureSettingsLoaded();
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = fontSize;
        markDirty();
    }

    public boolean isBold() {
        ensureSettingsLoaded();
        return isBold;
    }

    public void setBold(boolean bold) {
        isBold = bold;
        markDirty();
    }

    public List<ThemePreset> getThemePresets() {
        ensureSettingsLoaded();
        return new ArrayList<>(themePresets);
    }

    public void setThemePresets(List<ThemePreset> presets) {
        this.themePresets = new ArrayList<>(presets);
        markDirty();
    }

    public void addThemePreset(ThemePreset preset) {
        themePresets.add(preset);
        markDirty();
    }

    public ThemePreset getCurrentPreset() {
        ensureSettingsLoaded();
        return themePresets.stream()
            .filter(preset -> preset.getName().equals(currentPresetName))
            .findFirst()
            .orElse(ThemePreset.defaultPreset());
    }

    public Theme getCurrentTheme() {
        ensureSettingsLoaded();
        return getCurrentPreset().getTheme(isDarkTheme);
    }

    public void setCurrentPresetName(String name) {
        this.currentPresetName = name;
        markDirty();
    }

    public boolean isDarkTheme() {
        ensureSettingsLoaded();
        return isDarkTheme;
    }

    public void toggleTheme() {
        isDarkTheme = !isDarkTheme;
        markDirty();
    }

    public boolean isUseAnimation() {
        ensureSettingsLoaded();
        return useAnimation;
    }

    public void setUseAnimation(boolean useAnimation) {
        this.useAnimation = useAnimation;
        markDirty();
    }

    public int getAnimationDuration() {
        ensureSettingsLoaded();
        return animationDuration;
    }

    public void setAnimationDuration(int animationDuration) {
        this.animationDuration = animationDuration;
        markDirty();
    }

    @Override
    protected void copyFrom(ReaderSettings source) {
        this.fontFamily = source.fontFamily;
        this.fontSize = source.fontSize;
        this.isBold = source.isBold;
        this.themePresets = new ArrayList<>(source.themePresets);
        this.currentPresetName = source.currentPresetName;
        this.isDarkTheme = source.isDarkTheme;
        this.useAnimation = source.useAnimation;
        this.animationDuration = source.animationDuration;
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
    protected ReaderSettings getDefault() {
        ReaderSettings settings = new ReaderSettings();
        settings.fontFamily = DEFAULT_FONT_FAMILY;
        settings.fontSize = DEFAULT_FONT_SIZE;
        settings.isBold = false;
        settings.themePresets = new ArrayList<>();
        settings.themePresets.add(ThemePreset.defaultPreset());
        settings.currentPresetName = "Default";
        settings.isDarkTheme = false;
        settings.useAnimation = true;
        settings.animationDuration = 300;
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
} 