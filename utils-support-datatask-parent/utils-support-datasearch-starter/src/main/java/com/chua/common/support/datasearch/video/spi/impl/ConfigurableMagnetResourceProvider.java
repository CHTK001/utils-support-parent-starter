package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonNode;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用可配置 HTML 磁力资源提供者。
 *
 * <p>不绑定具体站点：搜索地址模板与各 CSS 选择器全部来自配置，
 * 因此新增一个磁力站通常只需要填配置，不必再写实现类。</p>
 *
 * <p>配置来源（按优先级）：</p>
 * <ol>
 *   <li>{@link VideoSource} 对应字段（编程方式注入）；</li>
 *   <li>系统属性，如 {@code -Dvideo.search.html.item=.result}；</li>
 *   <li>环境变量，如 {@code VIDEO_SEARCH_HTML_ITEM=.result}。</li>
 * </ol>
 *
 * <p>配置项：</p>
 * <table border="1">
 *   <caption>配置项说明</caption>
 *   <tr><th>VideoSource 字段</th><th>系统属性</th><th>说明</th></tr>
 *   <tr><td>videoSourceUrl</td><td>video.search.html.url</td>
 *       <td>搜索地址模板，支持 {@code {kw}}、{@code {page}}、{@code {pageIndex}}</td></tr>
 *   <tr><td>videoSourceItemSelector</td><td>video.search.html.item</td>
 *       <td>结果条目选择器（必填）</td></tr>
 *   <tr><td>videoSourceTitleSelector</td><td>video.search.html.title</td>
 *       <td>条目内标题选择器</td></tr>
 *   <tr><td>videoSourceMagnetSelector</td><td>video.search.html.magnet</td>
 *       <td>磁力元素选择器，默认 {@code a[href^=magnet:]}</td></tr>
 *   <tr><td>videoSourceDetailSelector</td><td>video.search.html.detail</td>
 *       <td>详情页链接选择器，列表页无磁力时二次抓取</td></tr>
 *   <tr><td>videoSourceSizeSelector</td><td>video.search.html.size</td>
 *       <td>条目内大小选择器</td></tr>
 *   <tr><td>videoSourceDateSelector</td><td>video.search.html.date</td>
 *       <td>条目内发布日期选择器</td></tr>
 * </table>
 *
 * <p>未配置搜索地址时返回空页（不报错），避免在聚合检索中产生噪声；
 * 已配置但缺少条目选择器时返回错误提示。</p>
 *
 * <p>两级抓取时详情页请求数量受 {@link #MAX_DETAIL_REQUESTS} 限制，
 * 避免一次检索放大成大量请求。</p>
 *
 * <p>示例（nyaa）：</p>
 * <pre>{@code
 * VideoSource source = new VideoSource();
 * source.setVideoSourceUrl("https://nyaa.si/?f=0&c=0_0&q={kw}&p={page}");
 * source.setVideoSourceItemSelector("table.torrent-list tbody tr");
 * source.setVideoSourceTitleSelector("td:nth-child(2) a:not(.comments)");
 * ConfigurableMagnetResourceProvider provider = new ConfigurableMagnetResourceProvider(source);
 * }</pre>
 *
 * <p>多站点：聚合场景下可一次配置多个站点，结果合并后按 infohash 去重，
 * 单个站点失败不影响其它站点。配置值为 JSON 数组：</p>
 * <pre>{@code
 * -Dvideo.search.html.sites=[{"name":"nyaa",
 *   "url":"https://nyaa.si/?f=0&c=0_0&q={kw}&p={page}",
 *   "item":"table.torrent-list tbody tr",
 *   "title":"td:nth-child(2) a:not(.comments)",
 *   "size":"td:nth-child(4)","date":"td:nth-child(5)"},
 *  {"name":"zhongziso","url":"https://www.zhongziso.com/list/{kw}/{page}",
 *   "item":"tbody","title":"h4 a","size":"strong","date":"strong:nth-of-type(2)"}]
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("html-magnet")
public class ConfigurableMagnetResourceProvider extends AbstractResourceProvider {

    /**
     * 提供者名称
     */
    private static final String NAME = "html-magnet";

    /**
     * 系统属性前缀
     */
    private static final String PROP_PREFIX = "video.search.html.";

    /**
     * 环境变量前缀
     */
    private static final String ENV_PREFIX = "VIDEO_SEARCH_HTML_";

    /**
     * 多站点配置键
     */
    private static final String SITES_KEY = "sites";

    /**
     * 默认磁力选择器
     */
    private static final String DEFAULT_MAGNET_SELECTOR = "a[href^=magnet:]";

    /**
     * 单次检索允许的详情页请求上限
     */
    private static final int MAX_DETAIL_REQUESTS = 5;

    /**
     * 单页结果条数上限
     */
    private static final int MAX_RESULTS = 100;

    /**
     * 磁力链接
     */
    private static final Pattern MAGNET_PATTERN = Pattern.compile("magnet:\\?xt=urn:btih:[^\"'\\s<>]+");

    /**
     * infohash（40 位十六进制或 32 位 base32）
     */
    private static final Pattern BTIH_PATTERN = Pattern.compile("urn:btih:([0-9a-zA-Z]{32,40})");

    /**
     * 形如域名的 {@code dn}（视为站点填充的占位值，需用真实标题覆盖）
     */
    private static final Pattern DOMAIN_PATTERN =
            Pattern.compile("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+$");

    /**
     * 可能承载磁力的属性
     */
    private static final String[] MAGNET_ATTRS = {
            "href", "data-magnet", "data-clipboard-text", "data-clipboard", "value"
    };

    /**
     * 日期时间格式
     */
    private static final List<DateTimeFormatter> DATE_TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

    /**
     * 日期格式
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"));

    /**
     * 创建 通用可配置磁力资源提供者 实例
     */
    public ConfigurableMagnetResourceProvider() {
        super();
    }

    /**
     * 创建 通用可配置磁力资源提供者 实例
     *
     * @param videoSource 视频源（承载站点与选择器配置）
     */
    public ConfigurableMagnetResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch == null ? "" : videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        ReturnPageResult<VideoInfoResult> blocked = checkBlocked(NAME);
        if (blocked != null) {
            return blocked;
        }

        List<ScrapeConfig> sites = ScrapeConfig.sites(videoSource);
        // 未配置站点：返回空页，避免聚合检索被未启用的通用源干扰
        if (sites.isEmpty()) {
            return ReturnPageResult.empty();
        }
        List<ScrapeConfig> usable = Wraps.list();
        for (ScrapeConfig site : sites) {
            if (StringUtils.hasText(site.itemSelector)) {
                usable.add(site);
            }
        }
        if (usable.isEmpty()) {
            return ReturnPageResult.error("html-magnet 未配置条目选择器（videoSourceItemSelector）");
        }

        int page = Math.max(1, videoSearch.getPage());
        int limit = limit(videoSearch, usable.get(0));
        List<VideoInfoResult> results = Wraps.list();
        Set<String> seen = new HashSet<>();
        Exception lastError = null;
        for (ScrapeConfig site : usable) {
            try {
                String url = buildUrl(site.urlTemplate, keyword, page);
                String html = MagnetSupport.get(url, referer(url));
                for (VideoInfoResult item : parsePage(html, url, limit(videoSearch, site), site)) {
                    if (seen.add(item.getVideoOriginId())) {
                        results.add(item);
                    }
                }
            } catch (Exception e) {
                // 单个站点失败不影响其它站点
                lastError = e;
            }
        }
        if (results.isEmpty()) {
            if (lastError != null && usable.size() == 1) {
                return ReturnPageResult.error("html-magnet 磁力检索失败: " + lastError.getMessage());
            }
            return ReturnPageResult.empty();
        }
        return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                .data(results)
                .pageNo(page)
                .pageSize(limit)
                .total((long) page * limit)
                .totalPages(page + 1)
                .build());
    }

    /**
     * 解析列表页。
     *
     * <p>包级可见，便于用固定 HTML 做离线回归。</p>
     *
     * @param html    页面 HTML
     * @param baseUri 页面地址（用于相对链接补全）
     * @param limit   结果条数上限
     * @param config  抓取配置
     * @return 结果列表
     */
    List<VideoInfoResult> parsePage(String html, String baseUri, int limit, ScrapeConfig config) {
        List<VideoInfoResult> results = Wraps.list();
        if (config == null || !StringUtils.hasText(config.itemSelector)) {
            return results;
        }
        Document document = Jsoup.parse(html == null ? "" : html, baseUri == null ? "" : baseUri);
        Set<String> seen = new HashSet<>();
        int detailRequests = 0;
        for (Element item : document.select(config.itemSelector)) {
            if (results.size() >= limit) {
                break;
            }
            String title = title(item, config);
            if (!StringUtils.hasText(title)) {
                continue;
            }
            String magnet = magnetOf(item, config.magnetSelector);
            if (!StringUtils.hasText(magnet) && StringUtils.hasText(config.detailSelector)
                    && detailRequests < MAX_DETAIL_REQUESTS) {
                detailRequests++;
                magnet = resolveDetail(selectHref(item, config.detailSelector), baseUri, config.magnetSelector);
            }
            String hash = MagnetSupport.group(BTIH_PATTERN, magnet);
            if (!StringUtils.hasText(hash) || !seen.add(hash.toLowerCase(Locale.ROOT))) {
                continue;
            }
            results.add(build(item, title, magnet, hash, config));
        }
        return results;
    }

    /**
     * 构建搜索地址。
     *
     * @param template 地址模板
     * @param keyword  关键词
     * @param page     页码（从 1 开始）
     * @return 搜索地址
     */
    static String buildUrl(String template, String keyword, int page) {
        String encoded = MagnetSupport.encode(keyword);
        // pageIndex 先替换，避免 {page} 命中 {pageIndex} 的前缀
        return template
                .replace("{kw}", encoded)
                .replace("{keyword}", encoded)
                .replace("{pageIndex}", String.valueOf(Math.max(0, page - 1)))
                .replace("{page}", String.valueOf(Math.max(1, page)));
    }

    /**
     * 提取条目标题。
     *
     * @param item   结果条目
     * @param config 抓取配置
     * @return 标题
     */
    private static String title(Element item, ScrapeConfig config) {
        Element element = StringUtils.hasText(config.titleSelector)
                ? item.selectFirst(config.titleSelector)
                : item.selectFirst("a[href]:not([href^=magnet:]):not([href^=javascript])");
        String text = element == null ? item.text() : element.text();
        return MagnetSupport.clean(text);
    }

    /**
     * 提取条目内的磁力。
     *
     * @param item     结果条目
     * @param selector 磁力选择器
     * @return 磁力链接，未命中返回 空字符串
     */
    private static String magnetOf(Element item, String selector) {
        Element element = item.selectFirst(StringUtils.hasText(selector) ? selector : DEFAULT_MAGNET_SELECTOR);
        return magnetValue(element);
    }

    /**
     * 从元素属性或文本中提取磁力。
     *
     * @param element 元素
     * @return 磁力链接，未命中返回 空字符串
     */
    private static String magnetValue(Element element) {
        if (element == null) {
            return "";
        }
        for (String attr : MAGNET_ATTRS) {
            String magnet = findMagnet(element.attr(attr));
            if (StringUtils.hasText(magnet)) {
                return magnet;
            }
        }
        return findMagnet(element.text());
    }

    /**
     * 在文本中查找磁力链接。
     *
     * @param value 文本
     * @return 磁力链接，未命中返回 空字符串
     */
    private static String findMagnet(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        Matcher matcher = MAGNET_PATTERN.matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    /**
     * 读取条目内链接的绝对地址。
     *
     * @param item     结果条目
     * @param selector 链接选择器
     * @return 绝对地址，未命中返回 空字符串
     */
    private static String selectHref(Element item, String selector) {
        Element element = item.selectFirst(selector);
        if (element == null || !StringUtils.hasText(element.attr("href"))) {
            return "";
        }
        String absolute = element.absUrl("href");
        return StringUtils.hasText(absolute) ? absolute : element.attr("href");
    }

    /**
     * 抓取详情页并提取磁力。
     *
     * @param href           详情页地址
     * @param referer        来源页
     * @param magnetSelector 磁力选择器
     * @return 磁力链接，失败返回 空字符串
     */
    private static String resolveDetail(String href, String referer, String magnetSelector) {
        if (!StringUtils.hasText(href) || !href.startsWith("http")) {
            return "";
        }
        try {
            String html = MagnetSupport.get(href, referer);
            Document document = Jsoup.parse(html, href);
            Element element = document.selectFirst(StringUtils.hasText(magnetSelector)
                    ? magnetSelector : DEFAULT_MAGNET_SELECTOR);
            return magnetValue(element);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 组装单条结果。
     *
     * @param item   结果条目
     * @param title  标题
     * @param magnet 磁力
     * @param hash   infohash
     * @param config 抓取配置
     * @return 视频信息
     */
    private static VideoInfoResult build(Element item, String title, String magnet,
                                         String hash, ScrapeConfig config) {
        String size = selectText(item, config.sizeSelector);
        String date = selectText(item, config.dateSelector);
        String normalized = normalizeMagnet(magnet, title);
        String lowerHash = hash.toLowerCase(Locale.ROOT);

        VideoInfoResult info = new VideoInfoResult();
        info.setVideoOriginId(lowerHash);
        info.setVideoName(title);
        info.setVideoTitle(title);
        info.setVideoPlatform(NAME);
        info.setVideoUrl(normalized);
        info.setDownloadUrls(normalized);
        info.setVideoSize(size);
        info.setVideoPublishDate(parseDate(date));
        info.setVideoType("video");
        info.setVideoCategory("video");
        info.setTags(StringUtils.hasText(config.siteName) && !NAME.equals(config.siteName)
                ? "magnet," + NAME + "," + config.siteName
                : "magnet," + NAME);
        info.setDownloadList(List.of(MagnetSupport.download(title, normalized, size, NAME, date, "")));
        return info;
    }

    /**
     * 规范化磁力：{@code dn} 缺失或是站点域名占位值时，替换为真实标题。
     *
     * @param magnet 原始磁力
     * @param title  资源标题
     * @return 规范化后的磁力
     */
    static String normalizeMagnet(String magnet, String title) {
        if (!StringUtils.hasText(magnet)) {
            return magnet;
        }
        int dn = magnet.indexOf("&dn=");
        if (dn < 0) {
            return magnet + "&dn=" + MagnetSupport.encode(title);
        }
        int end = magnet.indexOf('&', dn + 4);
        String value = end < 0 ? magnet.substring(dn + 4) : magnet.substring(dn + 4, end);
        // 已有有意义的名字时保持站点原样
        if (StringUtils.hasText(value) && !DOMAIN_PATTERN.matcher(value).matches()) {
            return magnet;
        }
        String head = magnet.substring(0, dn) + "&dn=" + MagnetSupport.encode(title);
        return end < 0 ? head : head + magnet.substring(end);
    }

    /**
     * 读取条目内选择器文本。
     *
     * @param item     结果条目
     * @param selector 选择器
     * @return 文本，未配置或未命中返回 空字符串
     */
    private static String selectText(Element item, String selector) {
        if (!StringUtils.hasText(selector)) {
            return "";
        }
        Element element = item.selectFirst(selector);
        return element == null ? "" : MagnetSupport.clean(element.text());
    }

    /**
     * 解析发布日期，支持 {@code yyyy-MM-dd}、{@code yyyy/MM/dd}、
     * {@code yyyy.MM.dd} 及其带时分秒的形式。
     *
     * @param value 日期文本
     * @return 时间，解析失败返回 空
     */
    static LocalDateTime parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.trim();
        for (DateTimeFormatter format : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, format);
            } catch (Exception ignored) {
                // 继续尝试下一种格式
            }
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(text, format).atStartOfDay();
            } catch (Exception ignored) {
                // 继续尝试下一种格式
            }
        }
        return null;
    }

    /**
     * 计算单页结果上限。
     *
     * @param search 搜索参数
     * @param config 抓取配置
     * @return 上限
     */
    private static int limit(VideoSearch search, ScrapeConfig config) {
        int limit = config.maxResource != null ? config.maxResource : search.getPageSize();
        if (limit <= 0) {
            limit = 10;
        }
        return Math.min(limit, MAX_RESULTS);
    }

    /**
     * 取搜索地址的站点根地址作为 Referer。
     *
     * @param url 搜索地址
     * @return 站根地址，解析失败返回 空字符串
     */
    private static String referer(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getAuthority() + "/";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 提供者名称。
     *
     * @return 名称
     */
    public String name() {
        return NAME;
    }

    /**
     * 通用抓取配置。
     *
     * <p>字段包级可见，便于离线测试直接构造与断言。</p>
     */
    static final class ScrapeConfig {

        /**
         * 站点标识
         */
        final String siteName;

        /**
         * 搜索地址模板
         */
        final String urlTemplate;

        /**
         * 结果条目选择器
         */
        final String itemSelector;

        /**
         * 标题选择器
         */
        final String titleSelector;

        /**
         * 磁力选择器
         */
        final String magnetSelector;

        /**
         * 详情页链接选择器
         */
        final String detailSelector;

        /**
         * 大小选择器
         */
        final String sizeSelector;

        /**
         * 发布日期选择器
         */
        final String dateSelector;

        /**
         * 单页结果上限
         */
        final Integer maxResource;

        private ScrapeConfig(String siteName, String urlTemplate, String itemSelector, String titleSelector,
                             String magnetSelector, String detailSelector, String sizeSelector,
                             String dateSelector, Integer maxResource) {
            this.siteName = siteName;
            this.urlTemplate = urlTemplate;
            this.itemSelector = itemSelector;
            this.titleSelector = titleSelector;
            this.magnetSelector = magnetSelector;
            this.detailSelector = detailSelector;
            this.sizeSelector = sizeSelector;
            this.dateSelector = dateSelector;
            this.maxResource = maxResource;
        }

        /**
         * 解析配置：{@link VideoSource} 字段优先，其次系统属性，最后环境变量。
         *
         * @param source 视频源
         * @return 抓取配置
         */
        static ScrapeConfig of(VideoSource source) {
            return new ScrapeConfig(
                    NAME,
                    value(source == null ? null : source.getVideoSourceUrl(), "url"),
                    value(source == null ? null : source.getVideoSourceItemSelector(), "item"),
                    value(source == null ? null : source.getVideoSourceTitleSelector(), "title"),
                    value(source == null ? null : source.getVideoSourceMagnetSelector(), "magnet"),
                    value(source == null ? null : source.getVideoSourceDetailSelector(), "detail"),
                    value(source == null ? null : source.getVideoSourceSizeSelector(), "size"),
                    value(source == null ? null : source.getVideoSourceDateSelector(), "date"),
                    source == null ? null : source.getVideoSourceMaxResource());
        }

        /**
         * 解析站点列表。
         *
         * <p>配置了 {@code video.search.html.sites}（JSON 数组）时按数组解析，
         * 否则回退到 {@link VideoSource} 单站点配置。</p>
         *
         * @param source 视频源
         * @return 站点配置列表，未配置返回空列表
         */
        static List<ScrapeConfig> sites(VideoSource source) {
            String raw = value(null, SITES_KEY);
            if (!StringUtils.hasText(raw)) {
                ScrapeConfig single = of(source);
                return StringUtils.hasText(single.urlTemplate) ? List.of(single) : List.of();
            }
            List<ScrapeConfig> sites = Wraps.list();
            try {
                JsonNode root = Json.parse(raw);
                if (root == null || !root.isArray()) {
                    return List.of();
                }
                for (int i = 0; i < root.size(); i++) {
                    JsonNode node = root.get(i);
                    if (node == null || !node.isObject()) {
                        continue;
                    }
                    String url = text(node, "url");
                    if (!StringUtils.hasText(url)) {
                        continue;
                    }
                    String name = text(node, "name");
                    JsonNode maxNode = node.get("max");
                    Integer max = maxNode == null || maxNode.isMissingValue() ? null : maxNode.toIntValue(0);
                    sites.add(new ScrapeConfig(
                            StringUtils.hasText(name) ? name : NAME + "-" + (i + 1),
                            url,
                            text(node, "item"),
                            text(node, "title"),
                            text(node, "magnet"),
                            text(node, "detail"),
                            text(node, "size"),
                            text(node, "date"),
                            max != null && max > 0 ? max : null));
                }
            } catch (Exception e) {
                return List.of();
            }
            return sites;
        }

        /**
         * 读取 JSON 节点文本。
         *
         * @param node 节点
         * @param key  键
         * @return 文本，缺失返回 空字符串
         */
        private static String text(JsonNode node, String key) {
            JsonNode value = node.get(key);
            return value == null || value.isMissingValue() ? "" : value.toStringValue("").trim();
        }

        /**
         * 解析单项配置。
         *
         * @param fieldValue 字段值
         * @param key        配置键
         * @return 配置值
         */
        private static String value(String fieldValue, String key) {
            if (StringUtils.hasText(fieldValue)) {
                return fieldValue.trim();
            }
            String property = System.getProperty(PROP_PREFIX + key);
            if (StringUtils.hasText(property)) {
                return property.trim();
            }
            String env = System.getenv(ENV_PREFIX + key.toUpperCase(Locale.ROOT));
            return env == null ? "" : env.trim();
        }
    }
}
