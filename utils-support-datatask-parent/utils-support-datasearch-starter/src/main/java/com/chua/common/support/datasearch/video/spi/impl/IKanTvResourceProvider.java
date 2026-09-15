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
* I看TV (ikantv) 影视搜索提供器
* @author CH
* @since 4.0.0
 */
@Spi("ikantv")
public class IKanTvResourceProvider extends AbstractResourceProvider {
    /**
    * ikantvresource提供者。
     */
    public IKanTvResourceProvider() { super(); }
    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://www.ikantv.com/?s=" + java.net.URLEncoder.encode(kw, "UTF-8")))
                    .timeout(Duration.ofSeconds(15)).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36").GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            // 死域检测：页面不含"影视"特征词说明站点已失效，快速失败
            if (resp.statusCode() >= 400 || !resp.body().contains("影视")) {
                return ReturnPageResult.error("ikantv 站点已失效 (http " + resp.statusCode() + ")");
            }

            List<VideoInfoResult> results = new ArrayList<>();
            parseHtml(resp.body(), results);
            return results.isEmpty() ? ReturnPageResult.empty() : ReturnPageResult.of(PageResult.<VideoInfoResult>builder().data(results).total(results.size()).build());
        } catch (Exception e) { return ReturnPageResult.error("ikantv 搜索失败: " + e.getMessage()); }
    }

    /**
     * 解析HTML
     * @param html HTML
     * @param results 结果
     */
    private void parseHtml(String html, List<VideoInfoResult> results) {
        java.util.regex.Pattern titlePat = java.util.regex.Pattern.compile("<a[^>]+href=\"([^\"]+)\"[^>]*>\\s*([^<]{2,})\\s*</a>");
        java.util.regex.Pattern exclude = java.util.regex.Pattern.compile(
                "^(?i)(登录|注册|首页|关于我们|联系我们|客户端|客服|下载|帮助|更多|全部|电影|电视剧|综艺|动漫|纪录片|搜索|热门|排行)$");
        java.util.regex.Matcher m = titlePat.matcher(html);
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        int idx = 0;
        while (m.find() && idx++ < 50 && results.size() < 10) {
            String link = m.group(1);
            String title = m.group(2).trim();
            if (title.length() < 3 || exclude.matcher(title).find() || !seen.add(link)) {
                continue;
            }
            VideoInfoResult v = new VideoInfoResult();
            v.setVideoName(title);
            v.setVideoPlatform("ikantv");
            v.setVideoDescription("链接: " + link);
            results.add(v);
        }
    }
}
