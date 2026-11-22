// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.ai.news;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AIHOT（aihot.news）精选资讯抓取器：拉取 RSS 2.0 端点并解析成
 * {@link AiHotNewsItem} 列表。
 *
 * <p>端点：{@code https://aihot.news/feed.xml}（无需 Key，约 50 条精选摘要，
 * 自带 {@code ttl=30} 分钟）。本类是纯 Java 实现，不依赖 Spring；落库与接口
 * 编排在业务侧（ai-starter）完成。</p>
 *
 * <p>容错口径：任一条 item 解析失败只跳过该条；整体抓取/解析失败返回空列表，
 * 由调用方决定是否沿用库中已有数据。</p>
 *
 * @author CH
 * @since 4.0.0.70
 */
@Slf4j
public final class AiHotNewsFetcher {

    /**
     * AIHOT 精选 RSS 端点（无需 Key）
     */
    public static final String DEFAULT_FEED_URL = "https://aihot.news/feed.xml";

    /**
     * 来源标识（与指标实现 {@code AiHotChatClientIndicator.SOURCE} 一致）
     */
    public static final String SOURCE = "aihot";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);

    /**
     * 描述里的站外原文链接（{@code <a href="https://...">阅读原文</a>}）
     */
    private static final Pattern HREF = Pattern.compile("href=\"(https?://[^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final String feedUrl;

    public AiHotNewsFetcher() {
        this(DEFAULT_FEED_URL);
    }

    public AiHotNewsFetcher(String feedUrl) {
        this.feedUrl = feedUrl == null || feedUrl.isBlank() ? DEFAULT_FEED_URL : feedUrl;
    }

    /**
     * 抓取并解析 RSS。
     *
     * @return 资讯条目列表；抓取或解析失败返回空列表
     */
    public List<AiHotNewsItem> fetch() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(feedUrl))
                    .timeout(DEFAULT_TIMEOUT)
                    .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                log.warn("[AiHotNews] 拉取失败 status={} url={}", response.statusCode(), feedUrl);
                return List.of();
            }
            return parse(new String(response.body(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[AiHotNews] 拉取异常 url={}: {}", feedUrl, e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 RSS 2.0 XML（DOM；禁用外部实体，只消费 item 的固定几个子元素）。
     *
     * @param xml RSS 文本
     * @return 条目列表；解析失败返回空列表
     */
    private List<AiHotNewsItem> parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                    .getDocumentElement();
            NodeList items = root.getElementsByTagName("item");
            List<AiHotNewsItem> result = new ArrayList<>(items.getLength());
            for (int i = 0; i < items.getLength(); i++) {
                if (!(items.item(i) instanceof Element item)) {
                    continue;
                }
                AiHotNewsItem parsed = parseItem(item);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[AiHotNews] RSS 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析单个 item。
     *
     * @param item item 元素
     * @return 条目；title 或 link 缺失时返回 null（无法去重定位，丢弃）
     */
    private AiHotNewsItem parseItem(Element item) {
        String title = textOf(item, "title");
        String link = textOf(item, "link");
        if (title == null || link == null) {
            return null;
        }
        String description = textOf(item, "description");
        return AiHotNewsItem.builder()
                .title(title)
                .link(link)
                .description(description)
                .descriptionText(description == null ? "" : stripHtml(description))
                .originUrl(originUrlOf(description, link))
                .category(textOf(item, "category"))
                .publishedAt(parsePubDate(textOf(item, "pubDate")))
                .build();
    }

    /**
     * 取元素下第一个同名子元素的文本（CDATA 一并取出）。
     *
     * @param parent 父元素
     * @param tag    子元素名
     * @return 文本（trim）；缺失返回 null
     */
    private static String textOf(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes == null || nodes.getLength() == 0) {
            return null;
        }
        String text = nodes.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    /**
     * 从描述里解析原文链接：第一个非 AIHOT 站内（aihot.news）的 http(s) 地址。
     *
     * @param description   原始描述（HTML）
     * @param fallbackTitle 站内链接（用于判定主机归属）
     * @return 原文链接；未解析出返回 null
     */
    private static String originUrlOf(String description, String fallbackTitle) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String selfHost = hostOf(fallbackTitle);
        Matcher matcher = HREF.matcher(description);
        while (matcher.find()) {
            String url = matcher.group(1).trim();
            String host = hostOf(url);
            if (host.isEmpty()) {
                continue;
            }
            if (selfHost.isEmpty() || !host.equalsIgnoreCase(selfHost)) {
                return url;
            }
        }
        return null;
    }

    /**
     * 取 URL 的主机名（小写、去 www. 前缀）。
     *
     * @param url 地址
     * @return 主机名；解析失败返回空串
     */
    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) {
                return "";
            }
            host = host.toLowerCase();
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * RSS pubDate（RFC 822/1123，如 {@code Fri, 20 Nov 2026 22:17:00 GMT}）→ 毫秒。
     *
     * @param pubDate 日期文本
     * @return 毫秒；解析失败返回 null
     */
    private static Long parsePubDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(pubDate.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * HTML → 纯文本：去标签、还原常见实体、合并空白。
     *
     * @param html 原始 HTML
     * @return 纯文本
     */
    private static String stripHtml(String html) {
        String text = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        text = text.replaceAll("(?s)<[^>]*>", " ");
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        return text.replaceAll("\\s+", " ").trim();
    }
}
