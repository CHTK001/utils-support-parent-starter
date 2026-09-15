package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.datasearch.video.spi.VideoProviderRegistry;
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
* Muou 摸鱼视频搜索 提供者
* 移植自 pansou/plugin/muou (Fish2018/pansou)
*
* <p>两阶段抓取：先搜索列表，再逐条访问详情页提取网盘链接。
* 支持的网盘：百度/夸克/阿里/迅雷/UC/115/123/pikpak/磁力/ed2k 等。</p>
*
* <p>数据源为海外站(666.666291.xyz)，请求经 {@link ProxyHttpClient}
* （内部通过 {@code ProxyFetcherFlow} 获取代理，获取失败自动无代理兜底）。</p>
* @author CH
* @since 4.0.0
 */
@Spi("muou")
public class MuouResourceProvider extends AbstractResourceProvider {

    private static final String SEARCH_URL = "https://666.666291.xyz/index.php/vod/search/wd/%s.html"; // 搜索url
    private static final String DETAIL_URL = "https://666.666291.xyz/index.php/vod/detail/id/%s.html"; // DETAIL_URL
    private static final Pattern ID_PATTERN = Pattern.compile("/vod/detail/id/(\\d+)\\.html"); // 标识模式

    private static final Pattern QUARK = Pattern.compile("https?://pan\\.quark\\.cn/s/[0-9a-zA-Z]+"); // QUARK
    private static final Pattern UC = Pattern.compile("https?://drive\\.uc\\.cn/s/[0-9a-zA-Z]+(?:\\?[^\\s\"']*)?"); // UC
    private static final Pattern BAIDU = Pattern.compile("https?://pan\\.baidu\\.com/s/[0-9a-zA-Z_\\-]+(?:\\?pwd=[0-9a-zA-Z]+)?"); // 百度云
    private static final Pattern ALIYUN = Pattern.compile("https?://(?:www\\.)?(aliyundrive\\.com|alipan\\.com)/s/[0-9a-zA-Z]+"); // 阿里云
    private static final Pattern XUNLEI = Pattern.compile("https?://pan\\.xunlei\\.com/s/[0-9a-zA-Z_\\-]+(?:\\?pwd=[0-9a-zA-Z]+)?"); // XUNLEI
    private static final Pattern TIANYI = Pattern.compile("https?://cloud\\.189\\.cn/t/[0-9a-zA-Z]+"); // TIANYI
    private static final Pattern LINK_115 = Pattern.compile("https?://115\\.com/s/[0-9a-zA-Z]+"); // 链接115
    private static final Pattern LINK_123 = Pattern.compile("https?://123pan\\.com/s/[0-9a-zA-Z]+"); // 链接123
    private static final Pattern PIKPAK = Pattern.compile("https?://mypikpak\\.com/s/[0-9a-zA-Z]+"); // PIKPAK
    private static final Pattern MAGNET = Pattern.compile("magnet:\\?xt=urn:btih:[0-9a-fA-F]{40}");

    /**
    * muouresource提供者。
     */
    public MuouResourceProvider() { super(); }
    /**
    * muouresource提供者。
    * @param vs vs
     */
    public MuouResourceProvider(VideoSource vs) { super(vs); }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        try {
            // 海外站(666.666291.xyz),走代理客户端(ProxyFetcherFlow 获取代理,失败则无代理兜底)
            HttpClient client = ProxyHttpClient.get();

            String url = String.format(SEARCH_URL, java.net.URLEncoder.encode(kw, "UTF-8"));
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://666.666291.xyz/").GET().build();
            HttpResponse<String> resp;
            try {
                resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (java.net.http.HttpTimeoutException e) {
                // 海外站连接超时，自动标记为被封，避免后续重复长等待
                VideoProviderRegistry.block("muou", VideoProviderRegistry.BlockReason.TIMEOUT);
                return ReturnPageResult.error("muou 连接超时，站点不可达");
            }
            String body = resp.body();

            // 跑路页检测：站点停服后会展示公告并返回 0 条结果
            if (body.contains("跑路")) {
                VideoProviderRegistry.block("muou", VideoProviderRegistry.BlockReason.UNREACHABLE);
                return ReturnPageResult.error("muou 站点已停服(跑路)，请更换站点");
            }

 // 收集不重复的视频 标识
            Set<String> ids = new LinkedHashSet<>();
            Matcher m = ID_PATTERN.matcher(resp.body());
            while (m.find()) {
                ids.add(m.group(1));
                if (ids.size() >= 6) {
                    break;
                }
            }

            List<VideoInfoResult> results = new ArrayList<>();
            for (String id : ids) {
                VideoInfoResult item = fetchDetail(client, id);
                if (item != null && item.getVideoDescription() != null
                        && item.getVideoDescription().contains("链接")) {
                    results.add(item);
                }
                if (results.size() >= 5) {
                    break;
                }
            }
            if (results.isEmpty()) {
                return ReturnPageResult.empty();
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("muou 搜索失败: " + e.getMessage());
        }
    }

    /**
    * 获取detail。
    * @param client 客户端
    * @param id 标识
    * @return 获取detail的结果
     */
    private VideoInfoResult fetchDetail(HttpClient client, String id) {
        try {
            String url = String.format(DETAIL_URL, id);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://666.666291.xyz/").GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String html = resp.body();

            // 提取标题
            String title = extractTitle(html);
            // 提取网盘链接
            String links = extractNetdiskLinks(html);

            VideoInfoResult v = new VideoInfoResult();
            v.setVideoName(title);
            v.setVideoPlatform("muou");
            v.setVideoDescription("链接: " + links);
            v.setVideoUrl("https://666.666291.xyz/index.php/vod/detail/id/" + id + ".html");
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    /**
    * extracttitle。
    * @param html HTML
    * @return extractTitle的结果
     */
    private String extractTitle(String html) {
        Matcher m = Pattern.compile("<h1[^>]*>([^<]+)</h1>").matcher(html);
        if (m.find()) {
            return m.group(1).trim();
        }
        m = Pattern.compile("<title>([^<]+)</title>").matcher(html);
        if (m.find()) {
            return m.group(1).replace("-再见，我们跑路了", "").trim();
        }
        return "未命名";
    }

    /**
    * extractnetdisk链接。
    * @param html HTML
    * @return extractnetdisk链接的结果
     */
    private String extractNetdiskLinks(String html) {
        StringBuilder sb = new StringBuilder();
        appendLinks(sb, QUARK, "夸克", html);
        appendLinks(sb, BAIDU, "百度", html);
        appendLinks(sb, ALIYUN, "阿里", html);
        appendLinks(sb, XUNLEI, "迅雷", html);
        appendLinks(sb, UC, "UC", html);
        appendLinks(sb, TIANYI, "天翼", html);
        appendLinks(sb, LINK_115, "115", html);
        appendLinks(sb, LINK_123, "123", html);
        appendLinks(sb, PIKPAK, "PikPak", html);
        appendLinks(sb, MAGNET, "磁力", html);
        return sb.toString();
    }

    /**
    * 追加链接。
    * @param sb sb
    * @param p p
    * @param type 类型
    * @param html HTML
     */
    private void appendLinks(StringBuilder sb, Pattern p, String type, String html) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = p.matcher(html);
        while (m.find()) {
            String url = m.group();
            if (seen.add(url)) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(type).append("=").append(url);
            }
        }
    }
}
