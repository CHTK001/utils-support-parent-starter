package com.chua.common.support.datasearch.typhoon.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 机构预报路径点实体。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TyphoonForecastPoint {

    /**
     * 时间
    */
    private String time;

    /**
     * 经度
    */
    private String lng;

    /**
     * 纬度
    */
    private String lat;

    /**
     * 强度等级
    */
    private String strong;

    /**
     * 风力（级）
    */
    private String power;

    /**
     * 风速（米/秒）
    */
    private String speed;

    /**
     * 中心气压（hpa）
    */
    private String pressure;
}
