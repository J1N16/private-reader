package com.lv.tool.privatereader.ui.settings;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.icons.AllIcons;
import com.lv.tool.privatereader.storage.StorageManager;
import com.lv.tool.privatereader.repository.BookRepository;
import com.lv.tool.privatereader.repository.ChapterCacheRepository;
import com.lv.tool.privatereader.repository.RepositoryModule;
import com.lv.tool.privatereader.repository.StorageRepository;
import com.lv.tool.privatereader.service.NotificationService;
import com.lv.tool.privatereader.settings.CacheSettings;
import com.lv.tool.privatereader.settings.CacheSettingsListener;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;

/**
 * 缓存设置界面
 */
public class CacheConfigurable implements Configurable {
    private static final Logger LOG = Logger.getInstance(CacheConfigurable.class);

    private JPanel mainPanel;
    private JBCheckBox enableCacheCheckBox;
    private JSpinner maxCacheSizeSpinner;
    private JSpinner maxCacheAgeSpinner;
    private JBCheckBox enablePreloadCheckBox;
    private JSpinner preloadCountSpinner;
    private JSpinner preloadDelaySpinner;
    private JLabel cachePathLabel;
    private JLabel booksPathLabel;
    private JButton clearCacheButton;
    private final CacheSettings settings;
    private final NotificationService notificationService;

    public CacheConfigurable() {
        this.settings = ApplicationManager.getApplication().getService(CacheSettings.class);
        this.notificationService = ApplicationManager.getApplication().getService(NotificationService.class);
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Private Reader 缓存设置";
    }

