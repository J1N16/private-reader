package com.lv.tool.privatereader.parser.site;

import com.intellij.openapi.diagnostic.Logger;
import com.lv.tool.privatereader.exception.PrivateReaderException;
import com.lv.tool.privatereader.parser.NovelParser;
import com.lv.tool.privatereader.parser.common.ChapterTitleUtils;
import com.lv.tool.privatereader.parser.common.MetadataAnalyzer;
import com.lv.tool.privatereader.parser.common.TextDensityAnalyzer;
import com.lv.tool.privatereader.parser.common.TextFormatter;
import com.lv.tool.privatereader.util.SafeHttpRequestExecutor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 通用网络小说解析器，用于智能解析网络小说网站
 */
public final class UniversalParser implements NovelParser {
    private static final Logger LOG = Logger.getInstance(UniversalParser.class);
    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
        "^(?:第)?[0-9零一二三四五六七八九十百千万]+[章节卷集].*$|^[0-9]+[、.][^\\d].*$"
    );
    private final String url;
    private Document document;
    private final SSLSocketFactory sslSocketFactory;
    private boolean initialized = false;
    private IOException lastInitError = null;

    public UniversalParser(final String url) {
        this.url = url;
        this.sslSocketFactory = createInsecureSSLSocketFactory();
        LOG.info("创建UniversalParser实例: " + url);
        // 不在构造函数中加载网页，改为延迟加载
    }

    /**
     * 初始化解析器，加载网页内容
     *
     * @return 是否初始化成功
     */
    private boolean initialize() {
        if (initialized) {
            return document != null;
        }

        initialized = true; // 标记为已尝试初始化，无论成功与否

        LOG.info("开始初始化解析器并加载网页: " + url);
        try {
            LOG.debug("尝试连接网址...");
            // 注：不再设置 System 全局代理属性。请求走 SafeHttpRequestExecutor（IntelliJ
            // HttpRequests），由 IDE 自身的代理配置管理，避免污染 JVM 全局代理设置。

            // 使用安全的HTTP请求执行器，带有重试机制
            String htmlContent = SafeHttpRequestExecutor.executeGetRequest(url);

            // 添加显式的 null 检查
            if (htmlContent == null) {
                LOG.error("SafeHttpRequestExecutor.executeGetRequest返回null，URL: " + url);
                lastInitError = new IOException("获取页面内容失败 (返回 null): " + url);
                return false;
            }

            // 使用Jsoup解析获取的HTML内容
            this.document = Jsoup.parse(htmlContent, url);
            LOG.info("成功获取页面内容，长度: " + document.html().length());
            return true;
        } catch (IOException e) {
            LOG.warn("连接网址失败: " + url + ", 错误: " + e.getMessage(), e);
            lastInitError = e;
            return false;
        } catch (Exception e) {
            LOG.error("初始化解析器时发生意外错误: " + url + ", 错误: " + e.getMessage(), e);
            lastInitError = new IOException("初始化解析器时发生意外错误: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 确保解析器已初始化
     *
     * @throws IOException 如果初始化失败
     */
    private void ensureInitialized() throws IOException {
        if (!initialized) {
            if (!initialize()) {
                throw lastInitError != null ? lastInitError : new IOException("初始化解析器失败，原因未知");
            }
        } else if (document == null && lastInitError != null) {
            throw lastInitError;
        }
    }

    @Override
    public String getTitle() {
        LOG.debug("开始识别书籍标题...");
        try {
            // 确保解析器已初始化
            ensureInitialized();

            String title = MetadataAnalyzer.findTitle(document);
            LOG.info("识别到书籍标题: " + title);
            return title;
        } catch (IOException e) {
            LOG.warn("获取书籍标题时发生错误: " + e.getMessage(), e);
            // 从URL中提取一个基本标题作为后备
            String urlPath = url.replaceAll("https?://[^/]+/", "");
            String[] pathSegments = urlPath.split("/");
            if (pathSegments.length > 0) {
                String lastSegment = pathSegments[pathSegments.length - 1];
                if (lastSegment.contains(".")) {
                    lastSegment = lastSegment.substring(0, lastSegment.lastIndexOf('.'));
                }
                if (!lastSegment.isEmpty()) {
                    LOG.debug("初始化失败，从URL中提取到标题: " + lastSegment);
                    return lastSegment;
                }
            }
            return url;
        }
    }

    @Override
    public String getAuthor() {
        LOG.debug("开始识别作者信息...");
        try {
            // 确保解析器已初始化
            ensureInitialized();

            String author = MetadataAnalyzer.findAuthor(document);
            LOG.info("识别到作者: " + author);
            return author;
        } catch (IOException e) {
            LOG.warn("获取作者信息时发生错误: " + e.getMessage(), e);
            return "未知作者";
        }
    }

    @Override
    public List<Chapter> parseChapterList() {
        LOG.debug("开始解析章节列表...");
        List<Chapter> chapters = new ArrayList<>();
        java.util.Set<String> seenUrls = new java.util.HashSet<>();

        try {
            // 确保解析器已初始化
            ensureInitialized();

            if (document == null) {
                LOG.warn("文档对象未初始化，无法解析章节列表。");
                return chapters;
            }

            Elements links = document.select("a[href]");
            LOG.debug("找到链接数量: " + links.size() + "，正在分析...");

            int processedLinks = 0;
            for (Element link : links) {
                String href = link.attr("abs:href");
                String title = link.text().trim();

                if (isChapterLink(href, title) && seenUrls.add(href)) {
                    chapters.add(new Chapter(title, href));
                    LOG.debug("找到章节: " + title + " -> " + href);
                }

                processedLinks++;
                if (processedLinks % 100 == 0) {
                    LOG.info(String.format("已处理 %d/%d 个链接，找到 %d 个章节",
                        processedLinks, links.size(), chapters.size()));
                }
            }
        } catch (IOException e) {
            LOG.warn("解析章节列表时发生错误: " + e.getMessage(), e);
            // 返回空列表，不抛出异常
        }

        // 如果没有找到章节，尝试查找可能的章节目录页面
        if (chapters.isEmpty() && document != null) {
            LOG.info("直接解析未找到章节，尝试查找目录页面...");
            Elements catalogLinks = document.select("a:matches(目录|章节|卷章|分卷|分章)");
            LOG.debug("找到可能的目录链接数量: " + catalogLinks.size() + "，开始逐个尝试...");

            for (Element link : catalogLinks) {
                try {
                    String catalogUrl = link.attr("abs:href");
                    LOG.info("正在尝试解析目录页面: " + catalogUrl);

                    // 使用安全的HTTP请求执行器
                    String catalogHtml = SafeHttpRequestExecutor.executeGetRequest(catalogUrl);

                    Document catalogDoc = Jsoup.parse(catalogHtml, catalogUrl);
                    Elements catalogChapters = catalogDoc.select("a[href]");
                    LOG.debug("目录页面中找到链接数量: " + catalogChapters.size() + "，开始分析...");

                    int processedCatalogLinks = 0;
                    for (Element chapter : catalogChapters) {
                        String href = chapter.attr("abs:href");
                        String title = chapter.text().trim();
                        if (isChapterLink(href, title) && seenUrls.add(href)) {
                            chapters.add(new Chapter(title, href));
                            LOG.debug("从目录页面找到章节: " + title + " -> " + href);
                        }

                        processedCatalogLinks++;
                        if (processedCatalogLinks % 50 == 0) {
                            LOG.info(String.format("目录页面已处理 %d/%d 个链接，找到 %d 个章节",
                                processedCatalogLinks, catalogChapters.size(), chapters.size()));
                        }
                    }
                    if (!chapters.isEmpty()) {
                        LOG.info(String.format("成功从目录页面解析到章节列表，共 %d 章，继续尝试其他目录链接...", chapters.size()));
                        // 不再break，继续尝试解析其他目录页面以聚合所有章节
                    }
                } catch (IOException e) {
                    LOG.warn("解析目录页面失败: " + e.getMessage() + "，尝试下一个目录链接");
                }
            }
        }

        if (chapters.isEmpty()) {
            LOG.warn("未能找到任何章节，请检查网页结构或尝试其他目录页面");
        } else {
            LOG.info(String.format("章节解析完成，共找到 %d 章，正在排序...", chapters.size()));
            // 可以在这里添加章节排序逻辑
        }

        return chapters;
    }

    @Override
    public String parseChapterContent(String chapterId) {
        LOG.debug("开始解析章节内容: " + chapterId);
        try {
            // 使用同步方法实现
            return parseChapterContentInternal(chapterId);
        } catch (Exception e) {
            LOG.warn("解析章节内容时发生错误: " + e.getMessage(), e);
            throw new PrivateReaderException(
                "解析章节内容失败: " + e.getMessage(),
                e,
                PrivateReaderException.ExceptionType.PARSE_ERROR
            );
        }
    }

    private String parseChapterContentInternal(String chapterId) {
        try {
            LOG.debug("正在连接章节页面...");

            // 使用安全的HTTP请求执行器
            String html = SafeHttpRequestExecutor.executeGetRequest(chapterId);

            LOG.debug("成功获取章节页面内容，长度: " + html.length());

            // 优化：只使用UTF-8编码，避免重复解析
            String content = null;
            try {
                content = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    Document chapterDoc = Jsoup.parse(html, chapterId);
                    return extractContent(chapterDoc);
                }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                LOG.error("[超时保护] 内容提取超时(10秒): " + chapterId, te);
                throw new PrivateReaderException("章节内容正文提取超时(10秒)", te, PrivateReaderException.ExceptionType.PARSE_ERROR);
            } catch (Exception e) {
                LOG.error("[异常保护] 内容提取异常: " + chapterId, e);
                throw new PrivateReaderException("章节内容正文提取异常: " + e.getMessage(), e, PrivateReaderException.ExceptionType.PARSE_ERROR);
            }

            if (content != null && !content.isEmpty()) {
                LOG.debug("成功解析章节内容，使用编码: UTF-8");
            }

            if (content == null || content.isEmpty()) {
                throw new PrivateReaderException(
                    "无法解析章节内容，请检查网页格式或编码",
                    PrivateReaderException.ExceptionType.PARSE_ERROR
                );
            }

            return content;
        } catch (IOException e) {
            throw new PrivateReaderException(
                "获取章节内容失败: " + e.getMessage(),
                e,
                PrivateReaderException.ExceptionType.NETWORK_ERROR
            );
        }
    }

    private boolean isChapterLink(String href, String title) {
        if (href == null || title == null || href.isEmpty() || title.isEmpty()) {
            return false;
        }

        // 1. URL特征判断
        boolean urlMatch = href.contains("/chapter/") ||
                          href.contains("/read/") ||
                          href.contains("/book/") ||
                          href.matches(".*/(\\d+).(html|htm|shtml|aspx|php)$") ||
                          href.matches(".*/chapter_\\d+.*") ||
                          href.matches(".*/c\\d+.*") ||
                          href.matches(".*/\\d+/\\d+.*");

        // 2. 标题特征判断
        boolean titleMatch = ChapterTitleUtils.isChapterTitle(title);

        // 3. 智能分析
        if (!urlMatch && !titleMatch) {
            // 检查URL中的数字序列
            boolean hasSequentialNumbers = href.matches(".*\\d+.*") &&
                                        !href.contains("javascript") &&
                                        !href.contains("login") &&
                                        !href.contains("register");

            // 检查标题长度和内容
            boolean titleLengthValid = title.length() >= 2 && title.length() <= 50;
            boolean titleHasValidChars = !title.contains("登录") &&
                                       !title.contains("注册") &&
                                       !title.contains("首页") &&
                                       !title.contains("最新") &&
                                       !title.contains("排行");

            // 如果URL包含序列数字且标题看起来合理，认为是章节链接
            if (hasSequentialNumbers && titleLengthValid && titleHasValidChars) {
                LOG.debug("通过智能分析识别到章节链接 - 标题: " + title);
                return true;
            }
        }

        if (urlMatch || titleMatch) {
            LOG.debug("识别到章节链接 - 标题: " + title + ", URL: " + href +
                     " (URL匹配: " + urlMatch + ", 标题匹配: " + titleMatch + ")");
        }

        return urlMatch || titleMatch;
    }

    private SSLSocketFactory createInsecureSSLSocketFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("创建SSL Socket Factory失败", e);
        }
    }

    private String extractContent(Document doc) {
        try {
            Element content = doc.selectFirst("div#content1, div.content_read, div.box_con #content");

            if (content == null) {
                LOG.info("常用选择器未找到内容，切换到智能分析模式...");
                content = TextDensityAnalyzer.findContentElement(doc);
            }

            if (content != null) {
                LOG.info("已找到正文内容，开始清理...");
                content.select("script, style, a, iframe, div.adsbygoogle, .bottem, .bottem2").remove();

                String text = content.text();
                LOG.debug("原始内容长度: " + text.length() + " 字符");

                text = text.replaceAll("(?i)^\\s*(广告|推广|http|www|com|net|org|xyz)[^，。！？]*", "")
                        .replaceAll("(?i)(八八中文网|88中文网|求书网|新笔趣阁|笔趣阁|顶点小说|番茄小说)[^，。！？]*", "")
                        .replaceAll("最新章节！", "")
                        .replaceAll("\\s*([，。！？])\\s*", "$1\n")
                        .replaceAll("\\s+", "\n")
                        .replaceAll("\\n{3,}", "\n\n")
                        .replaceAll("^\\s*第[0-9零一二三四五六七八九十百千万亿]+[章节卷集部篇].*$", "")
                        .replaceAll("^\\s*[0-9]+[、.][^0-9]*$", "")
                        .replaceAll("^\\s*第[0-9零一二三四五六七八九十百千万亿]+回.*$", "")
                        .replaceAll("^\\s*[序楔终][章话].*$", "")
                        .replaceAll("^\\s*[前序楔引]言.*$", "")
                        .replaceAll("^\\s*[后终]记.*$", "")
                        .replaceAll("^\\s*[卷部篇][0-9零一二三四五六七八九十百千万亿]+.*$", "")
                        .replaceAll("^\\s*[上中下]篇.*$|^\\s*番外.*$|^\\s*特别篇.*$|^\\s*外传.*$", "")
                        .replaceAll("^\\s*[早中午晚]章.*$|^\\s*[春夏秋冬]章.*$", "")
                        .replaceAll("^\\s*(间|幕)?插.*$", "")
                        .trim();

                String formatted = TextFormatter.format(text);
                LOG.info(String.format("内容处理完成，最终长度: %d 字符", formatted.length()));
                return formatted;
            }
        } catch (Exception e) {
            LOG.error("提取内容时发生错误", e);
        }

        return null;
    }

}
