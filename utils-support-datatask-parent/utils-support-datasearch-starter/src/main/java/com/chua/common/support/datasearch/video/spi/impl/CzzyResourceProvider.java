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
 * 厂长资源 (czzy) 影视搜索提供器
 * 支持多域名切换：cz01.tv / czzy.top / 4kcz.com
 */
@Spi("czzy")
public class CzzyResourceProvider extends AbstractResourceProvider {

    private static final String[] DOMAINS = {"cz01.tv", "czzy.top", "4kcz.com"};

    public CzzyResourceProvider() { super(); }
    public CzzyResourceProvider(com.chua.common.support.datasearch.video.model.VideoSource vs) { super(vs); }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) return ReturnPageResult.error("关键词不能为空");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();
            String encoded = java.net.URLEncoder.encode(kw, "UTF-8");
            List<VideoInfoResult> results = new ArrayList<>();
            for (String domain : DOMAINS) {
                if (results.size() >= 10) break;
                try {
                    String url = "https://www." + domain + "/search?kw=" + encoded;
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(12))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .header("Accept-Language", "zh-CN,zh;q=0.9")
                            .GET().build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    parseHtml(resp.body(), results, domain);
                } catch (Exception ignored) { continue; }
            }
            if (results.isEmpty()) return ReturnPageResult.empty();
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("czzy 搜索失败: " + e.getMessage());
        }
    }

    private void parseHtml(String html, List<VideoInfoResult> results, String domain) {
        // 搜索列表：匹配包含 /vod/ 或 /detail/ 或视频标题格式的链接
        java.util.regex.Pattern itemPat = java.util.regex.Pattern.compile(
            "<a[^>]+href=\"(https?://(?:www\\.)?" + java.util.regex.Pattern.quote(domain) + "/(?:vod|detail|search|movie)[^\"]+)\"[^>]*>\\s*([^<]{3,})\\s*</a>",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = itemPat.matcher(html);
        while (m.find() && results.size() < 10) {
            String link = m.group(1);
            String title = m.group(2).trim().replaceAll("[\\s\\n\\r]+", " ");
            if (title.length() >= 3 && !title.contains("首页") && !title.contains("登录")) {
                VideoInfoResult v = new VideoInfoResult();
                v.setVideoName(title);
                v.setVideoPlatform("czzy");
                v.setVideoDescription("链接: " + link);
                results.add(v);
            }
        }
        // 降级：匹配任意链接
        if (results.isEmpty()) {
            java.util.regex.Pattern any = java.util.regex.Pattern.compile(
                "<a[^>]+href=\"(https?://(?:www\\.)?" + java.util.regex.Pattern.quote(domain) + "/[^\"\']+)[^>]*>\\s*([^<]{4,})\\s*</a>",
                java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher am = any.matcher(html);
            while (am.find() && results.size() < 10) {
                String title = am.group(2).trim();
                if (title.contains("电影") || title.contains("剧") || title.contains("片") || title.contains("第")) {
                    VideoInfoResult v = new VideoInfoResult();
                    v.setVideoName(title);
                    v.setVideoPlatform("czzy");
                    v.setVideoDescription("链接: " + am.group(1));
                    results.add(v);
                }
            }
        }
    }
}
