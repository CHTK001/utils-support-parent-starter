package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PanSearch 资源搜索提供器（从 pansou 插件移植）
 *
 * <p>从 PanSearch 网盘搜索引擎获取结果，支持百度、阿里云盘、夸克等
 * 多种网盘类型。使用 Next.js JSON API 进行数据查询。</p>
 *
 * <p>移植自 pansou 项目的 pansearch 插件（Go→Java），
 * 保留了核心搜索逻辑，简化了并发和缓存部分。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see <a href="https://github.com/fish2018/pansou/tree/main/plugin/pansearch">pansearch Go 源码</a>
 */
@Spi("pansearch")
public class PanSearchResourceProvider extends AbstractResourceProvider {

    private static final String WEBSITE_URL = "https://www.pansearch.me/search";
    private static final String API_BASE = "https://www.pansearch.me/_next/data";
    private static final String PAGE_SIZE = "10";

    private static final Pattern BUILD_ID_RE = Pattern.compile("\"buildId\":\"([^\"]+)\"");

    private static final ObjectMapper mapper = new ObjectMapper();

    public PanSearchResourceProvider() {
        super();
    }

    public PanSearchResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        try {
            String keyword = videoSearch.getKeyword();
            if (!StringUtils.hasText(keyword)) {
                return ReturnPageResult.error("关键词不能为空");
            }

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            String buildId = fetchBuildId(client);
            if (buildId == null) {
                return ReturnPageResult.error("获取 buildId 失败");
            }

            String apiUrl = String.format("%s/%s/search.json?keyword=%s&offset=%d",
                    API_BASE, buildId,
                    java.net.URLEncoder.encode(keyword, "UTF-8"),
                    (videoSearch.getPage() - 1) * Integer.parseInt(PAGE_SIZE));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://www.pansearch.me/")
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());

            JsonNode pageProps = root.get("pageProps");
            if (pageProps == null) {
                return ReturnPageResult.empty();
            }
            JsonNode data = pageProps.get("data");
            if (data == null) {
                return ReturnPageResult.empty();
            }

            int total = data.get("total").asInt(0);
            JsonNode items = data.get("data");
            if (items == null || items.isEmpty()) {
                return ReturnPageResult.empty();
            }

            List<VideoInfoResult> results = new ArrayList<>();
            int limit = Math.min(items.size(), 10);
            for (int i = 0; i < limit; i++) {
                JsonNode item = items.get(i);
                results.add(mapToVideoInfo(item, keyword));
            }

            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results)
                    .pageNo(videoSearch.getPage())
                    .pageSize(results.size())
                    .total(total)
                    .totalPages(total / 10 + 1)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("PanSearch 搜索失败: " + e.getMessage());
        }
    }

    private String fetchBuildId(HttpClient client) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(WEBSITE_URL))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        String html = resp.body();
        Matcher m = BUILD_ID_RE.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    @SuppressWarnings("unchecked")
    private VideoInfoResult mapToVideoInfo(JsonNode item, String keyword) {
        VideoInfoResult info = new VideoInfoResult();
        String content = item.get("content").asText("");
        String panType = item.get("pan").asText("");

        List<JsonNode> links = new ArrayList<>();
        Matcher linkRe = Pattern.compile("href=\"([^\"]+)\"").matcher(content);
        while (linkRe.find()) {
            links.add(null); // placeholders
            if (links.size() >= 3) break;
        }

        String url = links.isEmpty() ? "" : links.get(0) != null ? "" : "";
        // Re-parse to get URLs
        List<String> urls = new ArrayList<>();
        linkRe.reset();
        while (linkRe.find()) {
            urls.add(linkRe.group(1));
            if (urls.size() >= 3) break;
        }

        if (!urls.isEmpty()) {
            info.setVideoUrl(urls.get(0));
            info.setVideoDescription("[" + panType + "] " + urls.get(0) + "\n" + content.replaceAll("<[^>]+>", "").trim());
        } else {
            info.setVideoDescription(content.replaceAll("<[^>]+>", "").trim());
        }

        info.setVideoTitle(extractTitle(content, keyword));
        info.setVideoPlatform("PanSearch");
        return info;
    }

    private String extractTitle(String content, String keyword) {
        int idx = content.indexOf("名称：");
        if (idx < 0) {
            idx = content.indexOf("title=\"");
        }
        if (idx < 0) {
            return keyword;
        }
        int end = content.indexOf("\n", idx + 10);
        if (end < 0) {
            end = Math.min(content.length(), idx + 100);
        }
        return content.substring(idx + 5, end).replaceAll("<[^>]+>", "").trim();
    }
}
