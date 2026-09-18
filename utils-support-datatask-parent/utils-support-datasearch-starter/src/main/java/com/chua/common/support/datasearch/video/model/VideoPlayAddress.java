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
    /** 视频play地址通道 */
    private List<VideoPlayAddressChannel> videoPlayAddressChannels;

    /** 创建 视频play地址 实例 */
    public VideoPlayAddress() {
    }

    /**
    * 获取视频play地址名称
    *
    * @return 获取视频play地址名称的结果
    */
    public String getVideoPlayAddressName() {
        return videoPlayAddressName;
    }

    /**
    * 设置视频play地址名称
    *
    * @param videoPlayAddressName 视频play地址名称
    */
    public void setVideoPlayAddressName(String videoPlayAddressName) {
        this.videoPlayAddressName = videoPlayAddressName;
    }

    /**
    * 获取视频play地址编码
    *
    * @return 获取视频play地址编码的结果
    */
    public String getVideoPlayAddressCode() {
        return videoPlayAddressCode;
    }

    /**
    * 设置视频play地址编码
    *
    * @param videoPlayAddressCode 视频play地址编码
    */
    public void setVideoPlayAddressCode(String videoPlayAddressCode) {
        this.videoPlayAddressCode = videoPlayAddressCode;
    }

    /**
    * 获取视频play地址通道
    *
    * @return 获取视频play地址通道的结果
    */
    public List<VideoPlayAddressChannel> getVideoPlayAddressChannels() {
        return videoPlayAddressChannels;
    }

    /**
    * 设置视频play地址通道
    *
    * @param videoPlayAddressChannels 视频play地址通道
    */
    public void setVideoPlayAddressChannels(List<VideoPlayAddressChannel> videoPlayAddressChannels) {
        this.videoPlayAddressChannels = videoPlayAddressChannels;
    }
}

