package com.chua.common.support.datasearch.weather.spi;

import com.chua.common.support.datasearch.weather.model.WeatherInfo;

/**
 * 天气数据提供者 SPI 接口。
 *
 * <p>两种查询口径，实现按自身数据源能力支持其一或全部：</p>
 * <ul>
 *   <li>{@link #getWeather(String)} —— 按城市名查询</li>
 *   <li>{@link #getWeather(Double, Double, Integer)} —— 按经纬度查询（顶栏天气按坐标定位，
 *       避免城市名歧义；不支持坐标的实现返回 空，由调用方改选其他数据源）</li>
 * </ul>
 *
 * <p>各实现通过 SPI 机制注册（如 wttr.in、Open-Meteo 等公开免费接口）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface WeatherProvider {

    /**
     * 获取数据源名称。
     *
     * @return 数据源名称
     */
    String name();

    /**
     * 查询指定城市的实时天气。
     *
     * @param city 城市名（如 "北京"、"Beijing"）
     * @return 天气信息；数据源不可达或城市不存在时返回 空
     */
    WeatherInfo getWeather(String city);

    /**
     * 按经纬度查询实时天气（含逐小时与逐日预报）。
     *
     * @param latitude  纬度
     * @param longitude 经度
     * @param days      预报天数，为空由数据源默认
     * @return 天气信息；数据源不支持坐标查询或不可达时返回 空
     */
    default WeatherInfo getWeather(Double latitude, Double longitude, Integer days) {
        return null;
    }
}
