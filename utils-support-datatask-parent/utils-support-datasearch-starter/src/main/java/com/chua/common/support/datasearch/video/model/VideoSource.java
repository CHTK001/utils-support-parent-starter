package com.chua.common.support.datasearch.video.model;

/**
 * 视频来源
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoSource {

    /**
     * 视频来源URL
    */
    private String videoSourceUrl;
    /**
     * 视频来源令牌
    */
    private String videoSourceToken;
    /**
     * 视频来源用户Agent
    */
    private String videoSourceUserAgent;
    /**
     * 视频来源连接超时
    */
    private Integer videoSourceConnectTimeout;
    /**
     * 视频来源最大值resource
    */
    private Integer videoSourceMaxResource;
    /**
     * 视频来源最小值year
    */
    private Integer videoSourceMinYear;
    /**
     * 视频来源结果条目选择器（CSS，通用 HTML 磁力解析用）
    */
    private String videoSourceItemSelector;
    /**
     * 视频来源结果标题选择器（CSS，为空时取条目内首个非磁力链接文本）
    */
    private String videoSourceTitleSelector;
    /**
     * 视频来源磁力选择器（CSS，为空时使用 a[href^=magnet:]）
    */
    private String videoSourceMagnetSelector;
    /**
     * 视频来源详情页链接选择器（CSS，列表页无磁力时按详情页二次抓取）
    */
    private String videoSourceDetailSelector;
    /**
     * 视频来源大小选择器（CSS）
    */
    private String videoSourceSizeSelector;
    /**
     * 视频来源发布日期选择器（CSS）
    */
    private String videoSourceDateSelector;

    /**
     * 创建 视频源 实例
    */
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
     * 获取视频源用户Agent
     *
     * @return 获取视频源用户Agent的结果
     */
    public String getVideoSourceUserAgent() {
        return videoSourceUserAgent;
    }

    /**
     * 设置视频源用户Agent
     *
     * @param videoSourceUserAgent 视频源用户Agent
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
     * 获取视频来源结果条目选择器
     *
     * @return 结果条目选择器的结果
     */
    public String getVideoSourceItemSelector() {
        return videoSourceItemSelector;
    }

    /**
     * 设置视频来源结果条目选择器
     *
     * @param videoSourceItemSelector 结果条目选择器
     */
    public void setVideoSourceItemSelector(String videoSourceItemSelector) {
        this.videoSourceItemSelector = videoSourceItemSelector;
    }

    /**
     * 获取视频来源结果标题选择器
     *
     * @return 标题选择器的结果
     */
    public String getVideoSourceTitleSelector() {
        return videoSourceTitleSelector;
    }

    /**
     * 设置视频来源结果标题选择器
     *
     * @param videoSourceTitleSelector 标题选择器
     */
    public void setVideoSourceTitleSelector(String videoSourceTitleSelector) {
        this.videoSourceTitleSelector = videoSourceTitleSelector;
    }

    /**
     * 获取视频来源磁力选择器
     *
     * @return 磁力选择器的结果
     */
    public String getVideoSourceMagnetSelector() {
        return videoSourceMagnetSelector;
    }

    /**
     * 设置视频来源磁力选择器
     *
     * @param videoSourceMagnetSelector 磁力选择器
     */
    public void setVideoSourceMagnetSelector(String videoSourceMagnetSelector) {
        this.videoSourceMagnetSelector = videoSourceMagnetSelector;
    }

    /**
     * 获取视频来源详情页链接选择器
     *
     * @return 详情页链接选择器的结果
     */
    public String getVideoSourceDetailSelector() {
        return videoSourceDetailSelector;
    }

    /**
     * 设置视频来源详情页链接选择器
     *
     * @param videoSourceDetailSelector 详情页链接选择器
     */
    public void setVideoSourceDetailSelector(String videoSourceDetailSelector) {
        this.videoSourceDetailSelector = videoSourceDetailSelector;
    }

    /**
     * 获取视频来源大小选择器
     *
     * @return 大小选择器的结果
     */
    public String getVideoSourceSizeSelector() {
        return videoSourceSizeSelector;
    }

    /**
     * 设置视频来源大小选择器
     *
     * @param videoSourceSizeSelector 大小选择器
     */
    public void setVideoSourceSizeSelector(String videoSourceSizeSelector) {
        this.videoSourceSizeSelector = videoSourceSizeSelector;
    }

    /**
     * 获取视频来源发布日期选择器
     *
     * @return 发布日期选择器的结果
     */
    public String getVideoSourceDateSelector() {
        return videoSourceDateSelector;
    }

    /**
     * 设置视频来源发布日期选择器
     *
     * @param videoSourceDateSelector 发布日期选择器
     */
    public void setVideoSourceDateSelector(String videoSourceDateSelector) {
        this.videoSourceDateSelector = videoSourceDateSelector;
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

