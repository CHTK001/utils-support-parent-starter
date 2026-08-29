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
 * 摩尔 (hdmoli) 影视搜索提供器
 */
@Spi("hdmoli")
public class HdMoliResourceProvider extends AbstractResourceProvider {

    public HdMoliResourceProvider() { super(); }
    public HdMoliResourceProvider(com.chua.common.support.datasearch.video.model.VideoSource vs) { super(vs); }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) return ReturnPageResult.error("关键词不能为空");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            String url = "https://www.hdmoli.com/search.php?searchword=" + java.net.URLEncoder.encode(kw, "UTF-8");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.body().contains("cloudflare") || resp.body().contains("5xx-error")) {
                return ReturnPageResult.empty();
            }
            List<VideoInfoResult> results = new ArrayList<>();
            parseHtml(resp.body(), results);
            return results.isEmpty() ? ReturnPageResult.empty() :
                ReturnPageResult.of(PageResult.<VideoInfoResult>builder().data(results).total(results.size()).build());
        } catch (Exception e) { return ReturnPageResult.error("hdmoli 搜索失败: " + e.getMessage()); }
    }

    private void parseHtml(String html, List<VideoInfoResult> results) {
        java.util.regex.Pattern dl = java.util.regex.Pattern.compile(
            "<a[^>]+href=\"(https?://www\\.hdmoli\\.com/(?:movie|tv|detail|v)/[^\"]+)\"[^>]*>\\s*([^<]{3,})\\s*</a>",
            java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher dm = dl.matcher(html);
        while (dm.find() && results.size() < 10) {
            VideoInfoResult v = new VideoInfoResult();
            v.setVideoName(dm.group(2).trim());
            v.setVideoPlatform("hdmoli");
            v.setVideoDescription("链接: " + dm.group(1));
            results.add(v);
        }
    }
}
