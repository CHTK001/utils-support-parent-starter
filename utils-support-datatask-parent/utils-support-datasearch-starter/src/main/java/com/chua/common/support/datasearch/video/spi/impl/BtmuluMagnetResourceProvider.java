package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoDownload;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 磁力资源提供者（斗图啦同级别的中文磁力站「BT目录」）。
 *
 * <p>数据源：<a href="https://www.btmulu.net">btmulu.net</a>，站内搜索页
 * {@code /search/{关键词}.html?page=N} 的结果条目 {@code article.item} 中，
 * 详情链接形如 {@code /hash/{40位infohash}.html}，因此可以直接由 infohash
 * 拼接标准磁力链接 {@code magnet:?xt=urn:btih:{infohash}&dn={标题}}，
 * 无需再请求详情页（一次请求即可拿到整页磁力结果）。</p>
 *
 * <p>结果同时写入 {@link VideoInfoResult#getVideoUrl()} 与
 * {@link VideoInfoResult#getDownloadList()}（{@link VideoDownload#getVideoDownloadMagnetic()}），
 * 因此既能直接播放/复制磁力，也能被下载列表消费。</p>
 *
 * <p>该站搜索页每页固定 20 条，{@code page} 参数直接透传给站点；
 * 站点提供按热度/大小/发布时间/收录时间排序，可通过 {@link VideoSearch#getOrder()} 指定
 * （hot/size/create/update）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("btmulu")
public class BtmuluMagnetResourceProvider extends AbstractResourceProvider {

    /**
     * 日志
     */
    private static final Logger log = LoggerFactory.getLogger(BtmuluMagnetResourceProvider.class);

    /**
     * 默认站点地址
     */
    private static final String DEFAULT_BASE_URL = "https://www.btmulu.net";

    /**
     * 模拟浏览器的 User-Agent
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /**
     * 站点每页条数
     */
    private static final int SITE_PAGE_SIZE = 20;

    /**
     * 详情链接中的 infohash
     */
    private static final Pattern HASH_PATTERN = Pattern.compile("/hash/([0-9a-fA-F]{40})\\.html");

    /**
     * 热度（seeders/热度值）
     */
    private static final Pattern HEAT_PATTERN = Pattern.compile("热度[:：]\\s*(\\d+)");

    /**
     * 文件大小
     */
    private static final Pattern SIZE_PATTERN =
            Pattern.compile("文件大小[:：]\\s*([0-9.]+\\s*[KMGTP]?B)", Pattern.CASE_INSENSITIVE);

    /**
     * 发布时间（直接匹配日期，避免站点标签/冒号变体导致失配）
     */
    private static final Pattern TIME_PATTERN =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");

    /**
     * 文件数量
     */
    private static final Pattern COUNT_PATTERN = Pattern.compile("文件数量[:：]\\s*(\\d+)");

    /**
     * 结果总数（站点汇总文案：第1-20条/共993条）
     */
    private static final Pattern TOTAL_PATTERN = Pattern.compile("共\\s*(\\d+)\\s*条");

    /**
     * 发布时间格式
     */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 复用的 HTTP 客户端
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 创建 磁力资源提供者 实例
     */
    public BtmuluMagnetResourceProvider() {
        super();
    }

    /**
     * 创建 磁力资源提供者 实例
     *
     * @param videoSource 视频源（可注入自定义站点地址）
     */
    public BtmuluMagnetResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        ReturnPageResult<VideoInfoResult> blocked = checkBlocked("btmulu");
        if (blocked != null) {
            return blocked;
        }

        int page = Math.max(1, videoSearch.getPage());
        try {
            String url = buildUrl(keyword, page, videoSearch.getOrder());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", getBaseUrl() + "/")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("[btmulu] 检索失败: url={}, status={}", url, response.statusCode());
                return ReturnPageResult.error("btmulu 磁力检索失败: HTTP " + response.statusCode());
            }

            Document document = Jsoup.parse(new String(response.body(), StandardCharsets.UTF_8), getBaseUrl());
            List<VideoInfoResult> results = parseItems(document);
            if (results.isEmpty()) {
                return ReturnPageResult.empty();
            }

            long total = parseTotal(document, results.size());
            int pageSize = Math.max(1, results.size());
            PageResult<VideoInfoResult> pageResult = PageResult.<VideoInfoResult>builder()
                    .data(results)
                    .pageNo(page)
                    .pageSize(pageSize)
                    .total(total)
                    .totalPages((int) ((total + pageSize - 1) / pageSize))
                    .build();
            log.info("[btmulu] 关键词[{}] 第{}页 命中 {} 条（共 {} 条）", keyword, page, results.size(), total);
            return ReturnPageResult.of(pageResult);
        } catch (Exception e) {
            log.warn("[btmulu] 检索异常: keyword={}, msg={}", keyword, e.getMessage());
            return ReturnPageResult.error("btmulu 磁力检索失败: " + e.getMessage());
        }
    }

    /**
     * 构建搜索地址。
     *
     * @param keyword 关键词
     * @param page    页码（从 1 开始）
     * @param order   排序方式（hot/size/create/update，可空）
     * @return 搜索地址
     */
    private String buildUrl(String keyword, int page, String order) {
        String sort = "/search";
        if (StringUtils.hasText(order)) {
            String value = order.trim().toLowerCase(Locale.ROOT);
            if (value.contains("hot") || value.contains("热度")) {
                sort = "/search-hot";
            } else if (value.contains("size") || value.contains("大小")) {
                sort = "/search-size";
            } else if (value.contains("create") || value.contains("收录")) {
                sort = "/search-create";
            } else if (value.contains("update") || value.contains("发布")) {
                sort = "/search-update";
            }
        }
        return getBaseUrl() + sort + "/" + encode(keyword) + ".html?page=" + page;
    }

    /**
     * 解析搜索结果条目。
     *
     * @param document 搜索页文档
     * @return 磁力结果列表
     */
    private List<VideoInfoResult> parseItems(Document document) {
        List<VideoInfoResult> results = Wraps.list();
        Set<String> seen = new HashSet<>();
        Elements articles = document.select("article.item");
        for (Element article : articles) {
            Element hashLink = findHashLink(article);
            if (hashLink == null) {
                continue;
            }
            Matcher hashMatcher = HASH_PATTERN.matcher(hashLink.attr("href"));
            if (!hashMatcher.find()) {
                continue;
            }
            String hash = hashMatcher.group(1).toLowerCase(Locale.ROOT);
            if (!seen.add(hash)) {
                continue;
            }
            Element h4 = hashLink.selectFirst("h4");
            String title = clean(h4 == null ? "" : h4.ownText());
            if (title.isEmpty()) {
                title = clean(hashLink.text());
            }
            if (title.isEmpty()) {
                title = hash;
            }

            String format = "";
            Element label = hashLink.selectFirst("h4 span.label");
            if (label != null) {
                format = clean(label.text());
            }

            Elements paragraphs = article.select("p");
            String meta = paragraphs.isEmpty() ? "" : clean(paragraphs.first().text());
            String size = group(SIZE_PATTERN, meta);
            String heat = group(HEAT_PATTERN, meta);
            String publishTime = group(TIME_PATTERN, meta);
            String fileCount = group(COUNT_PATTERN, meta);
            String files = paragraphs.size() > 1 ? clean(paragraphs.get(1).text()) : "";

            String magnet = toMagnet(hash, title);
            VideoInfoResult info = new VideoInfoResult();
            info.setVideoId(parseId(article.attr("data-key")));
            info.setVideoOriginId(hash);
            info.setVideoName(title);
            info.setVideoTitle(title);
            info.setVideoPlatform(name());
            info.setVideoUrl(magnet);
            info.setDownloadUrls(magnet);
            info.setVideoSize(size);
            info.setVideoQuality(format);
            info.setVideoType(format);
            info.setVideoCategory("movie");
            info.setVideoPopularity(heat);
            info.setVideoRemark(buildRemark(heat, fileCount));
            info.setVideoPublishDate(parseTime(publishTime));
            info.setVideoDescription(truncate(files, 500));
            info.setTags("magnet,btmulu");
            info.setDownloadList(List.of(toDownload(title, format, size, publishTime, magnet)));
            results.add(info);
        }
        return results;
    }

    /**
     * 在条目内查找指向 infohash 详情页的链接（跳过广告条目）。
     *
     * @param article 条目元素
     * @return 命中返回链接元素，否则返回 空
     */
    private static Element findHashLink(Element article) {
        for (Element link : article.select("a[href]")) {
            if (HASH_PATTERN.matcher(link.attr("href")).find()) {
                return link;
            }
        }
        return null;
    }

    /**
     * 构建磁力链接。
     *
     * @param hash  infohash（40 位十六进制）
     * @param title 资源标题（作为 dn）
     * @return 标准磁力链接
     */
    private static String toMagnet(String hash, String title) {
        return "magnet:?xt=urn:btih:" + hash + "&dn=" + encode(title);
    }

    /**
     * 构建下载项。
     *
     * @param title       标题
     * @param format      格式
     * @param size        大小
     * @param publishTime 发布时间
     * @param magnet      磁力链接
     * @return 下载项
     */
    private static VideoDownload toDownload(String title, String format, String size,
                                            String publishTime, String magnet) {
        VideoDownload download = new VideoDownload();
        download.setVideoDownloadName(title);
        download.setVideoDownloadUrl(magnet);
        download.setVideoDownloadMagnetic(magnet);
        download.setVideoDownloadSize(size);
        download.setVideoDownloadType("magnet");
        download.setVideoDownloadPlatform("btmulu");
        download.setVideoDownloadQuality(format);
        download.setVideoDownloadShareTime(publishTime);
        return download;
    }

    /**
     * 解析结果总数。
     *
     * @param document 搜索页文档
     * @param fallback 解析失败时的兜底值
     * @return 结果总数
     */
    private static long parseTotal(Document document, int fallback) {
        Element summary = document.selectFirst("div.summary");
        if (summary != null) {
            Matcher matcher = TOTAL_PATTERN.matcher(clean(summary.text()));
            if (matcher.find()) {
                try {
                    return Long.parseLong(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    // 忽略，走兜底
                }
            }
        }
        return fallback;
    }

    /**
     * 从文本中提取首个分组。
     *
     * @param pattern 正则
     * @param text    文本
     * @return 命中分组，未命中返回 空字符串
     */
    private static String group(Pattern pattern, String text) {
        if (StringUtils.hasText(text)) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return clean(matcher.group(1));
            }
        }
        return "";
    }

    /**
     * 解析条目数字 ID。
     *
     * @param value 原始值
     * @return 数字 ID，解析失败返回 空
     */
    private static Integer parseId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析发布时间。
     *
     * @param value 时间文本
     * @return 时间，解析失败返回 空
     */
    private static LocalDateTime parseTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), TIME_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建备注（热度/文件数）。
     *
     * @param heat      热度
     * @param fileCount 文件数量
     * @return 备注文本
     */
    private static String buildRemark(String heat, String fileCount) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(heat)) {
            builder.append("热度=").append(heat);
        }
        if (StringUtils.hasText(fileCount)) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append("文件数=").append(fileCount);
        }
        return builder.toString();
    }

    /**
     * 截断文本。
     *
     * @param value 原文本
     * @param max   最大长度
     * @return 截断后的文本
     */
    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 清洗文本（去不换行空格、压缩空白）。
     *
     * @param value 原文本
     * @return 清洗后的文本
     */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    /**
     * URL 编码。
     *
     * @param value 原值
     * @return 编码后的值（空格编码为 %20）
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 获取站点地址（支持 {@link VideoSource} 注入覆盖）。
     *
     * @return 站点地址（无末尾斜杠）
     */
    protected String getBaseUrl() {
        if (videoSource != null && StringUtils.hasText(videoSource.getVideoSourceUrl())) {
            String url = videoSource.getVideoSourceUrl().trim();
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        return DEFAULT_BASE_URL;
    }

    /**
     * 提供者名称。
     *
     * @return 名称
     */
    public String name() {
        return "btmulu";
    }
}
