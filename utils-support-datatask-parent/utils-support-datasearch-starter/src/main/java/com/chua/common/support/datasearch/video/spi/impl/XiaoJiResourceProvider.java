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
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.datasearch.video.model.VideoDownload;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.datasearch.video.spi.DownloadLinkProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jsoup.nodes.Document;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 小鸡影视资源提供器
 *
 * <p>参考 pansou 对小鸡影视站点的 HTML 结构解析，
 * 将检索结果页和详情页解析为 {@link VideoInfoResult} 及下载列表。</p>
 *
 * @author CH
 * @see <a href="https://github.com/fish2018/pansou/blob/main/plugin/xiaoji/html%E7%BB%93%E6%9E%84%E5%88%86%E6%9E%90.md">pansou xiaoji 解析说明</a>
 * @since 4.0.0.42
 */
@Spi("xiaoji")
public class XiaoJiResourceProvider extends AbstractResourceProvider implements DownloadLinkProvider {
    public XiaoJiResourceProvider() {
        super();
    }

    public XiaoJiResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    /**
     * 简单本地调试入口
     */
    public static void main(String[] args) {
        VideoSearch videoSearch = new VideoSearch();
        videoSearch.setKeyword("凡人修仙传");
        XiaoJiResourceProvider cldiResourceProvider = new XiaoJiResourceProvider(new VideoSource());
        cldiResourceProvider.searchResource(videoSearch);
    }

