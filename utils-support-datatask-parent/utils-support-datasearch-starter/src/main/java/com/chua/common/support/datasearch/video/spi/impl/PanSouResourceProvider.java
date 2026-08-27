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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * PanSou 网盘资源搜索提供器
 *
 * <p>通过 PanSou API（https://so.252035.xyz）聚合搜索百度网盘、阿里云盘、
 * 夸克网盘等多种网盘资源。PanSou 是一个开源的网盘资源搜索 API 服务，
 * 支持多频道并发搜索和智能排序。</p>
 *
 * <p>PanSou 可自部署，需要在 VideoSource 中配置 API 地址，
 * 或者使用默认公共实例。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see <a href="https://github.com/fish2018/pansou">PanSou 项目</a>
 */
@Spi("pansou")
public class PanSouResourceProvider extends AbstractResourceProvider {

    private static final String DEFAULT_API_URL = "http://localhost:8888";

    public PanSouResourceProvider() {
        super();
    }

    public PanSouResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    @Override
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

            String jsonBody = String.format(
                    "{\"kw\":\"%s\",\"res\":\"results\"}",
                    keyword.replace("\\", "\\\\").replace("\"", "\\\""));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/search"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = com.chua.common.support.lang.json.Json.parse(resp.body());

            JsonNode resultsNode = root.get("results");
            if (resultsNode.isMissingValue() || !resultsNode.isArray() || resultsNode.size() == 0) {
                return ReturnPageResult.empty();
            }

            var results = new ArrayList<VideoInfoResult>();
            int limit = Math.min(resultsNode.size(), 10);
            for (int i = 0; i < limit; i++) {
                JsonNode item = resultsNode.get(i);
                results.add(mapToVideoInfo(item));
            }

            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results)
                    .pageNo(videoSearch.getPage())
                    .pageSize(results.size())
                    .total(results.size())
                    .totalPages(1)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("PanSou 搜索失败: " + e.getMessage());
        }
    }

    private VideoInfoResult mapToVideoInfo(JsonNode item) {
        VideoInfoResult info = new VideoInfoResult();
        info.setVideoTitle(item.get("title").toStringValue());
        info.setVideoAliasName(item.get("title").toStringValue());
        info.setVideoName(item.get("title").toStringValue());

        String content = item.get("content").toStringValue();
        JsonNode links = item.get("links");
        if (links != null && links.isArray() && links.size() > 0) {
            JsonNode firstLink = links.get(0);
            String linkType = firstLink.get("type").toStringValue();
            String linkUrl = firstLink.get("url").toStringValue();
            String password = firstLink.get("password").toStringValue();
            info.setVideoUrl(linkUrl);
            info.setVideoDescription(String.format("[%s] %s  密码: %s\n%s", linkType, linkUrl, password, content));
        } else {
            info.setVideoDescription(content);
        }

        JsonNode images = item.get("images");
        if (images != null && images.isArray() && images.size() > 0) {
            info.setVideoCover(images.get(0).toStringValue());
        }

        info.setVideoPlatform("PanSou");

        return info;
    }
}
