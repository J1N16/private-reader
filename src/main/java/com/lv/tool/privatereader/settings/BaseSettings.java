package com.lv.tool.privatereader.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.storage.SettingsStorage;
import org.jetbrains.annotations.NotNull;


/**
 * 基础设置类
 *
 * 所有设置类的基类，提供 JSON 文件存储支持
 */
public abstract class BaseSettings<T extends BaseSettings<T>> {
    private static final Logger LOG = Logger.getInstance(BaseSettings.class);
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private transient boolean loaded = false;
    private transient boolean dirty = false;

    protected BaseSettings() {
        // 构造函数不再自动加载设置，避免递归调用
    }

    protected void ensureSettingsLoaded() {
        if (!loaded) {
            loadSettings();
        }
    }

    @SuppressWarnings("unchecked")
    protected void loadSettings() {
        if (loaded) {
            LOG.debug("[配置诊断] " + getClass().getSimpleName() + " 已加载，跳过加载过程");
            return;
        }

        LOG.debug("[配置诊断] 开始加载 " + getClass().getSimpleName() + " 配置");
        try {
            SettingsStorage settingsStorage = SettingsStorage.getInstance();
            if (settingsStorage == null) {
                LOG.error("[配置诊断] 无法获取 SettingsStorage 实例，使用默认值");
                useDefaultSettings();
                return;
            }

            T loadedSettings = (T) settingsStorage.loadSettings(getClass());
            if (loadedSettings != null) {
                LOG.debug("[配置诊断] 成功从存储加载 " + getClass().getSimpleName() + " 配置");
                copyFrom(loadedSettings);
            } else {
                LOG.debug("[配置诊断] 未找到 " + getClass().getSimpleName() + " 配置文件，使用默认值");
                // 如果没有加载到设置，使用默认值
                useDefaultSettings();
            }
            loaded = true;
            dirty = false;
        } catch (Exception e) {
            LOG.error("[配置诊断] 加载设置失败: " + getClass().getSimpleName(), e);
            // 加载失败时使用默认值
            useDefaultSettings();
        }
    }

    /**
     * 使用默认设置
     */
    private void useDefaultSettings() {
        try {
            T defaultSettings = getDefault();
            if (defaultSettings != null) {
                LOG.debug("[配置诊断] 应用默认设置: " + getClass().getSimpleName());
                copyFrom(defaultSettings);
            } else {
                LOG.error("[配置诊断] 获取默认设置失败: " + getClass().getSimpleName());
            }
            loaded = true;
            dirty = false;
        } catch (Exception e) {
            LOG.error("[配置诊断] 应用默认设置失败: " + getClass().getSimpleName(), e);
            loaded = true; // 防止无限递归
            dirty = false;
        }
    }

    @SuppressWarnings("unchecked")
    public void saveSettings() {
        if (!loaded) {
            LOG.warn("[配置诊断] 尝试保存未加载的设置: " + getClass().getSimpleName() + "，先加载设置");
            loadSettings();
        }

        if (dirty) {
            LOG.debug("[配置诊断] 开始保存 " + getClass().getSimpleName() + " 配置 (dirty=true)");
            try {
                SettingsStorage settingsStorage = SettingsStorage.getInstance();
                if (settingsStorage == null) {
                    LOG.error("[配置诊断] 无法获取 SettingsStorage 实例，保存失败");
                    return;
                }

                settingsStorage.saveSettings((T) this);
                LOG.debug("[配置诊断] 成功保存 " + getClass().getSimpleName() + " 配置");
                dirty = false;
            } catch (Exception e) {
                LOG.error("[配置诊断] 保存设置失败: " + getClass().getSimpleName(), e);
            }
        } else {
            LOG.debug("[配置诊断] 跳过保存 " + getClass().getSimpleName() + " 配置 (dirty=false)");
        }
    }

    protected void markDirty() {
        this.dirty = true;
        LOG.debug("[配置诊断] 标记 " + getClass().getSimpleName() + " 配置为已修改");
    }

    protected boolean isDirty() {
        return dirty;
    }

    protected boolean isLoaded() {
        return loaded;
    }

    /**
     * 从源设置复制
     */
    protected abstract void copyFrom(@NotNull T other);

    /**
     * 获取默认设置
     * @return 默认设置
     */
    protected abstract T getDefault();
}