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
 * 鱼语哥 (yuhuage) 网盘搜索提供器
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("yuhuage")
public class YuHuaGeResourceProvider extends AbstractResourceProvider {

    private static final String API_URL = "https://www.yuhuage.com/api/v1/search";

    public YuHuaGeResourceProvider() { super(); }
    public YuHuaGeResourceProvider(com.chua.common.support.datasearch.video.model.VideoSource vs) { super(vs); }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) return ReturnPageResult.error("关键词不能为空");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();

            String url = API_URL + "?keyword=" + java.net.URLEncoder.encode(kw, "UTF-8");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            List<VideoInfoResult> results = new ArrayList<>();
            parseJson(resp.body(), results);
            
            if (results.isEmpty()) return ReturnPageResult.empty();
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("yuhUAGE 搜索失败: " + e.getMessage());
        }
    }

    private void parseJson(String json, List<VideoInfoResult> results) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode data = root.path("data");
            
            int idx = 0;
            for (com.fasterxml.jackson.databind.JsonNode item : data) {
                if (idx++ >= 10) break;
                
                VideoInfoResult v = new VideoInfoResult();
                v.setVideoName(item.path("title").asText(""));
                v.setVideoPlatform("yuhuage");
                v.setVideoDescription("链接: " + item.path("url").asText(""));
                results.add(v);
            }
        } catch (Exception e) { /* ignore */ }
    }
}
