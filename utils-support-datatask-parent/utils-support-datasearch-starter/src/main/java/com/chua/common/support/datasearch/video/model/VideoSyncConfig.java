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

    public VideoSyncConfig() {
    }

    public String getVideoSyncConfigId() { return videoSyncConfigId; }
    public void setVideoSyncConfigId(String videoSyncConfigId) { this.videoSyncConfigId = videoSyncConfigId; }

    public String getVideoSyncConfigName() { return videoSyncConfigName; }
    public void setVideoSyncConfigName(String videoSyncConfigName) { this.videoSyncConfigName = videoSyncConfigName; }

    public String getVideoSourceId() { return videoSourceId; }
    public void setVideoSourceId(String videoSourceId) { this.videoSourceId = videoSourceId; }

    public Boolean getVideoSyncConfigEnabled() { return videoSyncConfigEnabled; }
    public void setVideoSyncConfigEnabled(Boolean videoSyncConfigEnabled) { this.videoSyncConfigEnabled = videoSyncConfigEnabled; }

    public Integer getVideoSyncInterval() { return videoSyncInterval; }
    public void setVideoSyncInterval(Integer videoSyncInterval) { this.videoSyncInterval = videoSyncInterval; }

    public LocalDateTime getVideoSyncConfigLastAsyncTime() { return videoSyncConfigLastAsyncTime; }
    public void setVideoSyncConfigLastAsyncTime(LocalDateTime videoSyncConfigLastAsyncTime) { this.videoSyncConfigLastAsyncTime = videoSyncConfigLastAsyncTime; }

    public String getVideoSyncConfigHeaders() { return videoSyncConfigHeaders; }
    public void setVideoSyncConfigHeaders(String videoSyncConfigHeaders) { this.videoSyncConfigHeaders = videoSyncConfigHeaders; }

    public String getVideoSyncConfigRemark() { return videoSyncConfigRemark; }
    public void setVideoSyncConfigRemark(String videoSyncConfigRemark) { this.videoSyncConfigRemark = videoSyncConfigRemark; }

    public String getVideoSyncConfigStatus() {
        return videoSyncConfigStatus;
    }

    public void setVideoSyncConfigStatus(String videoSyncConfigStatus) {
        this.videoSyncConfigStatus = videoSyncConfigStatus;
    }

    public String getVideoSyncConfigMessage() {
        return videoSyncConfigMessage;
    }

    public void setVideoSyncConfigMessage(String videoSyncConfigMessage) {
        this.videoSyncConfigMessage = videoSyncConfigMessage;
    }

    public String getVideoSyncConfigLastOffset() {
        return videoSyncConfigLastOffset;
    }

    public void setVideoSyncConfigLastOffset(String videoSyncConfigLastOffset) {
        this.videoSyncConfigLastOffset = videoSyncConfigLastOffset;
    }

    public String getVideoSyncConfigLastLog() {
        return videoSyncConfigLastLog;
    }

    public void setVideoSyncConfigLastLog(String videoSyncConfigLastLog) {
        this.videoSyncConfigLastLog = videoSyncConfigLastLog;
    }

    public Integer getSyncCount() { return syncCount; }
    public void setSyncCount(Integer syncCount) { this.syncCount = syncCount; }

    public Long getSyncVideoCount() { return syncVideoCount; }
    public void setSyncVideoCount(Long syncVideoCount) { this.syncVideoCount = syncVideoCount; }
}

