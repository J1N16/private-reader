package com.lv.tool.privatereader.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.lv.tool.privatereader.settings.CacheSettings;
import com.lv.tool.privatereader.settings.NotificationReaderSettings;
import com.lv.tool.privatereader.settings.PluginSettings;
import com.lv.tool.privatereader.settings.ReaderSettings;
import com.lv.tool.privatereader.settings.ReaderModeSettings;

/**
 * 私人阅读器配置类
 * 集中管理所有配置项
 */
@Service(Service.Level.APP)
public final class PrivateReaderConfig {
    // 通知组ID常量
    public static final String NOTIFICATION_GROUP_ID = "Private Reader";
    public static final String NOTIFICATION_GROUP_ID_READER = "PrivateReader";

    // 应用级别设置
    private final PluginSettings pluginSettings;
    private final ReaderSettings readerSettings;
    private final CacheSettings cacheSettings;
    private final NotificationReaderSettings notificationSettings;
    private final ReaderModeSettings modeSettings;
    
    public PrivateReaderConfig() {
        // 直接使用 ApplicationManager 获取应用级别服务
        this.pluginSettings = ApplicationManager.getApplication().getService(PluginSettings.class);
        this.readerSettings = ApplicationManager.getApplication().getService(ReaderSettings.class);
        this.cacheSettings = ApplicationManager.getApplication().getService(CacheSettings.class);
        this.notificationSettings = ApplicationManager.getApplication().getService(NotificationReaderSettings.class);
        this.modeSettings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
    }
    
    /**
     * 获取插件是否启用
     */
    public boolean isPluginEnabled() {
        return pluginSettings != null && pluginSettings.isEnabled();
    }
    
    /**
     * 获取阅读器字体
     */
    public String getReaderFont() {
        return readerSettings != null ? readerSettings.getFontFamily() : "Serif";
    }
    
    /**
     * 获取阅读器字体大小
     */
    public int getReaderFontSize() {
        return readerSettings != null ? readerSettings.getFontSize() : 16;
    }
    
    /**
     * 获取阅读器行间距
     */
    public float getReaderLineSpacing() {
        // 由于ReaderSettings没有行间距设置，返回默认值
        return 1.5f;
    }
    
    /**
     * 获取缓存是否启用
     */
    public boolean isCacheEnabled() {
        return cacheSettings != null && cacheSettings.isEnableCache();
    }
    
    /**
     * 获取缓存大小限制（MB）
     */
    public int getCacheSizeLimit() {
        return cacheSettings != null ? cacheSettings.getMaxCacheSize() : 100;
    }
    
    /**
     * 获取缓存过期时间（天）
     */
    public int getCacheExpirationDays() {
        return cacheSettings != null ? cacheSettings.getMaxCacheAge() : 7;
    }
    
    /**
     * 获取预加载章节数
     */
    public int getPreloadChapterCount() {
        return cacheSettings != null ? cacheSettings.getPreloadCount() : 3;
    }
    
    /**
     * 获取通知阅读器页面大小
     */
    public int getNotificationPageSize() {
        return notificationSettings != null ? notificationSettings.getPageSize() : 70;
    }
    
    /**
     * 获取是否显示页码
     */
    public boolean isShowPageNumber() {
        return notificationSettings != null && notificationSettings.isShowPageNumber();
    }
    
    /**
     * 获取阅读模式
     */
    public ReaderMode getReaderMode() {
        if (modeSettings == null) {
            return ReaderMode.PANEL;
        }
        return modeSettings.isNotificationMode() ? ReaderMode.NOTIFICATION : ReaderMode.PANEL;
    }
    
    /**
     * 阅读模式枚举
     */
    public enum ReaderMode {
        PANEL,      // 面板模式
        NOTIFICATION // 通知模式
    }
} 