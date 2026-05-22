package com.lv.tool.privatereader.exception;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.lv.tool.privatereader.config.PrivateReaderConfig;
import com.lv.tool.privatereader.util.NetworkUtils;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import java.io.IOException;
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

    private static PrivateReaderException.ExceptionType determineExceptionType(Throwable exception) {
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
        switch (exception.getType()) {
            case NETWORK_ERROR:
            case NETWORK_TIMEOUT:
            case NETWORK_CONNECTION_REFUSED:
                handleNetworkRecovery(project);
                break;
            case STORAGE_ERROR:
                handleStorageRecovery(project);
                break;
            case PARSE_ERROR:
                handleParseRecovery(project);
                break;
        }
    }

    private static void handleNetworkRecovery(Project project) {
        if (NetworkUtils.isNetworkAvailable()) {
            showRecoveryNotification(project, "网络连接已恢复", "正在重新加载数据...");
        } else {
            showRecoveryNotification(project, "网络连接不可用", "请检查网络连接后重试");
        }
    }

    private static void handleStorageRecovery(Project project) {
        showRecoveryNotification(project, "存储错误", "正在尝试修复存储问题...");
        // TODO: 实现存储恢复逻辑
    }

    private static void handleParseRecovery(Project project) {
        showRecoveryNotification(project, "解析错误", "正在尝试使用备用解析方案...");
        // TODO: 实现解析恢复逻辑
    }

    private static void showRecoveryNotification(Project project, String title, String content) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(PrivateReaderConfig.NOTIFICATION_GROUP_ID)
                .createNotification(content, NotificationType.INFORMATION)
                .setTitle(title)
                .notify(project);
    }

    private static String getErrorMessage(PrivateReaderException exception) {
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

    private static String getErrorTitle(PrivateReaderException.ExceptionType type) {
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
