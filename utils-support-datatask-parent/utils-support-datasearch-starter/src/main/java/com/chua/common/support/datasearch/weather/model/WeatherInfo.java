package com.chua.common.support.datasearch.weather.model;

import lombok.Data;

import java.util.List;

/**
 * 实时天气信息实体。
 *
 * <p>数据源无关的统一结构：无论 {@code wttr-in}、{@code open-meteo} 还是后台自建气象源，
 * 都归一化成本实体后对外返回。天气现象以 {@link #weatherCode}（WMO 4677 数字码）为准，
 * {@link #weatherDesc} 仅用于人读展示。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class WeatherInfo {

    /**
     * 城市名
    */
    private String city;

    /**
     * 查询纬度
    */
    private Double latitude;

    /**
     * 查询经度
    */
    private Double longitude;

    /**
     * 气温（摄氏度）
    */
    private Double tempC;

    /**
     * 体感温度（摄氏度）
    */
    private Double feelsLikeC;

    /**
     * 相对湿度（%）
    */
    private Integer humidity;

    /**
     * 云量（%）
    */
    private Integer cloudcover;

    /**
     * 天气描述（如晴、多云）
    */
    private String weatherDesc;

    /**
     * 天气现象码（WMO 4677 数字码，前端图标/文案的唯一依据）
    */
    private Integer weatherCode;

    /**
     * 风速（公里/小时）
    */
    private Double windSpeedKmph;

    /**
     * 风向（角度，0=北顺时针）
    */
    private Double windDirection;

    /**
     * 气压（hpa）
    */
    private Integer pressure;

    /**
     * 观测时间（当地时间）
    */
    private String observationTime;

    /**
     * 数据源标识（如 open-meteo、wttr-in、backend）
    */
    private String source;

    /**
     * 当天逐小时天气
    */
    private List<HourlyWeather> hourly;

    /**
     * 未来数日天气预报
    */
    private List<DailyForecast> forecast;
}
