package com.chua.common.support.datasearch.weather.model;

import lombok.Data;

import java.util.List;

/**
 * 实时天气信息实体。
 *
 * <p>对应 wttr.in {@code ?format=j1} 响应的 current_condition 简化字段，
 * 同时携带当天逐小时采样（{@link #hourly}）与未来数日预报（{@link #forecast}）。</p>
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
     * 风速（公里/小时）
    */
    private Double windSpeedKmph;

    /**
     * 气压（hpa）
    */
    private Integer pressure;

    /**
     * 观测时间（当地时间）
    */
    private String observationTime;

    /**
     * 当天逐小时天气（8 个点，3 小时间隔，00:00-21:00）
    */
    private List<HourlyWeather> hourly;

    /**
     * 未来数日天气预报（wttr.入 提供 3 天）
    */
    private List<DailyForecast> forecast;
}
