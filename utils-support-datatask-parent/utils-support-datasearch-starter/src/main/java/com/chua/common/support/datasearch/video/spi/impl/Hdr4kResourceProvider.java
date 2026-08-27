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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 4KHDR 网盘搜索提供器
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("hdr4k")
public class Hdr4kResourceProvider extends AbstractResourceProvider {

    private static final String SEARCH_URL = "https://www.4khdr.cn/search.php";

    public Hdr4kResourceProvider() { super(); }
    public Hdr4kResourceProvider(com.chua.common.support.datasearch.video.model.VideoSource vs) { super(vs); }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) return ReturnPageResult.error("关键词不能为空");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();

            String postData = "srchtxt=" + java.net.URLEncoder.encode(kw, "UTF-8") + "&searchsubmit=yes";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(SEARCH_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://www.4khdr.cn/")
                    .POST(HttpRequest.BodyPublishers.ofString(postData))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            List<VideoInfoResult> results = new ArrayList<>();
            parseHtml(resp.body(), results);
            
            if (results.isEmpty()) return ReturnPageResult.empty();
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("hdr4k 搜索失败: " + e.getMessage());
        }
    }

    private void parseHtml(String html, List<VideoInfoResult> results) {
        Pattern titlePat = Pattern.compile("<a[^>]+href='thread-\\d+-\\d+-\\d+\\.html'[^>]*>([^<]+)</a>");
        Pattern linkPat = Pattern.compile("href='thread-(\\d+)-\\d+-\\d+\\.html'");
        
        Matcher tm = titlePat.matcher(html);
        Matcher lm = linkPat.matcher(html);
        int idx = 0;
        while ((tm.find() || lm.find()) && idx++ < 10) {
            if (tm.find()) {
                String title = tm.group(1).trim();
                if (title.length() < 3) continue;
                
                VideoInfoResult v = new VideoInfoResult();
                v.setVideoName(title);
                v.setVideoPlatform("hdr4k");
                v.setVideoDescription("标题: " + title);
                results.add(v);
            }
        }
    }
}
