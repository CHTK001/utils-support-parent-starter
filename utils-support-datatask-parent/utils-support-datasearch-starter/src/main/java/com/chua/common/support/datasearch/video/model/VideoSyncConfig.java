package com.chua.common.support.datasearch.video.model;

import java.time.LocalDateTime;

/**
 * 视频同步配置
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoSyncConfig {

    /** 视频同步配置ID */
    private String videoSyncConfigId;
    /** 视频同步配置名称 */
    private String videoSyncConfigName;
    /** 视频来源ID */
    private String videoSourceId;
    /** 视频同步配置是否启用 */
    private Boolean videoSyncConfigEnabled;
    /** 视频同步间隔 */
    private Integer videoSyncInterval;
    /** 视频同步配置最后异步时间 */
    private LocalDateTime videoSyncConfigLastAsyncTime;
    /** 视频同步配置headers */
    private String videoSyncConfigHeaders;
    /** 视频同步配置remark */
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

    /** 创建 VideoSyncConfig 实例 */
    public VideoSyncConfig() {
    }

    /** 获取VideoSyncConfigId */
    public String getVideoSyncConfigId() { return videoSyncConfigId; }
    /** 设置VideoSyncConfigId */
    public void setVideoSyncConfigId(String videoSyncConfigId) { this.videoSyncConfigId = videoSyncConfigId; }

    /** 获取VideoSyncConfigName */
    public String getVideoSyncConfigName() { return videoSyncConfigName; }
    /** 设置VideoSyncConfigName */
    public void setVideoSyncConfigName(String videoSyncConfigName) { this.videoSyncConfigName = videoSyncConfigName; }

    /** 获取VideoSourceId */
    public String getVideoSourceId() { return videoSourceId; }
    /** 设置VideoSourceId */
    public void setVideoSourceId(String videoSourceId) { this.videoSourceId = videoSourceId; }

    /** 获取VideoSyncConfigEnabled */
    public Boolean getVideoSyncConfigEnabled() { return videoSyncConfigEnabled; }
    /** 设置VideoSyncConfigEnabled */
    public void setVideoSyncConfigEnabled(Boolean videoSyncConfigEnabled) { this.videoSyncConfigEnabled = videoSyncConfigEnabled; }

    /** 获取VideoSyncInterval */
    public Integer getVideoSyncInterval() { return videoSyncInterval; }
    /** 设置VideoSyncInterval */
    public void setVideoSyncInterval(Integer videoSyncInterval) { this.videoSyncInterval = videoSyncInterval; }

    /** 获取VideoSyncConfigLastAsyncTime */
    public LocalDateTime getVideoSyncConfigLastAsyncTime() { return videoSyncConfigLastAsyncTime; }
    /** 设置VideoSyncConfigLastAsyncTime */
    public void setVideoSyncConfigLastAsyncTime(LocalDateTime videoSyncConfigLastAsyncTime) { this.videoSyncConfigLastAsyncTime = videoSyncConfigLastAsyncTime; }

    /** 获取VideoSyncConfigHeaders */
    public String getVideoSyncConfigHeaders() { return videoSyncConfigHeaders; }
    /** 设置VideoSyncConfigHeaders */
    public void setVideoSyncConfigHeaders(String videoSyncConfigHeaders) { this.videoSyncConfigHeaders = videoSyncConfigHeaders; }

    /** 获取VideoSyncConfigRemark */
    public String getVideoSyncConfigRemark() { return videoSyncConfigRemark; }
    /** 设置VideoSyncConfigRemark */
    public void setVideoSyncConfigRemark(String videoSyncConfigRemark) { this.videoSyncConfigRemark = videoSyncConfigRemark; }

    /** 获取VideoSyncConfigStatus */
    public String getVideoSyncConfigStatus() {
        return videoSyncConfigStatus;
    }

    /** 设置VideoSyncConfigStatus */
    public void setVideoSyncConfigStatus(String videoSyncConfigStatus) {
        this.videoSyncConfigStatus = videoSyncConfigStatus;
    }

    /** 获取VideoSyncConfigMessage */
    public String getVideoSyncConfigMessage() {
        return videoSyncConfigMessage;
    }

    /** 设置VideoSyncConfigMessage */
    public void setVideoSyncConfigMessage(String videoSyncConfigMessage) {
        this.videoSyncConfigMessage = videoSyncConfigMessage;
    }

    /** 获取VideoSyncConfigLastOffset */
    public String getVideoSyncConfigLastOffset() {
        return videoSyncConfigLastOffset;
    }

    /** 设置VideoSyncConfigLastOffset */
    public void setVideoSyncConfigLastOffset(String videoSyncConfigLastOffset) {
        this.videoSyncConfigLastOffset = videoSyncConfigLastOffset;
    }

    /** 获取VideoSyncConfigLast记录日志 */
    public String getVideoSyncConfigLastLog() {
        return videoSyncConfigLastLog;
    }

    /** 设置VideoSyncConfigLast记录日志 */
    public void setVideoSyncConfigLastLog(String videoSyncConfigLastLog) {
        this.videoSyncConfigLastLog = videoSyncConfigLastLog;
    }

    /** 获取Sync计算数量 */
    public Integer getSyncCount() { return syncCount; }
    /** 设置Sync计算数量 */
    public void setSyncCount(Integer syncCount) { this.syncCount = syncCount; }

    /** 获取SyncVideo计算数量 */
    public Long getSyncVideoCount() { return syncVideoCount; }
    /** 设置SyncVideo计算数量 */
    public void setSyncVideoCount(Long syncVideoCount) { this.syncVideoCount = syncVideoCount; }
}

