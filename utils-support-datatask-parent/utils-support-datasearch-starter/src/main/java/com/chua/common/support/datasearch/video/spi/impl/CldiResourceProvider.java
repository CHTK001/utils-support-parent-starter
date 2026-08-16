package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.MapUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.datasearch.network.lang.code.ListReturnResult;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cldi 资源搜索提供器
 *
 * <p>参考 pansou 项目的 HTML 结构解析实现，
 * 通过关键字在 Cldi 站点搜索资源并封装为 {@link VideoInfoResult} 分页结果。</p>
 *
 * @author CH
 * @see <a href="https://github.com/fish2018/pansou/blob/main/plugin/cldi/html%E7%BB%93%E6%9E%84%E5%88%86%E6%9E%90.md">pansou cldi 解析说明</a>
 * @since 4.0.0.42
 */
@Spi("cldi")
public class CldiResourceProvider extends AbstractResourceProvider implements DownloadLinkProvider {
    public CldiResourceProvider() {
        super();
    }

    public CldiResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    /**
     * 简单本地调试入口
     */
    public static void main(String[] args) {
        VideoSearch videoSearch = new VideoSearch();
        videoSearch.setKeyword("凡人修仙传");
        CldiResourceProvider cldiResourceProvider = new CldiResourceProvider(new VideoSource());
        cldiResourceProvider.searchResource(videoSearch);
    }

    /**
     * 根据关键字和分页条件搜索资源
     *
     * @param videoSearch 搜索条件
     * @return 分页结果
     */
    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        try {
            String videoSourceUrl = StringUtils.defaultString(videoSource.getVideoSourceUrl(), "https://wvmzbxki.1122132.xyz");
            String encodedKeyword = URLEncoder.encode(StringUtils.defaultString(videoSearch.getKeyword(), ""), StandardCharsets.UTF_8);
            String url = videoSourceUrl + "/search-" + encodedKeyword
                    + "-1"
                    + "-2"
                    + "-" + videoSearch.getPage() + ".html";

            ClientResponse response = HttpClientFactory.of(url).get();
            if (response == null) {
                return ReturnPageResult.empty();
            }
            JsoupResponse jsoupResponse = new JsoupResponse(response.getBodyString(), JsoupResponse.Mappings.builder()
                    .parentXpath("//*[@class='ssbox']")
                    .addMapping(JsoupResponse.MappingsPath.builder()
                            .path(".title h3").css().field("videoTitle")
                            .build())
                    .addMapping(JsoupResponse.MappingsPath.builder()
                            .path(".slist .lightColor")
                            .css()
                            .isFirst()
                            .field("videoSize")
                            .build())
                    .addMapping(JsoupResponse.MappingsPath.builder()
                            .path(".sbar a")
                            .href()
                            .css()
                            .field("downloadUrls")
                            .build())
                    .addMapping(JsoupResponse.MappingsPath.builder()
                            .path(".sbar b")
                            .isLast()
                            .css()
                            .field("videoPopularity")
                            .build())
                    .build());

            List<VideoInfoResult> videoInfoResults = jsoupResponse != null ? jsoupResponse.eval(VideoInfoResult.class) : List.of();
            int total = videoSearch.getPageSize();
            if (jsoupResponse != null) {
                Map<String, Object> totalMap = jsoupResponse.eval(JsoupResponse.MappingsPath.builder()
                        .path(".tbox .msg .orange")
                        .isLast()
                        .css()
                        .field("total")
                        .build());
                total = MapUtils.getInteger(totalMap, "total", videoSearch.getPageSize());
            }

            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(videoInfoResults)
                    .pageNo(videoSearch.getPage())
                    .pageSize(videoSearch.getPageSize())
                    .total(total)
                    .totalPages(videoSearch.getPageSize() > 0 ? (total + videoSearch.getPageSize() - 1) / videoSearch.getPageSize() : 0)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("Cldi资源检索失败: " + e.getMessage());
        }
    }

    @Override
    public ListReturnResult<String> searchDownloadUrls(String keyword) {
        VideoSearch videoSearch = new VideoSearch();
        videoSearch.setKeyword(keyword);
        ReturnPageResult<VideoInfoResult> result = searchResource(videoSearch);
        if (result == null || result.getData() == null || result.getData().getData() == null) {
            return ListReturnResult.empty();
        }
        return (ListReturnResult<String>) ListReturnResult.ok(result.getData().getData().stream().map(VideoInfoResult::getDownloadUrls)
                .collect(Collectors.toList()));
    }
}

