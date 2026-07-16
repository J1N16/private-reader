package com.lv.tool.privatereader.util;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 网络性能监控器
 * 用于收集和分析网络请求的性能指标
 */
@Service(Service.Level.APP)
public final class NetworkPerformanceMonitor implements Disposable {
    private static final Logger LOG = Logger.getInstance(NetworkPerformanceMonitor.class);
    
    // 性能指标统计
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong timeoutRequests = new AtomicLong(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final AtomicLong totalDataSize = new AtomicLong(0);
    
    // 响应时间统计
    private final AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxResponseTime = new AtomicLong(0);
    
    // 错误统计
    private final ConcurrentHashMap<String, AtomicInteger> errorTypes = new ConcurrentHashMap<>();
    
    // 域名性能统计
    private final ConcurrentHashMap<String, DomainStats> domainStats = new ConcurrentHashMap<>();
    
    // 最近请求记录
    private final AtomicReference<RequestRecord> lastRequest = new AtomicReference<>();
    private ScheduledFuture<?> reportTask;

    public NetworkPerformanceMonitor() {
        // 启动定期报告
        startPeriodicReporting();
    }
    
    public static NetworkPerformanceMonitor getInstance() {
        return ApplicationManager.getApplication().getService(NetworkPerformanceMonitor.class);
    }
    
    /**
     * 记录请求开始
     */
    public void recordRequestStart(String url, String domain) {
        long requestId = totalRequests.incrementAndGet();
        long startTime = System.currentTimeMillis();
        
        RequestRecord record = new RequestRecord(requestId, url, domain, startTime);
        lastRequest.set(record);
        
        // 更新域名统计
        domainStats.computeIfAbsent(domain, k -> new DomainStats()).recordRequest();
        
        LOG.debug("[性能监控] 请求开始 #" + requestId + ": " + domain);
    }
    
    /**
     * 记录请求成功
     */
    public void recordRequestSuccess(String url, String domain, long responseTime, long dataSize) {
        successfulRequests.incrementAndGet();
        totalResponseTime.addAndGet(responseTime);
        totalDataSize.addAndGet(dataSize);
        
        // 更新响应时间统计
        updateResponseTimeStats(responseTime);
        
        // 更新域名统计
        DomainStats stats = domainStats.get(domain);
        if (stats != null) {
            stats.recordSuccess(responseTime, dataSize);
        }
        
        LOG.debug("[性能监控] 请求成功: " + domain + ", 响应时间: " + responseTime + "ms, 数据大小: " + dataSize + " 字节");
    }
    
    /**
     * 记录请求失败
     */
    public void recordRequestFailure(String url, String domain, long responseTime, String errorType, String errorMessage) {
        failedRequests.incrementAndGet();
        totalResponseTime.addAndGet(responseTime);
        
        // 更新响应时间统计
        updateResponseTimeStats(responseTime);
        
        // 更新错误类型统计
        errorTypes.computeIfAbsent(errorType, k -> new AtomicInteger(0)).incrementAndGet();
        
        // 更新域名统计
        DomainStats stats = domainStats.get(domain);
        if (stats != null) {
            stats.recordFailure(responseTime, errorType);
        }
        
        LOG.debug("[性能监控] 请求失败: " + domain + ", 响应时间: " + responseTime + "ms, 错误类型: " + errorType);
    }
    
    /**
     * 记录请求超时
     */
    public void recordRequestTimeout(String url, String domain, long timeoutDuration) {
        timeoutRequests.incrementAndGet();
        totalResponseTime.addAndGet(timeoutDuration);
        
        // 更新响应时间统计
        updateResponseTimeStats(timeoutDuration);
        
        // 更新错误类型统计
        errorTypes.computeIfAbsent("TIMEOUT", k -> new AtomicInteger(0)).incrementAndGet();
        
        // 更新域名统计
        DomainStats stats = domainStats.get(domain);
        if (stats != null) {
            stats.recordTimeout(timeoutDuration);
        }
        
        LOG.debug("[性能监控] 请求超时: " + domain + ", 超时时间: " + timeoutDuration + "ms");
    }
    
    /**
     * 更新响应时间统计
     */
    private void updateResponseTimeStats(long responseTime) {
        // 使用 accumulateAndGet 替代手动的 CAS 循环，内部实现可能更高效且代码更简洁
        minResponseTime.accumulateAndGet(responseTime, Math::min);
        maxResponseTime.accumulateAndGet(responseTime, Math::max);
    }
    
    /**
     * 获取性能统计报告
     */
    public String getPerformanceReport() {
        long total = totalRequests.get();
        if (total == 0) {
            return "暂无网络请求统计";
        }
        
        long success = successfulRequests.get();
        long failed = failedRequests.get();
        long timeout = timeoutRequests.get();
        long totalTime = totalResponseTime.get();
        long totalData = totalDataSize.get();
        
        double successRate = (double) success / total * 100;
        double avgResponseTime = (double) totalTime / total;
        double avgDataSize = total > 0 ? (double) totalData / total : 0;
        
        StringBuilder report = new StringBuilder();
        report.append("=== 网络性能统计报告 ===\n");
        report.append(String.format("总请求数: %d\n", total));
        report.append(String.format("成功率: %.1f%% (%d/%d)\n", successRate, success, total));
        report.append(String.format("失败数: %d\n", failed));
        report.append(String.format("超时数: %d\n", timeout));
        report.append(String.format("平均响应时间: %.1fms\n", avgResponseTime));
        report.append(String.format("最小响应时间: %dms\n", minResponseTime.get() == Long.MAX_VALUE ? 0 : minResponseTime.get()));
        report.append(String.format("最大响应时间: %dms\n", maxResponseTime.get()));
        report.append(String.format("平均数据大小: %.1f 字节\n", avgDataSize));
        
        // 错误类型统计
        if (!errorTypes.isEmpty()) {
            report.append("\n错误类型统计:\n");
            errorTypes.forEach((errorType, count) -> {
                report.append(String.format("  %s: %d\n", errorType, count.get()));
            });
        }
        
        // 域名性能统计
        if (!domainStats.isEmpty()) {
            report.append("\n域名性能统计:\n");
            domainStats.forEach((domain, stats) -> {
                report.append(String.format("  %s: 成功率 %.1f%%, 平均响应时间 %.1fms\n", 
                        domain, stats.getSuccessRate(), stats.getAverageResponseTime()));
            });
        }
        
        return report.toString();
    }
    
    /**
     * 获取简化的性能统计
     */
    public String getSimpleStats() {
        long total = totalRequests.get();
        if (total == 0) {
            return "暂无请求";
        }
        
        long success = successfulRequests.get();
        long totalTime = totalResponseTime.get();
        double successRate = (double) success / total * 100;
        double avgTime = (double) totalTime / total;
        
        return String.format("请求: %d, 成功率: %.1f%%, 平均耗时: %.1fms", 
                total, successRate, avgTime);
    }
    
    /**
     * 重置统计
     */
    public void resetStats() {
        totalRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);
        timeoutRequests.set(0);
        totalResponseTime.set(0);
        totalDataSize.set(0);
        minResponseTime.set(Long.MAX_VALUE);
        maxResponseTime.set(0);
        errorTypes.clear();
        domainStats.clear();
        lastRequest.set(null);
        
        LOG.info("[性能监控] 网络性能统计已重置");
    }
    
