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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用网络小说解析器，用于智能解析网络小说网站
 */
public final class UniversalParser implements NovelParser {
    private static final Logger LOG = Logger.getInstance(UniversalParser.class);
    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
        "^(?:第)?[0-9零一二三四五六七八九十百千万]+[章节卷集].*$|^[0-9]+[、.][^\\d].*$"
    );
    /**
     * 从页面脚本中提取移动站地址,例如 uaredirect("https://m.biquge.one/7_117547/")。
     * 部分站点(如 biquge.one)会在桌面页剥离目录链接,仅暴露移动站入口,
     * 而移动站的 all.html 提供完整章节目录。
     */
    private static final Pattern MOBILE_REDIRECT_PATTERN = Pattern.compile(
        "uaredirect\\(\\s*[\"']([^\"']+)[\"']\\s*\\)"
    );
    /** 目录页链接识别:/index.html、/all.html 等 */
    private static final Pattern CATALOG_PAGE_PATTERN = Pattern.compile(".*/(all|index)(_[0-9]+)?\\.html?$", Pattern.CASE_INSENSITIVE);
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

            // 使用安全的HTTP请求执行器,带有重试机制。
            // 取原始字节而非字符串:由 Jsoup 根据 HTTP Content-Type 或页面
            // <meta charset> 自动探测编码,兼容 biquge.one 等 GBK/GB2312 站点,
            // 避免误按 UTF-8 解码导致中文乱码。
            byte[] htmlBytes = SafeHttpRequestExecutor.executeGetRequestBytes(url);

            // 添加显式的 null 检查
            if (htmlBytes == null) {
                LOG.error("SafeHttpRequestExecutor.executeGetRequestBytes返回null,URL: " + url);
                lastInitError = new IOException("获取页面内容失败 (返回 null): " + url);
                return false;
            }

            // 使用Jsoup解析获取的HTML内容,charset 传 null 触发自动探测
            this.document = parseDocument(htmlBytes, url);
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

                    // 使用安全的HTTP请求执行器,取原始字节交给 Jsoup 自动探测编码
                    byte[] catalogHtml = SafeHttpRequestExecutor.executeGetRequestBytes(catalogUrl);

                    Document catalogDoc = parseDocument(catalogHtml, catalogUrl);
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

        // 兜底:部分站点(如 biquge.one)在书籍首页剥离了目录区的<a>,
        // 仅保留少量"最新章节"链接,导致目录严重不完整。
        // 若目录明显残缺,尝试从页面脚本暴露的移动站地址
        // (如 uaredirect("https://m.biquge.one/7_117547/"))找到其 all.html
        // 完整目录页,并用其替换残缺目录。
        if (document != null && isCatalogIncomplete(chapters)) {
            List<Chapter> fullCatalog = tryFullCatalogFallback(chapters, seenUrls);
            if (!fullCatalog.isEmpty()) {
                chapters = fullCatalog;
            }
        }

        return chapters;
    }

    /**
     * 判断已解析目录是否明显残缺。
     *
     * <p>以标题中的最大章号作为参照:若最大章号远大于已解析数量,
     * 说明站点只暴露了少量最新章节(如 biquge.one 首页仅 9 条),
     * 需要回退到完整目录页。
     */
    private boolean isCatalogIncomplete(List<Chapter> chapters) {
        if (chapters.isEmpty()) {
            return true;
        }
        int maxNumber = 0;
        for (Chapter c : chapters) {
            Integer n = extractChapterNumber(c.title());
            if (n != null && n > maxNumber) {
                maxNumber = n;
            }
        }
        if (maxNumber == 0) {
            return false; // 无明确章号,无法判断
        }
        return maxNumber > chapters.size() * 2 && maxNumber - chapters.size() > 10;
    }

    /** 从标题中提取阿拉伯数字章号,如 "第2273章 ..." -> 2273。 */
    private Integer extractChapterNumber(String title) {
        if (title == null) {
            return null;
        }
        Matcher m = Pattern.compile("第\\s*([0-9]+)\\s*[章节回]").matcher(title);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 将移动站目录中的章节链接归一到当前书籍所在的主站目录。
     *
     * <p>部分站点(如 biquge.one)移动站与主站的章节 ID 相同,但路径形式
     * 不同:移动站 {@code https://m.biquge.one/117_117547/29389839.html},
     * 主站 {@code https://www.biquge.one/117/117547/29389839.html}。
     * 章节正文解析逻辑针对主站结构调优,因此这里将"数字.html"形式的章节
     * 文件重新挂到书籍 URL 目录下;其他情况保持原样。
     */
    private String normalizeChapterUrl(String chapterUrl) {
        if (chapterUrl == null || url == null) {
            return chapterUrl;
        }
        try {
            java.net.URI cu = new java.net.URI(chapterUrl);
            java.net.URI book = new java.net.URI(url);
            String cuHost = cu.getHost();
            String bookHost = book.getHost();
            if (cuHost == null || bookHost == null || cuHost.equalsIgnoreCase(bookHost)) {
                return chapterUrl;
            }
            String path = cu.getPath();
            if (path == null) {
                return chapterUrl;
            }
            String file = path.substring(path.lastIndexOf('/') + 1);
            if (!file.matches("[0-9]+\\.html?")) {
                return chapterUrl;
            }
            String base = url.endsWith("/") ? url : url + "/";
            return base + file;
        } catch (Exception e) {
            return chapterUrl;
        }
    }

    /**
     * 尝试获取更完整的章节目录。
     *
     * <p>检测依据:页面脚本中的 {@code uaredirect} 移动站入口,以及
     * {@code /all.html}(移动站完整目录)。若解析到的章节数明显少于
     * 可推断的章节总数(根据最大章号),则认为是残缺目录,需回退到完整目录页。
     *
     * @param current  已解析的章节列表
     * @param seenUrls 已去重的 URL 集合
     * @return 更完整的章节列表;若未找到更优结果则返回空列表
     */
    private List<Chapter> tryFullCatalogFallback(List<Chapter> current, java.util.Set<String> seenUrls) {
        List<String> candidates = collectCatalogCandidates();
        if (candidates.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        List<Chapter> best = new java.util.ArrayList<>(current);
        java.util.Set<String> bestSeen = new java.util.HashSet<>(seenUrls);

        for (String catalogUrl : candidates) {
            try {
                LOG.info("尝试解析完整目录页: " + catalogUrl);
                // 目录页为额外尝试,失败快速跳过(0 次重试),不拖延主流程
                byte[] html = SafeHttpRequestExecutor.executeGetRequestBytes(catalogUrl, 0, 0);
                Document doc = parseDocument(html, catalogUrl);

                List<Chapter> parsed = new java.util.ArrayList<>();
                java.util.Set<String> localSeen = new java.util.HashSet<>();
                for (Element link : doc.select("a[href]")) {
                    String href = normalizeChapterUrl(link.attr("abs:href"));
                    String title = link.text().trim();
                    if (isChapterLink(href, title) && localSeen.add(href)) {
                        parsed.add(new Chapter(title, href));
                    }
                }

                LOG.info(String.format("完整目录页 %s 解析到 %d 章(当前 %d 章)",
                        catalogUrl, parsed.size(), current.size()));

                // 仅当结果更完整且明显是章节目录时才采用
                if (parsed.size() > best.size() && looksLikeFullCatalog(parsed, best.size())) {
                    best = parsed;
                    bestSeen = localSeen;
                    LOG.info(String.format("已采用更完整的目录页,共 %d 章", best.size()));
                    // 找到完整目录后不再尝试其他候选
                    break;
                }
            } catch (IOException e) {
                LOG.warn("解析完整目录页失败: " + catalogUrl + ", 错误: " + e.getMessage());
            } catch (Exception e) {
                LOG.warn("解析完整目录页时发生意外错误: " + catalogUrl, e);
            }
        }

        if (best.size() <= current.size()) {
            return java.util.Collections.emptyList();
        }

        // 结果回填去重集合,保持调用方状态一致
        seenUrls.clear();
        seenUrls.addAll(bestSeen);
        return best;
    }

    /**
     * 收集可能的完整目录页候选地址。
     *
     * <ul>
     *   <li>页面脚本 {@code uaredirect("...")} 中的移动站地址,拼接 all.html;</li>
     *   <li>直接匹配的 {@code /all.html}、{@code /index.html} 等目录页链接。</li>
     * </ul>
     */
    private List<String> collectCatalogCandidates() {
        List<String> candidates = new java.util.ArrayList<>();

        // 1) 页面脚本中暴露的移动站地址
        Element html = document;
        String pageHtml = html != null ? html.html() : "";
        Matcher m = MOBILE_REDIRECT_PATTERN.matcher(pageHtml);
        if (m.find()) {
            String mobileUrl = m.group(1).trim();
            String base = mobileUrl.endsWith("/") ? mobileUrl : mobileUrl + "/";
            candidates.add(base + "all.html");
            candidates.add(base + "index.html");
            LOG.info("从页面脚本发现移动站入口: " + mobileUrl + ",将尝试其完整目录页");
        }

        // 2) 页面中直接存在的 all/index 目录页链接
        for (Element link : document.select("a[href]")) {
            String abs = link.attr("abs:href");
            if (CATALOG_PAGE_PATTERN.matcher(abs).matches()) {
                candidates.add(abs);
            }
        }

        // 3) 基于当前书籍 URL 的常规拼接(部分站点直接支持 /all.html)
        if (url != null && !url.isEmpty()) {
            String base = url.endsWith("/") ? url : url + "/";
            candidates.add(base + "all.html");
        }

        // 去重并保持顺序
        List<String> unique = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String c : candidates) {
            if (c != null && !c.isEmpty() && seen.add(c)) {
                unique.add(c);
            }
        }
        return unique;
    }

    /**
     * 判断解析结果是否确实是一个接近全量的章节目录。
     *
     * <p>启发式:目录中的章节标题通常带明确章号,若最大章号显著大于
     * 当前已解析数量,说明当前目录残缺。
     */
    private boolean looksLikeFullCatalog(List<Chapter> parsed, int currentSize) {
        if (parsed == null || parsed.isEmpty()) {
            return false;
        }
        if (parsed.size() < currentSize + 5) {
            return false;
        }
        // 目录若包含大量带章号的标题,基本可确定为真实目录
        int numbered = 0;
        for (Chapter c : parsed) {
            if (c.title() != null && c.title().matches("^\\s*第?[0-9零一二三四五六七八九十百千万亿]+[章节卷集部篇回].*")) {
                numbered++;
            }
        }
        return numbered >= Math.max(5, parsed.size() / 2);
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

            // 使用安全的HTTP请求执行器,取原始字节交给 Jsoup 自动探测编码
            byte[] html = SafeHttpRequestExecutor.executeGetRequestBytes(chapterId);

            if (html == null) {
                throw new PrivateReaderException(
                    "无法获取章节内容 (返回 null): " + chapterId,
                    PrivateReaderException.ExceptionType.NETWORK_ERROR
                );
            }

            LOG.debug("成功获取章节页面内容,长度: " + html.length + " 字节");

            // 由 Jsoup 依据 <meta charset> 自动识别编码,兼容 GBK/GB2312 站点
            String content = null;
            try {
                content = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        Document chapterDoc = parseDocument(html, chapterId);
                        return extractContent(chapterDoc);
                    } catch (java.io.IOException ioe) {
                        throw new java.io.UncheckedIOException("解析章节页面失败: " + chapterId, ioe);
                    }
                }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                LOG.error("[超时保护] 内容提取超时(10秒): " + chapterId, te);
                throw new PrivateReaderException("章节内容正文提取超时(10秒)", te, PrivateReaderException.ExceptionType.PARSE_ERROR);
            } catch (Exception e) {
                LOG.error("[异常保护] 内容提取异常: " + chapterId, e);
                throw new PrivateReaderException("章节内容正文提取异常: " + e.getMessage(), e, PrivateReaderException.ExceptionType.PARSE_ERROR);
            }

            if (content != null && !content.isEmpty()) {
                LOG.debug("成功解析章节内容(编码由页面 charset 声明自动识别)");
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

    /**
     * 解析 HTML 字节为 JSDocument,交由 Jsoup 自动探测字符集。
     *
     * <p>charset 传 {@code null} 时,Jsoup 会优先使用 HTTP Content-Type 中的
     * 编码;缺失时扫描文档 {@code <meta charset>} / {@code <meta http-equiv>}
     * 声明。这样 GBK/GB2312 页面不会被误按 UTF-8 解码而产生乱码。
     *
     * @param htmlBytes 原始 HTML 字节
     * @param baseUri   用于解析相对链接的基准 URL
     * @return 解析后的 Jsoup 文档
     * @throws java.io.IOException 读取字节流失败时
     */
    private Document parseDocument(byte[] htmlBytes, String baseUri) throws java.io.IOException {
        if (htmlBytes == null) {
            LOG.warn("HTML 字节为 null,返回空文档。baseUri: " + baseUri);
            return Jsoup.parse("", baseUri);
        }
        return Jsoup.parse(new java.io.ByteArrayInputStream(htmlBytes), null, baseUri);
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
            Element content = doc.selectFirst("div#content1, div.content_read, div.box_con #content, div#chaptercontent, #nr, #content");

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
