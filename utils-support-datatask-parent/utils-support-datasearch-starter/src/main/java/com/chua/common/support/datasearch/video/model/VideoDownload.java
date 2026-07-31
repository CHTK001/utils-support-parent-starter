package com.chua.common.support.datasearch.video.model;

/**
 * 视频下载链接
 *
 * @author CH
 * @since 2025/9/18 09:26
 */
public class VideoDownload {

    private String videoDownloadUrl;
    private String videoDownloadName;
    private String videoDownloadQuality;
    private String videoDownloadPlatform;
    private String videoDownloadSize;
    private String videoDownloadShareTime;
    private String videoDownloadType;
    private String videoDownloadMagnetic;
    private Byte videoDownloadStatus;

    public VideoDownload() {
    }

    public String getVideoDownloadUrl() {
        return videoDownloadUrl;
    }

    public void setVideoDownloadUrl(String videoDownloadUrl) {
        this.videoDownloadUrl = videoDownloadUrl;
    }

    public String getVideoDownloadName() {
        return videoDownloadName;
    }

    public void setVideoDownloadName(String videoDownloadName) {
        this.videoDownloadName = videoDownloadName;
    }

    public String getVideoDownloadQuality() {
        return videoDownloadQuality;
    }

    public void setVideoDownloadQuality(String videoDownloadQuality) {
        this.videoDownloadQuality = videoDownloadQuality;
    }

    public String getVideoDownloadPlatform() {
        return videoDownloadPlatform;
    }

    public void setVideoDownloadPlatform(String videoDownloadPlatform) {
        this.videoDownloadPlatform = videoDownloadPlatform;
    }

    public String getVideoDownloadSize() {
        return videoDownloadSize;
    }

    public void setVideoDownloadSize(String videoDownloadSize) {
        this.videoDownloadSize = videoDownloadSize;
    }

    public String getVideoDownloadShareTime() {
        return videoDownloadShareTime;
    }

    public void setVideoDownloadShareTime(String videoDownloadShareTime) {
        this.videoDownloadShareTime = videoDownloadShareTime;
    }

    public String getVideoDownloadType() {
        return videoDownloadType;
    }

    public void setVideoDownloadType(String videoDownloadType) {
        this.videoDownloadType = videoDownloadType;
    }

    public String getVideoDownloadMagnetic() {
        return videoDownloadMagnetic;
    }

    public void setVideoDownloadMagnetic(String videoDownloadMagnetic) {
        this.videoDownloadMagnetic = videoDownloadMagnetic;
    }

    public Byte getVideoDownloadStatus() {
        return videoDownloadStatus;
    }

    public void setVideoDownloadStatus(Byte videoDownloadStatus) {
        this.videoDownloadStatus = videoDownloadStatus;
    }
}

