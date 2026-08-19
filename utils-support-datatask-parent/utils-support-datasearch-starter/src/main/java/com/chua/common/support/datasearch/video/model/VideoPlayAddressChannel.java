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

    /** 创建 VideoPlayAddressChannel 实例 */
    public VideoPlayAddressChannel() {
    }

    /** 获取VideoPlayAddressChannelName */
    public String getVideoPlayAddressChannelName() {
        return videoPlayAddressChannelName;
    }

    /** 设置VideoPlayAddressChannelName */
    public void setVideoPlayAddressChannelName(String videoPlayAddressChannelName) {
        this.videoPlayAddressChannelName = videoPlayAddressChannelName;
    }

    /** 获取VideoPlayAddressUrl */
    public String getVideoPlayAddressUrl() {
        return videoPlayAddressUrl;
    }

    /** 设置VideoPlayAddressUrl */
    public void setVideoPlayAddressUrl(String videoPlayAddressUrl) {
        this.videoPlayAddressUrl = videoPlayAddressUrl;
    }
}

