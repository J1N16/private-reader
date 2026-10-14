package com.lv.tool.privatereader.exception;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.lv.tool.privatereader.config.PrivateReaderConfig;
import com.lv.tool.privatereader.storage.StorageManager;
import com.lv.tool.privatereader.util.NetworkUtils;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 异常处理器
 * 统一处理插件中的异常
 */
public class ExceptionHandler {
    private static final Logger LOG = Logger.getInstance(ExceptionHandler.class);
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    /**
     * 自动恢复所需的外部环境抽象(V28)。
     *
     * <p>把「网络可用性」「存储服务」「通知展示」三处容器/外部依赖收敛为一个可注入接口,
     * 使恢复分支可在 IntelliJ 容器外确定性测试;生产实现见 {@link #DEFAULT_RECOVERY_HOST}。</p>
     */
    interface RecoveryHost {
        boolean isNetworkAvailable();

        /** 存储根目录路径;服务不可用时返回 {@code null}。 */
        @Nullable Path storageBasePath();

        /** 缓存目录路径;服务不可用时返回 {@code null}。 */
        @Nullable Path storageCachePath();

        void showNotification(@Nullable Project project, @NotNull String title, @NotNull String content);
    }

    /**
     * 生产环境默认实现:网络用 {@link NetworkUtils}、存储用 {@code StorageManager} 服务、
     * 通知用 IntelliJ 通知组。行为与拆分前完全一致。
     */
    static final RecoveryHost DEFAULT_RECOVERY_HOST = new RecoveryHost() {
        @Override
        public boolean isNetworkAvailable() {
            return NetworkUtils.isNetworkAvailable();
        }

        @Override
        public @Nullable Path storageBasePath() {
            StorageManager storageManager = ApplicationManager.getApplication().getService(StorageManager.class);
            return storageManager == null ? null : Path.of(storageManager.getBaseStoragePath());
        }

        @Override
        public @Nullable Path storageCachePath() {
            StorageManager storageManager = ApplicationManager.getApplication().getService(StorageManager.class);
            return storageManager == null ? null : Path.of(storageManager.getCachePath());
        }

        @Override
        public void showNotification(@Nullable Project project, @NotNull String title, @NotNull String content) {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID)
                    .createNotification(content, NotificationType.INFORMATION)
                    .setTitle(title)
                    .notify(project);
        }
    };

    /**
     * 处理异常
     */
    public static void handle(Project project, PrivateReaderException exception) {
        // 记录日志
        LOG.error(exception);

        // 根据异常类型处理
        String title = getErrorTitle(exception.getType());
        String content = getErrorMessage(exception);

        // 显示通知
        showErrorNotification(project, title, content);

        // 对特定类型的异常进行自动恢复
        handleRecovery(project, exception);
    }

    /**
     * 处理普通异常
     */
    public static void handle(Project project, Throwable exception, String message) {
        // 转换为PrivateReaderException
        PrivateReaderException preaderException = convertException(exception, message);
        handle(project, preaderException);
    }

    /**
     * 响应式重试操作（接收 Single 返回值的异步操作）
     *
     * @param <T> 返回类型
     * @param operation 要执行的操作
     * @param project 项目
     * @param operationName 操作名称（用于日志）
     * @param maxRetries 最大重试次数
     * @return 包含结果的Single
     */
    public static <T> Single<T> retryReactive(
            Supplier<Single<T>> operation,
            Project project,
            String operationName,
            int maxRetries) {

        return operation.get()
            .onErrorResumeNext(e -> {
                LOG.warn(String.format("操作'%s'失败，开始重试", operationName), e);
                return Single.error(e);
            })
            .retryWhen(errors -> {
                AtomicInteger retryCount = new AtomicInteger(0);
                return errors.flatMapSingle(error -> {
                    if (error instanceof PrivateReaderException) {
                        return Single.error(error);
                    }
                    if (retryCount.incrementAndGet() <= maxRetries) {
                        LOG.info(String.format("操作'%s'失败，准备第%d次重试", operationName, retryCount.get()));
                        return Single.timer(RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
                    }
                    return Single.error(error);
                });
            })
            .onErrorResumeNext(e -> {
                if (e instanceof PrivateReaderException) {
                    return Single.error(e);
                }
                return Single.error(new PrivateReaderException(
                    String.format("操作'%s'失败，已重试%d次", operationName, maxRetries),
                    e,
                    determineExceptionType(e)
                ));
            })
            .doOnError(e -> handle(project, (Throwable) e, String.format("操作'%s'失败", operationName)));
    }

    /**
     * 响应式重试操作（接收同步 Supplier 的便捷方法）
     *
     * @param <T> 返回类型
     * @param operation 要执行的操作（同步返回值）
     * @param project 项目
     * @param operationName 操作名称（用于日志）
     * @param maxRetries 最大重试次数
     * @return 包含结果的Single
     */
    public static <T> Single<T> retryReactive(
            Supplier<T> operation,
            Project project,
            String operationName,
            int maxRetries,
            boolean sync) {

        return Single.fromCallable(operation::get)
            .subscribeOn(Schedulers.io())
            .doOnError(e -> LOG.error(String.format("操作'%s'失败: %s", operationName, e.getMessage()), e))
            .retryWhen(errors -> {
                AtomicInteger retryCount = new AtomicInteger(0);
                return errors.flatMapSingle(error -> {
                    if (retryCount.incrementAndGet() <= maxRetries) {
                        LOG.info(String.format("操作'%s'失败，准备第%d次重试", operationName, retryCount.get()));
                        long delay = (long) (RETRY_DELAY_MS * (1 + Math.random() * 0.3));
                        return Single.timer(delay, TimeUnit.MILLISECONDS);
                    }
                    return Single.error(new PrivateReaderException(
                        String.format("操作'%s'失败，已重试%d次", operationName, maxRetries),
                        error,
                        determineExceptionType(error)
                    ));
                });
            })
            .doOnSuccess(result -> LOG.info(String.format("操作'%s'成功完成", operationName)));
    }

    /**
     * 响应式重试操作（使用默认的最大重试次数）
     */
    public static <T> Single<T> retryReactive(
            Supplier<Single<T>> operation,
            Project project,
            String operationName) {
        return retryReactive(operation, project, operationName, MAX_RETRIES);
    }

    private static PrivateReaderException convertException(Throwable exception, String message) {
        PrivateReaderException.ExceptionType type = determineExceptionType(exception);
        return new PrivateReaderException(message, exception, type);
    }

    /**
     * 根据异常类型推断统一的 ExceptionType(包级可见供测试)。
     */
    static PrivateReaderException.ExceptionType determineExceptionType(Throwable exception) {
        if (exception instanceof SocketTimeoutException) {
            return PrivateReaderException.ExceptionType.NETWORK_TIMEOUT;
        } else if (exception instanceof ConnectException) {
            return PrivateReaderException.ExceptionType.NETWORK_CONNECTION_REFUSED;
        } else if (exception instanceof UnknownHostException) {
            return PrivateReaderException.ExceptionType.NETWORK_ERROR;
        } else if (exception instanceof SSLException) {
            return PrivateReaderException.ExceptionType.NETWORK_SSL_ERROR;
        } else if (exception instanceof IOException) {
            return PrivateReaderException.ExceptionType.STORAGE_ERROR;
        }
        return PrivateReaderException.ExceptionType.UNKNOWN_ERROR;
    }

    private static void handleRecovery(Project project, PrivateReaderException exception) {
        handleRecovery(project, exception, DEFAULT_RECOVERY_HOST);
    }

    /** 处理自动恢复(环境由调用方注入,便于容器外测试)。 */
    static void handleRecovery(Project project, PrivateReaderException exception, RecoveryHost host) {
        switch (exception.getType()) {
            case NETWORK_ERROR:
            case NETWORK_TIMEOUT:
            case NETWORK_CONNECTION_REFUSED:
                handleNetworkRecovery(project, host);
                break;
            case STORAGE_ERROR:
                handleStorageRecovery(project, host);
                break;
            case PARSE_ERROR:
                handleParseRecovery(project, host);
                break;
        }
    }

    private static void handleNetworkRecovery(Project project, RecoveryHost host) {
        if (host.isNetworkAvailable()) {
            host.showNotification(project, "网络连接已恢复", "正在重新加载数据...");
        } else {
            host.showNotification(project, "网络连接不可用", "请检查网络连接后重试");
        }
    }

    private static void handleStorageRecovery(Project project, RecoveryHost host) {
        try {
            Path basePath = host.storageBasePath();
            if (basePath == null) {
                host.showNotification(project, "存储错误", "存储服务不可用,请重启IDE后重试");
                return;
            }

            // 检查存储根目录是否可写
            if (!Files.isWritable(basePath)) {
                host.showNotification(project, "存储错误", "存储目录不可写,请检查磁盘空间和权限: " + basePath);
                return;
            }

            // 确保缓存目录存在,清理可能损坏的过期缓存
            Path cachePath = host.storageCachePath();
            if (cachePath != null && !Files.exists(cachePath)) {
                Files.createDirectories(cachePath);
                LOG.info("存储恢复:重建缓存目录 " + cachePath);
            }

            host.showNotification(project, "存储已恢复", "存储目录检查通过,请重试操作");
        } catch (Exception e) {
            LOG.warn("存储恢复失败: " + e.getMessage(), e);
            host.showNotification(project, "存储错误", "自动恢复失败,请检查磁盘空间和权限");
        }
    }

    private static void handleParseRecovery(Project project, RecoveryHost host) {
        // 解析失败多为网页结构变更或编码问题,无安全可靠的自动修复方案。
        // 如实提示用户,避免"正在修复"的虚假承诺。
        host.showNotification(project, "解析错误", "章节解析失败,可能是网页结构变更或编码问题。请稍后重试,或刷新章节列表 / 切换书源");
    }

    private static void showRecoveryNotification(Project project, String title, String content) {
        DEFAULT_RECOVERY_HOST.showNotification(project, title, content);
    }

    static String getErrorMessage(PrivateReaderException exception) {
        String baseMessage = exception.getMessage();
        switch (exception.getType()) {
            case NETWORK_ERROR:
                return String.format("网络连接失败: %s\n请检查网络连接后重试", baseMessage);
            case NETWORK_TIMEOUT:
                return String.format("网络请求超时: %s\n请稍后重试", baseMessage);
            case NETWORK_CONNECTION_REFUSED:
                return String.format("连接被拒绝: %s\n服务器可能暂时不可用", baseMessage);
            case NETWORK_SSL_ERROR:
                return String.format("SSL/TLS错误: %s\n请检查网络安全设置", baseMessage);
            case PARSE_ERROR:
                return String.format("解析失败: %s\n正在尝试使用备用方案", baseMessage);
            case STORAGE_ERROR:
                return String.format("存储错误: %s\n请检查磁盘空间和权限", baseMessage);
            default:
                return baseMessage;
        }
    }

    static String getErrorTitle(PrivateReaderException.ExceptionType type) {
        switch (type) {
            case NETWORK_ERROR:
            case NETWORK_TIMEOUT:
            case NETWORK_CONNECTION_REFUSED:
            case NETWORK_SSL_ERROR:
                return "网络错误";
            case PARSE_ERROR:
            case HTML_PARSE_ERROR:
            case JSON_PARSE_ERROR:
                return "解析错误";
            case STORAGE_ERROR:
            case FILE_READ_ERROR:
            case FILE_WRITE_ERROR:
                return "存储错误";
            case CONFIG_ERROR:
                return "配置错误";
            case PERMISSION_ERROR:
                return "权限错误";
            case RESOURCE_NOT_FOUND:
                return "资源不存在";
            case CONCURRENCY_ERROR:
                return "并发错误";
            default:
                return "未知错误";
        }
    }

    private static void showErrorNotification(Project project, String title, String content) {
        try {
            Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID)
                    .createNotification(content, NotificationType.ERROR)
                    .setTitle(title);

            notification.notify(project);
        } catch (Exception e) {
            // 如果通知显示失败，只记录日志
            LOG.error("无法显示错误通知", e);
        }
    }
}
