package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
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
 * HTML 抓取型视频搜索提供器基类
 *
 * <p>从指定 URL 抓取搜索页面 HTML，正则提取标题和链接。
   * 子类只需实现 搜索url() 即可快速接入新站点。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public abstract class HtmlScraperResourceProvider extends AbstractResourceProvider {

    protected final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /**
     * 搜索 URL 模板，%s 替换编码后的关键词
     *
     * @param encodedKeyword encodedkeyword
     * @return 搜索url的结果
     */
    protected abstract String searchUrl(String encodedKeyword);

    /**
     * 结果行正则：群体(1)=链接，群体(2)=标题，群体(3)=大小
     *
     * @return 结果模式的结果
     */
    protected abstract Pattern resultPattern();

    /**
     * 从匹配中构建 视频信息结果；可覆写自定义映射
     *
     * @param m m
     * @param keyword keyword
     * @return 构建结果的结果
     */
    protected VideoInfoResult buildResult(Matcher m, String keyword) {
        VideoInfoResult v = new VideoInfoResult();
        v.setVideoUrl(m.group(1));
        v.setVideoName(m.group(2).trim().replaceAll("</?mark>", ""));
        v.setVideoDescription(m.group(3).trim());
        return v;
    }

    public HtmlScraperResourceProvider() { super(); }
    /**
     * htmlscraperresource提供者。
     * @param vs vs
     */
    public HtmlScraperResourceProvider(com.chua.common.support.datasearch.video.model.VideoSource vs) { super(vs); }

    @Override
    public com.chua.common.support.datasearch.network.lang.code.ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String kw = videoSearch.getKeyword();
        if (!com.chua.common.support.utils.StringUtils.hasText(kw)) {
            return com.chua.common.support.datasearch.network.lang.code.ReturnPageResult.error("关键词不能为空");
        }
        try {
            String encoded = java.net.URLEncoder.encode(kw, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(searchUrl(encoded)))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            String html = resp.body();

            List<VideoInfoResult> results = new ArrayList<>();
            Matcher m = resultPattern().matcher(html);
            while (m.find() && results.size() < 10) {
                results.add(buildResult(m, kw));
            }
            if (results.isEmpty()) {
                return com.chua.common.support.datasearch.network.lang.code.ReturnPageResult.empty();
            }
            return com.chua.common.support.datasearch.network.lang.code.ReturnPageResult.of(
                    com.chua.common.support.lang.code.PageResult.<VideoInfoResult>builder()
                            .data(results).total(results.size()).build());
        } catch (Exception e) {
            return com.chua.common.support.datasearch.network.lang.code.ReturnPageResult.error("搜索失败: " + e.getMessage());
        }
    }
}
