package com.chua.common.support.datasearch.emoji.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.datasearch.emoji.model.EmojiInfo;
import com.chua.common.support.datasearch.emoji.spi.EmojiProvider;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.spi.annotations.Spi;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于「斗图啦」垂直站点的表情包提供器。
 *
 * <p>数据源：<a href="http://www.doutula.com">斗图啦</a>，
 * 通过站内搜索页 {@code /search?keyword={关键词}} 抓取结果卡片，
 * 解析标题、原图地址与详情页地址。</p>
 *
 * <p>该站搜索接口不区分页码（同一关键词多次请求结果稳定），
 * 因此这里一次性抓取该关键词的全量结果并缓存，再按 {@link #PAGE_SIZE} 在内存中分页，
 * 从而让 {@link #search(String, int)} 的页码语义对调用方保持有效。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("doutula")
public class DoutulaEmojiProvider implements EmojiProvider {

    /**
     * 日志
     */
    private static final Logger log = LoggerFactory.getLogger(DoutulaEmojiProvider.class);

    /**
     * 站点基础地址（该站点 HTTPS 证书已失效，使用 HTTP 访问）
     */
    private static final String BASE_URL = "http://www.doutula.com";

    /**
     * 模拟浏览器的 User-Agent
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * 每页条数（内存分页）
     */
    private static final int PAGE_SIZE = 30;

    /**
     * 结果卡片内的可点击条目选择器
     */
    private static final String ITEM_SELECTOR = "div.pic-content a[href]";

    /**
     * 详情页 ID 提取模式
     */
    private static final Pattern ID_PATTERN = Pattern.compile("/photo/(\\d+)");

    /**
     * HTTP 客户端
     */
    private final HttpClient httpClient;

    /**
     * 关键词结果缓存（关键词语料在该站是静态的，缓存可显著降低抓取次数）
     */
    private final Map<String, List<EmojiInfo>> cache = new ConcurrentHashMap<>();

    /**
     * 创建「斗图啦」表情包提供器。
     */
    public DoutulaEmojiProvider() {
        this.httpClient = HttpClientFactory.getClient();
    }

    @Override
    /**
     * 名称
     */
    public String name() {
        return "doutula";
    }

    @Override
    /**
     * 搜索
     */
    public List<EmojiInfo> search(String keyword, int page) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        List<EmojiInfo> all = load(keyword);
        int from = (page < 1 ? 0 : page - 1) * PAGE_SIZE;
        if (from >= all.size()) {
            return Collections.emptyList();
        }
        int to = Math.min(from + PAGE_SIZE, all.size());
        return Wraps.list(all.subList(from, to));
    }

    /**
     * 加载关键词的全量结果（惰性 + 缓存）。
     *
     * @param keyword 关键词
     * @return 全量表情包列表
     */
    private List<EmojiInfo> load(String keyword) {
        String key = keyword.trim();
        List<EmojiInfo> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<EmojiInfo> list = Wraps.list();
        Document document = loadDocument(BASE_URL + "/search?keyword=" + encode(key));
        if (document != null) {
            Elements items = document.select(ITEM_SELECTOR);
            for (Element item : items) {
                EmojiInfo info = parseItem(item);
                if (info != null) {
                    list.add(info);
                }
            }
        }
        log.info("[doutula] 关键词[{}] 抓取 {} 条", key, list.size());
        cache.put(key, list);
        return list;
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
                    .header("Referer", BASE_URL + "/")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .execute();
            if (!response.isSuccess() || response.getBody() == null) {
                log.warn("[doutula] 请求失败: url={}, status={}", url, response.getStatusCode());
                return null;
            }
            return Jsoup.parse(new String(response.getBody(), StandardCharsets.UTF_8), BASE_URL);
        } catch (Exception e) {
            log.warn("[doutula] 请求异常: url={}, msg={}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 解析单条结果。
     *
     * <p>结果卡片里混有占位锚点（仅含 loader 图、无 {@code data-original}），
     * 这类条目会被跳过。</p>
     *
     * @param item 条目元素
     * @return 表情包信息，条目无效时返回 空
     */
    private EmojiInfo parseItem(Element item) {
        Element img = item.selectFirst("img[data-original]");
        if (img == null) {
            return null;
        }
        String imageUrl = img.attr("data-original").trim();
        if (imageUrl.isEmpty()) {
            return null;
        }
        String href = item.attr("href");
        Element title = item.selectFirst("p");
        return new EmojiInfo(extractId(href), title == null ? "" : title.text().trim(),
                imageUrl, imageUrl, href, name(), Collections.emptyList());
    }

    /**
     * 从详情链接中提取表情 ID。
     *
     * @param href 详情链接
     * @return 表情 ID，未匹配返回 空字符串
     */
    private static String extractId(String href) {
        if (href == null) {
            return "";
        }
        Matcher matcher = ID_PATTERN.matcher(href);
        return matcher.find() ? matcher.group(1) : "";
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
