package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PanSou 网盘资源搜索提供器
 *
 * <p>通过 PanSou API（https://so.252035.xyz）聚合搜索百度网盘、阿里云盘、
 * 夸克网盘等多种网盘资源。PanSou 是一个开源的网盘资源搜索 API 服务。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see <a href="https://github.com/fish2018/pansou">PanSou 项目</a>
 */
@Spi("pansou")
public class PanSouResourceProvider extends AbstractResourceProvider {

    private static final String DEFAULT_API_URL = "http://localhost:8888";
    private static final ObjectMapper mapper = new ObjectMapper();

    public PanSouResourceProvider() {
        super();
    }

    public PanSouResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        String apiUrl = StringUtils.defaultString(
                videoSource != null ? videoSource.getVideoSourceUrl() : null,
                DEFAULT_API_URL);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            String jsonBody = mapper.writeValueAsString(Map.of(
                    "kw", keyword,
                    "res", "results"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/search"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> root = mapper.readValue(resp.body(), Map.class);

            Object resultsObj = root.get("results");
            if (!(resultsObj instanceof List results) || results.isEmpty()) {
                return ReturnPageResult.empty();
            }

            var videoResults = new ArrayList<VideoInfoResult>();
            int limit = Math.min(results.size(), 10);
            for (int i = 0; i < limit; i++) {
                Map<String, Object> item = (Map<String, Object>) results.get(i);
                videoResults.add(mapToVideoInfo(item));
            }

            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(videoResults)
                    .pageNo(videoSearch.getPage())
                    .pageSize(videoResults.size())
                    .total(videoResults.size())
                    .totalPages(1)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("PanSou 搜索失败: " + e.getMessage());
        }
    }

    private VideoInfoResult mapToVideoInfo(Map<String, Object> item) {
        VideoInfoResult info = new VideoInfoResult();
        info.setVideoTitle(String.valueOf(item.get("title")));
        info.setVideoAliasName(String.valueOf(item.get("title")));
        info.setVideoName(String.valueOf(item.get("title")));

        String content = String.valueOf(item.getOrDefault("content", ""));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> links = (List<Map<String, Object>>) item.get("links");
        if (links != null && !links.isEmpty()) {
            Map<String, Object> firstLink = links.get(0);
            String linkType = String.valueOf(firstLink.get("type"));
            String linkUrl = String.valueOf(firstLink.get("url"));
            String password = String.valueOf(firstLink.getOrDefault("password", ""));
            info.setVideoUrl(linkUrl);
            info.setVideoDescription(
                    "[" + linkType + "] " + linkUrl + "  密码: " + password + "\n" + content);
        } else {
            info.setVideoDescription(content);
        }

        @SuppressWarnings("unchecked")
        List<String> images = (List<String>) item.get("images");
        if (images != null && !images.isEmpty()) {
            info.setVideoCover(images.get(0));
        }

        info.setVideoPlatform("PanSou");
        return info;
    }
}
