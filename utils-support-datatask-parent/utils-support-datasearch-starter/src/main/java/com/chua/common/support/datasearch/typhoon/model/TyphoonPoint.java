package com.chua.common.support.datasearch.typhoon.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 台风路径点实体。
 *
 * <p>对应详情 {@code points[]} 元素，含实测/预报路径点与多机构预报。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TyphoonPoint {

    /** 时间 */
    private String time;

    /** 经度 */
    private String lng;

    /** 纬度 */
    private String lat;

    /** 强度等级 */
    private String strong;

    /** 风力（级） */
    private String power;

    /** 风速（米/秒） */
    private String speed;

    /** 中心气压（hPa） */
    private String pressure;

    /** 移动速度（公里/小时） */
    private String movespeed;

    /** 移动方向 */
    private String movedirection;

    /** 7 级风圈半径 */
    private String radius7;

    /** 10 级风圈半径 */
    private String radius10;

    /** 12 级风圈半径 */
    private String radius12;

    /** 各机构预报 */
    private List<TyphoonForecast> forecast;
}
