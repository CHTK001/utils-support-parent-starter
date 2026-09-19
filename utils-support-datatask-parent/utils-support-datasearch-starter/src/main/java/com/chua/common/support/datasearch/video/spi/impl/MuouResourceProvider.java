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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
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

    /** 搜索页地址（%s 替换 UTF-8 编码后的关键词） */
    private static final String SEARCH_URL = "https://666.666291.xyz/index.php/vod/search/wd/%s.html";
    /** 详情页地址（%s 替换视频标识） */
    private static final String DETAIL_URL = "https://666.666291.xyz/index.php/vod/detail/id/%s.html";
    /** 详情页链接标识提取模式 */
    private static final Pattern ID_PATTERN = Pattern.compile("/vod/detail/id/(\\d+)\\.html");
    /** 夸克网盘链接模式 */
    private static final Pattern QUARK = Pattern.compile("https?://pan\\.quark\\.cn/s/[0-9a-zA-Z]+");
    /** UC网盘链接模式 */
    private static final Pattern UC = Pattern.compile("https?://drive\\.uc\\.cn/s/[0-9a-zA-Z]+(?:\\?[^\\s\"']*)?");
    /** 百度网盘链接模式 */
    private static final Pattern BAIDU = Pattern.compile("https?://pan\\.baidu\\.com/s/[0-9a-zA-Z_\\-]+(?:\\?pwd=[0-9a-zA-Z]+)?");
    /** 阿里云盘链接模式 */
    private static final Pattern ALIYUN = Pattern.compile("https?://(?:www\\.)?(aliyundrive\\.com|alipan\\.com)/s/[0-9a-zA-Z]+");
    /** 迅雷网盘链接模式 */
    private static final Pattern XUNLEI = Pattern.compile("https?://pan\\.xunlei\\.com/s/[0-9a-zA-Z_\\-]+(?:\\?pwd=[0-9a-zA-Z]+)?");
    /** 天翼云盘链接模式 */
    private static final Pattern TIANYI = Pattern.compile("https?://cloud\\.189\\.cn/t/[0-9a-zA-Z]+");
    /** 115网盘链接模式 */
    private static final Pattern LINK_115 = Pattern.compile("https?://115\\.com/s/[0-9a-zA-Z]+");
    /** 123网盘链接模式 */
    private static final Pattern LINK_123 = Pattern.compile("https?://123pan\\.com/s/[0-9a-zA-Z]+");
    /** PikPak链接模式 */
    private static final Pattern PIKPAK = Pattern.compile("https?://mypikpak\\.com/s/[0-9a-zA-Z]+");
    /** 磁力链接模式 */
    private static final Pattern MAGNET = Pattern.compile("magnet:\\?xt=urn:btih:[0-9a-fA-F]{40}");
    /** h1 标题提取模式 */
    private static final Pattern TITLE_H1_PATTERN = Pattern.compile("<h1[^>]*>([^<]+)</h1>");
    /** title 标签提取模式 */
    private static final Pattern TITLE_TAG_PATTERN = Pattern.compile("<title>([^<]+)</title>");
    /** 占位标题（无法提取时使用） */
    private static final String UNNAMED_TITLE = "未命名";
    /** 停服公告特征词：命中即标记站点不可用 */
    private static final String SHUTDOWN_MARKER = "跑路";
    /** 详情标识扫描上限（避免遍历过多） */
    private static final int MAX_ID_COUNT = 6;
    /** 结果条数上限 */
    private static final int MAX_RESULT_COUNT = 5;
    /** 日志记录器 */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MuouResourceProvider.class);

    /**
     * 创建 muou资源提供器 实例（无参构造，使用默认数据源）。
     */
    public MuouResourceProvider() {
        super();
    }

    /**
     * 创建 muou资源提供器 实例。
     *
     * @param vs 视频数据源，不能为 null
     */
    public MuouResourceProvider(VideoSource vs) {
        super(vs);
    }

    @Override
    /**
     * 搜索resource。
     * <p>两阶段抓取：搜索页提取视频标识，再逐条访问详情页提取网盘链接。
     * 海外站经代理客户端访问，连接超时或命中停服公告时自动标记站点不可用。</p>
     *
     * @param videoSearch 视频搜索，keyword 不能为空，为 null/空时返回错误结果
     * @return 搜索resource的结果；无匹配网盘链接时返回空结果，站点不可用时返回错误结果
     */
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        // 已封禁站点直接跳过，避免海外代理长等待（67s 级）
        ReturnPageResult<VideoInfoResult> blocked = checkBlocked("muou");
        if (blocked != null) {
            return blocked;
        }
        try {
            // 海外站(666.666291.xyz),走代理客户端(ProxyFetcherFlow 获取代理,失败则无代理兜底)
            HttpClient client = ProxyHttpClient.get();

            String url = String.format(SEARCH_URL, URLEncoder.encode(kw, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://666.666291.xyz/").GET().build();
            HttpResponse<String> resp;
            try {
                resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                // 海外站连接超时，自动标记为被封，避免后续重复长等待
                VideoProviderRegistry.block("muou", VideoProviderRegistry.BlockReason.TIMEOUT);
                return ReturnPageResult.error("muou 连接超时，站点不可达");
            } catch (java.io.IOException e) {
                // 代理隧道协议错误（RST_STREAM 等）同样标记，避免下次再等 60+s
                VideoProviderRegistry.block("muou", VideoProviderRegistry.BlockReason.TIMEOUT);
                return ReturnPageResult.error("muou 代理连接失败: " + e.getMessage());
            }
            String body = resp.body();

            // 停服公告检测：命中即标记站点不可用
            if (body.contains(SHUTDOWN_MARKER)) {
                VideoProviderRegistry.block("muou", VideoProviderRegistry.BlockReason.UNREACHABLE);
                return ReturnPageResult.error("muou 站点已停服，请更换站点");
            }

 // 收集不重复的视频标识
            Set<String> ids = new LinkedHashSet<>(MAX_ID_COUNT);
            Matcher m = ID_PATTERN.matcher(body);
            while (m.find()) {
                ids.add(m.group(1));
                if (ids.size() >= MAX_ID_COUNT) {
                    break;
                }
            }

            List<VideoInfoResult> results = new ArrayList<>(MAX_RESULT_COUNT);
            for (String id : ids) {
                VideoInfoResult item = fetchDetail(client, id);
                if (item != null && item.getVideoDescription() != null
                        && item.getVideoDescription().contains("链接")) {
                    results.add(item);
                }
                if (results.size() >= MAX_RESULT_COUNT) {
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
     * <p>访问指定视频详情页并组装 视频信息结果（标题 + 网盘链接 + 详情页 URL）。</p>
     *
     * @param client 代理客户端，不能为 null
     * @param id 视频标识，数字字符串，不能为 null
     * @return 视频信息结果；请求异常或解析失败时返回 空（调用方需判空）
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
            // 详情页抓取失败（超时/站点失效）时静默跳过，由上层按条数兜底
            log.warn("muou 详情页抓取失败 id={}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * 提取标题。
     * <p>优先 h1 标签，缺失时退化到 title 标签（剔除站点后缀）。</p>
     *
     * @param html 详情页 HTML，不能为 null
     * @return 标题文本；均缺失时返回占位值 未命名
     */
    private String extractTitle(String html) {
        Matcher m = TITLE_H1_PATTERN.matcher(html);
        if (m.find()) {
            return m.group(1).trim();
        }
        m = TITLE_TAG_PATTERN.matcher(html);
        if (m.find()) {
            return m.group(1).replace("-再见，我们跑路了", "").trim();
        }
        return UNNAMED_TITLE;
    }

    /**
     * 提取netdisk链接。
     * <p>按网盘类型逐个扫描 HTML，拼接 类型=链接 形式的文本。</p>
     *
     * @param html 详情页 HTML，不能为 null
     * @return 链接拼接文本；无任何网盘链接时返回 空串
     */
    private String extractNetdiskLinks(String html) {
        StringBuilder sb = new StringBuilder(256);
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
     * <p>按指定模式扫描 HTML，去重后以 类型=链接 形式追加到 sb。</p>
     *
     * @param sb 目标字符串构建器，不能为 null
     * @param p 链接匹配模式，不能为 null
     * @param type 网盘类型标签（用于输出前缀），不能为 null
     * @param html 待扫描 HTML，不能为 null
     */
    private void appendLinks(StringBuilder sb, Pattern p, String type, String html) {
        Set<String> seen = new LinkedHashSet<>(8);
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