    @Override
    public @Nullable JComponent createComponent() {
        enableCacheCheckBox = new JBCheckBox("启用章节缓存");

        SpinnerNumberModel sizeModel = new SpinnerNumberModel(100, 10, 10000, 10);
        maxCacheSizeSpinner = new JSpinner(sizeModel);

        SpinnerNumberModel ageModel = new SpinnerNumberModel(7, 1, 365, 1);
        maxCacheAgeSpinner = new JSpinner(ageModel);

        // 预加载设置
        enablePreloadCheckBox = new JBCheckBox("启用章节预加载");

        SpinnerNumberModel countModel = new SpinnerNumberModel(3, 1, 10, 1);
        preloadCountSpinner = new JSpinner(countModel);

        SpinnerNumberModel delayModel = new SpinnerNumberModel(500, 100, 5000, 100);
        preloadDelaySpinner = new JSpinner(delayModel);

        // 获取存储管理器
        StorageManager storageManager = null;
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        if (openProjects.length > 0) {
            storageManager = openProjects[0].getService(StorageManager.class);
        }

        // 缓存路径显示
        String cachePath = storageManager != null ? storageManager.getCachePath() : settings.getCacheDirectoryPath();
        String shortCachePath = getShortPath(cachePath);
        cachePathLabel = createPathLabel(shortCachePath, cachePath, true);

        // 打开缓存目录的链接
        LinkLabel<String> openCacheLink = new LinkLabel<>("打开缓存目录", null);
        openCacheLink.setListener((source, data) -> {
            File cacheDir = new File(cachePath);
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            openDirectory(cacheDir);
        }, null);

        // 书架数据存储路径显示
        final String[] booksPathHolder = new String[1];
        booksPathHolder[0] = "未知";

        if (openProjects.length > 0) {
            if (storageManager != null) {
                booksPathHolder[0] = storageManager.getBooksFilePath();
                LOG.info("从StorageManager获取书籍数据路径: " + booksPathHolder[0]);
            } else {
                // 尝试从RepositoryModule获取
                RepositoryModule repositoryModule = RepositoryModule.getInstance();
                if (repositoryModule != null) {
                    BookRepository bookRepository = repositoryModule.getBookRepository();
                    booksPathHolder[0] = getBookDataPath();
                    LOG.info("获取书籍数据路径: " + booksPathHolder[0]);
                } else {
                    booksPathHolder[0] = getBookDataPath();
                    LOG.info("获取书籍数据路径(回退): " + booksPathHolder[0]);
                }
            }
        }

        // 检查书籍数据文件是否存在
        File booksFile = new File(booksPathHolder[0]);
        boolean booksFileExists = booksFile.exists() && booksFile.isFile();
        String shortBooksPath = getShortPath(booksPathHolder[0]);

        if (booksFileExists) {
            long fileSize = booksFile.length();
            shortBooksPath += " (" + formatFileSize(fileSize) + ")";
        }

        booksPathLabel = createPathLabel(shortBooksPath, booksPathHolder[0], booksFileExists);

        // 打开书籍数据目录的链接
        LinkLabel<String> openBooksLink = new LinkLabel<>("打开书籍数据目录", null);
        openBooksLink.setListener((source, data) -> {
            try {
                if (booksFileExists) {
                    openDirectory(booksFile.getParentFile());
                } else {
                    Messages.showWarningDialog("书籍数据文件不存在", "警告");
                }
            } catch (Exception e) {
                LOG.error("打开书籍数据目录失败: " + e.getMessage(), e);
                notificationService.showError("打开书籍数据目录失败", e.getMessage());
            }
        }, null);

        // 复制缓存路径按钮
        JButton copyCachePathButton = new JButton("复制路径");
        copyCachePathButton.addActionListener(e -> copyToClipboard(cachePath));

        // 复制书籍数据路径按钮
        JButton copyBooksPathButton = new JButton("复制路径");
        copyBooksPathButton.addActionListener(e -> copyToClipboard(booksPathHolder[0]));

        // 清理缓存按钮
        clearCacheButton = new JButton("清理缓存");
        clearCacheButton.addActionListener(e -> {
            int result = Messages.showYesNoDialog(
                "确定要清理所有缓存吗？这将删除所有缓存的章节内容。",
                "清理缓存",
                Messages.getQuestionIcon()
            );
            if (result == Messages.YES) {
                try {
                    Project[] projects = ProjectManager.getInstance().getOpenProjects();
                    if (projects.length > 0) {
                        // 尝试使用Repository接口
                        RepositoryModule repositoryModule = RepositoryModule.getInstance();
                        if (repositoryModule != null) {
                            ChapterCacheRepository cacheRepository = repositoryModule.getChapterCacheRepository();
                            if (cacheRepository != null) {
                                cacheRepository.clearAllCache();
                                Messages.showInfoMessage("缓存已清理完成", "清理缓存");
                                return;
                            }
                        }

                        notificationService.showError("无法清理缓存", "章节缓存仓库未初始化");
                    } else {
                        notificationService.showError("无法清理缓存", "没有打开的项目");
                    }
                } catch (Exception ex) {
                    LOG.error("清理缓存失败: " + ex.getMessage(), ex);
                    notificationService.showError("清理缓存失败", ex.getMessage());
                }
            }
        });

        // 缓存路径面板
        JPanel cachePathPanel = new JPanel(new BorderLayout());
        cachePathPanel.add(cachePathLabel, BorderLayout.CENTER);
        cachePathPanel.add(copyCachePathButton, BorderLayout.EAST);

        // 书籍数据路径面板
        JPanel booksPathPanel = new JPanel(new BorderLayout());
        booksPathPanel.add(booksPathLabel, BorderLayout.CENTER);
        booksPathPanel.add(copyBooksPathButton, BorderLayout.EAST);

        // 构建表单
        mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("启用缓存:", enableCacheCheckBox)
            .addLabeledComponent("最大缓存大小 (MB):", maxCacheSizeSpinner)
            .addLabeledComponent("缓存过期时间 (天):", maxCacheAgeSpinner)
            .addSeparator(10)
            .addLabeledComponent("预加载设置:", enablePreloadCheckBox)
            .addLabeledComponent("预加载章节数:", preloadCountSpinner)
            .addLabeledComponent("预加载延迟 (毫秒):", preloadDelaySpinner)
            .addSeparator(10)
            .addLabeledComponent("缓存目录:", cachePathPanel)
            .addComponentToRightColumn(openCacheLink)
            .addLabeledComponent("书籍数据:", booksPathPanel)
            .addComponentToRightColumn(openBooksLink)
                .addComponentFillVertically(new JPanel(), 0)
            .addComponent(clearCacheButton)
                .getPanel();

        mainPanel.setBorder(JBUI.Borders.empty(10));

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        return settings.isEnableCache() != enableCacheCheckBox.isSelected() ||
               settings.getMaxCacheSize() != (Integer) maxCacheSizeSpinner.getValue() ||
               settings.getMaxCacheAge() != (Integer) maxCacheAgeSpinner.getValue() ||
               settings.isEnablePreload() != enablePreloadCheckBox.isSelected() ||
               settings.getPreloadCount() != (Integer) preloadCountSpinner.getValue() ||
               settings.getPreloadDelay() != (Integer) preloadDelaySpinner.getValue();
    }

