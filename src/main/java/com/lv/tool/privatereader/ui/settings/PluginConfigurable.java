package com.lv.tool.privatereader.ui.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.FormBuilder;
import com.lv.tool.privatereader.settings.PluginSettings;
import com.lv.tool.privatereader.settings.PluginSettingsListener;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class PluginConfigurable implements Configurable {
    private static final Logger LOG = Logger.getInstance(PluginConfigurable.class);

    private JPanel mainPanel;
    private JBCheckBox enabledCheckBox;
    private final PluginSettings settings;

    public PluginConfigurable() {
        this.settings = ApplicationManager.getApplication().getService(PluginSettings.class);
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Private Reader Plugin Settings";
    }

    @Override
    public @Nullable JComponent createComponent() {
        enabledCheckBox = new JBCheckBox("启用插件");
        enabledCheckBox.setSelected(settings.isEnabled());

        mainPanel = FormBuilder.createFormBuilder()
                .addComponent(enabledCheckBox)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        return mainPanel;
    }

    @Override
    public boolean isModified() {
        return settings.isEnabled() != enabledCheckBox.isSelected();
    }

    @Override
    public void apply() {
        boolean wasEnabled = settings.isEnabled();
        boolean newEnabled = enabledCheckBox.isSelected();

        LOG.info("[配置诊断] PluginConfigurable.apply(): 当前启用状态: " + wasEnabled + ", 新启用状态: " + newEnabled);

        settings.setEnabled(newEnabled);
        LOG.info("[配置诊断] PluginConfigurable.apply(): 已设置新的启用状态");

        // 强制保存设置
        settings.saveSettings();
        LOG.info("[配置诊断] PluginConfigurable.apply(): 已保存设置");

        // 如果启用状态发生变化，发布事件
        if (wasEnabled != newEnabled) {
            LOG.info("[配置诊断] PluginConfigurable.apply(): 启用状态发生变化，发布事件");
            try {
                ApplicationManager.getApplication()
                    .getMessageBus()
                    .syncPublisher(PluginSettingsListener.TOPIC)
                    .settingsChanged();
                LOG.info("[配置诊断] PluginConfigurable.apply(): 成功发布设置变更事件");
            } catch (Exception e) {
                LOG.error("[配置诊断] PluginConfigurable.apply(): 发布设置变更事件失败", e);
            }
        } else {
            LOG.info("[配置诊断] PluginConfigurable.apply(): 启用状态未变化，不发布事件");
        }
    }

    @Override
    public void reset() {
        enabledCheckBox.setSelected(settings.isEnabled());
    }
}