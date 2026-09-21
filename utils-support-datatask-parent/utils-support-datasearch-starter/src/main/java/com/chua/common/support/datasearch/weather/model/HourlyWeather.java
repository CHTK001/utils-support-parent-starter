package com.chua.common.support.datasearch.weather.model;

import lombok.Data;

/**
 * 逐小时天气实体。
 *
 * <p>数据源无关的统一结构：{@link #time} 固定为当地时间
 * {@code yyyy-MM-ddTHH:mm}，各数据源（wttr-in 的 3 小时采样、
 * Open-Meteo 的逐小时预报）在各自实现内换算成该格式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class HourlyWeather {

    /**
     * 采样时间点（当地时间 yyyy-MM-ddTHH:mm）
    */
    private String time;

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
     * 天气描述（如晴、多云）
    */
    private String weatherDesc;

    /**
     * 天气现象码（WMO 4677 数字码；数据源无此口径时为空）
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
     * 降水量（毫米）
    */
    private Double precipitation;

    /**
     * 降水概率（%）
    */
    private Integer precipitationProbability;
}
