package com.chua.common.support.datasearch.video.model;

import java.util.List;

/**
 * 在线地址
 *
 * @author CH
 * @since 2025/9/18 09:26
 */
public class VideoPlayAddress {

    private String videoPlayAddressName;
    private String videoPlayAddressCode;
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

