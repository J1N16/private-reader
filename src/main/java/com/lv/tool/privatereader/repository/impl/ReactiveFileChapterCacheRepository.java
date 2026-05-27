package com.lv.tool.privatereader.repository.impl;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.diagnostic.Logger;

/**
 * 响应式文件章节缓存仓库
 * 
 * 使用文件系统实现的响应式章节缓存仓库，为IntelliJ平台提供服务。
 * 扩展自ReactiveChapterCacheRepositoryImpl，提供平台所需的构造函数。
 */
public class ReactiveFileChapterCacheRepository extends ReactiveChapterCacheRepositoryImpl {
    private static final Logger LOG = Logger.getInstance(ReactiveFileChapterCacheRepository.class);
    
    /**
     * 构造函数，用于 IntelliJ 服务系统
     * 
     * @param application Application 实例
     */
    public ReactiveFileChapterCacheRepository(Application application) {
        super();
        LOG.info("通过 Application 初始化 ReactiveFileChapterCacheRepository");
    }
} 