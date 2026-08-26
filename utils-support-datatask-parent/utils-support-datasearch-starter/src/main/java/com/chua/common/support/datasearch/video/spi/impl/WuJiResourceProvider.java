package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;

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
 * 无极网盘资源提供器
 *
 * <p>通过解析 {@code https://xcili.net} 的搜索结果页面，
 * 将资源列表转换为 {@link VideoInfoResult} 集合。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("wuji")
public class WuJiResourceProvider extends AbstractResourceProvider {

    /** 匹配搜索结果行：result-title 链接（含 mark 标签）+ result-meta 大小/日期 */
    private static final Pattern RESULT_ROW = Pattern.compile(
            "<td class=\"result-title\"><a href=\"([^\"]+)\">(.*?)</a></td>"
            + "\\s*<td[^>]*class=\"result-meta\"\\s*>"
            + "([\\d.]+)\\s*([A-Z]+)",
            Pattern.DOTALL);

    /**
     * 创建 WuJiResourceProvider 实例
     */
    public WuJiResourceProvider() {
        super();
    }

    /**
     * 创建 WuJiResourceProvider 实例
     * @param videoSource videoSource
     */
    public WuJiResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    /**
     * 获取搜索地址模板
     */
    protected String getUrl() {
        if (videoSource != null && StringUtils.hasText(videoSource.getVideoSourceUrl())) {
            return videoSource.getVideoSourceUrl();
        }
        return "https://xcili.net/search?q=%s";
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        try {
            String keyword = java.net.URLEncoder.encode(
                    videoSearch.getKeyword(), java.nio.charset.StandardCharsets.UTF_8);
            String url = String.format(getUrl(), keyword);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String html = response.body();

            List<VideoInfoResult> results = new ArrayList<>();
            Matcher matcher = RESULT_ROW.matcher(html);
            while (matcher.find() && results.size() < 10) {
                String href = matcher.group(1);
                String title = matcher.group(2).replaceAll("</?mark>", "").trim();
                String size = matcher.group(3) + " " + matcher.group(4);

                VideoInfoResult info = new VideoInfoResult();
                info.setVideoName(title);
                info.setVideoAliasName(title);
                info.setVideoDescription("Size: " + size + " | Detail: https://xcili.net" + href);
                results.add(info);
            }

            if (results.isEmpty()) {
                return ReturnPageResult.error("无极资源未找到相关视频");
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results)
                    .pageNo(videoSearch.getPage())
                    .pageSize(results.size())
                    .total(results.size())
                    .totalPages(1)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("无极资源检索失败: " + e.getMessage());
        }
    }
}
