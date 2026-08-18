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

    public VideoPlayAddress() {
    }

    public String getVideoPlayAddressName() {
        return videoPlayAddressName;
    }

    public void setVideoPlayAddressName(String videoPlayAddressName) {
        this.videoPlayAddressName = videoPlayAddressName;
    }

    public String getVideoPlayAddressCode() {
        return videoPlayAddressCode;
    }

    public void setVideoPlayAddressCode(String videoPlayAddressCode) {
        this.videoPlayAddressCode = videoPlayAddressCode;
    }

    public List<VideoPlayAddressChannel> getVideoPlayAddressChannels() {
        return videoPlayAddressChannels;
    }

    public void setVideoPlayAddressChannels(List<VideoPlayAddressChannel> videoPlayAddressChannels) {
        this.videoPlayAddressChannels = videoPlayAddressChannels;
    }
}

