package com.lv.tool.privatereader.ui.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.ColorPicker;
import com.intellij.util.ui.FormBuilder;
import com.lv.tool.privatereader.settings.ReaderSettings;
import com.lv.tool.privatereader.settings.ReaderSettingsListener;
import com.lv.tool.privatereader.settings.Theme;
import com.lv.tool.privatereader.settings.ThemePreset;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Collections;

public class ReaderConfigurable implements Configurable {
    private JPanel mainPanel;
    private ComboBox<String> fontFamilyComboBox;
    private ComboBox<Integer> fontSizeComboBox;
    private JCheckBox boldCheckBox;
    private ComboBox<String> themePresetComboBox;
    private JCheckBox darkModeCheckBox;
    private JCheckBox animationCheckBox;
    private JButton textColorButton;
    private JButton bgColorButton;
    private JButton accentColorButton;
    private JPanel textColorPreview;
    private JPanel bgColorPreview;
    private JPanel accentColorPreview;
    private final ReaderSettings settings;

    public ReaderConfigurable() {
        this.settings = ApplicationManager.getApplication().getService(ReaderSettings.class);
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Reader Settings";
    }

    @Override
    public @Nullable JComponent createComponent() {
        // 获取系统所有字体
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] fontFamilies = ge.getAvailableFontFamilyNames();
        fontFamilyComboBox = new ComboBox<>(fontFamilies);
        
        // 字体大小选项
        Integer[] sizes = {12, 14, 16, 18, 20, 22, 24, 26, 28, 30};
        fontSizeComboBox = new ComboBox<>(sizes);
        
        boldCheckBox = new JCheckBox("粗体");
        
        // 主题设置
        themePresetComboBox = new ComboBox<>(new String[]{"Default", "Custom"});
        darkModeCheckBox = new JCheckBox("深色模式");
        animationCheckBox = new JCheckBox("启用动画效果");
        
        // 创建颜色选择按钮和预览面板
        textColorButton = createColorButton("选择文本颜色");
        bgColorButton = createColorButton("选择背景颜色");
        accentColorButton = createColorButton("选择强调颜色");
        
        // 添加颜色选择器事件处理
        textColorButton.addActionListener(e -> {
            Color newColor = ColorPicker.showDialog(mainPanel, "选择文本颜色", textColorPreview.getBackground(), true, Collections.emptyList(), true);
            if (newColor != null) {
                updateColorPreview(textColorPreview, newColor);
            }
        });
        
        bgColorButton.addActionListener(e -> {
            Color newColor = ColorPicker.showDialog(mainPanel, "选择背景颜色", bgColorPreview.getBackground(), true, Collections.emptyList(), true);
            if (newColor != null) {
                updateColorPreview(bgColorPreview, newColor);
            }
        });
        
        accentColorButton.addActionListener(e -> {
            Color newColor = ColorPicker.showDialog(mainPanel, "选择强调颜色", accentColorPreview.getBackground(), true, Collections.emptyList(), true);
            if (newColor != null) {
                updateColorPreview(accentColorPreview, newColor);
            }
        });
        
        textColorPreview = createColorPreviewPanel();
        bgColorPreview = createColorPreviewPanel();
        accentColorPreview = createColorPreviewPanel();
        
        // 添加主题预设选择监听器
        themePresetComboBox.addActionListener(e -> {
            boolean isCustom = "Custom".equals(themePresetComboBox.getSelectedItem());
            textColorButton.setEnabled(isCustom);
            bgColorButton.setEnabled(isCustom);
            accentColorButton.setEnabled(isCustom);
            textColorPreview.setEnabled(isCustom);
            bgColorPreview.setEnabled(isCustom);
            accentColorPreview.setEnabled(isCustom);
        });
        
        // 加载当前设置
        loadSettings();
        
