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

    /** 创建 VideoDownload 实例 */
    public VideoDownload() {
    }

    /** 获取VideoDownloadUrl */
    public String getVideoDownloadUrl() {
        return videoDownloadUrl;
    }

    /** 设置VideoDownloadUrl */
    public void setVideoDownloadUrl(String videoDownloadUrl) {
        this.videoDownloadUrl = videoDownloadUrl;
    }

    /** 获取VideoDownloadName */
    public String getVideoDownloadName() {
        return videoDownloadName;
    }

    /** 设置VideoDownloadName */
    public void setVideoDownloadName(String videoDownloadName) {
        this.videoDownloadName = videoDownloadName;
    }

    /** 获取VideoDownloadQuality */
    public String getVideoDownloadQuality() {
        return videoDownloadQuality;
    }

    /** 设置VideoDownloadQuality */
    public void setVideoDownloadQuality(String videoDownloadQuality) {
        this.videoDownloadQuality = videoDownloadQuality;
    }

    /** 获取VideoDownloadPlatform */
    public String getVideoDownloadPlatform() {
        return videoDownloadPlatform;
    }

    /** 设置VideoDownloadPlatform */
    public void setVideoDownloadPlatform(String videoDownloadPlatform) {
        this.videoDownloadPlatform = videoDownloadPlatform;
    }

    /** 获取VideoDownload获取大小 */
    public String getVideoDownloadSize() {
        return videoDownloadSize;
    }

    /** 设置VideoDownload获取大小 */
    public void setVideoDownloadSize(String videoDownloadSize) {
        this.videoDownloadSize = videoDownloadSize;
    }

    /** 获取VideoDownloadShareTime */
    public String getVideoDownloadShareTime() {
        return videoDownloadShareTime;
    }

    /** 设置VideoDownloadShareTime */
    public void setVideoDownloadShareTime(String videoDownloadShareTime) {
        this.videoDownloadShareTime = videoDownloadShareTime;
    }

    /** 获取VideoDownloadType */
    public String getVideoDownloadType() {
        return videoDownloadType;
    }

    /** 设置VideoDownloadType */
    public void setVideoDownloadType(String videoDownloadType) {
        this.videoDownloadType = videoDownloadType;
    }

    /** 获取VideoDownloadMagnetic */
    public String getVideoDownloadMagnetic() {
        return videoDownloadMagnetic;
    }

    /** 设置VideoDownloadMagnetic */
    public void setVideoDownloadMagnetic(String videoDownloadMagnetic) {
        this.videoDownloadMagnetic = videoDownloadMagnetic;
    }

    /** 获取VideoDownloadStatus */
    public Byte getVideoDownloadStatus() {
        return videoDownloadStatus;
    }

    /** 设置VideoDownloadStatus */
    public void setVideoDownloadStatus(Byte videoDownloadStatus) {
        this.videoDownloadStatus = videoDownloadStatus;
    }
}

