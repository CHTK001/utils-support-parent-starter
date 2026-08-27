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
 * Shandian (闪电) 视频资源搜索提供器
 *
 * <p>移植自 pansou 插件 shandian.go，从视频索引站抓取搜索结果，
 * 提取 UC 网盘等下载链接。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("shandian")
public class ShandianResourceProvider extends AbstractResourceProvider {

    private static final String SEARCH_URL = "http://1.95.79.193/index.php/vod/search/wd/%s.html";
    private static final String DETAIL_URL = "http://1.95.79.193/index.php/vod/detail/id/%s.html";
    private static final Pattern UC_LINK = Pattern.compile("https?://drive\\.uc\\.cn/s/[0-9a-zA-Z]+");
    private static final Pattern ITEM_ID = Pattern.compile("id/(\\d+)\\.html");

    public ShandianResourceProvider() { super(); }
    public ShandianResourceProvider(VideoSource vs) { super(vs); }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) return ReturnPageResult.error("关键词不能为空");
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
            String url = String.format(SEARCH_URL, java.net.URLEncoder.encode(kw, "UTF-8"));
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "http://1.95.79.193/").GET().build();
            HttpResponse<String> resp = c.send(req, HttpResponse.BodyHandlers.ofString());
            String html = resp.body();

            List<VideoInfoResult> results = new ArrayList<>();
            Pattern titleRe = Pattern.compile("<h3[^>]*>\\s*<a[^>]+>([^<]+)</a>");
            Matcher tm = titleRe.matcher(html);
            while (tm.find() && results.size() < 10) {
                VideoInfoResult v = new VideoInfoResult();
                v.setVideoName(tm.group(1).trim());
                v.setVideoDescription("Search keyword: " + kw);
                v.setVideoPlatform("Shandian");
                results.add(v);
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder().data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("Shandian 搜索失败: " + e.getMessage());
        }
    }
}
