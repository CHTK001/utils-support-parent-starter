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
 * 云搜2 (yunso2) 网盘搜索提供器
 */
@Spi("yunso2")
public class YunSo2ResourceProvider extends AbstractResourceProvider {
    public YunSo2ResourceProvider() { super(); }
    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) return ReturnPageResult.error("关键词不能为空");
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://www.yunso2.com/?s=" + java.net.URLEncoder.encode(kw, "UTF-8")))
                    .timeout(Duration.ofSeconds(15)).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36").GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            List<VideoInfoResult> results = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("href=\"([^\"]*pan\\.baidu\\.com[^\"]+)\"").matcher(resp.body());
            int idx = 0;
            while (m.find() && idx++ < 10) {
                VideoInfoResult v = new VideoInfoResult();
                v.setVideoName("云搜2资源");
                v.setVideoPlatform("yunso2");
                v.setVideoDescription(m.group(1));
                results.add(v);
            }
            return results.isEmpty() ? ReturnPageResult.empty() : ReturnPageResult.of(PageResult.<VideoInfoResult>builder().data(results).total(results.size()).build());
        } catch (Exception e) { return ReturnPageResult.error("yunso2 搜索失败: " + e.getMessage()); }
    }
}
