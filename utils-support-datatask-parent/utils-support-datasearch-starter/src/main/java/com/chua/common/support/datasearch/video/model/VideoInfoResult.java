package com.chua.common.support.datasearch.video.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
* 视频信息结果 ?视Ƶ搜索结果 POJO?
* <p>
* ?MyBatis-Plus ʵ体解耦的纯数据类，可?工具-通用 中独立ʹ用?
*
* @author CH
* @since 4.0.0.42
 */
public class VideoInfoResult {

    /** 视频标识 */
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
    /** 视频发布日期 */
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
    /** 视频作者 */
    private String videoAuthor;
    /** 视频director */
    private String videoDirector;
    /** 视频写入器 */
    private String videoWriter;
    /** 视频actor */
    private String videoActor;
    /** 视频描述 */
    private String videoDescription;
    /** 视频originid */
    private String videoOriginId;
    /** 视频popularity */
    private String videoPopularity;
    /** 视频vote数量 */
    private Integer videoVoteCount;

 // 视频信息结果 特定 字段
    private List<?> videoMarkList;
    private List<?> downloadList; // download列表
    private List<?> playAddressesList; // play地址列表
    /** 下载urls */
    private String downloadUrls;
    /** 标签 */
    private String tags;
    /** 是否被封 (true=该站点已封/过期，无需重试) */
    private boolean blocked;
    /** 被封原因 */
    private String blockReason;

    /** 创建 视频信息结果 实例 */
    public VideoInfoResult() {}

 // --- Getters 和 Setters ---

    /**
    * 获取视频id
    *
    * @return 获取视频id的结果
     */
    public Integer getVideoId() { return videoId; }
    /**
    * 设置视频id
    *
    * @param videoId 视频标识
     */
    public void setVideoId(Integer videoId) { this.videoId = videoId; }

    /**
    * 获取视频title
    *
    * @return 获取视频title的结果
     */
    public String getVideoTitle() { return videoTitle; }
    /**
    * 设置视频title
    *
    * @param videoTitle 视频title
     */
    public void setVideoTitle(String videoTitle) { this.videoTitle = videoTitle; }

    /**
    * 获取视频名称
    *
    * @return 获取视频名称的结果
     */
    public String getVideoName() { return videoName; }
    /**
    * 设置视频名称
    *
    * @param videoName 视频名称
     */
    public void setVideoName(String videoName) { this.videoName = videoName; }

    /**
    * 获取视频别名名称
    *
    * @return 获取视频别名名称的结果
     */
    public String getVideoAliasName() { return videoAliasName; }
    /**
    * 设置视频别名名称
    *
    * @param videoAliasName 视频别名名称
     */
    public void setVideoAliasName(String videoAliasName) { this.videoAliasName = videoAliasName; }

    /**
    * 获取视频score
    *
    * @return 获取视频score的结果
     */
    public BigDecimal getVideoScore() { return videoScore; }
    /**
    * 设置视频score
    *
    * @param videoScore 视频score
     */
    public void setVideoScore(BigDecimal videoScore) { this.videoScore = videoScore; }

    /**
    * 获取视频year
    *
    * @return 获取视频year的结果
     */
    public Integer getVideoYear() { return videoYear; }
    /**
    * 设置视频year
    *
    * @param videoYear 视频year
     */
    public void setVideoYear(Integer videoYear) { this.videoYear = videoYear; }

    /**
    * 获取视频platform
    *
    * @return 获取视频platform的结果
     */
    public String getVideoPlatform() { return videoPlatform; }
    /**
    * 设置视频platform
    *
    * @param videoPlatform 视频platform
     */
    public void setVideoPlatform(String videoPlatform) { this.videoPlatform = videoPlatform; }

    /**
    * 获取视频language
    *
    * @return 获取视频language的结果
     */
    public String getVideoLanguage() { return videoLanguage; }
    /**
    * 设置视频language
    *
    * @param videoLanguage 视频language
     */
    public void setVideoLanguage(String videoLanguage) { this.videoLanguage = videoLanguage; }

    /**
    * 获取视频quality
    *
    * @return 获取视频quality的结果
     */
    public String getVideoQuality() { return videoQuality; }
    /**
    * 设置视频quality
    *
    * @param videoQuality 视频quality
     */
    public void setVideoQuality(String videoQuality) { this.videoQuality = videoQuality; }

