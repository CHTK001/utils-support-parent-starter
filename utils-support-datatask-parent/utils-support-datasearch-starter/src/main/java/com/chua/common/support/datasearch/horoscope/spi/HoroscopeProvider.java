package com.chua.common.support.datasearch.horoscope.spi;

import com.chua.common.support.datasearch.horoscope.model.HoroscopeInfo;

/**
* 星座运势数据提供者 SPI 接口。
*
* <p>封装十二星座今日 / 本周 / 本月运势查询能力。
* 各实现通过 SPI 机制注册，例如基于在线运势接口的数据源。
*
* @author CH
* @since 4.0.0.42
 */
public interface HoroscopeProvider {

    /**
    * 获取数据源名称
    *
    * @return 数据源名称
     */
    String name();

    /**
    * 获取指定星座与周期的运势。
    *
    * @param sign 星座（如：白羊座、金牛座）
    * @param type 周期：today=今日 / week=本周 / month=本月
    * @return 运势信息；参数非法或查询失败返回 空
     */
    HoroscopeInfo get(String sign, String type);

    /**
    * 获取指定星座今日运势。
    *
    * @param sign 星座
    * @return 今日运势
     */
    default HoroscopeInfo getToday(String sign) {
        return get(sign, "today");
    }

    /**
    * 获取指定星座本周运势。
    *
    * @param sign 星座
    * @return 本周运势
     */
    default HoroscopeInfo getWeek(String sign) {
        return get(sign, "week");
    }

    /**
    * 获取指定星座本月运势。
    *
    * @param sign 星座
    * @return 本月运势
     */
    default HoroscopeInfo getMonth(String sign) {
        return get(sign, "month");
    }
}
