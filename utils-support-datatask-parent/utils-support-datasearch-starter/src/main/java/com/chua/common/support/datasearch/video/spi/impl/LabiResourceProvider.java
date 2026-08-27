package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.lang.code.PageResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Spi("labi")
public class LabiResourceProvider extends AbstractResourceProvider {
    public LabiResourceProvider() { super(); }
    @Override
    public com.chua.common.support.datasearch.network.lang.code.ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!com.chua.common.support.utils.StringUtils.hasText(kw))
            return com.chua.common.support.datasearch.network.lang.code.ReturnPageResult.error("关键词不能为空");
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
            String url = "http://xiaocge.fun/index.php/vod/search/wd/" + java.net.URLEncoder.encode(kw, "UTF-8") + ".html";
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "http://xiaocge.fun/").GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            List<VideoInfoResult> results = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("href=\"([^\"]*vod/detail/id/[^\"]+)\"").matcher(resp.body());
            int idx = 0;
            while (m.find() && idx++ < 10) {
                VideoInfoResult v = new VideoInfoResult();
                v.setVideoName("labi资源");
                v.setVideoPlatform("labi");
                v.setVideoDescription("链接: http://xiaocge.fun/" + m.group(1).replaceFirst("^/", ""));
                results.add(v);
            }
            return results.isEmpty() ? com.chua.common.support.datasearch.network.lang.code.ReturnPageResult.empty()
                    : com.chua.common.support.datasearch.network.lang.code.ReturnPageResult.of(
                    com.chua.common.support.lang.code.PageResult.<VideoInfoResult>builder()
                            .data(results).total(results.size()).build());
        } catch (Exception e) { return com.chua.common.support.datasearch.network.lang.code.ReturnPageResult.error("labi 搜索失败: " + e.getMessage()); }
    }
}
