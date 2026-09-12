package com.chua.common.support.datasearch.weather.model;

import lombok.Data;

/**
 * 逐小时天气实体。
 *
 * <p>对应 wttr.in j1 响应的 weather[].hourly 单点，
   * 时间 为 3 小时间隔的采样点（0/300/600/…/2100，即 00:00-21:00）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class HourlyWeather {

    /** 采样时间点（0-2100，3 小时间隔） */
    private String time;

    /** 气温（摄氏度） */
    private Double tempC;

    /** 体感温度（摄氏度） */
    private Double feelsLikeC;

    /** 相对湿度（%） */
    private Integer humidity;

    /** 天气描述（如晴、多云） */
    private String weatherDesc;

    /** 风速（公里/小时） */
    private Double windSpeedKmph;
}
