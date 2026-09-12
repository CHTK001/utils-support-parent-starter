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

    /** 创建 视频play地址通道 实例 */
    public VideoPlayAddressChannel() {
    }

    /**
    * 获取视频play地址通道名称
    *
    * @return 获取视频play地址通道名称的结果
     */
    public String getVideoPlayAddressChannelName() {
        return videoPlayAddressChannelName;
    }

    /**
    * 设置视频play地址通道名称
    *
    * @param videoPlayAddressChannelName 视频play地址通道名称
     */
    public void setVideoPlayAddressChannelName(String videoPlayAddressChannelName) {
        this.videoPlayAddressChannelName = videoPlayAddressChannelName;
    }

    /**
    * 获取视频play地址url
    *
    * @return 获取视频play地址url的结果
     */
    public String getVideoPlayAddressUrl() {
        return videoPlayAddressUrl;
    }

    /**
    * 设置视频play地址url
    *
    * @param videoPlayAddressUrl 视频play地址url
     */
    public void setVideoPlayAddressUrl(String videoPlayAddressUrl) {
        this.videoPlayAddressUrl = videoPlayAddressUrl;
    }
}

