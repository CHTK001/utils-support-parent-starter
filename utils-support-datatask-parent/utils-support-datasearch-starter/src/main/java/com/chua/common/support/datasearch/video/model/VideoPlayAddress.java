package com.chua.common.support.datasearch.video.model;

import java.util.List;

/**
 * 在线地址
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoPlayAddress {

    /** 视频play地址名称 */
    private String videoPlayAddressName;
    /** 视频play地址代码 */
    private String videoPlayAddressCode;
    /** 视频play地址channels */
    private List<VideoPlayAddressChannel> videoPlayAddressChannels;

    /** 创建 VideoPlayAddress 实例 */
    public VideoPlayAddress() {
    }

    /** 获取VideoPlayAddressName */
    public String getVideoPlayAddressName() {
        return videoPlayAddressName;
    }

    /** 设置VideoPlayAddressName */
    public void setVideoPlayAddressName(String videoPlayAddressName) {
        this.videoPlayAddressName = videoPlayAddressName;
    }

    /** 获取VideoPlayAddressCode */
    public String getVideoPlayAddressCode() {
        return videoPlayAddressCode;
    }

    /** 设置VideoPlayAddressCode */
    public void setVideoPlayAddressCode(String videoPlayAddressCode) {
        this.videoPlayAddressCode = videoPlayAddressCode;
    }

    /** 获取VideoPlayAddressChannels */
    public List<VideoPlayAddressChannel> getVideoPlayAddressChannels() {
        return videoPlayAddressChannels;
    }

    /** 设置VideoPlayAddressChannels */
    public void setVideoPlayAddressChannels(List<VideoPlayAddressChannel> videoPlayAddressChannels) {
        this.videoPlayAddressChannels = videoPlayAddressChannels;
    }
}

