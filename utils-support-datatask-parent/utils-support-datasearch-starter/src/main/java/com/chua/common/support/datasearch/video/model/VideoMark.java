package com.chua.common.support.datasearch.video.model;

import java.math.BigDecimal;

/**
 * 视频评分
 *
 * @author CH
 * @since 2025/9/18 09:26
 */
public class VideoMark {

    private String videoMarkType;
    private Integer videoMarkPeople;
    private BigDecimal videoMarkScore;

    public VideoMark() {
    }

    public String getVideoMarkType() {
        return videoMarkType;
    }

    public void setVideoMarkType(String videoMarkType) {
        this.videoMarkType = videoMarkType;
    }

    public Integer getVideoMarkPeople() {
        return videoMarkPeople;
    }

    public void setVideoMarkPeople(Integer videoMarkPeople) {
        this.videoMarkPeople = videoMarkPeople;
    }

    public BigDecimal getVideoMarkScore() {
        return videoMarkScore;
    }

    public void setVideoMarkScore(BigDecimal videoMarkScore) {
        this.videoMarkScore = videoMarkScore;
    }
}

