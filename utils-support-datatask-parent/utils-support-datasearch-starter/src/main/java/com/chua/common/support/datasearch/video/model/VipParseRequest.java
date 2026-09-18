package com.chua.common.support.datasearch.video.model;

/**
 * VIP 解析请求模型。
 *
 * <p>封装 VIP 视频解析的输入参数：视频 URL、来源编码、是否强制刷新缓存等。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class VipParseRequest {

    /** 视频播放页 URL */
    private String url;
    /** 视频来源编码（如 bilibili/douyin/youku） */
    private String source;
    /** 是否强制解析（忽略缓存） */
    private boolean forceRefresh;
    /** 请求超时毫秒 */
    private int timeoutMs;

    /** 无参构造器。 */
    public VipParseRequest() {}

    /**
    * 构造器。
    *
    * @param url   视频 URL
    * @param source 来源编码
    */
    public VipParseRequest(String url, String source) {
        this.url = url;
        this.source = source;
    }

    /**
    * 获取视频 URL。
    * 
    * @return URL
    */
    public String getUrl() { return url; }
    /**
    * 设置视频 URL。
    * 
    * @param url URL
    */
    public void setUrl(String url) { this.url = url; }
    /**
    * 获取来源编码。
    * 
    * @return 编码
    */
    public String getSource() { return source; }
    /**
    * 设置来源编码。
    * 
    * @param source 编码
    */
    public void setSource(String source) { this.source = source; }
    /**
    * 获取是否强制解析。
    * 
    * @return 是否强制
    */
    public boolean isForceRefresh() { return forceRefresh; }
    /**
    * 设置是否强制解析。
    * 
    * @param forceRefresh 是否强制
    */
    public void setForceRefresh(boolean forceRefresh) { this.forceRefresh = forceRefresh; }
    /**
    * 获取超时毫秒。
    * 
    * @return 超时
    */
    public int getTimeoutMs() { return timeoutMs; }
    /**
    * 设置超时毫秒。
    * 
    * @param timeoutMs 超时
    */
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
