package com.lv.tool.privatereader.ui.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.lv.tool.privatereader.settings.ReaderModeSettings;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * 阅读模式设置界面
 */
public class ReaderModeConfigurable implements Configurable {
    private JPanel mainPanel;
    private JBCheckBox notificationModeCheckBox;
    private final ReaderModeSettings settings;

    public ReaderModeConfigurable() {
        settings = ApplicationManager.getApplication().getService(ReaderModeSettings.class);
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "阅读模式设置";
    }

    @Override
    public @Nullable JComponent createComponent() {
        notificationModeCheckBox = new JBCheckBox("使用通知栏模式阅读");
        
        // 加载当前设置
        notificationModeCheckBox.setSelected(settings.isNotificationMode());
        
        // 构建设置面板
        mainPanel = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("阅读模式选择"))
                .addComponent(notificationModeCheckBox)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        return settings.isNotificationMode() != notificationModeCheckBox.isSelected();
    }

    @Override
    public void apply() throws ConfigurationException {
        boolean newValue = notificationModeCheckBox.isSelected();
        // 只有在值实际改变时才更新并发送通知
        if (settings.isNotificationMode() != newValue) {
            settings.setNotificationMode(newValue);
            // 保存设置
            settings.saveSettings();
            // 通知设置变更 (这个逻辑应该在 setNotificationMode 内部处理，检查确认)
            // ApplicationManager.getApplication().getMessageBus()
            //         .syncPublisher(ReaderModeSettings.TOPIC)
            //         .modeChanged(newValue);
        } else {
            // 如果值没有改变，但可能被标记为 dirty（例如，如果 isModified 逻辑更复杂），
            // 仍然需要保存以清除 dirty 标志。
            settings.saveSettings();
        }
    }

    @Override
    public void reset() {
        // 从 settings 重新加载值到 UI 组件
        notificationModeCheckBox.setSelected(settings.isNotificationMode());
    }
} 