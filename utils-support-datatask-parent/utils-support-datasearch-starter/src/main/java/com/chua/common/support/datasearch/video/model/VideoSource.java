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

    public VideoSource() {
    }

    public String getVideoSourceUrl() {
        return videoSourceUrl;
    }

    public void setVideoSourceUrl(String videoSourceUrl) {
        this.videoSourceUrl = videoSourceUrl;
    }

    public String getVideoSourceToken() {
        return videoSourceToken;
    }

    public void setVideoSourceToken(String videoSourceToken) {
        this.videoSourceToken = videoSourceToken;
    }

    public String getVideoSourceUserAgent() {
        return videoSourceUserAgent;
    }

    public void setVideoSourceUserAgent(String videoSourceUserAgent) {
        this.videoSourceUserAgent = videoSourceUserAgent;
    }

    public Integer getVideoSourceConnectTimeout() {
        return videoSourceConnectTimeout;
    }

    public void setVideoSourceConnectTimeout(Integer videoSourceConnectTimeout) {
        this.videoSourceConnectTimeout = videoSourceConnectTimeout;
    }

    public Integer getVideoSourceMaxResource() {
        return videoSourceMaxResource;
    }

    public void setVideoSourceMaxResource(Integer videoSourceMaxResource) {
        this.videoSourceMaxResource = videoSourceMaxResource;
    }

    public Integer getVideoSourceMinYear() {
        return videoSourceMinYear;
    }

    public void setVideoSourceMinYear(Integer videoSourceMinYear) {
        this.videoSourceMinYear = videoSourceMinYear;
    }

    public String[] getVideoSourceTypeForList() {
        return new String[]{"MV", "TV", "AC"};
    }
}

