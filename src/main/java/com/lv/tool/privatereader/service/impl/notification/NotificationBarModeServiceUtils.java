package com.lv.tool.privatereader.service.impl.notification;

import com.intellij.openapi.application.ApplicationManager;
import com.lv.tool.privatereader.settings.ReaderModeSettings;

/**
 * 通知栏模式相关工具类。
 * <p>
 * 承载「返回阅读器」等需要访问服务容器的模式切换逻辑,与纯展示的
 * {@link NotificationDisplayManager} 解耦:展示层只负责渲染与执行调用方注入的回调,
 * 不再直接依赖 IntelliJ 服务容器。
 */
public final class NotificationBarModeServiceUtils {

    private NotificationBarModeServiceUtils() {
    }

    /**
     * 从通知栏模式切回阅读器模式(在 UI 线程执行)。
     * <p>
     * 原实现位于 {@code NotificationDisplayManager.switchBackToReader()},V14 回调化改造
     * 后移入本类,保持行为等价。
     */
    public static void switchBackToReader() {
        ApplicationManager.getApplication().invokeLater(() -> {
            ReaderModeSettings settings = ApplicationManager.getApplication()
                    .getService(ReaderModeSettings.class);
            if (settings != null) {
                settings.setNotificationMode(false);
            }
        });
    }
}