        // 构建设置面板
        mainPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("字体设置"), new JSeparator())
                .addLabeledComponent(new JBLabel("字体: "), fontFamilyComboBox)
                .addLabeledComponent(new JBLabel("大小: "), fontSizeComboBox)
                .addComponent(boldCheckBox)
                .addLabeledComponent(new JBLabel("主题设置"), new JSeparator())
                .addLabeledComponent(new JBLabel("主题: "), themePresetComboBox)
                .addComponent(darkModeCheckBox)
                .addComponent(animationCheckBox)
                .addLabeledComponent(new JBLabel("自定义主题颜色"), new JSeparator())
                .addLabeledComponent(new JBLabel("文本颜色: "), createColorPanel(textColorButton, textColorPreview))
                .addLabeledComponent(new JBLabel("背景颜色: "), createColorPanel(bgColorButton, bgColorPreview))
                .addLabeledComponent(new JBLabel("强调颜色: "), createColorPanel(accentColorButton, accentColorPreview))
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        
        return mainPanel;
    }

    private JButton createColorButton(String text) {
        JButton button = new JButton(text);
        button.setPreferredSize(new Dimension(120, 25));
        return button;
    }

    private JPanel createColorPreviewPanel() {
        JPanel panel = new JPanel();
        panel.setPreferredSize(new Dimension(50, 25));
        panel.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        return panel;
    }

    private JPanel createColorPanel(JButton button, JPanel preview) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        panel.add(button);
        panel.add(preview);
        return panel;
    }

    private void loadSettings() {
        // 加载字体设置
        fontFamilyComboBox.setSelectedItem(settings.getFontFamily());
        fontSizeComboBox.setSelectedItem(settings.getFontSize());
        boldCheckBox.setSelected(settings.isBold());
        
        // 加载主题设置
        Theme currentTheme = settings.getCurrentTheme();
        darkModeCheckBox.setSelected(settings.isDarkTheme());
        animationCheckBox.setSelected(settings.isUseAnimation());
        
        // 如果是自定义主题，加载颜色设置
        if (settings.getCurrentPreset().getName().equals("Custom")) {
            themePresetComboBox.setSelectedItem("Custom");
            updateColorPreview(textColorPreview, currentTheme.getTextColor());
            updateColorPreview(bgColorPreview, currentTheme.getBackgroundColor());
            updateColorPreview(accentColorPreview, currentTheme.getAccentColor());
        } else {
            themePresetComboBox.setSelectedItem("Default");
        }
        
        // 更新颜色选择器的启用状态
        boolean isCustom = "Custom".equals(themePresetComboBox.getSelectedItem());
        textColorButton.setEnabled(isCustom);
        bgColorButton.setEnabled(isCustom);
        accentColorButton.setEnabled(isCustom);
        textColorPreview.setEnabled(isCustom);
        bgColorPreview.setEnabled(isCustom);
        accentColorPreview.setEnabled(isCustom);
    }

    private void updateColorPreview(JPanel preview, Color color) {
        preview.setBackground(color);
        preview.repaint();
    }

    @Override
    public boolean isModified() {
        Theme currentTheme = settings.getCurrentTheme();
        boolean isCustomTheme = settings.getCurrentPreset().getName().equals("Custom");
        
        return !settings.getFontFamily().equals(fontFamilyComboBox.getSelectedItem()) ||
               settings.getFontSize() != (Integer) fontSizeComboBox.getSelectedItem() ||
               settings.isBold() != boldCheckBox.isSelected() ||
               settings.isDarkTheme() != darkModeCheckBox.isSelected() ||
               settings.isUseAnimation() != animationCheckBox.isSelected() ||
               (isCustomTheme && (
                   !currentTheme.getTextColor().equals(textColorPreview.getBackground()) ||
                   !currentTheme.getBackgroundColor().equals(bgColorPreview.getBackground()) ||
                   !currentTheme.getAccentColor().equals(accentColorPreview.getBackground())
               ));
    }

    @Override
    public void apply() {
        // 应用字体设置
        settings.setFontFamily((String) fontFamilyComboBox.getSelectedItem());
        settings.setFontSize((Integer) fontSizeComboBox.getSelectedItem());
        settings.setBold(boldCheckBox.isSelected());
        
        // 应用主题设置
        settings.setUseAnimation(animationCheckBox.isSelected());
        
        // 如果选择了自定义主题，创建新的主题
        if (themePresetComboBox.getSelectedItem().equals("Custom")) {
            Theme customTheme = new Theme.Builder()
                .setName("Custom Theme")
                .setTextColor(textColorPreview.getBackground())
                .setBackgroundColor(bgColorPreview.getBackground())
                .setAccentColor(accentColorPreview.getBackground())
                .setDark(darkModeCheckBox.isSelected())
                .build();
                
            // 创建自定义主题预设
            ThemePreset customPreset = new ThemePreset(
                "Custom",
                customTheme,
                customTheme // 使用相同的主题作为深色版本
            );
            
            // 更新设置
            settings.setThemePresets(List.of(ThemePreset.defaultPreset(), customPreset));
            settings.setCurrentPresetName("Custom");
        } else {
            // 使用默认主题
            settings.setThemePresets(List.of(ThemePreset.defaultPreset()));
            settings.setCurrentPresetName("Default");
        }
        
        // 设置深色模式
        if (settings.isDarkTheme() != darkModeCheckBox.isSelected()) {
            settings.toggleTheme();
        }
        
        // 通知设置变更
        ApplicationManager.getApplication()
            .getMessageBus()
            .syncPublisher(ReaderSettingsListener.TOPIC)
            .settingsChanged();

        // 保存设置
        settings.saveSettings();
    }

    @Override
    public void reset() {
        loadSettings();
    }
} 