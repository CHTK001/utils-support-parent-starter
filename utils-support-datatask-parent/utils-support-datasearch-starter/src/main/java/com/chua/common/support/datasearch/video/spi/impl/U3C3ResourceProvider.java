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
 * U3C3 网盘搜索提供器
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("u3c3")
public class U3C3ResourceProvider extends AbstractResourceProvider {

    private static final String SEARCH_URL = "https://www.u3c3.com/search?keyword=";

    public U3C3ResourceProvider() { super(); }
    public U3C3ResourceProvider(com.chua.common.support.datasearch.video.model.VideoSource vs) { super(vs); }

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
            parseHtml(resp.body(), results);
            
            if (results.isEmpty()) return ReturnPageResult.empty();
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("u3c3 搜索失败: " + e.getMessage());
        }
    }

    private void parseHtml(String html, List<VideoInfoResult> results) {
        java.util.regex.Pattern linkPat = java.util.regex.Pattern.compile("href=\"([^\"]*pan\\.baidu\\.com[^\"]+)\"");
        java.util.regex.Matcher m = linkPat.matcher(html);
        int idx = 0;
        while (m.find() && idx++ < 10) {
            VideoInfoResult v = new VideoInfoResult();
            v.setVideoName("百度网盘资源");
            v.setVideoPlatform("u3c3");
            v.setVideoDescription("链接: " + m.group(1));
            results.add(v);
        }
    }
}
