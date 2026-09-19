package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.datasearch.video.model.SubtitleSearchRequest;
import com.chua.common.support.datasearch.video.model.SubtitleSearchResult;
import com.chua.common.support.datasearch.video.spi.SubtitleSearchProvider;
import com.chua.common.support.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * subhd 字幕搜索提供者。
 *
 * <p>主链路调用 subhd.tv API；API 失效（404/非 JSON/无 data 字段）时自动降级为
 * subhd.tv HTML 搜索页抓取，从 "的搜索结果" 之后的结果卡片区域提取字幕条目，
 * 避免命中侧栏的 IMDb 热门推荐列表。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("subhd")
public class DemoSubtitleSearchProvider implements SubtitleSearchProvider {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(DemoSubtitleSearchProvider.class);
    /** subhd 搜索接口地址（当前已 404，仅作兼容保留，正常走 HTML 降级） */
    private static final String SUBHD_API_SEARCH_URL = "https://subhd.tv/api/search";
    /** subhd HTML 搜索页地址（API 失效时的降级路径） */
    private static final String SUBHD_HTML_SEARCH_URL = "https://subhd.tv/search/";
    /** JSON 对象映射器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 用户代理字符串 */
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    /** 结果区域起点标记：搜索结果标题（例如 "盗梦空间 的搜索结果"） */
    private static final String RESULT_REGION_MARKER = "的搜索结果";
    /** 结果区域向后扩展的字符数（一般足够覆盖一页结果卡片） */
    private static final int REGION_EXTENSION_CHARS = 20000;
    /** 卡片尾部扩展字符数：截取卡片正文文本所用范围 */
    private static final int CARD_TAIL_EXTENSION_CHARS = 3000;
    /** 结果卡片模板中的字幕详情链接，同时捕获单/双引号 href */
    private static final Pattern CARD_PATTERN =
            Pattern.compile("<a[^>]+href=[\"'](/d/[^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    /** 结果卡片模板中的字幕条目链接（/a/xxx），用于提取条目编号 */
    private static final Pattern ENTRY_PATTERN =
            Pattern.compile("<a[^>]+href=[\"'](/a/[^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL);
    /** 字幕语言标签匹配（卡片正文中的 语言/格式 字段） */
    private static final Pattern LANG_PATTERN =
            Pattern.compile("zh|en|双语|English|Chinese", Pattern.CASE_INSENSITIVE);

    /**
    * 创建 subhd 字幕搜索提供者实例（无参构造，供 SPI 框架反射实例化）。
    */
    public DemoSubtitleSearchProvider() {
        // 无参构造：全部状态由请求参数携带，无成员字段初始化
    }

    @Override
    /**
     * 搜索Subtitles。
     * <p>主链路调用 subhd.tv API（若 API 响应含 data 数组则解析）；
     * 否则自动降级为 subhd.tv HTML 搜索页抓取。</p>
     *
     * @param request 搜索请求，keyword 不能为空，为 null 时返回错误结果
     * @return 搜索Subtitles的结果；API 与 HTML 均不可用时返回带错误信息的结果
     */
    public ReturnPageResult<SubtitleSearchResult> searchSubtitles(SubtitleSearchRequest request) {
        String keyword = request.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }

        log.info("从 SubHD 检索字幕: {}", keyword);
        try {
            ClientResponse resp = HttpClientFactory.of(SUBHD_API_SEARCH_URL)
                    .header("User-Agent", UA)
                    .connectTimeout(15000)
                    .readTimeout(15000)
                    .query("keyword", keyword)
                    .query("page", String.valueOf(request.getPage()))
                    .header("Referer", "https://subhd.tv/")
                    .header("Accept", "application/json")
                    .get();
            if (resp != null && resp.getBodyString() != null
                    && resp.getBodyString().contains("\"data\"")) {
                ReturnPageResult<SubtitleSearchResult> parsed = parseSubHdResponse(resp.getBodyString(), request);
                if (parsed.isSuccess()) {
                    return parsed;
                }
                log.warn("SubHD API 响应解析失败: {}，降级为 HTML 搜索页抓取", parsed.getMessage());
            }
            log.warn("SubHD API 无可用响应，降级为 HTML 搜索页抓取");
        } catch (Exception e) {
            log.warn("SubHD API 检索异常: {}，降级为 HTML 搜索页抓取", e.getMessage());
        }
        return fallbackSearch(keyword, request);
    }

    /**
     * 解析subhd响应。
     *
     * @param body    响应体 JSON 文本，不能为 null
     * @param request 搜索请求，language 非 null 时按语言过滤
     * @return subhd响应的解析结果；JSON 结构异常时返回错误结果
     */
    private ReturnPageResult<SubtitleSearchResult> parseSubHdResponse(String body,
                                                                      SubtitleSearchRequest request) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                return ReturnPageResult.error("SubHD 响应格式异常");
            }
            List<SubtitleSearchResult> items = new ArrayList<>();
            for (JsonNode node : data) {
                String lang = node.has("lang") ? node.get("lang").asText() : "unknown";
                if (request.getLanguage() != null
                        && !request.getLanguage().equalsIgnoreCase(lang)) {
                    continue;
                }
                items.add(SubtitleSearchResult.builder()
                        .subtitleId(node.has("id") ? node.get("id").asText() : null)
                        .videoName(node.has("movie_title") ? node.get("movie_title").asText() : "")
                        .language(lang)
                        .subtitleContent(node.has("title") ? node.get("title").asText() : "")
                        .source("subhd")
                        .videoUrl(node.has("url") ? node.get("url").asText() : "")
                        .build());
            }
            return buildPageResult(items, request);
        } catch (Exception e) {
            log.warn("SubHD JSON 解析失败: {}", e.getMessage());
            return ReturnPageResult.error("解析失败: " + e.getMessage());
        }
    }

    /**
     * 降级: 通过 subhd HTML 搜索页面抓取字幕。
     * <p>仅扫描 "的搜索结果" 标题之后的结果区域，避免命中侧栏推荐列表。</p>
     *
     * @param keyword 关键词，用于拼接搜索页 URL 与回填视频名
     * @param request 搜索请求
     * @return 降级检索结果；页面不可达或为空时返回错误结果
     */
    private ReturnPageResult<SubtitleSearchResult> fallbackSearch(String keyword,
                                                                  SubtitleSearchRequest request) {
        try {
            String url = SUBHD_HTML_SEARCH_URL
                    + java.net.URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            ClientResponse resp = HttpClientFactory.of(url)
                    .header("User-Agent", UA)
                    .connectTimeout(15000)
                    .readTimeout(15000)
                    .header("Referer", "https://subhd.tv/")
                    .get();
            if (resp == null || resp.getBodyString() == null || resp.getBodyString().isEmpty()) {
                return ReturnPageResult.error("SubHD fallback 响应为空");
            }
            return parseHtmlFallback(resp.getBodyString(), keyword, request);
        } catch (Exception e) {
            log.warn("SubHD fallback 检索异常: {}", e.getMessage());
            return ReturnPageResult.error("检索失败: " + e.getMessage());
        }
    }

    /**
     * 解析html降级。
     * <p>定位结果区域后，按结果卡片逐个提取 详情页链接 + 条目语言/正文。</p>
     *
     * @param html    HTML 文本，不能为 null
     * @param keyword 关键词，用于回填字幕条目的视频名
     * @param request 搜索请求，language 非 null 时按语言过滤，pageSize 限制抓取条数
     * @return 解析结果；无匹配卡片时返回空结果
     */
    private ReturnPageResult<SubtitleSearchResult> parseHtmlFallback(String html,
                                                                     String keyword,
                                                                     SubtitleSearchRequest request) {
        List<SubtitleSearchResult> items = new ArrayList<>();
        int regionStart = html.indexOf(RESULT_REGION_MARKER);
        // 结果区域未找到时退化为全文扫描（站点改版兜底）
        String region = regionStart >= 0
                ? html.substring(regionStart, Math.min(html.length(), regionStart + REGION_EXTENSION_CHARS))
                : html;

        Matcher cards = CARD_PATTERN.matcher(region);
        while (cards.find() && items.size() < request.getPageSize()) {
            int cardEnd = Math.min(region.length(), cards.end() + CARD_TAIL_EXTENSION_CHARS);
            String card = region.substring(cards.end(), cardEnd);
            String detailPath = cards.group(1);

            // 卡片内第一个 /a/ 条目链接作为字幕条目标识，缺失时用序号兜底
            String subtitleId = "subhd-" + (items.size() + 1);
            Matcher entry = ENTRY_PATTERN.matcher(card);
            if (entry.find()) {
                subtitleId = "subhd-" + entry.group(1).replace("/", "");
            }

            // 卡片正文（清洗 HTML 标签与多余空白）
            String content = card.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();

            // 语言标签：卡片正文中的 语言/格式 字段，缺省 zh
            String lang = "zh";
            Matcher langM = LANG_PATTERN.matcher(content);
            if (langM.find()) {
                lang = langM.group();
            }

            if (request.getLanguage() != null
                    && !request.getLanguage().equalsIgnoreCase(lang)) {
                continue;
            }

            items.add(SubtitleSearchResult.builder()
                    .subtitleId(subtitleId)
                    .videoName(keyword)
                    .language(lang)
                    .subtitleContent(content)
                    .source("subhd")
                    .videoUrl("https://subhd.tv" + detailPath)
                    .build());
        }
        return buildPageResult(items, request);
    }

    /**
     * 构建分页结果。
     *
     * @param items   条目列表，不能为 null（可为空）
     * @param request 搜索请求，page/pageSize 决定分页窗口
     * @return 分页结果
     */
    private ReturnPageResult<SubtitleSearchResult> buildPageResult(List<SubtitleSearchResult> items,
                                                                   SubtitleSearchRequest request) {
        int total = items.size();
        int from = Math.min((request.getPage() - 1) * request.getPageSize(), total);
        int to = Math.min(from + request.getPageSize(), total);
        List<SubtitleSearchResult> pageItems = items.subList(from, to);

        PageResult<SubtitleSearchResult> pageResult = PageResult.<SubtitleSearchResult>builder()
                .data(pageItems)
                .pageNo(request.getPage())
                .pageSize(request.getPageSize())
                .total(total)
                .totalPages(request.getPageSize() > 0
                        ? (total + request.getPageSize() - 1) / request.getPageSize() : 0)
                .build();
        return ReturnPageResult.of(pageResult);
    }
}
