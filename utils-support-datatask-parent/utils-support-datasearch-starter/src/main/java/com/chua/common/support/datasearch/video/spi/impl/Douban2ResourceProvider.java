package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 豆瓣 (douban2) 影视搜索提供器。
 *
 * <p>抓取 www.douban.com 搜索页并提取视频条目链接。
 * 与 {@code douban} 提供器互补，作为第二搜索入口。</p>
 *
 * @author CH
 * @since 4.0.0
 */
@Spi("douban2")
public class Douban2ResourceProvider extends AbstractResourceProvider {

    /** 搜索页地址前缀（拼接 UTF-8 编码后的关键词） */
    private static final String SEARCH_URL = "https://www.douban.com/search?query=";
    /** 标题链接匹配模式：群体(1)=链接，群体(2)=标题 */
    private static final Pattern TITLE_LINK_PATTERN =
            Pattern.compile("<a[^>]+href=\"([^\"]+)\"[^>]*>\\s*([^<]{2,})\\s*</a>");
    /** 结果条数上限 */
    private static final int MAX_RESULT_COUNT = 10;

    /**
    * 创建 douban2资源提供器 实例（无参构造，使用默认数据源）。
    */
    public Douban2ResourceProvider() {
        super();
    }

    @Override
    /**
    * 搜索resource。
    *
    * @param videoSearch 视频搜索，keyword 不能为空，为 null/空时返回错误结果
    * @return 搜索resource的结果；无匹配时返回空结果
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
            List<VideoInfoResult> results = new ArrayList<>(MAX_RESULT_COUNT);
            Matcher m = TITLE_LINK_PATTERN.matcher(resp.body());
            int idx = 0;
            while (m.find() && idx++ < MAX_RESULT_COUNT) {
                VideoInfoResult v = new VideoInfoResult();
                v.setVideoName(m.group(2).trim());
                v.setVideoPlatform("douban2");
                v.setVideoDescription("链接: " + m.group(1));
                results.add(v);
            }
            if (results.isEmpty()) {
                return ReturnPageResult.empty();
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results).total(results.size()).build());
        } catch (Exception e) {
            return ReturnPageResult.error("douban2 搜索失败: " + e.getMessage());
        }
    }
}
