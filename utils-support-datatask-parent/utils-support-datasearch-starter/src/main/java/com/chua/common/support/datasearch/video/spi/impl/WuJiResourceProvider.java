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

/**
* 无极网盘资源提供器 — 解析 xcili.net 搜索结果。
*
* <p>使用字符串分段提取（非正则），逐行解析搜索结果表。</p>
*
* @author CH
* @since 4.0.0.42
* @return 获取url的结果
* @param videoSource 视频源
 */
@Spi("wuji")
public class WuJiResourceProvider extends AbstractResourceProvider {

    /**
    * wujiresource提供者。
    * @return 获取url的结果
    * @param videoSource 视频源
     */
    private static final String MARKER = "<td class=\"result-title\"";

    public WuJiResourceProvider() {
        super();
    }

    public WuJiResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    protected String getUrl() {
        if (videoSource != null && StringUtils.hasText(videoSource.getVideoSourceUrl())) {
            return videoSource.getVideoSourceUrl();
        }
        return "https://xcili.net/search?q=%s";
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        List<VideoInfoResult> results = new ArrayList<>();
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
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            String html = resp.body();

            /* 按 result-title 标记分段提取 */
            int pos = 0;
            while (results.size() < 10) {
                int tIdx = html.indexOf(MARKER, pos);
                if (tIdx < 0) {
                    break;
                }
                /* 提取标题 */
                int aStart = html.indexOf("<a href=\"", tIdx);
                if (aStart < 0 || aStart > tIdx + 200) { break; }
                int hrefStart = aStart + 9;
                int hrefEnd = html.indexOf("\"", hrefStart);
                if (hrefEnd < 0) { break; }
                String href = html.substring(hrefStart, hrefEnd);

                int textStart = html.indexOf(">", hrefEnd) + 1;
                int textEnd = html.indexOf("</a>", textStart);
                if (textStart <= hrefEnd || textEnd < textStart) { break; }
                String title = html.substring(textStart, textEnd)
                        .replaceAll("</?mark>", "").trim();

                /* 提取大小 */
                String size = "";
                int metaIdx = html.indexOf("result-meta", textEnd);
                if (metaIdx > 0 && metaIdx < textEnd + 500) {
                    int divStart = html.indexOf("<div>", metaIdx);
                    int divEnd = html.indexOf("</div>", divStart);
                    if (divStart > 0 && divEnd > divStart) {
                        size = html.substring(divStart + 5, divEnd).trim();
                    }
                }

                VideoInfoResult info = new VideoInfoResult();
                info.setVideoName(title);
                info.setVideoAliasName(title);
                info.setVideoDescription("Size: " + size + " | Detail: https://xcili.net" + href);
                results.add(info);

                pos = textEnd;
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
