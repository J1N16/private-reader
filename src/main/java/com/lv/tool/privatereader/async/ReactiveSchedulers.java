package com.lv.tool.privatereader.async;

import com.intellij.openapi.application.ApplicationManager;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * 响应式调度器管理类
 * 优化和统一项目中的调度器使用
 *
 * 说明（2026-08-10 裁剪）：
 * 原实现暴露 IO/COMPUTE/BACKGROUND/TIMER/PLATFORM 五类调度器及监控线程，但全项目实际
 * 仅使用 {@link #io()} 与 {@link #runOnUI(Runnable)}（BACKGROUND 实为 Schedulers.io() 别名，
 * TIMER/PLATFORM/COMPUTE 无调用方，监控线程仅在 debug 下运行且无人消费）。
 * 故裁剪为最小可用集，删除冗余调度器、任务计数、监控线程与死方法，并移除 plugin.xml 中
 * 的 applicationService 注册（消除与手动单例并存的双实例问题）。
 */
public final class ReactiveSchedulers {
    // 单例实例
    private static final ReactiveSchedulers INSTANCE = new ReactiveSchedulers();

    // 调度器实例
    private final Scheduler ioScheduler;

    private ReactiveSchedulers() {
        // 创建IO调度器，用于网络请求和文件操作
        this.ioScheduler = Schedulers.io();
    }

    /**
     * 获取单例实例
     */
    public static ReactiveSchedulers getInstance() {
        return INSTANCE;
    }

    /**
     * 获取I/O调度器
     * 适用于网络请求、文件读写等I/O密集型操作
     */
    public Scheduler io() {
        return ioScheduler;
    }

    /**
     * 在UI线程上执行操作
     *
     * @param runnable 要执行的操作
     */
    public void runOnUI(Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(runnable);
    }
}
