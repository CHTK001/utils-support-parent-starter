package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 短剧网 (duanjuw) 短剧搜索提供器。
 *
 * <p>抓取 www.duanjuw.com 搜索页并提取短剧条目链接。
 * 当前域名已被其他公司（非短剧站）占用，命中非短剧特征或 403 时快速失败。</p>
 *
 * @author CH
 * @since 4.0.0
 */
@Spi("duanjuw")
public class DuanJuWangResourceProvider extends AbstractResourceProvider {

    /** 搜索页地址前缀（拼接 UTF-8 编码后的关键词） */
    private static final String SEARCH_URL = "https://www.duanjuw.com/?s=";
    /** 站点特征词：正常短剧站页面包含该词 */
    private static final String SITE_MARKER = "短剧";
    /** 标题链接匹配模式：群体(1)=链接，群体(2)=标题 */
    private static final Pattern TITLE_LINK_PATTERN =
            Pattern.compile("<a[^>]+href=\"([^\"]+)\"[^>]*>\\s*([^<]{2,})\\s*</a>");
    /** 导航菜单标题排除模式（非短剧搜索结果） */
    private static final Pattern NAV_TITLE_EXCLUDE_PATTERN =
            Pattern.compile("^(?i)(登录|注册|首页|关于我们|联系我们|客户端|客服|下载|帮助|更多|全部|新闻|资讯|公告)$");
    /** 链接扫描上限（防止大页面遍历过深） */
    private static final int MAX_SCAN_LINKS = 50;
    /** 结果条数上限 */
    private static final int MAX_RESULT_COUNT = 10;
    /** 最短有效标题长度（过滤导航短词） */
    private static final int MIN_TITLE_LENGTH = 3;

    /**
     * 创建 duanjuw资源提供器 实例（无参构造，使用默认数据源）。
     */
    public DuanJuWangResourceProvider() {
        super();
    }

    /**
     * 创建 duanjuw资源提供器 实例。
     *
     * @param vs 视频数据源，不能为 null
     */
    public DuanJuWangResourceProvider(VideoSource vs) {
        super(vs);
    }

    @Override
    /**
     * 搜索resource。
     *
     * @param videoSearch 视频搜索，keyword 不能为空，为 null/空时返回错误结果
     * @return 搜索resource的结果；站点失效（非短剧特征/403）时返回错误结果
     */
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!StringUtils.hasText(kw)) {
            return ReturnPageResult.error("关键词不能为空");
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            String url = SEARCH_URL + URLEncoder.encode(kw, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            // 死域检测：域名被其他公司占据（非短剧站）或 403，快速失败
            if (resp.statusCode() >= 400 || !resp.body().contains(SITE_MARKER)) {
                return ReturnPageResult.error("duanjuw 站点已失效或域名被占用 (http "
                        + resp.statusCode() + ")");
            }

            List<VideoInfoResult> results = new ArrayList<>(MAX_RESULT_COUNT);
            parseHtml(resp.body(), results);
            if (results.isEmpty()) {
                return ReturnPageResult.empty();
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("duanjuw 搜索失败: " + e.getMessage());
        }
    }

    /**
     * 解析HTML。
     * <p>提取短剧条目链接，排除导航菜单项与短词，去重后写入 results。</p>
     *
     * @param html 响应 HTML 文本，不能为 null
     * @param results 结果列表，方法内追加条目，不能为 null
     */
    private void parseHtml(String html, List<VideoInfoResult> results) {
        Set<String> seen = new LinkedHashSet<>(MAX_RESULT_COUNT);
        Matcher m = TITLE_LINK_PATTERN.matcher(html);
        int idx = 0;
        while (m.find() && idx++ < MAX_SCAN_LINKS && results.size() < MAX_RESULT_COUNT) {
            String link = m.group(1);
            String title = m.group(2).trim();
            // 短词与导航菜单标题过滤
            if (title.length() < MIN_TITLE_LENGTH
                    || NAV_TITLE_EXCLUDE_PATTERN.matcher(title).find()) {
                continue;
            }
            // 去重
            if (!seen.add(link)) {
                continue;
            }
            VideoInfoResult v = new VideoInfoResult();
            v.setVideoName(title);
            v.setVideoPlatform("duanjuw");
            v.setVideoDescription("链接: " + link);
            results.add(v);
        }
    }
}
