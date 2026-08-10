package com.lv.tool.privatereader.util;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.io.HttpRequests;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeoutException;

/**
 * 安全的HTTP请求执行器
 * 用于执行HTTP请求，避免在ForkJoinPool中执行时出现SecurityException
 * 添加了重试机制和更详细的日志
 *
 * 说明（2026-08-10）：
 * 复用 IntelliJ 平台线程池（AppExecutorUtil.getAppExecutorService()）替代自建静态线程池，
 * 线程池生命周期随应用统一管理，避免插件卸载时线程池泄漏。移除无调用方的 shutdown() 与
 * getThreadPoolStatus() 线程池细节输出。
 */
@SuppressWarnings("deprecation")
public class SafeHttpRequestExecutor {
    private static final Logger LOG = Logger.getInstance(SafeHttpRequestExecutor.class);
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_RETRY_DELAY_MS = 1000;
    private static final int DEFAULT_CONNECT_TIMEOUT = 20000;  // 增加连接超时到20秒
    private static final int DEFAULT_READ_TIMEOUT = 25000;     // 增加读取超时到25秒
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    // 复用 IntelliJ 平台线程池，生命周期由应用统一管理
    private static final ExecutorService httpExecutor = AppExecutorUtil.getAppExecutorService();

    // 性能监控统计
    private static final AtomicLong totalRequests = new AtomicLong(0);
    private static final AtomicLong successfulRequests = new AtomicLong(0);
    private static final AtomicLong failedRequests = new AtomicLong(0);
    private static final AtomicLong totalRequestTime = new AtomicLong(0);

    /**
     * 安全地执行HTTP请求，带有重试机制
     *
     * @param url 请求的URL
     * @return HTTP响应内容
     * @throws IOException 如果请求失败或被中断
     */
    public static String executeGetRequest(final String url) throws IOException {
        return executeGetRequest(url, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS);
    }

    /**
     * 安全地执行HTTP请求，带有重试机制和自定义重试参数
     *
     * @param url 请求的URL
     * @param maxRetries 最大重试次数
     * @param retryDelayMs 重试延迟（毫秒）
     * @return HTTP响应内容
     * @throws IOException 如果请求失败或被中断
     */
    public static String executeGetRequest(final String url, final int maxRetries, final int retryDelayMs) throws IOException {
        long requestId = totalRequests.incrementAndGet();
        long startTime = System.currentTimeMillis();
        
        // 提取域名用于性能监控
        String domain = extractDomain(url);
        
        LOG.debug("[性能监控] 开始HTTP请求 #{}: {}，最大重试次数: {}", requestId, url, maxRetries);
        LOG.debug("[性能监控] 当前线程: {}, 线程ID: {}", Thread.currentThread().getName(), Thread.currentThread().threadId());

        // 记录请求开始
        NetworkPerformanceMonitor.getInstance().recordRequestStart(url, domain);

        IOException lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            long attemptStartTime = System.currentTimeMillis();
            
            if (attempt > 0) {
                LOG.debug("[性能监控] 第 {} 次重试请求 #{}: {}", attempt, requestId, url);
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOG.warn("[性能监控] 重试等待被中断 #" + requestId);
                    throw new IOException("重试等待被中断", ie);
                }
            }

