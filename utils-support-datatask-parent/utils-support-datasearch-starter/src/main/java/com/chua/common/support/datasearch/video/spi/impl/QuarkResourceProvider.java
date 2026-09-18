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
 * 夸克盘 (quark) 网盘搜索提供器。
 *
 * <p>请求 pan.quark.cn 搜索接口并提取真实分享链接。
 * 当前站点为纯 JS 渲染的 SPA 壳页（ice-container），无服务端结果数据，
 * 命中壳页时快速返回不可用错误，避免返回无意义条目。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("quark")
public class QuarkResourceProvider extends AbstractResourceProvider {

    /** 搜索接口地址前缀（拼接 UTF-8 编码后的关键词） */
    private static final String API_URL = "https://pan.quark.cn/s/search?kw=";
    /** 分享链接匹配模式：pan.quark.cn/s/xxx */
    private static final Pattern SHARE_LINK_PATTERN =
            Pattern.compile("(https?://(?:pan\\.)?quark\\.cn/s/[\\w/]+)");
    /** SPA 壳页特征：搜索页为纯 JS 渲染时无结果数据 */
    private static final String SPA_SHELL_MARKER = "ice-container";
    /** 夸克网盘分享页标题特征 */
    private static final String SPA_TITLE_MARKER = "夸克网盘分享";
    /** 结果条数上限 */
    private static final int MAX_RESULT_COUNT = 10;
    /** 占位标题（夸克壳页无法解析真实条目标题） */
    private static final String PLACEHOLDER_NAME = "夸克网盘资源";

    /**
    * 创建 quark资源提供器 实例（无参构造，使用默认数据源）。
    */
    public QuarkResourceProvider() {
        super();
    }

    /**
    * 创建 quark资源提供器 实例。
    *
    * @param vs 视频数据源，不能为 null
    */
    public QuarkResourceProvider(VideoSource vs) {
        super(vs);
    }

    @Override
    /**
    * 搜索resource。
    *
    * @param videoSearch 视频搜索，keyword 不能为空，为 null/空时返回错误结果
    * @return 搜索resource的结果；命中 SPA 壳页时返回不可用错误
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

            String url = API_URL + URLEncoder.encode(kw, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            // SPA 壳页检测：纯 JS 渲染无服务端结果数据，直接判为不可用
            if (body.contains(SPA_SHELL_MARKER) || body.contains(SPA_TITLE_MARKER)) {
                return ReturnPageResult.error("quark 搜索页为纯 JS 渲染，当前接口不可用");
            }

            List<VideoInfoResult> results = new ArrayList<>(MAX_RESULT_COUNT);
            parseQuarkHtml(body, results);

            if (results.isEmpty()) {
                return ReturnPageResult.empty();
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("quark 搜索失败: " + e.getMessage());
        }
    }

    /**
    * 解析夸克HTML。
    * <p>提取真实分享链接（pan.quark.cn/s/xxx）作为结果，
    * 跳过重复链接，避免把 SPA 页面标题当作条目。</p>
    *
    * @param html 响应 HTML 文本，不能为 null
    * @param results 结果列表，方法内追加条目，不能为 null
    */
    private void parseQuarkHtml(String html, List<VideoInfoResult> results) {
        Set<String> seen = new LinkedHashSet<>(MAX_RESULT_COUNT);
        Matcher m = SHARE_LINK_PATTERN.matcher(html);
        int idx = 0;
        while (m.find() && idx++ < MAX_RESULT_COUNT) {
            String link = m.group(1);
            // 已出现的链接跳过，保证结果不重复
            if (!seen.add(link)) {
                continue;
            }
            VideoInfoResult v = new VideoInfoResult();
            v.setVideoName(PLACEHOLDER_NAME);
            v.setVideoPlatform("quark");
            v.setVideoDescription("链接: " + link);
            results.add(v);
        }
    }
}