    /**
     * 启动定期报告
     */
    private void startPeriodicReporting() {
        reportTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            () -> {
                try {
                    logPerformanceReport();
                } catch (Exception e) {
                    LOG.error("[性能监控] 定期报告时发生错误", e);
                }
            },
            5,
            5,
            TimeUnit.MINUTES
        );
    }
    
    /**
     * 记录性能报告
     */
    private void logPerformanceReport() {
        long total = totalRequests.get();
        if (total > 0) {
            LOG.info("[性能监控] " + getSimpleStats());
        }
    }

    @Override
    public void dispose() {
        if (reportTask != null) {
            reportTask.cancel(false);
            reportTask = null;
        }
    }

    /**
     * 请求记录
     */
    private static class RequestRecord {
        final long requestId;
        final String url;
        final String domain;
        final long startTime;
        
        RequestRecord(long requestId, String url, String domain, long startTime) {
            this.requestId = requestId;
            this.url = url;
            this.domain = domain;
            this.startTime = startTime;
        }
    }
    
    /**
     * 域名统计
     */
    private static class DomainStats {
        private final AtomicInteger totalRequests = new AtomicInteger(0);
        private final AtomicInteger successfulRequests = new AtomicInteger(0);
        private final AtomicInteger failedRequests = new AtomicInteger(0);
        private final AtomicInteger timeoutRequests = new AtomicInteger(0);
        private final AtomicLong totalResponseTime = new AtomicLong(0);
        private final AtomicLong totalDataSize = new AtomicLong(0);
        
        void recordRequest() {
            totalRequests.incrementAndGet();
        }
        
        void recordSuccess(long responseTime, long dataSize) {
            successfulRequests.incrementAndGet();
            totalResponseTime.addAndGet(responseTime);
            totalDataSize.addAndGet(dataSize);
        }
        
        void recordFailure(long responseTime, String errorType) {
            failedRequests.incrementAndGet();
            totalResponseTime.addAndGet(responseTime);
        }
        
        void recordTimeout(long timeoutDuration) {
            timeoutRequests.incrementAndGet();
            totalResponseTime.addAndGet(timeoutDuration);
        }
        
        double getSuccessRate() {
            int total = totalRequests.get();
            return total > 0 ? (double) successfulRequests.get() / total * 100 : 0;
        }
        
        double getAverageResponseTime() {
            int total = totalRequests.get();
            return total > 0 ? (double) totalResponseTime.get() / total : 0;
        }
    }
} 