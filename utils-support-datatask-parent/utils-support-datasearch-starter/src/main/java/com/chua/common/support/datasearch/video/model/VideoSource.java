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
    /** 视频来源用户智能体 */
    private String videoSourceUserAgent;
    /** 视频来源连接超时 */
    private Integer videoSourceConnectTimeout;
    /** 视频来源最大值resource */
    private Integer videoSourceMaxResource;
    /** 视频来源最小值year */
    private Integer videoSourceMinYear;

    /** 创建 视频源 实例 */
    public VideoSource() {
    }

    /**
    * 获取视频源url
    *
    * @return 获取视频源url的结果
     */
    public String getVideoSourceUrl() {
        return videoSourceUrl;
    }

    /**
    * 设置视频源url
    *
    * @param videoSourceUrl 视频源url
     */
    public void setVideoSourceUrl(String videoSourceUrl) {
        this.videoSourceUrl = videoSourceUrl;
    }

    /**
    * 获取视频源令牌
    *
    * @return 获取视频源令牌的结果
     */
    public String getVideoSourceToken() {
        return videoSourceToken;
    }

    /**
    * 设置视频源令牌
    *
    * @param videoSourceToken 视频源令牌
     */
    public void setVideoSourceToken(String videoSourceToken) {
        this.videoSourceToken = videoSourceToken;
    }

    /**
    * 获取视频源用户智能体
    *
    * @return 获取视频源用户智能体的结果
     */
    public String getVideoSourceUserAgent() {
        return videoSourceUserAgent;
    }

    /**
    * 设置视频源用户智能体
    *
    * @param videoSourceUserAgent 视频源用户智能体
     */
    public void setVideoSourceUserAgent(String videoSourceUserAgent) {
        this.videoSourceUserAgent = videoSourceUserAgent;
    }

    /**
    * 获取视频源连接超时
    *
    * @return 获取视频源连接超时的结果
     */
    public Integer getVideoSourceConnectTimeout() {
        return videoSourceConnectTimeout;
    }

    /**
    * 设置视频源连接超时
    *
    * @param videoSourceConnectTimeout 视频源连接超时
     */
    public void setVideoSourceConnectTimeout(Integer videoSourceConnectTimeout) {
        this.videoSourceConnectTimeout = videoSourceConnectTimeout;
    }

    /**
    * 获取视频源最大值Resource
    *
    * @return 获取视频源最大resource的结果
     */
    public Integer getVideoSourceMaxResource() {
        return videoSourceMaxResource;
    }

    /**
    * 设置视频源最大值Resource
    *
    * @param videoSourceMaxResource 视频源最大resource
     */
    public void setVideoSourceMaxResource(Integer videoSourceMaxResource) {
        this.videoSourceMaxResource = videoSourceMaxResource;
    }

    /**
    * 获取视频源最小值Year
    *
    * @return 获取视频源最小year的结果
     */
    public Integer getVideoSourceMinYear() {
        return videoSourceMinYear;
    }

    /**
    * 设置视频源最小值Year
    *
    * @param videoSourceMinYear 视频源最小year
     */
    public void setVideoSourceMinYear(Integer videoSourceMinYear) {
        this.videoSourceMinYear = videoSourceMinYear;
    }

    /**
    * 获取视频源类型for列表
    *
    * @return 获取视频源类型for列表的结果
     */
    public String[] getVideoSourceTypeForList() {
        return new String[]{"MV", "TV", "AC"};
    }
}

