package com.chua.common.support.datasearch.emoji.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.datasearch.emoji.model.EmojiInfo;
import com.chua.common.support.datasearch.emoji.spi.EmojiProvider;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Bing 图片检索的通用表情包提供器。
 *
 * <p>数据源：Bing 图片搜索，检索词为 {@code 关键词 表情包}。
 * 相比垂直站点，通用检索覆盖面更广，可作为垂直数据源未命中时的兜底；
 * 结果取自结果项 {@code a.iusc} 上承载的 {@code m} JSON（原图 / 缩略图 / 来源页 / 标题）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("bing")
public class BingEmojiProvider implements EmojiProvider {

    /**
     * 日志
     */
    private static final Logger log = LoggerFactory.getLogger(BingEmojiProvider.class);

    /**
     * 图片搜索地址
     */
    private static final String SEARCH_URL = "https://www.bing.com/images/search";

    /**
     * 结果项选择器
     */
    private static final String ITEM_SELECTOR = "a.iusc";

    /**
     * 单页检索条数
     */
    private static final int PAGE_SIZE = 35;

    /**
     * 检索词后缀
     */
    private static final String QUERY_SUFFIX = " 表情包";

    /**
     * 模拟浏览器的 User-Agent
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * JSON 映射器
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * HTTP 客户端
     */
    private final HttpClient httpClient;

    /**
     * 创建 Bing 表情包提供器。
     */
    public BingEmojiProvider() {
        this.httpClient = HttpClientFactory.getClient();
    }

    @Override
    /**
     * 名称
     */
    public String name() {
        return "bing";
    }

    @Override
    /**
     * 搜索
     */
    public List<EmojiInfo> search(String keyword, int page) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        int pageNo = page < 1 ? 1 : page;
        int first = 1 + (pageNo - 1) * PAGE_SIZE;
        String url = SEARCH_URL + "?q=" + encode(keyword + QUERY_SUFFIX)
                + "&first=" + first + "&count=" + PAGE_SIZE;
        Document document = loadDocument(url);
        if (document == null) {
            return Collections.emptyList();
        }
        List<EmojiInfo> result = Wraps.list();
        Elements items = document.select(ITEM_SELECTOR);
        for (Element item : items) {
            EmojiInfo info = parseItem(item);
            if (info != null) {
                result.add(info);
            }
        }
        log.info("[bing] 关键词[{}] 第{}页命中 {} 条", keyword, pageNo, result.size());
        return result;
    }

    /**
     * 加载并解析 HTML 文档。
     *
     * @param url 请求地址
     * @return 解析后的文档，失败返回 空
     */
    private Document loadDocument(String url) {
        try {
            ClientResponse response = httpClient.request(url, HttpMethod.GET)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.bing.com/")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .execute();
            if (!response.isSuccess() || response.getBody() == null) {
                log.warn("[bing] 请求失败: url={}, status={}", url, response.getStatusCode());
                return null;
            }
            return Jsoup.parse(new String(response.getBody(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[bing] 请求异常: url={}, msg={}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 解析单条检索结果。
     *
     * @param item 结果项元素
     * @return 表情包信息，解析失败返回 空
     */
    private EmojiInfo parseItem(Element item) {
        String meta = item.attr("m");
        if (meta.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(meta);
            String imageUrl = text(node, "murl");
            if (imageUrl.isBlank()) {
                return null;
            }
            String title = text(node, "t");
            return new EmojiInfo("", title, imageUrl, text(node, "turl"),
                    text(node, "purl"), name(), Collections.emptyList());
        } catch (Exception e) {
            log.debug("[bing] 结果项解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 读取 JSON 文本字段。
     *
     * @param node 节点
     * @param field 字段名
     * @return 字段值，缺失返回 空字符串
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? "" : value.asText("");
    }

    /**
     * URL 编码关键词。
     *
     * @param keyword 关键词
     * @return 编码后的关键词
     */
    private static String encode(String keyword) {
        return URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8).replace("+", "%20");
    }
}
