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

    private Integer videoId;
    private String videoTitle;
    private String videoName;
    private String videoAliasName;
    private BigDecimal videoScore;
    private Integer videoYear;
    private String videoPlatform;
    private String videoLanguage;
    private String videoQuality;
    private String videoThumbnail;
    private String videoCover;
    private String videoUrl;
    private BigDecimal videoViews;
    private Integer videoLikes;
    private Integer videoStatus;
    private Integer videoDuration;
    private Integer videoVersion;
    private String videoDouBanId;
    private LocalDateTime videoPublishDate;
    private String videoCategory;
    private String videoType;
    private String videoRelease;
    private String videoDistrict;
    private String videoSize;
    private String videoAuthor;
    private String videoDirector;
    private String videoWriter;
    private String videoActor;
    private String videoDescription;
    private String videoOriginId;
    private String videoPopularity;
    private Integer videoVoteCount;

    // VideoInfoResult specific fields
    private List<?> videoMarkList;
    private List<?> downloadList;
    private List<?> playAddressesList;
    private String downloadUrls;
    private String tags;

    public VideoInfoResult() {}

    // --- Getters and Setters ---

    public Integer getVideoId() { return videoId; }
    public void setVideoId(Integer videoId) { this.videoId = videoId; }

    public String getVideoTitle() { return videoTitle; }
    public void setVideoTitle(String videoTitle) { this.videoTitle = videoTitle; }

    public String getVideoName() { return videoName; }
    public void setVideoName(String videoName) { this.videoName = videoName; }

    public String getVideoAliasName() { return videoAliasName; }
    public void setVideoAliasName(String videoAliasName) { this.videoAliasName = videoAliasName; }

    public BigDecimal getVideoScore() { return videoScore; }
    public void setVideoScore(BigDecimal videoScore) { this.videoScore = videoScore; }

    public Integer getVideoYear() { return videoYear; }
    public void setVideoYear(Integer videoYear) { this.videoYear = videoYear; }

    public String getVideoPlatform() { return videoPlatform; }
    public void setVideoPlatform(String videoPlatform) { this.videoPlatform = videoPlatform; }

    public String getVideoLanguage() { return videoLanguage; }
    public void setVideoLanguage(String videoLanguage) { this.videoLanguage = videoLanguage; }

    public String getVideoQuality() { return videoQuality; }
    public void setVideoQuality(String videoQuality) { this.videoQuality = videoQuality; }

    public String getVideoThumbnail() { return videoThumbnail; }
    public void setVideoThumbnail(String videoThumbnail) { this.videoThumbnail = videoThumbnail; }

    public String getVideoCover() { return videoCover; }
    public void setVideoCover(String videoCover) { this.videoCover = videoCover; }

    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }

    public BigDecimal getVideoViews() { return videoViews; }
    public void setVideoViews(BigDecimal videoViews) { this.videoViews = videoViews; }

    public Integer getVideoLikes() { return videoLikes; }
    public void setVideoLikes(Integer videoLikes) { this.videoLikes = videoLikes; }

    public Integer getVideoStatus() { return videoStatus; }
    public void setVideoStatus(Integer videoStatus) { this.videoStatus = videoStatus; }

    public Integer getVideoDuration() { return videoDuration; }
    public void setVideoDuration(Integer videoDuration) { this.videoDuration = videoDuration; }

    public Integer getVideoVersion() { return videoVersion; }
    public void setVideoVersion(Integer videoVersion) { this.videoVersion = videoVersion; }

    public String getVideoDouBanId() { return videoDouBanId; }
    public void setVideoDouBanId(String videoDouBanId) { this.videoDouBanId = videoDouBanId; }

    public LocalDateTime getVideoPublishDate() { return videoPublishDate; }
    public void setVideoPublishDate(LocalDateTime videoPublishDate) { this.videoPublishDate = videoPublishDate; }

    public String getVideoCategory() { return videoCategory; }
    public void setVideoCategory(String videoCategory) { this.videoCategory = videoCategory; }

    public String getVideoType() { return videoType; }
    public void setVideoType(String videoType) { this.videoType = videoType; }

    public String getVideoRelease() { return videoRelease; }
    public void setVideoRelease(String videoRelease) { this.videoRelease = videoRelease; }

    public String getVideoDistrict() { return videoDistrict; }
    public void setVideoDistrict(String videoDistrict) { this.videoDistrict = videoDistrict; }

    public String getVideoSize() { return videoSize; }
    public void setVideoSize(String videoSize) { this.videoSize = videoSize; }

    public String getVideoAuthor() { return videoAuthor; }
    public void setVideoAuthor(String videoAuthor) { this.videoAuthor = videoAuthor; }

    public String getVideoDirector() { return videoDirector; }
    public void setVideoDirector(String videoDirector) { this.videoDirector = videoDirector; }

    public String getVideoWriter() { return videoWriter; }
    public void setVideoWriter(String videoWriter) { this.videoWriter = videoWriter; }

    public String getVideoActor() { return videoActor; }
    public void setVideoActor(String videoActor) { this.videoActor = videoActor; }

    public String getVideoDescription() { return videoDescription; }
    public void setVideoDescription(String videoDescription) { this.videoDescription = videoDescription; }

    public String getVideoOriginId() { return videoOriginId; }
    public void setVideoOriginId(String videoOriginId) { this.videoOriginId = videoOriginId; }

    public String getVideoPopularity() { return videoPopularity; }
    public void setVideoPopularity(String videoPopularity) { this.videoPopularity = videoPopularity; }

    public Integer getVideoVoteCount() { return videoVoteCount; }
    public void setVideoVoteCount(Integer videoVoteCount) { this.videoVoteCount = videoVoteCount; }

    public List<?> getVideoMarkList() { return videoMarkList; }
    public void setVideoMarkList(List<?> videoMarkList) { this.videoMarkList = videoMarkList; }

    public List<?> getDownloadList() { return downloadList; }
    public void setDownloadList(List<?> downloadList) { this.downloadList = downloadList; }

    public List<?> getPlayAddressesList() { return playAddressesList; }
    public void setPlayAddressesList(List<?> playAddressesList) { this.playAddressesList = playAddressesList; }

    public String getDownloadUrls() { return downloadUrls; }
    public void setDownloadUrls(String downloadUrls) { this.downloadUrls = downloadUrls; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
}

