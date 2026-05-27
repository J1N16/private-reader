package com.lv.tool.privatereader.async;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.intellij.openapi.diagnostic.Logger;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 响应式任务管理器
 * 使用RxJava3实现响应式异步任务处理
 */
public class ReactiveTaskManager {
    private static final Logger LOG = Logger.getInstance(ReactiveTaskManager.class);
    private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;
    private static final long DEFAULT_TIMEOUT_MS = 30000;

    // 限制任务指标Map的大小，防止因动态任务名导致的内存泄漏
    // 最多保留500个任务的指标，写入后24小时过期
    private final Cache<String, TaskInfo<?>> taskMetrics = CacheBuilder.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();

    private final Map<String, Disposable> runningTasks = new ConcurrentHashMap<>();
    private final Disposable monitorDisposable;

    private static class SingletonHolder {
        private static final ReactiveTaskManager INSTANCE = new ReactiveTaskManager();
    }

    public static ReactiveTaskManager getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private static class TaskInfo<T> {
        final AtomicLong totalExecutionTime = new AtomicLong();
        final AtomicInteger executionCount = new AtomicInteger();
        final AtomicInteger failureCount = new AtomicInteger();
        final AtomicInteger retryCount = new AtomicInteger();
        volatile Throwable lastError;
        volatile long lastExecutionTime;
        volatile long startTime;

        void recordExecution(long executionTime) {
            totalExecutionTime.addAndGet(executionTime);
            executionCount.incrementAndGet();
            lastExecutionTime = System.currentTimeMillis();
        }

        void recordFailure(Throwable error) {
            failureCount.incrementAndGet();
            lastError = error;
        }

        void recordRetry() {
            retryCount.incrementAndGet();
        }

        Map<String, Object> getMetrics() {
            return Map.of(
                "totalExecutionTime", totalExecutionTime.get(),
                "executionCount", executionCount.get(),
                "failureCount", failureCount.get(),
                "retryCount", retryCount.get(),
                "lastError", lastError != null ? lastError.getMessage() : "none",
                "lastExecutionTime", lastExecutionTime
            );
        }
    }

    private ReactiveTaskManager() {
        // 启动监控任务
        monitorDisposable = Observable.interval(1, TimeUnit.MINUTES)
            .doOnNext(tick -> monitorTasks())
            .subscribe();
    }

    /**
     * 提交任务并返回响应式Single
     * @param taskName 任务名称
     * @param task 任务供应商
     * @param options 任务选项
     * @return 包含任务结果的Single
     */
    public <T> Single<T> submitTask(String taskName, Supplier<T> task, TaskOptions options) {
        TaskInfo<T> taskInfo;
        try {
            @SuppressWarnings("unchecked")
            TaskInfo<T> cached = (TaskInfo<T>) taskMetrics.get(taskName, TaskInfo::new);
            taskInfo = cached;
        } catch (Exception e) {
            taskInfo = new TaskInfo<>();
        }
        final TaskInfo<T> finalTaskInfo = taskInfo;

        Single<T> taskSingle = Single.fromCallable(task::get)
            .subscribeOn(Schedulers.io())
            .doOnSubscribe(disposable -> {
                runningTasks.put(taskName, disposable);
                finalTaskInfo.startTime = System.currentTimeMillis();
                LOG.info("开始执行任务: " + taskName);
            })
            .doOnSuccess(result -> {
                long executionTime = System.currentTimeMillis() - finalTaskInfo.startTime;
                finalTaskInfo.recordExecution(executionTime);
                LOG.info("任务执行成功: " + taskName + "，耗时: " + executionTime + "ms");
            })
            .doOnError(error -> {
                finalTaskInfo.recordFailure(error);
                LOG.warn("任务执行失败: " + taskName + ", 错误: " + error.getMessage(), error);
                if (options.errorHandler != null) {
                    options.errorHandler.accept(error);
                }
            })
            .doFinally(() -> runningTasks.remove(taskName));

        // 添加重试逻辑
        if (options.maxRetries > 0) {
            taskSingle = taskSingle.retryWhen(errors -> {
                AtomicInteger retryCount = new AtomicInteger(0);
                return errors.flatMapSingle(error -> {
                    if (retryCount.incrementAndGet() <= options.maxRetries) {
                        finalTaskInfo.recordRetry();
                        LOG.warn("重试任务: " + taskName + ", 第" + retryCount.get() + "次");
                        return Single.timer(options.retryDelayMs, TimeUnit.MILLISECONDS);
                    }
                    return Single.error(error);
                });
            });
        }

        // 添加超时控制
        if (options.timeoutMs > 0) {
            taskSingle = taskSingle.timeout(options.timeoutMs, TimeUnit.MILLISECONDS);
        }

        return taskSingle;
    }