    @Override
    public void apply() throws ConfigurationException {
        LOG.info("[配置诊断] CacheConfigurable.apply(): 开始应用缓存设置");

        boolean oldEnableCache = settings.isEnableCache();
        int oldMaxCacheSize = settings.getMaxCacheSize();
        int oldMaxCacheAge = settings.getMaxCacheAge();
        boolean oldEnablePreload = settings.isEnablePreload();
        int oldPreloadCount = settings.getPreloadCount();
        int oldPreloadDelay = settings.getPreloadDelay();

        boolean newEnableCache = enableCacheCheckBox.isSelected();
        int newMaxCacheSize = (Integer) maxCacheSizeSpinner.getValue();
        int newMaxCacheAge = (Integer) maxCacheAgeSpinner.getValue();
        boolean newEnablePreload = enablePreloadCheckBox.isSelected();
        int newPreloadCount = (Integer) preloadCountSpinner.getValue();
        int newPreloadDelay = (Integer) preloadDelaySpinner.getValue();

        LOG.info("[配置诊断] CacheConfigurable.apply(): 旧值: enableCache=" + oldEnableCache +
                ", maxCacheSize=" + oldMaxCacheSize + ", maxCacheAge=" + oldMaxCacheAge +
                ", enablePreload=" + oldEnablePreload + ", preloadCount=" + oldPreloadCount +
                ", preloadDelay=" + oldPreloadDelay);

        LOG.info("[配置诊断] CacheConfigurable.apply(): 新值: enableCache=" + newEnableCache +
                ", maxCacheSize=" + newMaxCacheSize + ", maxCacheAge=" + newMaxCacheAge +
                ", enablePreload=" + newEnablePreload + ", preloadCount=" + newPreloadCount +
                ", preloadDelay=" + newPreloadDelay);

        // 设置新值
        settings.setEnableCache(newEnableCache);
        settings.setMaxCacheSize(newMaxCacheSize);
        settings.setMaxCacheAge(newMaxCacheAge);
        settings.setEnablePreload(newEnablePreload);
        settings.setPreloadCount(newPreloadCount);
        settings.setPreloadDelay(newPreloadDelay);

        // 强制保存设置
        settings.saveSettings();
        LOG.info("[配置诊断] CacheConfigurable.apply(): 已保存缓存设置");

        // 如果设置发生变化，发布事件
        boolean settingsChanged = oldEnableCache != newEnableCache ||
                                 oldMaxCacheSize != newMaxCacheSize ||
                                 oldMaxCacheAge != newMaxCacheAge ||
                                 oldEnablePreload != newEnablePreload ||
                                 oldPreloadCount != newPreloadCount ||
                                 oldPreloadDelay != newPreloadDelay;

        if (settingsChanged) {
            LOG.info("[配置诊断] CacheConfigurable.apply(): 设置发生变化，发布事件");
            try {
                ApplicationManager.getApplication()
                    .getMessageBus()
                    .syncPublisher(CacheSettingsListener.TOPIC)
                    .cacheSettingsChanged(settings);
                LOG.info("[配置诊断] CacheConfigurable.apply(): 成功发布缓存设置变更事件");
            } catch (Exception e) {
                LOG.error("[配置诊断] CacheConfigurable.apply(): 发布缓存设置变更事件失败", e);
            }
        } else {
            LOG.info("[配置诊断] CacheConfigurable.apply(): 设置未变化，不发布事件");
        }
    }

    @Override
    public void reset() {
        enableCacheCheckBox.setSelected(settings.isEnableCache());
        maxCacheSizeSpinner.setValue(settings.getMaxCacheSize());
        maxCacheAgeSpinner.setValue(settings.getMaxCacheAge());
        enablePreloadCheckBox.setSelected(settings.isEnablePreload());
        preloadCountSpinner.setValue(settings.getPreloadCount());
        preloadDelaySpinner.setValue(settings.getPreloadDelay());

        // 更新启用状态
        preloadCountSpinner.setEnabled(enablePreloadCheckBox.isSelected());
        preloadDelaySpinner.setEnabled(enablePreloadCheckBox.isSelected());
    }

