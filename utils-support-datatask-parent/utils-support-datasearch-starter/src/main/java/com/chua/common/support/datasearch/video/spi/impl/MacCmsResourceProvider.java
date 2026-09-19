package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * MacCMS 标准资源站提供者实现。
 * <p>
 * 对接 MacCMS v10 采集接口（<code>?ac=videolist&wd=关键词</code>），
 * 这是资源站领域最通用的 JSON 协议，视频源 URL 可通过
 * {@link VideoSource#getVideoSourceUrl()} 注入，从而支持任意 MacCMS 资源站。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("maccms")
public class MacCmsResourceProvider extends AbstractResourceProvider {

    /**
     * 日志对象。
     */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MacCmsResourceProvider.class);

    /**
     * 默认资源站接口（MacCMS v1 标准采集地址示例）。
     */
    private static final String DEFAULT_URL = "https://cj.lziapi.com/api.php/provide/vod/?";

    /**
     * JSON 解析器。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * HTTP 客户端（复用，重定向跟随）。
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 请求超时时间，单位秒。
     */
    private static final int TIMEOUT_SECONDS = 20;

    /**
     * 构造方法，创建 MacCmsResource提供者 实例。
     */
    public MacCmsResourceProvider() {
        super();
    }

    /**
     * 构造方法，创建 MacCmsResource提供者 实例。
     *
     * @param videoSource video来源，不允许为 null
     */
    public MacCmsResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    /**
     * 获取资源站接口地址（支持 VideoSource 注入覆盖）。
     * @return 接口地址
     */
    protected String getUrl() {
        if (videoSource != null && StringUtils.hasText(videoSource.getVideoSourceUrl())) {
            return videoSource.getVideoSourceUrl();
        }
        return DEFAULT_URL;
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        String keyword = videoSearch.getKeyword();
        if (!StringUtils.hasText(keyword)) {
            return ReturnPageResult.error("关键词不能为空");
        }

        List<VideoInfoResult> results = new ArrayList<>();
        try {
            String base = getUrl();
            if (base.endsWith("?")) {
                base = base.substring(0, base.length() - 1);
            }
            String url = base
                    + "?ac=videolist"
                    + "&wd=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                    + "&pg=" + videoSearch.getPage();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> resp = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = OBJECT_MAPPER.readTree(resp.body());

            JsonNode listNode = root.path("list");
            if (!listNode.isArray() || listNode.isEmpty()) {
                return ReturnPageResult.error("MacCMS 资源站未找到相关视频");
            }

            for (JsonNode item : listNode) {
                VideoInfoResult info = new VideoInfoResult();
                Integer vid = item.path("vod_id").asInt(0);
                info.setVideoId(vid);
                info.setVideoOriginId(String.valueOf(vid));
                info.setVideoName(item.path("vod_name").asText(""));
                info.setVideoAliasName(item.path("vod_sub").asText(""));
                info.setVideoCover(item.path("vod_pic").asText(""));
                info.setVideoYear(parseYear(item.path("vod_year").asText("")));
                info.setVideoDistrict(item.path("vod_area").asText(""));
                info.setVideoLanguage(item.path("vod_lang").asText(""));
                info.setVideoDirector(item.path("vod_director").asText(""));
                info.setVideoActor(item.path("vod_actor").asText(""));
                info.setVideoDescription(item.path("vod_content").asText(""));
                info.setVideoScore(parseScore(item.path("vod_score").asText("")));
                info.setVideoType(item.path("vod_class").asText(""));
                info.setVideoCategory(mapCategory(item.path("type_name").asText(
                        item.path("vod_class").asText(""))));
                info.setVideoPlatform("maccms");
                info.setVideoRemark(item.path("vod_remarks").asText(""));
                info.setVideoPublishDate(parseTime(item.path("vod_time").asText("")));
                // 播放/下载地址：vod_play_url 为 "名称1$地址1#名称2$地址2" 格式
                String playUrl = item.path("vod_play_url").asText("");
                info.setVideoUrl(extractFirstUrl(playUrl));
                results.add(info);
            }

            if (results.isEmpty()) {
                return ReturnPageResult.error("MacCMS 资源站未找到相关视频");
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results)
                    .pageNo(root.path("page").asInt(videoSearch.getPage()))
                    .pageSize(results.size())
                    .total(root.path("total").asInt(results.size()))
                    .build());
        } catch (Exception e) {
            log.warn("MacCMS 资源站搜索失败: {}", e.getMessage());
            return ReturnPageResult.error("MacCMS 资源站搜索失败: " + e.getMessage());
        }
    }

    /**
     * 从 vod_play_url 提取第一个播放地址。
     * 格式："第01集$https://...m3u8#第02集$https://..."
     * @param playUrl 原始播放串
     * @return 第一个地址，无则空串
     */
    private String extractFirstUrl(String playUrl) {
        if (!StringUtils.hasText(playUrl)) {
            return "";
        }
        for (String seg : playUrl.split("#")) {
            int idx = seg.indexOf('$');
            if (idx >= 0 && idx + 1 < seg.length()) {
                return seg.substring(idx + 1).trim();
            }
        }
        return "";
    }

    /**
     * 分类名映射为标准 videoCategory。
     * @param typeName 资源站原始分类
     * @return 标准分类（movie/tv/anime/variety/documentary）
     */
    private String mapCategory(String typeName) {
        if (!StringUtils.hasText(typeName)) {
            return "movie";
        }
        if (typeName.contains("剧") || typeName.contains("连续")) {
            return "tv";
        }
        if (typeName.contains("动漫") || typeName.contains("动画") || typeName.contains("番")) {
            return "anime";
        }
        if (typeName.contains("综艺") || typeName.contains("真人秀")) {
            return "variety";
        }
        if (typeName.contains("纪录")) {
            return "documentary";
        }
        return "movie";
    }

    /**
     * 解析年份文本为 Integer。
     * @param text 年份文本
     * @return 年份，解析失败返回 null
     */
    private Integer parseYear(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String digits = text.replaceAll("\\D", "");
        if (digits.length() >= 4) {
            try {
                return Integer.parseInt(digits.substring(0, 4));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 解析评分文本为 BigDecimal。
     * @param text 评分文本
     * @return 评分，解析失败返回 null
     */
    private java.math.BigDecimal parseScore(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return new java.math.BigDecimal(text.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 解析资源站时间戳（秒）为 LocalDateTime。
     * @param text 时间戳文本
     * @return 时间，解析失败返回 null
     */
    private LocalDateTime parseTime(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignored) {
            return null;
        }
    }
}