    /**
    * 获取视频thumbnail
    *
    * @return 获取视频thumbnail的结果
     */
    public String getVideoThumbnail() { return videoThumbnail; }
    /**
    * 设置视频thumbnail
    *
    * @param videoThumbnail 视频thumbnail
     */
    public void setVideoThumbnail(String videoThumbnail) { this.videoThumbnail = videoThumbnail; }

    /**
    * 获取视频cover
    *
    * @return 获取视频cover的结果
     */
    public String getVideoCover() { return videoCover; }
    /**
    * 设置视频cover
    *
    * @param videoCover 视频cover
     */
    public void setVideoCover(String videoCover) { this.videoCover = videoCover; }

    /**
    * 获取视频url
    *
    * @return 获取视频url的结果
     */
    public String getVideoUrl() { return videoUrl; }
    /**
    * 设置视频url
    *
    * @param videoUrl 视频url
     */
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }

    /**
    * 获取视频views
    *
    * @return 获取视频views的结果
     */
    public BigDecimal getVideoViews() { return videoViews; }
    /**
    * 设置视频views
    *
    * @param videoViews 视频views
     */
    public void setVideoViews(BigDecimal videoViews) { this.videoViews = videoViews; }

    /**
    * 获取视频likes
    *
    * @return 获取视频likes的结果
     */
    public Integer getVideoLikes() { return videoLikes; }
    /**
    * 设置视频likes
    *
    * @param videoLikes 视频likes
     */
    public void setVideoLikes(Integer videoLikes) { this.videoLikes = videoLikes; }

    /**
    * 获取视频状态
    *
    * @return 获取视频状态的结果
     */
    public Integer getVideoStatus() { return videoStatus; }
    /**
    * 设置视频状态
    *
    * @param videoStatus 视频状态
     */
    public void setVideoStatus(Integer videoStatus) { this.videoStatus = videoStatus; }

    /**
    * 获取视频持续时间
    *
    * @return 获取视频持续时间的结果
     */
    public Integer getVideoDuration() { return videoDuration; }
    /**
    * 设置视频持续时间
    *
    * @param videoDuration 视频持续时间
     */
    public void setVideoDuration(Integer videoDuration) { this.videoDuration = videoDuration; }

    /**
    * 获取视频版本
    *
    * @return 获取视频版本的结果
     */
    public Integer getVideoVersion() { return videoVersion; }
    /**
    * 设置视频版本
    *
    * @param videoVersion 视频版本
     */
    public void setVideoVersion(Integer videoVersion) { this.videoVersion = videoVersion; }

    /**
    * 获取视频doubanid
    *
    * @return 获取视频doubanid的结果
     */
    public String getVideoDouBanId() { return videoDouBanId; }
    /**
    * 设置视频doubanid
    *
    * @param videoDouBanId 视频doubanid
     */
    public void setVideoDouBanId(String videoDouBanId) { this.videoDouBanId = videoDouBanId; }

    /**
    * 获取视频发布日期
    *
    * @return 获取视频发布日期的结果
     */
    public LocalDateTime getVideoPublishDate() { return videoPublishDate; }
    /**
    * 设置视频发布日期
    *
    * @param videoPublishDate 视频发布日期
     */
    public void setVideoPublishDate(LocalDateTime videoPublishDate) { this.videoPublishDate = videoPublishDate; }

    /**
    * 获取视频分类
    *
    * @return 获取视频分类的结果
     */
    public String getVideoCategory() { return videoCategory; }
    /**
    * 设置视频分类
    *
    * @param videoCategory 视频分类
     */
    public void setVideoCategory(String videoCategory) { this.videoCategory = videoCategory; }

    /**
    * 获取视频类型
    *
    * @return 获取视频类型的结果
     */
    public String getVideoType() { return videoType; }
    /**
    * 设置视频类型
    *
    * @param videoType 视频类型
     */
    public void setVideoType(String videoType) { this.videoType = videoType; }

    /**
    * 获取视频释放
    *
    * @return 获取视频release的结果
     */
    public String getVideoRelease() { return videoRelease; }
    /**
    * 设置视频释放
    *
    * @param videoRelease 视频release
     */
    public void setVideoRelease(String videoRelease) { this.videoRelease = videoRelease; }

    /**
    * 获取视频district
    *
    * @return 获取视频district的结果
     */
    public String getVideoDistrict() { return videoDistrict; }
    /**
    * 设置视频district
    *
    * @param videoDistrict 视频district
     */
    public void setVideoDistrict(String videoDistrict) { this.videoDistrict = videoDistrict; }

    /**
    * 获取视频获取大小
    *
    * @return 获取视频大小的结果
     */
    public String getVideoSize() { return videoSize; }
    /**
    * 设置视频获取大小
    *
    * @param videoSize 视频大小
     */
    public void setVideoSize(String videoSize) { this.videoSize = videoSize; }

    /**
    * 获取视频作者
    *
    * @return 获取视频作者的结果
     */
    public String getVideoAuthor() { return videoAuthor; }
    /**
    * 设置视频作者
    *
    * @param videoAuthor 视频作者
     */
    public void setVideoAuthor(String videoAuthor) { this.videoAuthor = videoAuthor; }

    /**
    * 获取视频director
    *
    * @return 获取视频director的结果
     */
    public String getVideoDirector() { return videoDirector; }
    /**
    * 设置视频director
    *
    * @param videoDirector 视频director
     */
    public void setVideoDirector(String videoDirector) { this.videoDirector = videoDirector; }

    /**
    * 获取视频writer
    *
    * @return 获取视频writer的结果
     */
    public String getVideoWriter() { return videoWriter; }
    /**
    * 设置视频writer
    *
    * @param videoWriter 视频writer
     */
    public void setVideoWriter(String videoWriter) { this.videoWriter = videoWriter; }

    /**
    * 获取视频actor
    *
    * @return 获取视频actor的结果
     */
    public String getVideoActor() { return videoActor; }
    /**
    * 设置视频actor
    *
    * @param videoActor 视频actor
     */
    public void setVideoActor(String videoActor) { this.videoActor = videoActor; }

    /**
    * 获取视频description
    *
    * @return 获取视频description的结果
     */
    public String getVideoDescription() { return videoDescription; }
    /**
    * 设置视频description
    *
    * @param videoDescription 视频description
     */
    public void setVideoDescription(String videoDescription) { this.videoDescription = videoDescription; }

    /**
    * 获取视频originid
    *
    * @return 获取视频originid的结果
     */
    public String getVideoOriginId() { return videoOriginId; }
    /**
    * 设置视频originid
    *
    * @param videoOriginId 视频originid
     */
    public void setVideoOriginId(String videoOriginId) { this.videoOriginId = videoOriginId; }

    /**
    * 获取视频popularity
    *
    * @return 获取视频popularity的结果
     */
    public String getVideoPopularity() { return videoPopularity; }
    /**
    * 设置视频popularity
    *
    * @param videoPopularity 视频popularity
     */
    public void setVideoPopularity(String videoPopularity) { this.videoPopularity = videoPopularity; }

    /**
    * 获取视频vote计算数量
    *
    * @return 获取视频vote数量的结果
     */
    public Integer getVideoVoteCount() { return videoVoteCount; }
    /**
    * 设置视频vote计算数量
    *
    * @param videoVoteCount 视频vote数量
     */
    public void setVideoVoteCount(Integer videoVoteCount) { this.videoVoteCount = videoVoteCount; }

    /**
    * 获取视频mark列表。
    * @return 获取视频mark列表的结果
     */
    public List<?> getVideoMarkList() { return videoMarkList; }
    /**
    * 设置视频标记列表
    *
    * @param videoMarkList 视频mark列表
     */
    public void setVideoMarkList(List<?> videoMarkList) { this.videoMarkList = videoMarkList; }

    /**
    * 获取download列表。
    * @return 获取download列表的结果
     */
    public List<?> getDownloadList() { return downloadList; }
    /**
    * 设置download列表
    *
    * @param downloadList download列表
     */
    public void setDownloadList(List<?> downloadList) { this.downloadList = downloadList; }

    /**
    * 获取play地址列表。
    * @return 获取play地址列表的结果
     */
    public List<?> getPlayAddressesList() { return playAddressesList; }
    /**
    * 设置play地址列表
    *
    * @param playAddressesList play地址列表
     */
    public void setPlayAddressesList(List<?> playAddressesList) { this.playAddressesList = playAddressesList; }

    /**
    * 获取downloadurls
    *
    * @return 获取downloadurls的结果
     */
    public String getDownloadUrls() { return downloadUrls; }
    /**
    * 设置downloadurls
    *
    * @param downloadUrls downloadurls
     */
    public void setDownloadUrls(String downloadUrls) { this.downloadUrls = downloadUrls; }

    /**
    * 获取标签
    *
    * @return 获取标签的结果
     */
    public String getTags() { return tags; }
    /**
    * 设置标签
    *
    * @param tags 标签
     */
    public void setTags(String tags) { this.tags = tags; }
}

