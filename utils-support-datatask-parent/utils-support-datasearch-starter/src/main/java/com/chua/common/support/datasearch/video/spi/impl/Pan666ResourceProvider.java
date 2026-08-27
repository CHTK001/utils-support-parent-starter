package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * pan666 网盘搜索提供器
 * 
 * <p>移植自 pansou 插件 pan666.go，调用 https://pan666.net/api/discussions API</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("pan666")
public class Pan666ResourceProvider extends AbstractResourceProvider {

    private static final String API_URL = "https://pan666.net/api/discussions";

    public Pan666ResourceProvider() { super(); }
    public Pan666ResourceProvider(com.chua.common.support.datasearch.video.model.VideoSource vs) { super(vs); }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) return ReturnPageResult.error("关键词不能为空");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();

            String url = API_URL + "?filter[q]=" + java.net.URLEncoder.encode(kw, "UTF-8")
                    + "&include=mostRelevantPost&page[offset]=0&page[limit]=20";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();

            List<VideoInfoResult> results = new ArrayList<>();
            parsePan666Json(body, kw, results);
            
            if (results.isEmpty()) {
                return ReturnPageResult.empty();
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("pan666 搜索失败: " + e.getMessage());
        }
    }

    private void parsePan666Json(String json, String kw, List<VideoInfoResult> results) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode data = root.path("data");
            com.fasterxml.jackson.databind.JsonNode included = root.path("included");

            int idx = 0;
            for (com.fasterxml.jackson.databind.JsonNode discussion : data) {
                if (idx++ >= 10) break;
                
                String title = discussion.path("attributes").path("title").asText("");
                String createdAt = discussion.path("attributes").path("createdAt").asText("");
                String discId = discussion.path("id").asText("");
                
                // 获取相关帖子
                String postIds = discussion.path("relationships").path("mostRelevantPost")
                        .path("data").path("id").asText("");
                
                // 找到帖子内容
                String content = "";
                for (com.fasterxml.jackson.databind.JsonNode post : included) {
                    if (post.path("id").asText("").equals(postIds)) {
                        content = post.path("attributes").path("contentHtml").asText("");
                        break;
                    }
                }
                
                // 提取链接
                List<String> links = extractLinks(content);
                if (links.isEmpty()) continue;
                
                VideoInfoResult v = new VideoInfoResult();
                v.setVideoName(title);
                v.setVideoPlatform("pan666");
                v.setVideoDescription(String.join("\n", links));
                results.add(v);
            }
        } catch (Exception e) {
            // 解析失败
        }
    }

    private List<String> extractLinks(String html) {
        List<String> links = new ArrayList<>();
        String[] patterns = {
            "pan.baidu.com", "aliyundrive.com", "cloud.189.cn", 
            "drive.uc.cn", "drive.google.com"
        };
        for (String pattern : patterns) {
            int start = 0;
            while ((start = html.indexOf(pattern, start)) != -1) {
                int end = start;
                while (end < html.length() && end < start + 200) {
                    char c = html.charAt(end);
                    if (c == ' ' || c == '\n' || c == '"' || c == '<') break;
                    end++;
                }
                String link = html.substring(start, end).trim();
                if (!link.isEmpty() && link.startsWith("http")) {
                    links.add(link);
                }
                start = end + 1;
            }
        }
        return links;
    }
}
