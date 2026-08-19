package com.chua.common.support.datasearch.video.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * VideoInfoResult ?视Ƶ搜索结果 POJO?
 * <p>
 * ?MyBatis-Plus ʵ体解耦的纯数据类，可?utils-common 中独立ʹ用?
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoInfoResult {

    /** 视频ID */
    private Integer videoId;
    /** 视频标题 */
    private String videoTitle;
    /** 视频名称 */
    private String videoName;
    /** 视频别名名称 */
    private String videoAliasName;
    /** 视频分数 */
    private BigDecimal videoScore;
    /** 视频year */
    private Integer videoYear;
    /** 视频platform */
    private String videoPlatform;
    /** 视频语言 */
    private String videoLanguage;
    /** 视频quality */
    private String videoQuality;
    /** 视频thumbnail */
    private String videoThumbnail;
    /** 视频cover */
    private String videoCover;
    /** 视频URL */
    private String videoUrl;
    /** 视频views */
    private BigDecimal videoViews;
    /** 视频likes */
    private Integer videoLikes;
    /** 视频状态 */
    private Integer videoStatus;
    /** 视频持续时间 */
    private Integer videoDuration;
    /** 视频版本 */
    private Integer videoVersion;
    /** 视频DOUBANID */
    private String videoDouBanId;
    /** 视频publish日期 */
    private LocalDateTime videoPublishDate;
    /** 视频分类 */
    private String videoCategory;
    /** 视频类型 */
    private String videoType;
    /** 视频release */
    private String videoRelease;
    /** 视频district */
    private String videoDistrict;
    /** 视频尺寸 */
    private String videoSize;
    /** 视频author */
    private String videoAuthor;
    /** 视频director */
    private String videoDirector;
    /** 视频写入器 */
    private String videoWriter;
    /** 视频actor */
    private String videoActor;
    /** 视频描述 */
    private String videoDescription;
    /** 视频originID */
    private String videoOriginId;
    /** 视频popularity */
    private String videoPopularity;
    /** 视频vote数量 */
    private Integer videoVoteCount;

    // VideoInfoResult specific fields
    private List<?> videoMarkList;
    private List<?> downloadList;
    private List<?> playAddressesList;
    /** 下载urls */
    private String downloadUrls;
    /** Tags */
    private String tags;

    /** 创建 VideoInfoResult 实例 */
    public VideoInfoResult() {}

    // --- Getters and Setters ---

    /** 获取VideoId */
    public Integer getVideoId() { return videoId; }
    /** 设置VideoId */
    public void setVideoId(Integer videoId) { this.videoId = videoId; }

    /** 获取VideoTitle */
    public String getVideoTitle() { return videoTitle; }
    /** 设置VideoTitle */
    public void setVideoTitle(String videoTitle) { this.videoTitle = videoTitle; }

    /** 获取VideoName */
    public String getVideoName() { return videoName; }
    /** 设置VideoName */
    public void setVideoName(String videoName) { this.videoName = videoName; }

    /** 获取VideoAliasName */
    public String getVideoAliasName() { return videoAliasName; }
    /** 设置VideoAliasName */
    public void setVideoAliasName(String videoAliasName) { this.videoAliasName = videoAliasName; }

    /** 获取VideoScore */
    public BigDecimal getVideoScore() { return videoScore; }
    /** 设置VideoScore */
    public void setVideoScore(BigDecimal videoScore) { this.videoScore = videoScore; }

    /** 获取VideoYear */
    public Integer getVideoYear() { return videoYear; }
    /** 设置VideoYear */
    public void setVideoYear(Integer videoYear) { this.videoYear = videoYear; }

    /** 获取VideoPlatform */
    public String getVideoPlatform() { return videoPlatform; }
    /** 设置VideoPlatform */
    public void setVideoPlatform(String videoPlatform) { this.videoPlatform = videoPlatform; }

    /** 获取VideoLanguage */
    public String getVideoLanguage() { return videoLanguage; }
    /** 设置VideoLanguage */
    public void setVideoLanguage(String videoLanguage) { this.videoLanguage = videoLanguage; }

    /** 获取VideoQuality */
    public String getVideoQuality() { return videoQuality; }
    /** 设置VideoQuality */
    public void setVideoQuality(String videoQuality) { this.videoQuality = videoQuality; }

    /** 获取VideoThumbnail */
    public String getVideoThumbnail() { return videoThumbnail; }
    /** 设置VideoThumbnail */
    public void setVideoThumbnail(String videoThumbnail) { this.videoThumbnail = videoThumbnail; }

    /** 获取VideoCover */
    public String getVideoCover() { return videoCover; }
    /** 设置VideoCover */
    public void setVideoCover(String videoCover) { this.videoCover = videoCover; }

    /** 获取VideoUrl */
    public String getVideoUrl() { return videoUrl; }
    /** 设置VideoUrl */
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }

    /** 获取VideoViews */
    public BigDecimal getVideoViews() { return videoViews; }
    /** 设置VideoViews */
    public void setVideoViews(BigDecimal videoViews) { this.videoViews = videoViews; }

    /** 获取VideoLikes */
    public Integer getVideoLikes() { return videoLikes; }
    /** 设置VideoLikes */
    public void setVideoLikes(Integer videoLikes) { this.videoLikes = videoLikes; }

    /** 获取VideoStatus */
    public Integer getVideoStatus() { return videoStatus; }
    /** 设置VideoStatus */
    public void setVideoStatus(Integer videoStatus) { this.videoStatus = videoStatus; }

    /** 获取VideoDuration */
    public Integer getVideoDuration() { return videoDuration; }
    /** 设置VideoDuration */
    public void setVideoDuration(Integer videoDuration) { this.videoDuration = videoDuration; }

    /** 获取VideoVersion */
    public Integer getVideoVersion() { return videoVersion; }
    /** 设置VideoVersion */
    public void setVideoVersion(Integer videoVersion) { this.videoVersion = videoVersion; }

    /** 获取VideoDouBanId */
    public String getVideoDouBanId() { return videoDouBanId; }
    /** 设置VideoDouBanId */
    public void setVideoDouBanId(String videoDouBanId) { this.videoDouBanId = videoDouBanId; }

    /** 获取Video发布Date */
    public LocalDateTime getVideoPublishDate() { return videoPublishDate; }
    /** 设置Video发布Date */
    public void setVideoPublishDate(LocalDateTime videoPublishDate) { this.videoPublishDate = videoPublishDate; }

    /** 获取VideoCategory */
    public String getVideoCategory() { return videoCategory; }
    /** 设置VideoCategory */
    public void setVideoCategory(String videoCategory) { this.videoCategory = videoCategory; }

    /** 获取VideoType */
    public String getVideoType() { return videoType; }
    /** 设置VideoType */
    public void setVideoType(String videoType) { this.videoType = videoType; }

    /** 获取Video释放 */
    public String getVideoRelease() { return videoRelease; }
    /** 设置Video释放 */
    public void setVideoRelease(String videoRelease) { this.videoRelease = videoRelease; }

    /** 获取VideoDistrict */
    public String getVideoDistrict() { return videoDistrict; }
    /** 设置VideoDistrict */
    public void setVideoDistrict(String videoDistrict) { this.videoDistrict = videoDistrict; }

    /** 获取Video获取大小 */
    public String getVideoSize() { return videoSize; }
    /** 设置Video获取大小 */
    public void setVideoSize(String videoSize) { this.videoSize = videoSize; }

    /** 获取VideoAuthor */
    public String getVideoAuthor() { return videoAuthor; }
    /** 设置VideoAuthor */
    public void setVideoAuthor(String videoAuthor) { this.videoAuthor = videoAuthor; }

    /** 获取VideoDirector */
    public String getVideoDirector() { return videoDirector; }
    /** 设置VideoDirector */
    public void setVideoDirector(String videoDirector) { this.videoDirector = videoDirector; }

    /** 获取VideoWriter */
    public String getVideoWriter() { return videoWriter; }
    /** 设置VideoWriter */
    public void setVideoWriter(String videoWriter) { this.videoWriter = videoWriter; }

    /** 获取VideoActor */
    public String getVideoActor() { return videoActor; }
    /** 设置VideoActor */
    public void setVideoActor(String videoActor) { this.videoActor = videoActor; }

    /** 获取VideoDescription */
    public String getVideoDescription() { return videoDescription; }
    /** 设置VideoDescription */
    public void setVideoDescription(String videoDescription) { this.videoDescription = videoDescription; }

    /** 获取VideoOriginId */
    public String getVideoOriginId() { return videoOriginId; }
    /** 设置VideoOriginId */
    public void setVideoOriginId(String videoOriginId) { this.videoOriginId = videoOriginId; }

    /** 获取VideoPopularity */
    public String getVideoPopularity() { return videoPopularity; }
    /** 设置VideoPopularity */
    public void setVideoPopularity(String videoPopularity) { this.videoPopularity = videoPopularity; }

    /** 获取VideoVote计算数量 */
    public Integer getVideoVoteCount() { return videoVoteCount; }
    /** 设置VideoVote计算数量 */
    public void setVideoVoteCount(Integer videoVoteCount) { this.videoVoteCount = videoVoteCount; }

    public List<?> getVideoMarkList() { return videoMarkList; }
    /** 设置Video标记List */
    public void setVideoMarkList(List<?> videoMarkList) { this.videoMarkList = videoMarkList; }

    public List<?> getDownloadList() { return downloadList; }
    /** 设置DownloadList */
    public void setDownloadList(List<?> downloadList) { this.downloadList = downloadList; }

    public List<?> getPlayAddressesList() { return playAddressesList; }
    /** 设置PlayAddressesList */
    public void setPlayAddressesList(List<?> playAddressesList) { this.playAddressesList = playAddressesList; }

    /** 获取DownloadUrls */
    public String getDownloadUrls() { return downloadUrls; }
    /** 设置DownloadUrls */
    public void setDownloadUrls(String downloadUrls) { this.downloadUrls = downloadUrls; }

    /** 获取Tags */
    public String getTags() { return tags; }
    /** 设置Tags */
    public void setTags(String tags) { this.tags = tags; }
}

