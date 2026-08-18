package com.chua.common.support.datasearch.video.model;

/**
 * 在线地址线路
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoPlayAddressChannel {

    /** 视频play地址通道名称 */
    private String videoPlayAddressChannelName;
    /** 视频play地址URL */
    private String videoPlayAddressUrl;

    public VideoPlayAddressChannel() {
    }

    public String getVideoPlayAddressChannelName() {
        return videoPlayAddressChannelName;
    }

    public void setVideoPlayAddressChannelName(String videoPlayAddressChannelName) {
        this.videoPlayAddressChannelName = videoPlayAddressChannelName;
    }

    public String getVideoPlayAddressUrl() {
        return videoPlayAddressUrl;
    }

    public void setVideoPlayAddressUrl(String videoPlayAddressUrl) {
        this.videoPlayAddressUrl = videoPlayAddressUrl;
    }
}

