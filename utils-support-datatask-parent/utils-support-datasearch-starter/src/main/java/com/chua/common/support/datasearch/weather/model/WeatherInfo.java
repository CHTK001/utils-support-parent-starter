package com.chua.common.support.datasearch.weather.model;

import java.util.List;

/**
 * 实时天气信息实体。
 * <p>数据源无关的统一结构：无论 {@code wttr-in}、{@code open-meteo} 还是后台自建气象源，
 * 都归一化成本实体后对外返回。天气现象以 {@link #weatherCode}（WMO 4677 数字码）为准，
 * {@link #weatherDesc} 仅用于人读展示。</p>
 *
 * @param city 城市名
 * @param latitude 查询纬度
 * @param longitude 查询经度
 * @param tempC 气温（摄氏度）
 * @param feelsLikeC 体感温度（摄氏度）
 * @param humidity 相对湿度（%）
 * @param cloudcover 云量（%）
 * @param weatherDesc 天气描述（如晴、多云）
 * @param weatherCode 天气现象码（WMO 4677
 *                    数字码，前端图标/文案的唯一依据）
 * @param windSpeedKmph 风速（公里/小时）
 * @param windDirection 风向（角度，0=北顺时针）
 * @param pressure 气压（hpa）
 * @param observationTime 观测时间（当地时间）
 * @param source 数据源标识（如 open-meteo、wttr-in、backend）
 * @param hourly 当天逐小时天气
 * @param forecast 未来数日天气预报
 *
 * @author CH
 * @since 4.0.0.42
 */
public record WeatherInfo(
        String city,
        Double latitude,
        Double longitude,
        Double tempC,
        Double feelsLikeC,
        Integer humidity,
        Integer cloudcover,
        String weatherDesc,
        Integer weatherCode,
        Double windSpeedKmph,
        Double windDirection,
        Integer pressure,
        String observationTime,
        String source,
        List<HourlyWeather> hourly,
        List<DailyForecast> forecast
) {

    /**
     * 规范构造器：逐小时与逐日预报列表做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。</p>
     *
     * <p>两个列表刻意保留 空 语义：{@code WttrInWeatherProvider} 在「未来数日预报为空数组」
     * 时会把 {@code hourly} 传成 {@code null}（{@code forecast.getFirst()} 短路），
     * 一旦改成拒绝 空 值就是把既有降级路径变成 空指针。
     * 元素本身不会是 空（均由解析器 {@code new} 出），故可用 {@link List#copyOf}。</p>
     *
     * @param hourly 当天逐小时天气
     * @param forecast 未来数日天气预报
     */
    public WeatherInfo {
        hourly = hourly == null ? null : List.copyOf(hourly);
        forecast = forecast == null ? null : List.copyOf(forecast);
    }
}
