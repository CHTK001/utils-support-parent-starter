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
    /** 视频下载共享时间 */
    private String videoDownloadShareTime;
    /** 视频下载类型 */
    private String videoDownloadType;
    /** 视频下载magnetic */
    private String videoDownloadMagnetic;
    /** 视频下载状态 */
    private Byte videoDownloadStatus;

    /** 创建 视频download 实例 */
    public VideoDownload() {
    }

    /**
     * 获取视频downloadurl
     *
     * @return 获取视频downloadurl的结果
     */
    public String getVideoDownloadUrl() {
        return videoDownloadUrl;
    }

    /**
     * 设置视频downloadurl
     *
     * @param videoDownloadUrl 视频downloadurl
     */
    public void setVideoDownloadUrl(String videoDownloadUrl) {
        this.videoDownloadUrl = videoDownloadUrl;
    }

    /**
     * 获取视频download名称
     *
     * @return 获取视频download名称的结果
     */
    public String getVideoDownloadName() {
        return videoDownloadName;
    }

    /**
     * 设置视频download名称
     *
     * @param videoDownloadName 视频download名称
     */
    public void setVideoDownloadName(String videoDownloadName) {
        this.videoDownloadName = videoDownloadName;
    }

    /**
     * 获取视频downloadquality
     *
     * @return 获取视频downloadquality的结果
     */
    public String getVideoDownloadQuality() {
        return videoDownloadQuality;
    }

    /**
     * 设置视频downloadquality
     *
     * @param videoDownloadQuality 视频downloadquality
     */
    public void setVideoDownloadQuality(String videoDownloadQuality) {
        this.videoDownloadQuality = videoDownloadQuality;
    }

    /**
     * 获取视频downloadplatform
     *
     * @return 获取视频downloadplatform的结果
     */
    public String getVideoDownloadPlatform() {
        return videoDownloadPlatform;
    }

    /**
     * 设置视频downloadplatform
     *
     * @param videoDownloadPlatform 视频downloadplatform
     */
    public void setVideoDownloadPlatform(String videoDownloadPlatform) {
        this.videoDownloadPlatform = videoDownloadPlatform;
    }

    /**
     * 获取视频download获取大小
     *
     * @return 获取视频download大小的结果
     */
    public String getVideoDownloadSize() {
        return videoDownloadSize;
    }

    /**
     * 设置视频download获取大小
     *
     * @param videoDownloadSize 视频download大小
     */
    public void setVideoDownloadSize(String videoDownloadSize) {
        this.videoDownloadSize = videoDownloadSize;
    }

    /**
     * 获取视频download共享时间
     *
     * @return 获取视频download共享时间的结果
     */
    public String getVideoDownloadShareTime() {
        return videoDownloadShareTime;
    }

    /**
     * 设置视频download共享时间
     *
     * @param videoDownloadShareTime 视频download共享时间
     */
    public void setVideoDownloadShareTime(String videoDownloadShareTime) {
        this.videoDownloadShareTime = videoDownloadShareTime;
    }

    /**
     * 获取视频download类型
     *
     * @return 获取视频download类型的结果
     */
    public String getVideoDownloadType() {
        return videoDownloadType;
    }

    /**
     * 设置视频download类型
     *
     * @param videoDownloadType 视频download类型
     */
    public void setVideoDownloadType(String videoDownloadType) {
        this.videoDownloadType = videoDownloadType;
    }

    /**
     * 获取视频downloadmagnetic
     *
     * @return 获取视频downloadmagnetic的结果
     */
    public String getVideoDownloadMagnetic() {
        return videoDownloadMagnetic;
    }

    /**
     * 设置视频downloadmagnetic
     *
     * @param videoDownloadMagnetic 视频downloadmagnetic
     */
    public void setVideoDownloadMagnetic(String videoDownloadMagnetic) {
        this.videoDownloadMagnetic = videoDownloadMagnetic;
    }

    /**
     * 获取视频download状态
     *
     * @return 获取视频download状态的结果
     */
    public Byte getVideoDownloadStatus() {
        return videoDownloadStatus;
    }

    /**
     * 设置视频download状态
     *
     * @param videoDownloadStatus 视频download状态
     */
    public void setVideoDownloadStatus(Byte videoDownloadStatus) {
        this.videoDownloadStatus = videoDownloadStatus;
    }
}

