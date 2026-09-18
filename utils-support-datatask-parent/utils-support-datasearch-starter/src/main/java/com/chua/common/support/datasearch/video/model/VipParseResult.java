package com.chua.common.support.datasearch.video.model;

import java.util.Collections;
import java.util.List;

/**
 * VIP 解析结果模型。
 *
 * <p>封装 VIP 视频解析的输出：解析状态、播放地址列表、来源、错误信息等。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class VipParseResult {

    /** 解析是否成功 */
    private boolean success;
    /** 播放地址列表 */
    private List<VideoPlayAddress> playAddresses;
    /** 错误信息 */
    private String errorMessage;
    /** 视频标题（如可获取） */
    private String title;

    /** 无参构造器。 */
    public VipParseResult() {}

    /**
    * 成功结果。
    *
    * @param playAddresses 播放地址列表
    * @return 解析结果
    */
    public static VipParseResult success(List<VideoPlayAddress> playAddresses) {
        VipParseResult r = new VipParseResult();
        r.success = true;
        r.playAddresses = playAddresses != null ? playAddresses : Collections.emptyList();
        return r;
    }

    /**
    * 错误结果。
    *
    * @param errorMessage 错误信息
    * @return 解析结果
    */
    public static VipParseResult error(String errorMessage) {
        VipParseResult r = new VipParseResult();
        r.success = false;
        r.errorMessage = errorMessage;
        r.playAddresses = Collections.emptyList();
        return r;
    }

    /**
    * 获取是否成功。
    * 
    * @return 是否成功
    */
    public boolean isSuccess() { return success; }
    /**
    * 设置是否成功。
    * 
    * @param success 是否成功
    */
    public void setSuccess(boolean success) { this.success = success; }
    /**
    * 获取播放地址列表。
    * 
    * @return 播放地址
    */
    public List<VideoPlayAddress> getPlayAddresses() { return playAddresses; }
    /**
    * 设置播放地址列表。
    * 
    * @param playAddresses 播放地址
    */
    public void setPlayAddresses(List<VideoPlayAddress> playAddresses) { this.playAddresses = playAddresses; }
    /**
    * 获取错误信息。
    * 
    * @return 错误信息
    */
    public String getErrorMessage() { return errorMessage; }
    /**
    * 设置错误信息。
    * 
    * @param errorMessage 错误信息
    */
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    /**
    * 获取视频标题。
    * 
    * @return 标题
    */
    public String getTitle() { return title; }
    /**
    * 设置视频标题。
    * 
    * @param title 标题
    */
    public void setTitle(String title) { this.title = title; }
}
