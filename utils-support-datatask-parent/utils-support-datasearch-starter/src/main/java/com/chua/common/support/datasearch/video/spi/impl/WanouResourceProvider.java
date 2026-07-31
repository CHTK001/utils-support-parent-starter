package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.datasearch.function.Splitter;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.utils.StringUtils;
import com.chua.common.support.datasearch.video.model.VideoDownload;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.model.VideoSource;
import com.chua.common.support.datasearch.video.spi.AbstractResourceProvider;
import com.chua.common.support.datasearch.video.spi.DownloadLinkProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author CH
 * @since 2025/9/20 18:32
 */
@Spi("Wanou")
public class WanouResourceProvider extends AbstractResourceProvider {
    public WanouResourceProvider() {
        super();
    }

    public WanouResourceProvider(VideoSource videoSource) {
        super(videoSource);
    }

    /**
     * 获取地址
     *
     * @return
     */
    protected String getUrl() {
        return StringUtils.defaultString(videoSource.getVideoSourceUrl(), "https://woog.nxog.eu.org/api.php/provide/vod?ac=detail&wd=%s");
    }

    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        try {
            String url = getUrl();
            url = String.format(url, videoSearch.getKeyword());
            ClientResponse response = HttpClientFactory.of(url).get();
            VideoList content = Json.fromJson(response.getBodyString(), VideoList.class);
            if (content == null) {
                return ReturnPageResult.empty();
            }
            Integer total = content.getTotal();
            PageResult<VideoInfoResult> pageResult = PageResult.<VideoInfoResult>builder()
                    .total(total != null ? total : 0)
                    .pageNo(videoSearch.getPage())
                    .pageSize(videoSearch.getPageSize())
                    .totalPages(total != null && videoSearch.getPageSize() > 0 ? total / videoSearch.getPageSize() : 0)
                    .data(content.getList().stream()
                            .map(item -> {
                                VideoInfoResult result = new VideoInfoResult();
                                result.setVideoTitle(item.getVodName());
                                result.setVideoType(item.getVodClass());
                                result.setVideoCover(item.getVodPic());
                                result.setVideoOriginId(String.valueOf(item.getVodId()));
                                result.setVideoName(item.getVodName());
                                result.setVideoAliasName(item.getVodSub());
                                result.setVideoActor(item.getVodActor());
                                result.setVideoDirector(item.getVodDirector());
                                result.setVideoDistrict(item.getVodArea());
                                result.setVideoLanguage(item.getVodLang());
                                result.setVideoRelease(item.getVodPubdate());
                                result.setVideoYear(Converter.createInteger(item.getVodYear()));
                                result.setVideoDescription(item.getVodBlurb());

                                String vodDownFrom = item.getVodDownFrom();
                                String vodDownUrl = item.getVodDownUrl();
                                List<VideoDownload> videoDownloads = new ArrayList<>();
                                registerDownload(videoDownloads, vodDownFrom, vodDownUrl);
                                return result;
                            })
                            .toList()
                    )
                    .build();
            return ReturnPageResult.of(pageResult);
        } catch (Exception e) {
            return ReturnPageResult.error("Wanou资源检索失败: " + e.getMessage());
        }
    }

    private void registerDownload(List<VideoDownload> videoDownloads, String vodDownFrom, String vodDownUrl) {
        if (null == vodDownFrom) {
            return;
        }

        List<String> froms = Splitter.on("$$$").splitToList(vodDownFrom);
        List<String> urls = Splitter.on("$$$").splitToList(vodDownUrl);
        for (int i = 0; i < froms.size(); i++) {
            VideoDownload videoDownload = new VideoDownload();
            videoDownload.setVideoDownloadName(transferName(froms.get(i)));
            videoDownload.setVideoDownloadUrl(urls.get(i));
            videoDownloads.add(videoDownload);
        }
    }

    public String transferName(String s) {
        return switch (s.toLowerCase()) {
            case "bd" -> "百度网盘";
            case "kg" -> "夸克网盘";
            case "uc" -> "UC网盘";
            case "aly" -> "阿里云盘";
            case "xl" -> "迅雷网盘";
            case "ty" -> "天翼云盘";
            case "115" -> "115网盘";
            case "mb" -> "移动网盘";
            case "wy" -> "微云";
            case "lz" -> "蓝奏云";
            case "jgy" -> "坚果云";
            case "123" -> "123网盘";
            case "pk" -> "PikPak";
            default -> s;
        };
    }

    @NoArgsConstructor
    @Data
    static class VideoList {

        @JsonProperty("code")
        private Integer code;
        @JsonProperty("msg")
        private String msg;
        @JsonProperty("page")
        private Integer page;
        @JsonProperty("pagecount")
        private Integer pagecount;
        @JsonProperty("limit")
        private Integer limit;
        @JsonProperty("total")
        private Integer total;
        @JsonProperty("list")
        private List<ListDTO> list;

        @NoArgsConstructor
        @Data
        public static class ListDTO {
            @JsonProperty("vod_id")
            private Integer vodId;
            @JsonProperty("type_id")
            private Integer typeId;
            @JsonProperty("type_id_1")
            private Integer typeId1;
            @JsonProperty("group_id")
            private Integer groupId;
            @JsonProperty("vod_name")
            private String vodName;
            @JsonProperty("vod_sub")
            private String vodSub;
            @JsonProperty("vod_en")
            private String vodEn;
            @JsonProperty("vod_status")
            private Integer vodStatus;
            @JsonProperty("vod_letter")
            private String vodLetter;
            @JsonProperty("vod_color")
            private String vodColor;
            @JsonProperty("vod_tag")
            private String vodTag;
            @JsonProperty("vod_class")
            private String vodClass;
            @JsonProperty("vod_pic")
            private String vodPic;
            @JsonProperty("vod_pic_thumb")
            private String vodPicThumb;
            @JsonProperty("vod_pic_slide")
            private String vodPicSlide;
            @JsonProperty("vod_pic_screenshot")
            private String vodPicScreenshot;
            @JsonProperty("vod_actor")
            private String vodActor;
            @JsonProperty("vod_director")
            private String vodDirector;
            @JsonProperty("vod_writer")
            private String vodWriter;
            @JsonProperty("vod_behind")
            private String vodBehind;
            @JsonProperty("vod_blurb")
            private String vodBlurb;
            @JsonProperty("vod_remarks")
            private String vodRemarks;
            @JsonProperty("vod_pubdate")
            private String vodPubdate;
            @JsonProperty("vod_total")
            private Integer vodTotal;
            @JsonProperty("vod_serial")
            private String vodSerial;
            @JsonProperty("vod_tv")
            private String vodTv;
            @JsonProperty("vod_weekday")
            private String vodWeekday;
            @JsonProperty("vod_area")
            private String vodArea;
            @JsonProperty("vod_lang")
            private String vodLang;
            @JsonProperty("vod_year")
            private String vodYear;
            @JsonProperty("vod_version")
            private String vodVersion;
            @JsonProperty("vod_state")
            private String vodState;
            @JsonProperty("vod_author")
            private String vodAuthor;
            @JsonProperty("vod_jumpurl")
            private String vodJumpurl;
            @JsonProperty("vod_tpl")
            private String vodTpl;
            @JsonProperty("vod_tpl_play")
            private String vodTplPlay;
            @JsonProperty("vod_tpl_down")
            private String vodTplDown;
            @JsonProperty("vod_isend")
            private Integer vodIsend;
            @JsonProperty("vod_lock")
            private Integer vodLock;
            @JsonProperty("vod_level")
            private Integer vodLevel;
            @JsonProperty("vod_copyright")
            private Integer vodCopyright;
            @JsonProperty("vod_points")
            private Integer vodPoints;
            @JsonProperty("vod_points_play")
            private Integer vodPointsPlay;
            @JsonProperty("vod_points_down")
            private Integer vodPointsDown;
            @JsonProperty("vod_hits")
            private Integer vodHits;
            @JsonProperty("vod_hits_day")
            private Integer vodHitsDay;
            @JsonProperty("vod_hits_week")
            private Integer vodHitsWeek;
            @JsonProperty("vod_hits_month")
            private Integer vodHitsMonth;
            @JsonProperty("vod_duration")
            private String vodDuration;
            @JsonProperty("vod_up")
            private Integer vodUp;
            @JsonProperty("vod_down")
            private Integer vodDown;
            @JsonProperty("vod_score")
            private String vodScore;
            @JsonProperty("vod_score_all")
            private Integer vodScoreAll;
            @JsonProperty("vod_score_num")
            private Integer vodScoreNum;
            @JsonProperty("vod_time")
            private String vodTime;
            @JsonProperty("vod_time_add")
            private Integer vodTimeAdd;
            @JsonProperty("vod_time_hits")
            private Integer vodTimeHits;
            @JsonProperty("vod_time_make")
            private Integer vodTimeMake;
            @JsonProperty("vod_trysee")
            private Integer vodTrysee;
            @JsonProperty("vod_douban_id")
            private Integer vodDoubanId;
            @JsonProperty("vod_douban_score")
            private String vodDoubanScore;
            @JsonProperty("vod_reurl")
            private String vodReurl;
            @JsonProperty("vod_rel_vod")
            private String vodRelVod;
            @JsonProperty("vod_rel_art")
            private String vodRelArt;
            @JsonProperty("vod_pwd")
            private String vodPwd;
            @JsonProperty("vod_pwd_url")
            private String vodPwdUrl;
            @JsonProperty("vod_pwd_play")
            private String vodPwdPlay;
            @JsonProperty("vod_pwd_play_url")
            private String vodPwdPlayUrl;
            @JsonProperty("vod_pwd_down")
            private String vodPwdDown;
            @JsonProperty("vod_pwd_down_url")
            private String vodPwdDownUrl;
            @JsonProperty("vod_content")
            private String vodContent;
            @JsonProperty("vod_play_from")
            private String vodPlayFrom;
            @JsonProperty("vod_play_server")
            private String vodPlayServer;
            @JsonProperty("vod_play_note")
            private String vodPlayNote;
            @JsonProperty("vod_play_url")
            private String vodPlayUrl;
            @JsonProperty("vod_down_from")
            private String vodDownFrom;
            @JsonProperty("vod_down_server")
            private String vodDownServer;
            @JsonProperty("vod_down_note")
            private String vodDownNote;
            @JsonProperty("vod_down_url")
            private String vodDownUrl;
            @JsonProperty("vod_plot")
            private Integer vodPlot;
            @JsonProperty("vod_plot_name")
            private String vodPlotName;
            @JsonProperty("vod_plot_detail")
            private String vodPlotDetail;
            @JsonProperty("type_name")
            private String typeName;
        }
    }
}

