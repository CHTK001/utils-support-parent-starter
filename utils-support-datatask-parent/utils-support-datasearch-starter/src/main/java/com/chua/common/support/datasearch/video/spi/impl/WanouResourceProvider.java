package com.chua.common.support.datasearch.video.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
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
import java.util.List;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Spi("wanou")
public class WanouResourceProvider extends AbstractResourceProvider {
    /**
     * 创建 wanouresource提供者 实例
    */
    public WanouResourceProvider() {
        super();
    }

    /**
     * 创建 wanouresource提供者 实例
     * @param videoSource 视频源
     */
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

    /**
     * 搜索Resource。
     * <p>请求 MacCMS v1 采集接口（ac=detail），解析 JSON 为视频列表。
     * 响应非 JSON（如 403 HTML）时快速返回不可用错误，避免解析异常。</p>
     *
     * @param videoSearch 视频搜索，keyword 不能为空，为 null/空时返回错误结果
     * @return 搜索Resource的结果；列表为空时返回空结果，响应异常时返回错误结果
     */
    @Override
    public ReturnPageResult<VideoInfoResult> searchResource(VideoSearch videoSearch) {
        if (!StringUtils.hasText(videoSearch.getKeyword())) {
            return ReturnPageResult.error("关键词不能为空");
        }
        try {
            String url = getUrl();
            url = String.format(url, videoSearch.getKeyword());
            ClientResponse response = HttpClientFactory.of(url).get();
            String body = response.getBodyString();
            // 非 JSON 响应（如 403 HTML）快速失败，避免 Jackson 解析炸裂
            if (body == null || !body.trim().startsWith("{")) {
                String preview = body == null ? "空响应"
                        : body.replaceAll("\\s+", " ").substring(0, Math.min(60, body.length()));
                return ReturnPageResult.error("Wanou 资源站不可用(非JSON响应): " + preview);
            }
            VideoList content = Json.fromJson(body, VideoList.class);
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
                                List<VideoDownload> videoDownloads = Wraps.list();
                                registerDownload(videoDownloads, vodDownFrom, vodDownUrl);
                                result.setDownloadList(videoDownloads);
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

    /**
     * 注册Download
     *
     * @param videoDownloads 视频downloads
     * @param vodDownFrom voddown从
     * @param vodDownUrl voddownurl
     */
    private void registerDownload(List<VideoDownload> videoDownloads, String vodDownFrom, String vodDownUrl) {
        if (null == vodDownFrom) {
            return;
        }

        List<String> froms = Splitter.on("$$$").splitToList(vodDownFrom);
        List<String> urls = Splitter.on("$$$").splitToList(vodDownUrl);
        for (int i = 0; i < froms.size() && i < urls.size(); i++) {
            VideoDownload videoDownload = new VideoDownload();
            videoDownload.setVideoDownloadName(transferName(froms.get(i)));
            videoDownload.setVideoDownloadUrl(urls.get(i));
            videoDownloads.add(videoDownload);
        }
    }

    /**
     * 调动名称
     *
     * @param s s
     * @return 调动名称的结果
     * @author CH
     * @since 4.0.0
     */
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

        /**
         * 代码
        */
        @JsonProperty("code")
        private Integer code;
        /**
         * 消息文本
        */
        @JsonProperty("msg")
        private String msg;
        /**
         * 页码
        */
        @JsonProperty("page")
        private Integer page;
        /**
         * 页数
        */
        @JsonProperty("pagecount")
        private Integer pagecount;
        /**
         * 限制数量
        */
        @JsonProperty("limit")
        private Integer limit;
        /**
         * 总数
        */
        @JsonProperty("total")
        private Integer total;
        /**
         * 列表数据
        */
        @JsonProperty("list")
        private List<ListDTO> list;

        @NoArgsConstructor
        @Data
        public static class ListDTO {
            /**
             * 视频标识
            */
            @JsonProperty("vod_id")
            private Integer vodId;
            /**
             * 类型标识
            */
            @JsonProperty("type_id")
            private Integer typeId;
            /**
             * 类型标识
            */
            @JsonProperty("type_id_1")
            private Integer typeId1;
            /**
             * 分组标识
            */
            @JsonProperty("group_id")
            private Integer groupId;
            /**
             * 影片名称
            */
            @JsonProperty("vod_name")
            private String vodName;
            /**
             * 副标题
            */
            @JsonProperty("vod_sub")
            private String vodSub;
            /**
             * 英文名称
            */
            @JsonProperty("vod_en")
            private String vodEn;
            /**
             * 状态标识
            */
            @JsonProperty("vod_status")
            private Integer vodStatus;
            /**
             * 首字母
            */
            @JsonProperty("vod_letter")
            private String vodLetter;
            /**
             * 颜色标识
            */
            @JsonProperty("vod_color")
            private String vodColor;
            /**
             * 标签
            */
            @JsonProperty("vod_tag")
            private String vodTag;
            /**
             * 分类
            */
            @JsonProperty("vod_class")
            private String vodClass;
            /**
             * 封面图
            */
            @JsonProperty("vod_pic")
            private String vodPic;
            /**
             * 缩略图
            */
            @JsonProperty("vod_pic_thumb")
            private String vodPicThumb;
            /**
             * 轮播图
            */
            @JsonProperty("vod_pic_slide")
            private String vodPicSlide;
            /**
             * 截图图集
            */
            @JsonProperty("vod_pic_screenshot")
            private String vodPicScreenshot;
            /**
             * 演员列表
            */
            @JsonProperty("vod_actor")
            private String vodActor;
            /**
             * 导演
            */
            @JsonProperty("vod_director")
            private String vodDirector;
            /**
             * 编剧
            */
            @JsonProperty("vod_writer")
            private String vodWriter;
            /**
             * 幕后花絮
            */
            @JsonProperty("vod_behind")
            private String vodBehind;
            /**
             * 简介
            */
            @JsonProperty("vod_blurb")
            private String vodBlurb;
            /**
             * 备注
            */
            @JsonProperty("vod_remarks")
            private String vodRemarks;
            /**
             * 上映时间
            */
            @JsonProperty("vod_pubdate")
            private String vodPubdate;
            /**
             * 总集数
            */
            @JsonProperty("vod_total")
            private Integer vodTotal;
            /**
             * 连载状态
            */
            @JsonProperty("vod_serial")
            private String vodSerial;
            /**
             * 电视台
            */
            @JsonProperty("vod_tv")
            private String vodTv;
            /**
             * 星期
            */
            @JsonProperty("vod_weekday")
            private String vodWeekday;
            /**
             * 地区
            */
            @JsonProperty("vod_area")
            private String vodArea;
            /**
             * 语言
            */
            @JsonProperty("vod_lang")
            private String vodLang;
            /**
             * 年份
            */
            @JsonProperty("vod_year")
            private String vodYear;
            /**
             * 版本
            */
            @JsonProperty("vod_version")
            private String vodVersion;
            /**
             * 状态
            */
            @JsonProperty("vod_state")
            private String vodState;
            /**
             * 作者
            */
            @JsonProperty("vod_author")
            private String vodAuthor;
            /**
             * 跳转地址
            */
            @JsonProperty("vod_jumpurl")
            private String vodJumpurl;
            /**
             * 模板
            */
            @JsonProperty("vod_tpl")
            private String vodTpl;
            /**
             * 播放模板
            */
            @JsonProperty("vod_tpl_play")
            private String vodTplPlay;
            /**
             * 下载模板
            */
            @JsonProperty("vod_tpl_down")
            private String vodTplDown;
            /**
             * 是否完结
            */
            @JsonProperty("vod_isend")
            private Integer vodIsend;
            /**
             * 是否锁定
            */
            @JsonProperty("vod_lock")
            private Integer vodLock;
            /**
             * 等级
            */
            @JsonProperty("vod_level")
            private Integer vodLevel;
            /**
             * 版权
            */
            @JsonProperty("vod_copyright")
            private Integer vodCopyright;
            /**
             * 积分
            */
            @JsonProperty("vod_points")
            private Integer vodPoints;
            /**
             * 播放所需积分
            */
            @JsonProperty("vod_points_play")
            private Integer vodPointsPlay;
            /**
             * 下载所需积分
            */
            @JsonProperty("vod_points_down")
            private Integer vodPointsDown;
            /**
             * 点击量
            */
            @JsonProperty("vod_hits")
            private Integer vodHits;
            /**
             * 日点击量
            */
            @JsonProperty("vod_hits_day")
            private Integer vodHitsDay;
            /**
             * 周点击量
            */
            @JsonProperty("vod_hits_week")
            private Integer vodHitsWeek;
            /**
             * 月点击量
            */
            @JsonProperty("vod_hits_month")
            private Integer vodHitsMonth;
            /**
             * 时长
            */
            @JsonProperty("vod_duration")
            private String vodDuration;
            /**
             * 是否上架
            */
            @JsonProperty("vod_up")
            private Integer vodUp;
            /**
             * 下载开关
            */
            @JsonProperty("vod_down")
            private Integer vodDown;
            /**
             * 评分
            */
            @JsonProperty("vod_score")
            private String vodScore;
            /**
             * 评分总数
            */
            @JsonProperty("vod_score_all")
            private Integer vodScoreAll;
            /**
             * 评分人数
            */
            @JsonProperty("vod_score_num")
            private Integer vodScoreNum;
            /**
             * 更新时间
            */
            @JsonProperty("vod_time")
            private String vodTime;
            /**
             * 添加时间戳
            */
            @JsonProperty("vod_time_add")
            private Integer vodTimeAdd;
            /**
             * 点击更新时间戳
            */
            @JsonProperty("vod_time_hits")
            private Integer vodTimeHits;
            /**
             * 制作时间戳
            */
            @JsonProperty("vod_time_make")
            private Integer vodTimeMake;
            /**
             * 试看秒数
            */
            @JsonProperty("vod_trysee")
            private Integer vodTrysee;
            /**
             * 豆瓣标识
            */
            @JsonProperty("vod_douban_id")
            private Integer vodDoubanId;
            /**
             * 豆瓣评分
            */
            @JsonProperty("vod_douban_score")
            private String vodDoubanScore;
            /**
             * 重定向地址
            */
            @JsonProperty("vod_reurl")
            private String vodReurl;
            /**
             * 相关视频
            */
            @JsonProperty("vod_rel_vod")
            private String vodRelVod;
            /**
             * 相关文章
            */
            @JsonProperty("vod_rel_art")
            private String vodRelArt;
            /**
             * 密码
            */
            @JsonProperty("vod_pwd")
            private String vodPwd;
            /**
             * 带密码地址
            */
            @JsonProperty("vod_pwd_url")
            private String vodPwdUrl;
            /**
             * 播放密码
            */
            @JsonProperty("vod_pwd_play")
            private String vodPwdPlay;
            /**
             * 带密码播放地址
            */
            @JsonProperty("vod_pwd_play_url")
            private String vodPwdPlayUrl;
            /**
             * 下载密码
            */
            @JsonProperty("vod_pwd_down")
            private String vodPwdDown;
            /**
             * 带密码下载地址
            */
            @JsonProperty("vod_pwd_down_url")
            private String vodPwdDownUrl;
            /**
             * 内容
            */
            @JsonProperty("vod_content")
            private String vodContent;
            /**
             * 播放来源
            */
            @JsonProperty("vod_play_from")
            private String vodPlayFrom;
            /**
             * 播放服务器
            */
            @JsonProperty("vod_play_server")
            private String vodPlayServer;
            /**
             * 播放说明
            */
            @JsonProperty("vod_play_note")
            private String vodPlayNote;
            /**
             * 播放地址
            */
            @JsonProperty("vod_play_url")
            private String vodPlayUrl;
            /**
             * 下载来源
            */
            @JsonProperty("vod_down_from")
            private String vodDownFrom;
            /**
             * 下载服务器
            */
            @JsonProperty("vod_down_server")
            private String vodDownServer;
            /**
             * 下载说明
            */
            @JsonProperty("vod_down_note")
            private String vodDownNote;
            /**
             * 下载地址
            */
            @JsonProperty("vod_down_url")
            private String vodDownUrl;
            /**
             * 剧情概要
            */
            @JsonProperty("vod_plot")
            private Integer vodPlot;
            /**
             * 剧情名称
            */
            @JsonProperty("vod_plot_name")
            private String vodPlotName;
            /**
             * 剧情详细介绍
            */
            @JsonProperty("vod_plot_detail")
            private String vodPlotDetail;
            /**
             * 类型名称
            */
            @JsonProperty("type_name")
            private String typeName;
        }
    }
}

