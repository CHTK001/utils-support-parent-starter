package com.chua.common.support.datasearch.weather.model;

import lombok.Data;

import java.util.List;

/**
 * 单日天气预报实体。
 *
 * <p>数据源无关的统一结构：由 {@code forecast_days} 决定天数，
 * 缺字段的三方数据源留空，由上层按 0 兜底。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class DailyForecast {

    /**
     * 日期（yyyy-MM-dd）
    */
    private String date;

    /**
     * 天气现象码（WMO 4677 数字码；数据源无此口径时为空）
    */
    private Integer weatherCode;

    /**
     * 最高气温（摄氏度）
    */
    private Double maxTempC;

    /**
     * 最低气温（摄氏度）
    */
    private Double minTempC;

    /**
     * 平均气温（摄氏度）
    */
    private Double avgTempC;

    /**
     * 最高体感温度（摄氏度）
    */
    private Double feelsLikeMaxC;

    /**
     * 最低体感温度（摄氏度）
    */
    private Double feelsLikeMinC;

    /**
     * 降水量（毫米）
    */
    private Double precipitation;

    /**
     * 降水概率（%）
    */
    private Integer precipitationProbability;

    /**
     * 最大风速（公里/小时）
    */
    private Double windSpeedKmph;

    /**
     * 主导风向（角度，0=北顺时针）
    */
    private Double windDirection;

    /**
     * 紫外线指数
    */
    private Double uvIndex;

    /**
     * 日照小时数
    */
    private String sunHour;

    /**
     * 日出时间（当地时间 yyyy-MM-ddTHH:mm）
    */
    private String sunrise;

    /**
     * 日落时间（当地时间 yyyy-MM-ddTHH:mm）
    */
    private String sunset;

    /**
     * 逐小时采样
    */
    private List<HourlyWeather> hourly;
}
