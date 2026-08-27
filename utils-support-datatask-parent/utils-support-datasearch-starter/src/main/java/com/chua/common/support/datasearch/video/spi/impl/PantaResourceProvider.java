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
 * 盼塔 (panta) 网盘搜索提供器
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("panta")
public class PantaResourceProvider extends AbstractResourceProvider {

    private static final String SEARCH_URL = "https://www.panta.vip/api/search";

    public PantaResourceProvider() { super(); }
    public PantaResourceProvider(com.chua.common.support.datasearch.video.model.VideoSource vs) { super(vs); }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) return ReturnPageResult.error("关键词不能为空");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();

            String jsonBody = "{\"keyword\":\"" + kw.replace("\"", "\\\"") + "\"}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(SEARCH_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            List<VideoInfoResult> results = new ArrayList<>();
            parsePantaJson(resp.body(), results);
            
            if (results.isEmpty()) return ReturnPageResult.empty();
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("panta 搜索失败: " + e.getMessage());
        }
    }

    private void parsePantaJson(String json, List<VideoInfoResult> results) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode list = root.path("data").path("list");
            
            int idx = 0;
            for (com.fasterxml.jackson.databind.JsonNode item : list) {
                if (idx++ >= 10) break;
                
                VideoInfoResult v = new VideoInfoResult();
                v.setVideoName(item.path("name").asText(""));
                v.setVideoPlatform("panta");
                
                StringBuilder sb = new StringBuilder();
                com.fasterxml.jackson.databind.JsonNode links = item.path("links");
                for (com.fasterxml.jackson.databind.JsonNode link : links) {
                    String type = link.path("type").asText("");
                    String url = link.path("url").asText("");
                    String pwd = link.path("pwd").asText("");
                    if (!url.isEmpty()) {
                        sb.append(type).append(":").append(url);
                        if (!pwd.isEmpty()) sb.append(" 密码:").append(pwd);
                        sb.append("\n");
                    }
                }
                v.setVideoDescription(sb.toString().trim());
                results.add(v);
            }
        } catch (Exception e) { /* ignore */ }
    }
}