    /**
     * 安全地打开目录
     * @param directory 要打开的目录
     */
    private void openDirectory(File directory) {
        try {
            // 检查目录是否存在
            if (!directory.exists()) {
                LOG.info("目录不存在，尝试创建: " + directory);
                boolean created = directory.mkdirs();
                LOG.info("目录创建结果: " + created);
            }

            // 检查是否是目录
            if (!directory.isDirectory()) {
                LOG.warn("路径不是目录: " + directory);
                directory = directory.getParentFile();
                LOG.info("使用父目录: " + directory);
            }

            // 尝试使用Desktop API打开
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                LOG.info("使用Desktop API打开目录: " + directory);
                Desktop.getDesktop().open(directory);
                return;
            }

            // 根据操作系统使用不同的命令
            String os = System.getProperty("os.name").toLowerCase();
            String[] command;

            if (os.contains("win")) {
                command = new String[]{"explorer", directory.getAbsolutePath()};
            } else if (os.contains("mac")) {
                command = new String[]{"open", directory.getAbsolutePath()};
            } else if (os.contains("nix") || os.contains("nux")) {
                command = new String[]{"xdg-open", directory.getAbsolutePath()};
            } else {
                // 最后尝试使用BrowserUtil
                LOG.info("使用BrowserUtil打开目录: " + directory);
                BrowserUtil.browse(directory);
                return;
            }

            LOG.info("使用命令打开目录: " + String.join(" ", command));
            Runtime.getRuntime().exec(command);

        } catch (Exception e) {
            LOG.error("打开目录失败: " + e.getMessage(), e);
            notificationService.showError("打开目录失败", e.getMessage());

            // 最后尝试使用BrowserUtil作为备选方案
            try {
                LOG.info("尝试使用BrowserUtil作为备选方案");
                BrowserUtil.browse(directory);
            } catch (Exception ex) {
                LOG.error("使用BrowserUtil打开目录失败: " + ex.getMessage(), ex);
            }
        }
    }

    /**
     * 将长路径转换为更短的显示形式
     * @param path 完整路径
     * @return 缩短后的路径
     */
    private String getShortPath(String path) {
        if (path == null || path.isEmpty()) {
            return "未知路径";
        }

        // 替换用户主目录为 ~
        String userHome = System.getProperty("user.home");
        if (path.startsWith(userHome)) {
            path = "~" + path.substring(userHome.length());
        }

        // 如果路径太长，截断中间部分
        if (path.length() > 50) {
            int start = 20;
            int end = path.length() - 25;
            path = path.substring(0, start) + "..." + path.substring(end);
        }

        return path;
    }

    /**
     * 格式化文件大小为人类可读形式
     * @param size 文件大小（字节）
     * @return 格式化后的大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else {
            return String.format("%.2f MB", size / (1024.0 * 1024.0));
        }
    }

    /**
     * 将文本复制到剪贴板
     * @param text 要复制的文本
     */
    private void copyToClipboard(String text) {
        try {
            StringSelection selection = new StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
            Messages.showInfoMessage("路径已复制到剪贴板", "复制成功");
        } catch (Exception e) {
            LOG.error("复制到剪贴板失败: " + e.getMessage(), e);
            notificationService.showError("复制到剪贴板失败", e.getMessage());
        }
    }

    /**
     * 创建带有图标的路径标签
     * @param displayText 显示文本
     * @param fullPath 完整路径（用于工具提示）
     * @param exists 文件或目录是否存在
     * @return 格式化的标签
     */
    private JLabel createPathLabel(String displayText, String fullPath, boolean exists) {
        JLabel label = new JLabel(displayText);

        // 设置图标
        Icon icon;
        if (exists) {
            // 使用文件夹图标
            icon = AllIcons.Nodes.Folder;
        } else {
            // 使用警告图标
            icon = AllIcons.General.Warning;
        }
        label.setIcon(icon);

        // 设置工具提示
        label.setToolTipText("<html><b>完整路径:</b><br>" + fullPath + "</html>");

        // 设置样式
        label.setForeground(JBUI.CurrentTheme.Link.Foreground.ENABLED);
        label.setBorder(JBUI.Borders.empty(2));

        return label;
    }

    private String getBookDataPath() {
        String defaultPath = new File(System.getProperty("user.home"), ".private-reader/books/index.json").getAbsolutePath();
        try {
            // 尝试从RepositoryModule获取
            RepositoryModule repositoryModule = RepositoryModule.getInstance();
            if (repositoryModule != null) {
                StorageRepository storageRepository = repositoryModule.getStorageRepository();
                if (storageRepository != null) {
                    String path = storageRepository.getBooksFilePath();
                    LOG.debug("从 StorageRepository 获取书籍数据路径: " + path);
                    return path;
                } else {
                     LOG.warn("未能从 RepositoryModule 获取 StorageRepository");
                }
            } else {
                 LOG.warn("未能获取 RepositoryModule 实例");
            }
        } catch (Exception e) {
            LOG.error("从 RepositoryModule 获取书籍数据路径时出错: " + e.getMessage(), e);
        }

        // 回退到默认路径
        LOG.warn("无法通过 RepositoryModule 获取书籍数据路径，返回默认路径: " + defaultPath);
        return defaultPath;
    }
}