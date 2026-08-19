package com.chua.common.support.datasearch.video.model;

import java.math.BigDecimal;

/**
 * 视频评分
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VideoMark {

    /** 视频mark类型 */
    private String videoMarkType;
    /** 视频markpeople */
    private Integer videoMarkPeople;
    /** 视频mark分数 */
    private BigDecimal videoMarkScore;

    /** 创建 VideoMark 实例 */
    public VideoMark() {
    }

    /** 获取Video标记Type */
    public String getVideoMarkType() {
        return videoMarkType;
    }

    /** 设置Video标记Type */
    public void setVideoMarkType(String videoMarkType) {
        this.videoMarkType = videoMarkType;
    }

    /** 获取Video标记People */
    public Integer getVideoMarkPeople() {
        return videoMarkPeople;
    }

    /** 设置Video标记People */
    public void setVideoMarkPeople(Integer videoMarkPeople) {
        this.videoMarkPeople = videoMarkPeople;
    }

    /** 获取Video标记Score */
    public BigDecimal getVideoMarkScore() {
        return videoMarkScore;
    }

    /** 设置Video标记Score */
    public void setVideoMarkScore(BigDecimal videoMarkScore) {
        this.videoMarkScore = videoMarkScore;
    }
}

