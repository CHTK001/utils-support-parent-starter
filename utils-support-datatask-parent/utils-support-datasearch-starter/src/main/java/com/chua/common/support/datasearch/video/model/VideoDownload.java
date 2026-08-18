package com.chua.common.support.datasearch.video.model;

/**
 * 视频下载链接
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoDownload {

    /** 视频下载URL */
    private String videoDownloadUrl;
    /** 视频下载名称 */
    private String videoDownloadName;
    /** 视频下载quality */
    private String videoDownloadQuality;
    /** 视频下载platform */
    private String videoDownloadPlatform;
    /** 视频下载尺寸 */
    private String videoDownloadSize;
    /** 视频下载share时间 */
    private String videoDownloadShareTime;
    /** 视频下载类型 */
    private String videoDownloadType;
    /** 视频下载magnetic */
    private String videoDownloadMagnetic;
    /** 视频下载状态 */
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

