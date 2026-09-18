package com.chua.common.support.datasearch.weather.spi;

import com.chua.common.support.datasearch.weather.model.WeatherInfo;

/**
* 天气数据提供者 SPI 接口。
*
* <p>按城市查询实时天气信息，各实现通过 SPI 机制注册
* （如基于 wttr.入 等公开免费接口的数据源）。</p>
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
}
