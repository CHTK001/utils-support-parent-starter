package com.chua.common.support.datasearch.video.model;

/**
 * 视频来源
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoSource {

    /** 视频来源URL */
    private String videoSourceUrl;
    /** 视频来源令牌 */
    private String videoSourceToken;
    /** 视频来源用户agent */
    private String videoSourceUserAgent;
    /** 视频来源connect超时 */
    private Integer videoSourceConnectTimeout;
    /** 视频来源最大值resource */
    private Integer videoSourceMaxResource;
    /** 视频来源最小值year */
    private Integer videoSourceMinYear;

    /** 创建 VideoSource 实例 */
    public VideoSource() {
    }

    /** 获取VideoSourceUrl */
    public String getVideoSourceUrl() {
        return videoSourceUrl;
    }

    /** 设置VideoSourceUrl */
    public void setVideoSourceUrl(String videoSourceUrl) {
        this.videoSourceUrl = videoSourceUrl;
    }

    /** 获取VideoSourceToken */
    public String getVideoSourceToken() {
        return videoSourceToken;
    }

    /** 设置VideoSourceToken */
    public void setVideoSourceToken(String videoSourceToken) {
        this.videoSourceToken = videoSourceToken;
    }

    /** 获取VideoSourceUserAgent */
    public String getVideoSourceUserAgent() {
        return videoSourceUserAgent;
    }

    /** 设置VideoSourceUserAgent */
    public void setVideoSourceUserAgent(String videoSourceUserAgent) {
        this.videoSourceUserAgent = videoSourceUserAgent;
    }

    /** 获取VideoSource连接Timeout */
    public Integer getVideoSourceConnectTimeout() {
        return videoSourceConnectTimeout;
    }

    /** 设置VideoSource连接Timeout */
    public void setVideoSourceConnectTimeout(Integer videoSourceConnectTimeout) {
        this.videoSourceConnectTimeout = videoSourceConnectTimeout;
    }

    /** 获取VideoSource最大值Resource */
    public Integer getVideoSourceMaxResource() {
        return videoSourceMaxResource;
    }

    /** 设置VideoSource最大值Resource */
    public void setVideoSourceMaxResource(Integer videoSourceMaxResource) {
        this.videoSourceMaxResource = videoSourceMaxResource;
    }

    /** 获取VideoSource最小值Year */
    public Integer getVideoSourceMinYear() {
        return videoSourceMinYear;
    }

    /** 设置VideoSource最小值Year */
    public void setVideoSourceMinYear(Integer videoSourceMinYear) {
        this.videoSourceMinYear = videoSourceMinYear;
    }

    /** 获取VideoSourceTypeForList */
    public String[] getVideoSourceTypeForList() {
        return new String[]{"MV", "TV", "AC"};
    }
}

