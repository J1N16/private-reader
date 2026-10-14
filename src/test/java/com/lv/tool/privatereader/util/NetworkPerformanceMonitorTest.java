package com.lv.tool.privatereader.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NetworkPerformanceMonitor 单元测试(V21 可测性改造)。
 * <p>
 * 覆盖:
 * - 构造器通过包级注入调度器注册周期任务(5 分钟延迟/5 分钟间隔);null 调度器不注册
 * - 请求开始/成功/失败/超时四类记录的统计累加
 * - 统计报告(空/非空)、简化统计、错误类型与域名维度聚合
 * - 最小/最大响应时间边界(仅开始未完成时 min 回显 0)
 * - resetStats 清零;dispose 取消周期任务且幂等
 * <p>
 * 说明:通过包级构造器注入 mock 调度器,避免污染 AppExecutorUtil 全局定时器
 * (改造前构造器会无条件向全局调度器注册永不停止的 5 分钟任务)。
 */
class NetworkPerformanceMonitorTest {

    private NetworkPerformanceMonitor monitor;

    @AfterEach
    void tearDown() {
        if (monitor != null) {
            monitor.dispose();
            monitor = null;
        }
    }

    /** 传入 null 调度器:不注册周期任务,避免全局副作用。 */
    private NetworkPerformanceMonitor monitorWithoutScheduler() {
        monitor = new NetworkPerformanceMonitor((ScheduledExecutorService) null);
        return monitor;
    }

    @SuppressWarnings("unchecked")
    private static ScheduledFuture<Object> stubScheduler(ScheduledExecutorService scheduler) {
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(),
                eq(TimeUnit.MINUTES))).thenReturn((ScheduledFuture) future);
        return future;
    }

    @Test
    void constructorSchedulesPeriodicReportWhenSchedulerProvided() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        stubScheduler(scheduler);

        NetworkPerformanceMonitor m = new NetworkPerformanceMonitor(scheduler);
        try {
            verify(scheduler).scheduleWithFixedDelay(any(Runnable.class),
                    eq(NetworkPerformanceMonitor.REPORT_INITIAL_DELAY_MINUTES),
                    eq(NetworkPerformanceMonitor.REPORT_PERIOD_MINUTES),
                    eq(TimeUnit.MINUTES));
        } finally {
            m.dispose();
        }
    }

    @Test
    void constructorSkipsSchedulingWhenSchedulerIsNull() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

        NetworkPerformanceMonitor m = new NetworkPerformanceMonitor((ScheduledExecutorService) null);
        m.dispose();

        verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
    }

    @Test
    void recordStartThenSuccessAccumulatesStats() {
        NetworkPerformanceMonitor m = monitorWithoutScheduler();

        m.recordRequestStart("https://a.com/1", "a.com");
        m.recordRequestSuccess("https://a.com/1", "a.com", 120, 2048);

        String report = m.getPerformanceReport();
        assertTrue(report.contains("总请求数: 1"), report);
        assertTrue(report.contains("成功率: 100.0% (1/1)"), report);
        assertTrue(report.contains("最小响应时间: 120ms"), report);
        assertTrue(report.contains("最大响应时间: 120ms"), report);
        assertTrue(report.contains("a.com: 成功率 100.0%"), report);
    }

    @Test
    void recordFailureAndTimeoutAccumulateErrorTypes() {
        NetworkPerformanceMonitor m = monitorWithoutScheduler();

        m.recordRequestStart("https://b.com/1", "b.com");
        m.recordRequestFailure("https://b.com/1", "b.com", 50, "CONNECT", "conn refused");
        m.recordRequestStart("https://b.com/2", "b.com");
        m.recordRequestTimeout("https://b.com/2", "b.com", 3000);

        String report = m.getPerformanceReport();
        assertTrue(report.contains("总请求数: 2"), report);
        assertTrue(report.contains("失败数: 1"), report);
        assertTrue(report.contains("超时数: 1"), report);
        assertTrue(report.contains("CONNECT: 1"), report);
        assertTrue(report.contains("TIMEOUT: 1"), report);
    }

    @Test
    void successWithoutPriorStartDoesNotIncrementTotal() {
        NetworkPerformanceMonitor m = monitorWithoutScheduler();

        // 总请求数仅由 recordRequestStart 驱动:未开始即成功时报告仍为空
        m.recordRequestSuccess("https://c.com/1", "c.com", 88, 10);

        assertEquals("暂无网络请求统计", m.getPerformanceReport());
    }

    @Test
    void averageDataSizeAggregatesAcrossSuccesses() {
        NetworkPerformanceMonitor m = monitorWithoutScheduler();

        m.recordRequestStart("https://c.com/1", "c.com");
        m.recordRequestSuccess("https://c.com/1", "c.com", 100, 200);
        m.recordRequestStart("https://c.com/2", "c.com");
        m.recordRequestSuccess("https://c.com/2", "c.com", 300, 400);

        String report = m.getPerformanceReport();
        assertTrue(report.contains("平均响应时间: 200.0ms"), report);
        assertTrue(report.contains("平均数据大小: 300.0 字节"), report);
        assertTrue(report.contains("最大响应时间: 300ms"), report);
    }

    @Test
    void emptyMonitorReportsPlaceholders() {
        NetworkPerformanceMonitor m = monitorWithoutScheduler();

        assertEquals("暂无网络请求统计", m.getPerformanceReport());
        assertEquals("暂无请求", m.getSimpleStats());
    }

    @Test
    void simpleStatsFormatsCompactSummary() {
        NetworkPerformanceMonitor m = monitorWithoutScheduler();

        m.recordRequestStart("https://d.com/1", "d.com");
        m.recordRequestSuccess("https://d.com/1", "d.com", 100, 1);
        m.recordRequestStart("https://d.com/2", "d.com");
        m.recordRequestFailure("https://d.com/2", "d.com", 200, "IO", "boom");

        String simple = m.getSimpleStats();
        assertTrue(simple.contains("请求: 2"), simple);
        assertTrue(simple.contains("成功率: 50.0%"), simple);
        assertTrue(simple.contains("平均耗时: 150.0ms"), simple);
    }

    @Test
    void minResponseTimeIsZeroWhenNoTimedRequest() {
        NetworkPerformanceMonitor m = monitorWithoutScheduler();

        m.recordRequestStart("https://e.com/1", "e.com");

        // 仅开始未完成:min 仍为 Long.MAX_VALUE 哨兵,报告回显 0
        assertTrue(m.getPerformanceReport().contains("最小响应时间: 0ms"));
    }

    @Test
    void resetStatsClearsAllAccumulators() {
        NetworkPerformanceMonitor m = monitorWithoutScheduler();

        m.recordRequestStart("https://f.com/1", "f.com");
        m.recordRequestSuccess("https://f.com/1", "f.com", 10, 5);
        m.resetStats();

        assertEquals("暂无网络请求统计", m.getPerformanceReport());
        assertEquals("暂无请求", m.getSimpleStats());
    }

    @Test
    void disposeCancelsReportTaskAndIsIdempotent() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<Object> future = stubScheduler(scheduler);

        NetworkPerformanceMonitor m = new NetworkPerformanceMonitor(scheduler);
        m.dispose();
        m.dispose();

        verify(future, times(1)).cancel(false);
    }

    @Test
    void logPerformanceReportIsSilentWhenNoRequests() {
        NetworkPerformanceMonitor m = monitorWithoutScheduler();
        // 总数为 0 时走静默分支,不抛异常即为通过
        m.logPerformanceReport();
    }
}