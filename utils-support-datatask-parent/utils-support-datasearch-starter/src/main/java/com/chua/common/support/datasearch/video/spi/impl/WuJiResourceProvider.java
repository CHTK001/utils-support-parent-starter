package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.MapUtils;
import com.chua.common.support.datasearch.network.core.utils.RegexUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.datasearch.network.jsoup.JsoupResponse;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.datasearch.video.spi.DownloadLinkProvider;

import java.util.List;
import java.util.Map;

/**
 * 无极网盘资源提供器
 *
 * <p>通过解析 {@code https://xcili.net} 的搜索结果页面，
 * 将资源列表转换为 {@link VideoInfoResult} 集合。</p>
 *
 * @author CH
 * @since 2025/9/20 18:32
 */
@Spi("wuji")
public class WuJiResourceProvider extends AbstractResourceProvider {
    public WuJiResourceProvider() {
        super();
    }

    public WuJiResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    /**
     * 简单本地调试入口
     */
    public static void main(String[] args) {
        VideoSearch videoSearch = new VideoSearch();
        videoSearch.setKeyword("凡人修仙传");
        WuJiResourceProvider cldiResourceProvider = new WuJiResourceProvider(new VideoSource());
        cldiResourceProvider.searchResource(videoSearch);
    }

    /**
     * 获取搜索地址模板
     *
     * @return 搜索 URL 模板，包含关键字占位符
     */
    protected String getUrl() {
        return StringUtils.defaultString(videoSource.getVideoSourceUrl(), "https://xcili.net/search?q=%s");
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        try {
            String url = String.format(getUrl(), videoSearch.getKeyword());
            ClientResponse response = HttpClientFactory.of(url)
                    .connectTimeout(videoSource.getVideoSourceConnectTimeout() != null ? videoSource.getVideoSourceConnectTimeout() : 10000)
                    .header("User-Agent", videoSource.getVideoSourceUserAgent())
                    .get();
            JsoupResponse jsoupResponse = new JsoupResponse(response.getBodyString(), JsoupResponse.Mappings.builder()
                    .parentXpath("//*[@class='file-list']//tr")
                    .addMapping(JsoupResponse.MappingsPath.builder()
                            .path("a")
                            .href()
                            .css()
                            .field("downloadUrls")
                            .build())
                    .addMapping(JsoupResponse.MappingsPath.builder()
                            .path(".td-size")
                            .css()
                            .field("videoSize")
                            .build())
                    .addMapping(JsoupResponse.MappingsPath.builder()
                            .path("a")
                            .css()
                            .isFirst()
                            .field("videoTitle")
                            .build())
                    .build());

            List<VideoInfoResult> videoInfoResults = jsoupResponse.eval(VideoInfoResult.class);
            Map<String, Object> total = jsoupResponse.eval(JsoupResponse.MappingsPath.builder()
                    .path(".container p")
                    .isLast()
                    .css()
                    .field("total")
                    .build());
            String string = MapUtils.getString(total, "total");
            Integer firstNumber = RegexUtils.getFirstNumber(string);
            int totalCount = firstNumber != null ? firstNumber : 0;

            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(videoInfoResults)
                    .pageNo(videoSearch.getPage())
                    .pageSize(videoSearch.getPageSize())
                    .total(totalCount)
                    .totalPages(videoSearch.getPageSize() > 0 ? (totalCount + videoSearch.getPageSize() - 1) / videoSearch.getPageSize() : 0)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("无极网盘资源检索失败: " + e.getMessage());
        }
    }
}

