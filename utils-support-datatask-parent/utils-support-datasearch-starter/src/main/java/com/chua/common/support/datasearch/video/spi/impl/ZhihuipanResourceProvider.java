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
 * 知乎盘 (zhihuipan) 网盘搜索提供器
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zhihuipan")
public class ZhihuipanResourceProvider extends AbstractResourceProvider {

    private static final String SEARCH_URL = "https://zhihuipan.com/search?q=";

    public ZhihuipanResourceProvider() { super(); }
    public ZhihuipanResourceProvider(com.chua.common.support.datasearch.video.model.VideoSource vs) { super(vs); }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) return ReturnPageResult.error("关键词不能为空");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();

            String url = SEARCH_URL + java.net.URLEncoder.encode(kw, "UTF-8");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            List<VideoInfoResult> results = new ArrayList<>();
            parseHtml(resp.body(), kw, results);
            
            if (results.isEmpty()) return ReturnPageResult.empty();
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("zhihuipan 搜索失败: " + e.getMessage());
        }
    }

    private void parseHtml(String html, String kw, List<VideoInfoResult> results) {
        // 简单正则提取标题和链接
        java.util.regex.Pattern titlePat = java.util.regex.Pattern.compile("<a[^>]+href=\"([^\"]+)\"[^>]*>([^<]{2,})</a>");
        java.util.regex.Matcher m = titlePat.matcher(html);
        int idx = 0;
        while (m.find() && idx++ < 10) {
            String url = m.group(1);
            String title = m.group(2).trim();
            if (title.length() < 3) continue;
            
            VideoInfoResult v = new VideoInfoResult();
            v.setVideoName(title);
            v.setVideoPlatform("zhihuipan");
            v.setVideoDescription("链接: " + url);
            results.add(v);
        }
    }
}