            try {
                String result = executeHttpRequest(url, requestId, attempt);
                long totalTime = System.currentTimeMillis() - startTime;
                long attemptTime = System.currentTimeMillis() - attemptStartTime;
                
                successfulRequests.incrementAndGet();
                totalRequestTime.addAndGet(totalTime);
                
                // 记录请求成功
                NetworkPerformanceMonitor.getInstance().recordRequestSuccess(url, domain, totalTime, result != null ? result.length() : 0);
                
                LOG.debug("[性能监控] HTTP请求成功 #{}: {}，总耗时: {}ms，本次尝试耗时: {}ms，内容长度: {} 字节",
                        requestId, url, totalTime, attemptTime, result != null ? result.length() : 0);

                return result;
            } catch (IOException e) {
                long attemptTime = System.currentTimeMillis() - attemptStartTime;
                lastException = e;

                // 根据异常类型决定是否继续重试
                if (e instanceof UnknownHostException) {
                    LOG.warn("[性能监控] 无法解析主机名 #" + requestId + ": " + url + "，耗时: " + attemptTime + "ms", e);
                    NetworkPerformanceMonitor.getInstance().recordRequestFailure(url, domain, attemptTime, "UNKNOWN_HOST", e.getMessage());
                } else if (e instanceof SocketTimeoutException) {
                    LOG.warn("[性能监控] 请求超时 #" + requestId + ": " + url + "，耗时: " + attemptTime + "ms", e);
                    NetworkPerformanceMonitor.getInstance().recordRequestTimeout(url, domain, attemptTime);
                } else {
                    LOG.warn("[性能监控] 请求失败 #" + requestId + ": " + url + "，耗时: " + attemptTime + "ms，错误: " + e.getMessage(), e);
                    NetworkPerformanceMonitor.getInstance().recordRequestFailure(url, domain, attemptTime, "IO_ERROR", e.getMessage());
                }

                // 如果已经达到最大重试次数，抛出最后一个异常
                if (attempt == maxRetries) {
                    failedRequests.incrementAndGet();
                    long totalTime = System.currentTimeMillis() - startTime;
                    LOG.warn("[性能监控] 达到最大重试次数 (" + maxRetries + ") #" + requestId + ": " + url + "，总耗时: " + totalTime + "ms", e);
                    
                    // 记录性能统计
                    logPerformanceStats();
                    
                    throw new IOException("请求失败，已重试 " + maxRetries + " 次: " + e.getMessage(), e);
                }
            }
        }

        // 这里不应该到达，但为了编译器满意
        throw lastException != null ? lastException : new IOException("未知错误");
    }

    /**
     * 执行单个HTTP请求
     *
     * @param url 请求的URL
     * @param requestId 请求ID
     * @param attempt 尝试次数
     * @return HTTP响应内容
     * @throws IOException 如果请求失败或被中断
     */
    private static String executeHttpRequest(final String url, long requestId, int attempt) throws IOException {
        long httpStartTime = System.currentTimeMillis();
        LOG.debug("[性能监控] 执行HTTP请求 #" + requestId + " (尝试 " + (attempt + 1) + "): " + url);
        
        // 使用专用线程池而不是共享平台线程池
        Future<String> future = httpExecutor.submit(() -> {
            long threadStartTime = System.currentTimeMillis();
            LOG.debug("[性能监控] 线程池任务开始 #" + requestId + "，线程: " + Thread.currentThread().getName());
            
            try {
                String result = HttpRequests.request(url)
                        .userAgent(DEFAULT_USER_AGENT)
                        .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                        .readTimeout(DEFAULT_READ_TIMEOUT)
                        .forceHttps(false)
                        .connect(request -> {
                            long connectTime = System.currentTimeMillis() - threadStartTime;
                            LOG.debug("[性能监控] 连接成功 #" + requestId + "，连接耗时: " + connectTime + "ms，开始读取内容");
                            String content = request.readString();
                            long readTime = System.currentTimeMillis() - threadStartTime - connectTime;
                            LOG.debug("[性能监控] 内容读取完成 #" + requestId + "，读取耗时: " + readTime + "ms，内容长度: " + (content != null ? content.length() : 0) + " 字节");
                            return content;
                        });
                
                long totalThreadTime = System.currentTimeMillis() - threadStartTime;
                LOG.debug("[性能监控] 线程池任务完成 #" + requestId + "，总耗时: " + totalThreadTime + "ms");
                return result;
            } catch (Exception e) {
                long totalThreadTime = System.currentTimeMillis() - threadStartTime;
                LOG.warn("[性能监控] 线程池任务失败 #" + requestId + "，耗时: " + totalThreadTime + "ms", e);
                throw e;
            }
        });

        try {
            // 设置超时时间为18秒，确保不会卡死
            String result = future.get(18, TimeUnit.SECONDS);
            long totalHttpTime = System.currentTimeMillis() - httpStartTime;
            LOG.debug("[性能监控] HTTP请求成功完成 #" + requestId + "，总耗时: " + totalHttpTime + "ms");
            return result;
        } catch (InterruptedException e) {
            long totalHttpTime = System.currentTimeMillis() - httpStartTime;
            LOG.warn("[性能监控] 执行HTTP请求时线程被中断 #" + requestId + "，URL: " + url + "，耗时: " + totalHttpTime + "ms", e);
            Thread.currentThread().interrupt(); // 恢复中断状态
            throw new IOException("HTTP请求被中断: " + url, e);
        } catch (ExecutionException e) {
            long totalHttpTime = System.currentTimeMillis() - httpStartTime;
            Throwable cause = e.getCause();
            LOG.warn("[性能监控] 执行HTTP请求时发生错误 #" + requestId + "，URL: " + url + "，耗时: " + totalHttpTime + "ms，原因: " + (cause != null ? cause.getMessage() : "null"), e);

            if (cause instanceof IOException) {
                throw (IOException) cause;
            } else if (cause instanceof Exception) {
                throw new IOException("执行HTTP请求时发生内部错误: " + cause.getMessage(), cause);
            } else {
                throw new IOException("执行HTTP请求时发生未知错误", e);
            }
        } catch (TimeoutException e) {
            long totalHttpTime = System.currentTimeMillis() - httpStartTime;
            future.cancel(true); // 主动中断底层线程
            LOG.warn("[性能监控] HTTP请求超时(18秒)并已主动取消 #" + requestId + "，URL: " + url + "，耗时: " + totalHttpTime + "ms", e);
            throw new IOException("HTTP请求超时(18秒)并已主动取消: " + url, e);
        } catch (Exception e) {
            long totalHttpTime = System.currentTimeMillis() - httpStartTime;
            LOG.error("[性能监控] 获取HTTP请求结果时发生意外错误 #" + requestId + "，URL: " + url + "，耗时: " + totalHttpTime + "ms", e);
            throw new IOException("获取HTTP请求结果时发生意外错误: " + e.getMessage() + ", URL: " + url, e);
        }
    }

    /**
     * 记录性能统计信息
     */
    private static void logPerformanceStats() {
        if (!LOG.isDebugEnabled()) {
            return;
        }

        long total = totalRequests.get();
        long success = successfulRequests.get();
        long failed = failedRequests.get();
        long totalTime = totalRequestTime.get();

        if (total > 0) {
            double successRate = (double) success / total * 100;
            double avgTime = (double) totalTime / total;

            LOG.debug("[性能统计] 总请求数: {}，成功: {} ({}%)，失败: {}，平均耗时: {}ms",
                    total, success, String.format("%.1f", successRate), failed, String.format("%.1f", avgTime));
        }
    }

    /**
     * 获取性能统计信息
     */
    public static String getPerformanceStats() {
        long total = totalRequests.get();
        long success = successfulRequests.get();
        long failed = failedRequests.get();
        long totalTime = totalRequestTime.get();
        
        if (total == 0) {
            return "暂无请求统计";
        }
        
        double successRate = (double) success / total * 100;
        double avgTime = (double) totalTime / total;
        
        return String.format("总请求: %d, 成功: %d (%.1f%%), 失败: %d, 平均耗时: %.1fms", 
                total, success, successRate, failed, avgTime);
    }

    /**
     * 重置性能统计
     */
    public static void resetPerformanceStats() {
        totalRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);
        totalRequestTime.set(0);
        LOG.info("[性能监控] 性能统计已重置");
    }

    /**
     * 提取URL的域名
     */
    private static String extractDomain(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            return host != null ? host : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}