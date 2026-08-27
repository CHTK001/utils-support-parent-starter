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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 混合盘 (hunhepan) 网盘搜索提供器
 * 
 * <p>移植自 pansou 插件 hunhepan.go，并行调用多个 API (hunhepan/qkpanso/kuake/misoso)</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("hunhepan")
public class HunhepanResourceProvider extends AbstractResourceProvider {

    private static final String[] APIS = {
        "https://hunhepan.com/open/search/disk",
        "https://qkpanso.com/v1/search/disk",
        "https://kuake8.com/v1/search/disk",
        "https://www.misoso.cc/v1/search/disk"
    };

    public HunhepanResourceProvider() { super(); }
    public HunhepanResourceProvider(com.chua.common.support.datasearch.video.model.VideoSource vs) { super(vs); }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) return ReturnPageResult.error("关键词不能为空");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();

            String escapedKw = kw.replace("\\", "\\\\").replace("\"", "\\\"");
            String jsonBody = "{\"page\":1,\"q\":\"" + escapedKw + "\",\"user\":\"\",\"exact\":false,\"format\":[],\"share_time\":\"\",\"size\":30,\"type\":\"\"}";

            List<CompletableFuture<List<VideoInfoResult>>> futures = new ArrayList<>();
            for (String api : APIS) {
                final String apiUrl = api;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(apiUrl))
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                                .build();

                        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        return parseHunhepanJson(resp.body(), kw);
                    } catch (Exception e) {
                        return new ArrayList<VideoInfoResult>();
                    }
                }));
            }

            List<VideoInfoResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .collect(Collectors.toList());

            results = deduplicate(results);
            
            if (results.isEmpty()) {
                return ReturnPageResult.empty();
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("hunhepan 搜索失败: " + e.getMessage());
        }
    }

    private List<VideoInfoResult> parseHunhepanJson(String json, String kw) {
        List<VideoInfoResult> results = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            if (root.path("code").asInt(-1) != 200) return results;
            
            com.fasterxml.jackson.databind.JsonNode list = root.path("data").path("list");
            int idx = 0;
            for (com.fasterxml.jackson.databind.JsonNode item : list) {
                if (idx++ >= 5) break;
                
                String diskName = item.path("disk_name").asText("");
                if (diskName.isEmpty()) continue;
                
                String link = item.path("link").asText("");
                if (link.isEmpty()) continue;
                
                VideoInfoResult v = new VideoInfoResult();
                v.setVideoName(diskName);
                v.setVideoPlatform("hunhepan");
                v.setVideoDescription("类型:" + item.path("disk_type").asText("") + 
                                     "\n密码:" + item.path("disk_pass").asText("") +
                                     "\n文件:" + item.path("files").asText("") +
                                     "\n链接:" + link);
                results.add(v);
            }
        } catch (Exception e) {
            // 解析失败
        }
        return results;
    }

    private List<VideoInfoResult> deduplicate(List<VideoInfoResult> results) {
        List<VideoInfoResult> unique = new ArrayList<>();
        for (VideoInfoResult r : results) {
            boolean dup = false;
            for (VideoInfoResult u : unique) {
                if (u.getVideoName().equals(r.getVideoName())) { dup = true; break; }
            }
            if (!dup) unique.add(r);
        }
        return unique;
    }
}
