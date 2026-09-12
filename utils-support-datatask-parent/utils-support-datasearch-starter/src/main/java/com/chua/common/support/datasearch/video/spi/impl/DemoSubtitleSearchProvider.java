package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.datasearch.video.model.SubtitleSearchRequest;
import com.chua.common.support.datasearch.video.model.SubtitleSearchResult;
import com.chua.common.support.datasearch.video.spi.SubtitleSearchProvider;
import com.chua.common.support.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
* subhd 字幕搜索提供者 — 从 subhd.tv 接口检索真实字幕数据
*
* @author CH
* @since 4.0.0.42
 */
@Spi("subhd")
public class DemoSubtitleSearchProvider implements SubtitleSearchProvider {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(DemoSubtitleSearchProvider.class);

    /** subhd 搜索接口地址 */
    private static final String SUBHD_SEARCH_URL = "https://subhd.tv/api/search";
    /** JSON 对象映射器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 用户代理字符串 */
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    @Override
    /** 搜索Subtitles */
    public ReturnPageResult<SubtitleSearchResult> searchSubtitles(SubtitleSearchRequest request) {
        String keyword = request.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }

        log.info("从 SubHD 检索字幕: {}", keyword);
        try {
            ClientResponse resp = HttpClientFactory.of(SUBHD_SEARCH_URL)
                    .header("User-Agent", UA)
                    .connectTimeout(15000)
                    .readTimeout(15000)
                    .query("keyword", keyword)
                    .query("page", String.valueOf(request.getPage()))
                    .header("Referer", "https://subhd.tv/")
                    .header("Accept", "application/json")
                    .get();
            if (resp == null || resp.getBodyString() == null || resp.getBodyString().isEmpty()) {
                log.warn("SubHD 返回空响应");
                return fallbackSearch(keyword, request);
            }
            return parseSubHdResponse(resp.getBodyString(), request);
        } catch (Exception e) {
            log.warn("SubHD 检索异常: {}，尝试 fallback", e.getMessage());
            return fallbackSearch(keyword, request);
        }
    }

    /**
    * 解析subhd响应
    * @param body 主体
    * @param request 请求
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
    * 降级: 通过 subhd HTML 搜索页面抓取字幕
     */
    private ReturnPageResult<SubtitleSearchResult> fallbackSearch(String keyword,
                                                                   SubtitleSearchRequest request) {
        try {
            String url = "https://subhd.tv/search/" + keyword;
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
    * 解析html降级
    * @param html HTML
    * @param keyword keyword
    * @param request 请求
     */
    private ReturnPageResult<SubtitleSearchResult> parseHtmlFallback(String html,
                                                                      String keyword,
                                                                      SubtitleSearchRequest request) {
        List<SubtitleSearchResult> items = new ArrayList<>();
        // 匹配字幕块: <a href="/d/xxx" ...>title</a>
        var pattern = java.util.regex.Pattern.compile(
                "<a[^>]*href=\"(/d/[^\"]+)\"[^>]*>\\s*<div[^>]*class=\"[^\"]*title[^\"]*\"[^>]*>([^<]+)</div>",
                java.util.regex.Pattern.DOTALL);
        var matcher = pattern.matcher(html);
        int idx = 0;
        while (matcher.find() && items.size() < request.getPageSize() * 2) {
            String detailPath = matcher.group(1);
            String title = matcher.group(2).trim();
            idx++;
            items.add(SubtitleSearchResult.builder()
                    .subtitleId("subhd-" + idx)
                    .videoName(title)
                    .language("zh")
                    .subtitleContent(title)
                    .source("subhd")
                    .videoUrl("https://subhd.tv" + detailPath)
                    .build());
        }
        return buildPageResult(items, request);
    }

    /**
    * 构建分页结果
    * @param items items
    * @param request 请求
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