    /**
     * 根据关键字搜索资源列表
     *
     * @param videoSearch 搜索条件
     * @return 分页结果
     */
    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        try {
            String videoSourceUrl = StringUtils
                    .defaultString(videoSource.getVideoSourceUrl(), "https://www.xiaojitv.com/?s=%s")
                    .formatted(videoSearch.getKeyword());

            ClientResponse response = HttpClientFactory.of(videoSourceUrl)
                    .header("User-Agent", videoSource.getVideoSourceUserAgent())
                    .get();
            JsoupResponse jsoupResponse = new JsoupResponse(response.getBodyString(), JsoupResponse.Mappings.builder()
                    .parentXpath("//*[@class='poster-grid']/article")
                    .addMapping(JsoupResponse.MappingsPath.builder()
                            .path(".poster-title a")
                            .href()
                            .css()
                            .field("downloadUrls")
                            .build())
                    .addMapping(JsoupResponse.MappingsPath.builder()
                            .path(".poster-title a")
                            .css()
                            .field("videoTitle")
                            .build())
                    .addMapping(JsoupResponse.MappingsPath.builder()
                            .path(".poster-link img")
                            .css()
                            .isFirst()
                            .src()
                            .field("videoCover")
                            .build())
                    .addMapping(JsoupResponse.MappingsPath.builder()
                            .path(".rating-score")
                            .css()
                            .field("videoScore")
                            .build())
                    .addMapping(JsoupResponse.MappingsPath.builder()
                            .path(".ajaxlistpv")
                            .css()
                            .field("videoViews")
                            .build())
                    .addMapping(JsoupResponse.MappingsPath.builder()
                            .path(".poster-category a")
                            .css()
                            .field("videoType")
                            .build())
                    .build());

            List<VideoInfoResult> videoInfoResults = jsoupResponse.eval(VideoInfoResult.class);
            for (VideoInfoResult videoInfoResult : videoInfoResults) {
                findDetail(videoInfoResult.getDownloadUrls(), videoInfoResult);
            }

            int total = MapUtils.getInteger(jsoupResponse.eval(JsoupResponse.MappingsPath.builder()
                    .path(".tbox .msg .orange")
                    .isLast()
                    .css()
                    .field("total")
                    .build()), "total", videoSearch.getPageSize());

            return ReturnPageResult.of(PageResult.<VideoInfoResult>builder()
                    .data(videoInfoResults)
                    .pageNo(videoSearch.getPage())
                    .pageSize(videoSearch.getPageSize())
                    .total(total)
                    .totalPages(videoSearch.getPageSize() > 0 ? (total + videoSearch.getPageSize() - 1) / videoSearch.getPageSize() : 0)
                    .build());
        } catch (Exception e) {
            return ReturnPageResult.error("小鸡影视资源检索失败: " + e.getMessage());
        }
    }

    /**
     * 解析详情页，补充导演、演员、别名、类型等信息
     *
     * @param downloadUrls    详情页地址
     * @param videoInfoResult 待补充的信息对象
     */
    private void findDetail(String downloadUrls, VideoInfoResult videoInfoResult) {
        try {
            ClientResponse detailResponse = HttpClientFactory.of(downloadUrls).header("User-Agent", videoSource.getVideoSourceUserAgent()).get();
            JsoupResponse jsoup = new JsoupResponse(detailResponse.getBodyString(), JsoupResponse.Mappings.builder().build());
            videoInfoResult.setVideoDirector(MapUtils.getString(jsoup.eval(JsoupResponse.MappingsPath.builder()
                    .css()
                    .path(".movie-directors span")
                    .field("videoDirector")
                    .build()), "videoDirector"));
            videoInfoResult.setVideoDescription(MapUtils.getString(jsoup.eval(JsoupResponse.MappingsPath.builder()
                    .css()
                    .path(".collapsible-content")
                    .field("collapsible-content")
                    .build()), "collapsible-content"));
            videoInfoResult.setVideoActor(MapUtils.getString(jsoup.eval(JsoupResponse.MappingsPath.builder()
                    .css()
                    .path(".movie-cast span")
                    .field("videoActor")
                    .build()), "videoActor"));
            videoInfoResult.setVideoAliasName(MapUtils.getString(jsoup.eval(JsoupResponse.MappingsPath.builder()
                    .css()
                    .path(".movie-alias")
                    .field("videoAliasName")
                    .build()), "videoAliasName").replace("又名：", ""));
            videoInfoResult.setVideoType(MapUtils.getStringSplitter(jsoup.eval(JsoupResponse.MappingsPath.builder()
                    .css()
                    .path(".movie-genres span")
                    .field("videoType")
                    .build()), "videoType", "\\s+", ","));

            Document document = jsoup.getDocument();
            String doubanId = document.select(".movie-douban a").text();
            registerDownloadUrl(videoInfoResult, doubanId);
        } catch (Exception e) {
            // ignored
        }
    }

    /**
     * 根据豆瓣 ID 拉取下载资源列表并注册到视频信息中
     *
     * @param videoInfoResult 视频信息
     * @param doubanId        豆瓣 ID
     */
    private void registerDownloadUrl(VideoInfoResult videoInfoResult, String doubanId) {
        try {
            ClientResponse postResponse = HttpClientFactory.of("https://www.xiaojitv.com/wp-admin/admin-ajax.php")
                    .form()
                    .header("User-Agent", videoSource.getVideoSourceUserAgent())
                    .body("action", "douban_get_download_resources")
                    .body("douban_id", doubanId)
                    .post();

            List<VideoDownload> videoDownloads = new LinkedList<>();
            DownloadList content = Json.fromJson(postResponse.getBodyString(), DownloadList.class);
            List<DownloadList.DataDTO> data = content.getData();
            for (DownloadList.DataDTO datum : data) {
                registerDownloadUrls(datum, videoDownloads);
            }

            videoInfoResult.setDownloadList(videoDownloads);
        } catch (Exception e) {
            // ignored
        }
    }

    /**
     * 将接口返回的资源列表转换为 {@link VideoDownload} 列表
     */
    private void registerDownloadUrls(DownloadList.DataDTO datum, List<VideoDownload> videoDownloads) {
        List<DownloadList.DataDTO.ResourcesDTO> resources = datum.getResources();
        for (DownloadList.DataDTO.ResourcesDTO resourceItem : resources) {
            String downloadUrl = resourceItem.getDownloadUrl();
            VideoDownload videoDownload = new VideoDownload();
            videoDownload.setVideoDownloadUrl(downloadUrl);
            videoDownload.setVideoDownloadName(resourceItem.getName());
            videoDownload.setVideoDownloadQuality(resourceItem.getClarity().replace("WEB-", ""));
            videoDownload.setVideoDownloadPlatform(resourceItem.getType());
            videoDownload.setVideoDownloadSize(resourceItem.getSize());
            videoDownload.setVideoDownloadShareTime(resourceItem.getTime());

            videoDownloads.add(videoDownload);
        }
    }

    /**
     * 仅返回下载地址列表的轻量查询
     *
     * @param keyword 关键字
     * @return 下载地址列表
     */
    @Override
    public ListReturnResult<String> searchDownloadUrls(String keyword) {
        VideoSearch videoSearch = new VideoSearch();
        videoSearch.setKeyword(keyword);
        ReturnPageResult<VideoInfoResult> videoInfoResultReturnPageResult = searchResource(videoSearch);
        return (ListReturnResult<String>) ListReturnResult.ok(videoInfoResultReturnPageResult.getData().getData().stream().map(VideoInfoResult::getDownloadUrls)
                .collect(Collectors.toList()));
    }


    @NoArgsConstructor
    @Data
    static class DownloadList {

        @JsonProperty("success")
        private Boolean success;
        @JsonProperty("data")
        private List<DataDTO> data;

        @NoArgsConstructor
        @Data
        public static class DataDTO {
            @JsonProperty("clarity")
            private String clarity;
            @JsonProperty("resources")
            private List<ResourcesDTO> resources;

            @NoArgsConstructor
            @Data
            public static class ResourcesDTO {
                @JsonProperty("name")
                private String name;
                @JsonProperty("download_url")
                private String downloadUrl;
                @JsonProperty("size")
                private String size;
                @JsonProperty("clarity")
                private String clarity;
                @JsonProperty("time")
                private String time;
                @JsonProperty("type")
                private String type;
                @JsonProperty("pwd")
                private String pwd;
            }
        }
    }
}

