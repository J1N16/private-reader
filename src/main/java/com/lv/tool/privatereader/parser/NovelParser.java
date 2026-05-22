package com.lv.tool.privatereader.parser;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.lv.tool.privatereader.model.Book;
import com.lv.tool.privatereader.repository.ChapterCacheRepository;
import com.lv.tool.privatereader.repository.RepositoryModule;
import com.intellij.util.xmlb.annotations.Tag;
import org.jetbrains.annotations.NotNull;
import com.google.gson.annotations.Expose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 小说解析器接口
 */
public interface NovelParser {
    // 定义LOG变量
    Logger LOG = LoggerFactory.getLogger(NovelParser.class);
    
    /**
     * 获取小说标题
     */
    String getTitle();

    /**
     * 获取作者
     */
    String getAuthor();

    /**
     * 获取章节列表
     * 优先使用缓存，缓存不存在或为空时才重新解析
     */
    default List<Chapter> getChapterList(Book book) {
        // 优先尝试使用缓存
        List<Chapter> cachedChapters = book.getCachedChapters();
        if (cachedChapters != null && !cachedChapters.isEmpty()) {
            return cachedChapters;
        }
        
        // 缓存不存在或为空，尝试重新解析
        try {
            List<Chapter> chapters = parseChapterList();
            // 更新缓存
            book.setCachedChapters(chapters);
            return chapters;
        } catch (Exception e) {
            // 解析失败，如果有任何缓存可用就使用缓存
            if (cachedChapters != null) {
                return cachedChapters;
            }
            // 完全没有可用数据，抛出异常
            throw new RuntimeException("无法获取章节列表", e);
        }
    }

    /**
     * 解析章节列表
     * 实际的解析逻辑由具体实现类提供
     */
    List<Chapter> parseChapterList();

    /**
     * 获取章节内容
     * 优先使用缓存，缓存不存在或过期时才获取新内容
     */
    default String getChapterContent(String chapterId, Book book) {
        // 获取缓存仓库
        ChapterCacheRepository cacheRepository = null;

        // 尝试从RepositoryModule获取
        try {
            RepositoryModule repositoryModule = RepositoryModule.getInstance();
            if (repositoryModule != null) {
                cacheRepository = repositoryModule.getChapterCacheRepository();
            }
        } catch (Exception e) {
            LOG.debug("获取ChapterCacheRepository失败: " + e.getMessage());
        }

        // 如果无法获取缓存服务，直接解析内容
        if (cacheRepository == null) {
            try {
                return parseChapterContent(chapterId);
            } catch (Exception e) {
                return "章节内容暂时无法访问：" + e.getMessage();
            }
        }
        
        // 使用新的ChapterCacheRepository
        // 优先尝试从缓存获取（只返回未过期的缓存）
        String cachedContent = cacheRepository.getCachedContent(book.getId(), chapterId);
        
        // 如果缓存存在且未过期，直接返回缓存内容
        if (cachedContent != null) {
            return cachedContent;
        }
        
        // 缓存不存在或已过期，尝试获取新内容
        try {
            String content = parseChapterContent(chapterId);
            // 更新缓存
            cacheRepository.cacheContent(book.getId(), chapterId, content);
            return content;
        } catch (Exception e) {
            // 获取失败，尝试使用任何可用的缓存（即使已过期）
            String fallbackContent = cacheRepository.getFallbackCachedContent(book.getId(), chapterId);
            if (fallbackContent != null) {
                return fallbackContent;
            }
            // 没有任何可用内容，返回错误信息
            return "章节内容暂时无法访问：" + e.getMessage();
        }
    }

    /**
     * 解析章节内容
     * 实际的解析逻辑由具体实现类提供
     */
    String parseChapterContent(String chapterId);

    /**
     * 章节信息
     */
    @Tag("Chapter")
    class Chapter {
        @Tag("title")
        @Expose
        private String title = "";
        
        @Tag("url")
        @Expose
        private String url = "";

        public Chapter() {
            // 默认构造函数，用于序列化
        }

        public Chapter(String title, String url) {
            this.title = title != null ? title : "";
            this.url = url != null ? url : "";
        }

        public String title() {
            return title != null ? title : "";
        }

        public void setTitle(String title) {
            this.title = title != null ? title : "";
        }

        public String url() {
            return url != null ? url : "";
        }

        public void setUrl(String url) {
            this.url = url != null ? url : "";
        }

        @Override
        public String toString() {
            return title();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Chapter chapter = (Chapter) o;
            return url().equals(chapter.url());
        }

        @Override
        public int hashCode() {
            return url().hashCode();
        }
    }
} 