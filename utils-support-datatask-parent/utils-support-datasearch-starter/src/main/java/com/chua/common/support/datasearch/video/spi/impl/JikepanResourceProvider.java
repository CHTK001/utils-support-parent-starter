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
 * 即刻盘 (jikepan) 网盘搜索提供器
 * 
 * <p>移植自 pansou 插件 jikepan.go，调用 https://api.jikepan.xyz/search API</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("jikepan")
public class JikepanResourceProvider extends AbstractResourceProvider {

    private static final String API_URL = "https://api.jikepan.xyz/search";

    public JikepanResourceProvider() { super(); }
    public JikepanResourceProvider(com.chua.common.support.datasearch.video.model.VideoSource vs) { super(vs); }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) return ReturnPageResult.error("关键词不能为空");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();

            String jsonBody = "{\"name\":\"" + kw.replace("\"", "\\\"") + "\",\"is_all\":false}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Referer", "https://jikepan.xyz/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();

            // 解析 JSON 结果
            List<VideoInfoResult> results = new ArrayList<>();
            parseJikepanJson(body, kw, results);
            
            if (results.isEmpty()) {
                return ReturnPageResult.empty();
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("jikepan 搜索失败: " + e.getMessage());
        }
    }

    private void parseJikepanJson(String json, String kw, List<VideoInfoResult> results) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            
            if (!"success".equals(root.path("msg").asText())) {
                return;
            }
            
            com.fasterxml.jackson.databind.JsonNode list = root.path("list");
            int idx = 0;
            for (com.fasterxml.jackson.databind.JsonNode item : list) {
                if (idx++ >= 10) break;
                
                String name = item.path("name").asText("");
                if (name.isEmpty()) continue;
                
                VideoInfoResult v = new VideoInfoResult();
                v.setVideoName(name);
                v.setVideoPlatform("jikepan");
                
                // 提取链接
                com.fasterxml.jackson.databind.JsonNode links = item.path("links");
                StringBuilder linkStr = new StringBuilder();
                for (com.fasterxml.jackson.databind.JsonNode link : links) {
                    String service = link.path("service").asText("");
                    String url = link.path("link").asText("");
                    String pwd = link.path("pwd").asText("");
                    if (!url.isEmpty()) {
                        linkStr.append(service).append(":").append(url);
                        if (!pwd.isEmpty()) linkStr.append(" 密码:").append(pwd);
                        linkStr.append("\n");
                    }
                }
                v.setVideoDescription(linkStr.toString().trim());
                results.add(v);
            }
        } catch (Exception e) {
            // 解析失败
        }
    }
}
