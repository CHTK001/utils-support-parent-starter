package com.chua.common.support.datasearch.video.model;

import java.time.LocalDateTime;

/**
 * 视频同步配置
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoSyncConfig {

    /** 视频同步配置标识 */
    private String videoSyncConfigId;
    /** 视频同步配置名称 */
    private String videoSyncConfigName;
    /** 视频来源标识 */
    private String videoSourceId;
    /** 视频同步配置是否启用 */
    private Boolean videoSyncConfigEnabled;
    /** 视频同步间隔 */
    private Integer videoSyncInterval;
    /** 视频同步配置最后异步时间 */
    private LocalDateTime videoSyncConfigLastAsyncTime;
    /** 视频同步配置头部 */
    private String videoSyncConfigHeaders;
    /** 视频同步配置评论 */
    private String videoSyncConfigRemark;
    /** 视频同步配置状态 */
    private String videoSyncConfigStatus;
    /** 视频同步配置消息 */
    private String videoSyncConfigMessage;
    /** 视频同步配置最后偏移 */
    private String videoSyncConfigLastOffset;
    /** 视频同步配置最后日志 */
    private String videoSyncConfigLastLog;
    /** 同步数量 */
    private Integer syncCount;
    /** 同步视频数量 */
    private Long syncVideoCount;

    /** 创建 视频同步配置 实例 */
    public VideoSyncConfig() {
    }

    /**
     * 获取视频同步配置id
     *
     * @return 获取视频同步配置id的结果
     */
    public String getVideoSyncConfigId() { return videoSyncConfigId; }
    /**
     * 设置视频同步配置id
     *
     * @param videoSyncConfigId 视频同步配置标识
     */
    public void setVideoSyncConfigId(String videoSyncConfigId) { this.videoSyncConfigId = videoSyncConfigId; }

    /**
     * 获取视频同步配置名称
     *
     * @return 获取视频同步配置名称的结果
     */
    public String getVideoSyncConfigName() { return videoSyncConfigName; }
    /**
     * 设置视频同步配置名称
     *
     * @param videoSyncConfigName 视频同步配置名称
     */
    public void setVideoSyncConfigName(String videoSyncConfigName) { this.videoSyncConfigName = videoSyncConfigName; }

    /**
     * 获取视频源id
     *
     * @return 获取视频源id的结果
     */
    public String getVideoSourceId() { return videoSourceId; }
    /**
     * 设置视频源id
     *
     * @param videoSourceId 视频源标识
     */
    public void setVideoSourceId(String videoSourceId) { this.videoSourceId = videoSourceId; }

    /**
     * 获取视频同步配置已启用
     *
     * @return 获取视频同步配置已启用的结果
     */
    public Boolean getVideoSyncConfigEnabled() { return videoSyncConfigEnabled; }
    /**
     * 设置视频同步配置已启用
     *
     * @param videoSyncConfigEnabled 视频同步配置已启用
     */
    public void setVideoSyncConfigEnabled(Boolean videoSyncConfigEnabled) { this.videoSyncConfigEnabled = videoSyncConfigEnabled; }

    /**
     * 获取视频同步间隔
     *
     * @return 获取视频同步间隔的结果
     */
    public Integer getVideoSyncInterval() { return videoSyncInterval; }
    /**
     * 设置视频同步间隔
     *
     * @param videoSyncInterval 视频同步间隔
     */
    public void setVideoSyncInterval(Integer videoSyncInterval) { this.videoSyncInterval = videoSyncInterval; }

    /**
     * 获取视频同步配置最后一个异步时间
     *
     * @return 获取视频同步配置最后一个异步时间的结果
     */
    public LocalDateTime getVideoSyncConfigLastAsyncTime() { return videoSyncConfigLastAsyncTime; }
    /**
     * 设置视频同步配置最后一个异步时间
     *
     * @param videoSyncConfigLastAsyncTime 视频同步配置最后一个异步时间
     */
    public void setVideoSyncConfigLastAsyncTime(LocalDateTime videoSyncConfigLastAsyncTime) { this.videoSyncConfigLastAsyncTime = videoSyncConfigLastAsyncTime; }

    /**
     * 获取视频同步配置头部
     *
     * @return 获取视频同步配置头部的结果
     */
    public String getVideoSyncConfigHeaders() { return videoSyncConfigHeaders; }
    /**
     * 设置视频同步配置头部
     *
     * @param videoSyncConfigHeaders 视频同步配置头部
     */
    public void setVideoSyncConfigHeaders(String videoSyncConfigHeaders) { this.videoSyncConfigHeaders = videoSyncConfigHeaders; }

    /**
     * 获取视频同步配置评论
     *
     * @return 获取视频同步配置评论的结果
     */
    public String getVideoSyncConfigRemark() { return videoSyncConfigRemark; }
    /**
     * 设置视频同步配置评论
     *
     * @param videoSyncConfigRemark 视频同步配置评论
     */
    public void setVideoSyncConfigRemark(String videoSyncConfigRemark) { this.videoSyncConfigRemark = videoSyncConfigRemark; }

    /**
     * 获取视频同步配置状态
     *
     * @return 获取视频同步配置状态的结果
     */
    public String getVideoSyncConfigStatus() {
        return videoSyncConfigStatus;
    }

    /**
     * 设置视频同步配置状态
     *
     * @param videoSyncConfigStatus 视频同步配置状态
     */
    public void setVideoSyncConfigStatus(String videoSyncConfigStatus) {
        this.videoSyncConfigStatus = videoSyncConfigStatus;
    }

    /**
     * 获取视频同步配置消息
     *
     * @return 获取视频同步配置消息的结果
     */
    public String getVideoSyncConfigMessage() {
        return videoSyncConfigMessage;
    }

    /**
     * 设置视频同步配置消息
     *
     * @param videoSyncConfigMessage 视频同步配置消息
     */
    public void setVideoSyncConfigMessage(String videoSyncConfigMessage) {
        this.videoSyncConfigMessage = videoSyncConfigMessage;
    }

    /**
     * 获取视频同步配置最后一个偏移量
     *
     * @return 获取视频同步配置最后一个偏移量的结果
     */
    public String getVideoSyncConfigLastOffset() {
        return videoSyncConfigLastOffset;
    }

    /**
     * 设置视频同步配置最后一个偏移量
     *
     * @param videoSyncConfigLastOffset 视频同步配置最后一个偏移量
     */
    public void setVideoSyncConfigLastOffset(String videoSyncConfigLastOffset) {
        this.videoSyncConfigLastOffset = videoSyncConfigLastOffset;
    }

    /**
     * 获取视频同步配置最后一个记录日志
     *
     * @return 获取视频同步配置最后一个日志的结果
     */
    public String getVideoSyncConfigLastLog() {
        return videoSyncConfigLastLog;
    }

    /**
     * 设置视频同步配置最后一个记录日志
     *
     * @param videoSyncConfigLastLog 视频同步配置最后一个日志
     */
    public void setVideoSyncConfigLastLog(String videoSyncConfigLastLog) {
        this.videoSyncConfigLastLog = videoSyncConfigLastLog;
    }

    /**
     * 获取同步计算数量
     *
     * @return 获取同步数量的结果
     */
    public Integer getSyncCount() { return syncCount; }
    /**
     * 设置同步计算数量
     *
     * @param syncCount 同步数量
     */
    public void setSyncCount(Integer syncCount) { this.syncCount = syncCount; }

    /**
     * 获取同步视频计算数量
     *
     * @return 获取同步视频数量的结果
     */
    public Long getSyncVideoCount() { return syncVideoCount; }
    /**
     * 设置同步视频计算数量
     *
     * @param syncVideoCount 同步视频数量
     */
    public void setSyncVideoCount(Long syncVideoCount) { this.syncVideoCount = syncVideoCount; }
}

