package com.lv.tool.privatereader.settings;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

/**
 * 插件设置
 */
@Service(Service.Level.APP)
public class PluginSettings extends BaseSettings<PluginSettings> {
    private static final Logger LOG = Logger.getInstance(PluginSettings.class);
    
    private boolean enabled = true;
    private boolean autoUpdate = true;
    private boolean showNotifications = true;
    private String language = "zh_CN";
    private boolean debugMode = false;
    
    @Override
    protected void copyFrom(PluginSettings source) {
        this.enabled = source.enabled;
        this.autoUpdate = source.autoUpdate;
        this.showNotifications = source.showNotifications;
        this.language = source.language;
        this.debugMode = source.debugMode;
    }
    
    @Override
    protected PluginSettings getDefault() {
        PluginSettings settings = new PluginSettings();
        settings.enabled = true;
        settings.autoUpdate = true;
        settings.showNotifications = true;
        settings.language = "zh_CN";
        settings.debugMode = false;
        return settings;
    }
    
    public boolean isEnabled() {
        ensureSettingsLoaded();
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        markDirty();
    }
    
    public boolean isAutoUpdate() {
        ensureSettingsLoaded();
        return autoUpdate;
    }
    
    public void setAutoUpdate(boolean autoUpdate) {
        this.autoUpdate = autoUpdate;
        markDirty();
    }
    
    public boolean isShowNotifications() {
        ensureSettingsLoaded();
        return showNotifications;
    }
    
    public void setShowNotifications(boolean showNotifications) {
        this.showNotifications = showNotifications;
        markDirty();
    }
    
    public String getLanguage() {
        ensureSettingsLoaded();
        return language;
    }
    
    public void setLanguage(String language) {
        this.language = language;
        markDirty();
    }
    
    public boolean isDebugMode() {
        ensureSettingsLoaded();
        return debugMode;
    }
    
    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
        markDirty();
    }
} 