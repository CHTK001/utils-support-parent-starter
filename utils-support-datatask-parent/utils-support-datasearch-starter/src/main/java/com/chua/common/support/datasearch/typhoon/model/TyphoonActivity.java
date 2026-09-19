package com.chua.common.support.datasearch.typhoon.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 活跃台风列表项实体。
 *
 * <p>对应浙江省水利厅 {@code /Api/TyhoonActivity} 响应元素。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TyphoonActivity {

    /**
     * 台风编号（如 202618）
    */
    private String tfid;

    /**
     * 中文名
    */
    private String name;

    /**
     * 英文名
    */
    private String enname;

    /**
     * 中心纬度
    */
    private String lat;

    /**
     * 中心经度
    */
    private String lng;

    /**
     * 强度等级（如 热带低压、热带风暴）
    */
    private String strong;

    /**
     * 风力（级）
    */
    private String power;

    /**
     * 中心气压（hpa）
    */
    private String pressure;

    /**
     * 风速（米/秒）
    */
    private String speed;

    /**
     * 移动方向
    */
    private String movedirection;

    /**
     * 移动速度（公里/小时）
    */
    private String movespeed;

    /**
     * 7 级风圈半径（公里）
    */
    private String radius7;

    /**
     * 10 级风圈半径（公里）
    */
    private String radius10;

    /**
     * 预警级别
    */
    private String warnlevel;

    /**
     * 观测时间
    */
    private String time;

    /**
     * 观测时间（中文格式）
    */
    private String timeformate;
}
