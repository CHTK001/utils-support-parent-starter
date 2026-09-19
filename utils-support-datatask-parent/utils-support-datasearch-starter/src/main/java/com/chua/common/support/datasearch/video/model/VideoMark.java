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

    /** 创建 视频mark 实例 */
    public VideoMark() {
    }

    /**
     * 获取视频标记类型
     *
     * @return 获取视频mark类型的结果
     */
    public String getVideoMarkType() {
        return videoMarkType;
    }

    /**
     * 设置视频标记类型
     *
     * @param videoMarkType 视频mark类型
     */
    public void setVideoMarkType(String videoMarkType) {
        this.videoMarkType = videoMarkType;
    }

    /**
     * 获取视频标记People
     *
     * @return 获取视频markpeople的结果
     */
    public Integer getVideoMarkPeople() {
        return videoMarkPeople;
    }

    /**
     * 设置视频标记People
     *
     * @param videoMarkPeople 视频markpeople
     */
    public void setVideoMarkPeople(Integer videoMarkPeople) {
        this.videoMarkPeople = videoMarkPeople;
    }

    /**
     * 获取视频标记Score
     *
     * @return 获取视频markscore的结果
     */
    public BigDecimal getVideoMarkScore() {
        return videoMarkScore;
    }

    /**
     * 设置视频标记Score
     *
     * @param videoMarkScore 视频markscore
     */
    public void setVideoMarkScore(BigDecimal videoMarkScore) {
        this.videoMarkScore = videoMarkScore;
    }
}

