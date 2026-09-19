package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 无极网盘资源提供器 — 解析 xcili.net 搜索结果。
 *
 * <p>使用字符串分段提取（非正则），按 result-title 标记逐行解析搜索结果表；
 * 支持通过 {@link VideoSource#getVideoSourceUrl()} 注入自定义资源站。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("wuji")
public class WuJiResourceProvider extends AbstractResourceProvider {

    /** 搜索结果行标记：result-title 单元格 */
    private static final String MARKER = "<td class=\"result-title\"";
    /** 标记与标题链接的最大允许间距（字符） */
    private static final int MAX_MARKER_TO_LINK_CHARS = 200;
    /** 标记与大小元信息的最大允许间距（字符） */
    private static final int MAX_MARKER_TO_META_CHARS = 500;
    /** 结果条数上限 */
    private static final int MAX_RESULT_COUNT = 10;
    /** 默认资源站搜索地址（%s 替换 UTF-8 编码后的关键词） */
    private static final String DEFAULT_SEARCH_URL = "https://xcili.net/search?q=%s";
    /** 详情页地址前缀（拼接站点相对 href） */
    private static final String DETAIL_URL_PREFIX = "https://xcili.net";

    /**
     * 创建 wuji资源提供器 实例（无参构造，使用默认数据源）。
     */
    public WuJiResourceProvider() {
        super();
    }

    /**
     * 创建 wuji资源提供器 实例。
     *
     * @param videoSource 视频数据源，不能为 null；videoSourceUrl 非空时覆盖默认资源站
     */
    public WuJiResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    /**
     * 获取资源站搜索地址。
     *
     * @return 数据源自定义地址优先，未配置时返回默认搜索地址
     */
    protected String getUrl() {
        if (videoSource != null && StringUtils.hasText(videoSource.getVideoSourceUrl())) {
            return videoSource.getVideoSourceUrl();
        }
        return DEFAULT_SEARCH_URL;
    }

    @Override
    /**
     * 搜索resource。
     * <p>抓取资源站搜索页，按 result-title 标记分段提取 标题/大小/详情页 链接；
     * 已封禁站点（见 {@link VideoProviderRegistry}）直接跳过。</p>
     *
     * @param videoSearch 视频搜索，keyword 不能为空，为 null/空时抛出异常
     * @return 搜索resource的结果；无匹配时返回错误结果，检索异常时返回错误结果
     */
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        // 已封禁站点直接跳过，避免无效请求
        ReturnPageResult<VideoInfoResult> blocked = checkBlocked("wuji");
        if (blocked != null) {
            return blocked;
        }
        List<VideoInfoResult> results = new ArrayList<>(MAX_RESULT_COUNT);
        try {
            String keyword = URLEncoder.encode(
                    videoSearch.getKeyword(), StandardCharsets.UTF_8);
            String url = String.format(getUrl(), keyword);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            String html = resp.body();

            /* 按 result-title 标记分段提取 */
            int pos = 0;
            while (results.size() < MAX_RESULT_COUNT) {
                int tIdx = html.indexOf(MARKER, pos);
                if (tIdx < 0) {
                    break;
                }
                /* 提取标题 */
                int aStart = html.indexOf("<a href=\"", tIdx);
                if (aStart < 0 || aStart > tIdx + MAX_MARKER_TO_LINK_CHARS) {
                    break;
                }
                int hrefStart = aStart + 9;
                int hrefEnd = html.indexOf("\"", hrefStart);
                if (hrefEnd < 0) {
                    break;
                }
                String href = html.substring(hrefStart, hrefEnd);

                int textStart = html.indexOf(">", hrefEnd) + 1;
                int textEnd = html.indexOf("</a>", textStart);
                if (textStart <= hrefEnd || textEnd < textStart) {
                    break;
                }
                String title = html.substring(textStart, textEnd)
                        .replaceAll("</?mark>", "").trim();

                /* 提取大小 */
                String size = "";
                int metaIdx = html.indexOf("result-meta", textEnd);
                if (metaIdx > 0 && metaIdx < textEnd + MAX_MARKER_TO_META_CHARS) {
                    int divStart = html.indexOf("<div>", metaIdx);
                    int divEnd = html.indexOf("</div>", divStart);
                    if (divStart > 0 && divEnd > divStart) {
                        size = html.substring(divStart + 5, divEnd).trim();
                    }
                }

                VideoInfoResult info = new VideoInfoResult();
                info.setVideoName(title);
                info.setVideoAliasName(title);
                info.setVideoDescription("Size: " + size + " | Detail: "
                        + DETAIL_URL_PREFIX + href);
                results.add(info);

                pos = textEnd;
            }

            if (results.isEmpty()) {
                return ReturnPageResult.error("无极资源未找到相关视频");
            }
            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(results)
                    .pageNo(videoSearch.getPage())
                    .pageSize(results.size())
                    .total(results.size())
                    .totalPages(1)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("无极资源检索失败: " + e.getMessage());
        }
    }
}
