package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xuexizhinan 学习指南搜索 provider
 * 移植自 pansou/plugin/xuexizhinan (fish2018/pansou)
 *
 * <p>WordPress + WP_Query 搜索，需访问详情页提取 magnet 磁链。</p>
 */
@Spi("xuexizhinan")
public class XuexizhinanResourceProvider extends AbstractResourceProvider {

    private static final String SEARCH_URL = "https://xuexizhinan.com/?post_type=book&s=%s";
    private static final Pattern BOOK_URL = Pattern.compile("https?://xuexizhinan\\.com/book/(\\d+)\\.html");
    private static final Pattern MAGNET = Pattern.compile("magnet:\\?xt=urn:btih:[0-9a-zA-Z]+");
    private static final Pattern QUARK = Pattern.compile("https?://pan\\.quark\\.cn/s/[0-9a-zA-Z]+");
    private static final Pattern BAIDU = Pattern.compile("https?://pan\\.baidu\\.com/s/[0-9a-zA-Z_\\-]+(?:\\?pwd=[0-9a-zA-Z]+)?");

    public XuexizhinanResourceProvider() { super(); }
    public XuexizhinanResourceProvider(VideoSource vs) { super(vs); }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) return ReturnPageResult.error("关键词不能为空");
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[] {
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
                }
            };
            javax.net.ssl.SSLContext sslCtx = javax.net.ssl.SSLContext.getInstance("TLS");
            sslCtx.init(null, trustAll, new java.security.SecureRandom());
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .sslContext(sslCtx)
                    .build();
            String url = String.format(SEARCH_URL, java.net.URLEncoder.encode(kw, "UTF-8"));
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            Set<String> ids = new LinkedHashSet<>();
            Matcher m = BOOK_URL.matcher(resp.body());
            while (m.find()) {
                ids.add(m.group(1));
                if (ids.size() >= 5) break;
            }
            List<VideoInfoResult> results = new ArrayList<>();
            for (String id : ids) {
                VideoInfoResult item = fetchDetail(client, id);
                if (item != null && item.getVideoDescription() != null
                        && item.getVideoDescription().contains("链接")) {
                    results.add(item);
                }
                if (results.size() >= 5) break;
            }
            if (results.isEmpty()) return ReturnPageResult.empty();
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("xuexizhinan 搜索失败: " + e.getMessage());
        }
    }

    private VideoInfoResult fetchDetail(HttpClient client, String id) {
        try {
            String url = "https://xuexizhinan.com/book/" + id + ".html";
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0").GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String html = resp.body();
            String title = extractTitle(html);
            String links = extractLinks(html);
            VideoInfoResult v = new VideoInfoResult();
            v.setVideoName(title);
            v.setVideoPlatform("xuexizhinan");
            v.setVideoDescription("链接: " + links);
            v.setVideoUrl(url);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractTitle(String html) {
        Matcher m = Pattern.compile("<h1[^>]*>([^<]+)</h1>").matcher(html);
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile("<title>([^<]+)</title>").matcher(html);
        return m.find() ? m.group(1).trim() : "未命名";
    }

    private String extractLinks(String html) {
        StringBuilder sb = new StringBuilder();
        appendLinks(sb, MAGNET, "磁力", html);
        appendLinks(sb, QUARK, "夸克", html);
        appendLinks(sb, BAIDU, "百度", html);
        return sb.toString();
    }

    private void appendLinks(StringBuilder sb, Pattern p, String type, String html) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = p.matcher(html);
        while (m.find()) {
            if (seen.add(m.group())) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(type).append("=").append(m.group());
            }
        }
    }
}
