package com.chua.common.support.datasearch.video.model;

import java.time.LocalDateTime;

/**
 * 视频同步配置
 *
 * @author CH
 * @since 2025/9/18 21:44
 */
public class VideoSyncConfig {

    private String videoSyncConfigId;
    private String videoSyncConfigName;
    private String videoSourceId;
    private Boolean videoSyncConfigEnabled;
    private Integer videoSyncInterval;
    private LocalDateTime videoSyncConfigLastAsyncTime;
    private String videoSyncConfigHeaders;
    private String videoSyncConfigRemark;
    private String videoSyncConfigStatus;
    private String videoSyncConfigMessage;
    private String videoSyncConfigLastOffset;
    private String videoSyncConfigLastLog;
    private Integer syncCount;
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