    /**
     * 使用默认选项提交任务
     * @param taskName 任务名称
     * @param task 任务供应商
     * @return 包含任务结果的Single
     */
    public <T> Single<T> submitTask(String taskName, Supplier<T> task) {
        return submitTask(taskName, task, new TaskOptions());
    }

    /**
     * 任务选项配置类
     */
    public static class TaskOptions {
        int priority = 0;
        int maxRetries = DEFAULT_MAX_RETRIES;
        long retryDelayMs = DEFAULT_RETRY_DELAY_MS;
        long timeoutMs = DEFAULT_TIMEOUT_MS;
        Consumer<Throwable> errorHandler = null;

        public TaskOptions setPriority(int priority) {
            this.priority = priority;
            return this;
        }

        public TaskOptions setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public TaskOptions setRetryDelay(long delayMs) {
            this.retryDelayMs = delayMs;
            return this;
        }

        public TaskOptions setTimeout(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public TaskOptions setErrorHandler(Consumer<Throwable> handler) {
            this.errorHandler = handler;
            return this;
        }
    }

    /**
     * 监控任务执行情况
     */
    private void monitorTasks() {
        try {
            int activeCount = runningTasks.size();

            LOG.info(String.format("Reactive Task Pool Status: active=%d", activeCount));

            taskMetrics.asMap().forEach((taskName, info) -> {
                LOG.info(String.format("Task '%s' metrics: %s", taskName, info.getMetrics()));
            });
        } catch (Exception e) {
            LOG.error("监控任务执行失败", e);
        }
    }

    /**
     * 取消正在执行的任务
     * @param taskName 任务名称
     * @return 是否成功取消
     */
    public boolean cancelTask(String taskName) {
        Disposable task = runningTasks.remove(taskName);
        if (task != null) {
            task.dispose();
            return true;
        }
        return false;
    }

    /**
     * 检查任务是否正在运行
     * @param taskName 任务名称
     * @return 是否正在运行
     */
    public boolean isTaskRunning(String taskName) {
        return runningTasks.containsKey(taskName);
    }

    /**
     * 取消以指定前缀开头的所有任务
     * @param prefix 任务名称前缀
     * @return 是否成功取消任何任务
     */
    public boolean cancelTasksByPrefix(String prefix) {
        boolean cancelled = false;
        for (String taskName : runningTasks.keySet()) {
            if (taskName.startsWith(prefix)) {
                Disposable task = runningTasks.remove(taskName);
                if (task != null) {
                    task.dispose();
                    cancelled = true;
                }
            }
        }
        return cancelled;
    }

    /**
     * 获取指定任务的度量信息
     * @param taskName 任务名称
     * @return 度量信息
     */
    public Map<String, Object> getTaskMetrics(String taskName) {
        TaskInfo<?> info = taskMetrics.getIfPresent(taskName);
        return info != null ? info.getMetrics() : Map.of();
    }

    /**
     * 获取所有任务的度量信息
     * @return 所有任务的度量信息
     */
    public Map<String, Map<String, Object>> getAllTaskMetrics() {
        Map<String, Map<String, Object>> allMetrics = new ConcurrentHashMap<>();
        taskMetrics.asMap().forEach((taskName, info) ->
            allMetrics.put(taskName, info.getMetrics()));
        return allMetrics;
    }

    /**
     * 关闭任务管理器
     */
    public void shutdown() {
        monitorDisposable.dispose();
        runningTasks.values().forEach(Disposable::dispose);
        runningTasks.clear();
    }
}
