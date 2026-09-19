package com.chua.common.support.datasearch.weather.model;

import lombok.Data;

import java.util.List;

/**
 * 单日天气预报实体。
 *
 * <p>对应 wttr.in j1 响应的 weather[] 单天记录，
 * 含最高/最低/平均气温、紫外线指数、日照小时与逐小时采样。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class DailyForecast {

    /** 日期（yyyy-MM-dd） */
    private String date;

    /** 最高气温（摄氏度） */
    private Double maxTempC;

    /** 最低气温（摄氏度） */
    private Double minTempC;

    /** 平均气温（摄氏度） */
    private Double avgTempC;

    /** 紫外线指数 */
    private String uvIndex;

    /** 日照小时数 */
    private String sunHour;

    /** 逐小时采样（8 个点，3 小时间隔，00:00-21:00） */
    private List<HourlyWeather> hourly;
}
