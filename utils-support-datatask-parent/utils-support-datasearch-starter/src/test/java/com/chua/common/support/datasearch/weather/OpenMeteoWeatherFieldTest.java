package com.chua.common.support.datasearch.weather;

import com.chua.common.support.datasearch.weather.model.DailyForecast;
import com.chua.common.support.datasearch.weather.model.HourlyWeather;
import com.chua.common.support.datasearch.weather.model.WeatherInfo;
import com.chua.common.support.datasearch.weather.spi.impl.OpenMeteoWeatherProvider;

import java.util.List;

/**
 * Open-Meteo 天气字段填充联调测试（访问 open-meteo 公开接口真实数据）。
 *
 * <p>回归点：{@code OpenMeteoWeatherProvider} 的请求串只带了
 * temperature_2m/relative_humidity_2m/weather_code/wind_speed_10m 四个指标，
 * 实体中 {@code feelsLikeC}、{@code cloudcover}、{@code pressure}、
 * {@code observationTime}、{@code avgTempC}、{@code uvIndex}、{@code sunHour}
 * 从未被赋值（同模块的 wttr.in 实现全部有值）。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class OpenMeteoWeatherFieldTest {

    private static int pass = 0;
    private static int fail = 0;

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        WeatherInfo info = new OpenMeteoWeatherProvider().getWeather("北京");
        check("可取到天气", info != null);
        if (info == null) {
            summary();
            return;
        }
        System.out.println("      city=" + info.getCity() + " temp=" + info.getTempC()
                + " feelsLike=" + info.getFeelsLikeC() + " cloud=" + info.getCloudcover()
                + " pressure=" + info.getPressure() + " obs=" + info.getObservationTime()
                + " desc=" + info.getWeatherDesc());

        check("实时体感温度 feelsLikeC（修复前恒为 null）", info.getFeelsLikeC() != null);
        check("实时云量 cloudcover（修复前恒为 null）", info.getCloudcover() != null);
        check("实时气压 pressure（修复前恒为 null）", info.getPressure() != null);
        check("实时观测时间 observationTime（修复前恒为 null）", notBlank(info.getObservationTime()));
        check("实时基础字段未回归", info.getTempC() != null && info.getHumidity() != null
                && info.getWindSpeedKmph() != null && notBlank(info.getWeatherDesc()));

        List<DailyForecast> forecast = info.getForecast();
        check("三日预报非空", forecast != null && forecast.size() == 3);
        if (forecast == null || forecast.isEmpty()) {
            summary();
            return;
        }
        for (DailyForecast day : forecast) {
            System.out.println("      " + day.getDate() + " min=" + day.getMinTempC()
                    + " avg=" + day.getAvgTempC() + " max=" + day.getMaxTempC()
                    + " uv=" + day.getUvIndex() + " sunHour=" + day.getSunHour()
                    + " hourly=" + (day.getHourly() == null ? 0 : day.getHourly().size()));
        }
        check("每日均温 avgTempC（修复前恒为 null）", forecast.stream().allMatch(d -> d.getAvgTempC() != null));
        check("每日紫外线 uvIndex（修复前恒为 null）", forecast.stream().allMatch(d -> notBlank(d.getUvIndex())));
        check("每日日照 sunHour（修复前恒为 null）", forecast.stream().allMatch(d -> notBlank(d.getSunHour())));
        check("均温落在最低/最高之间", forecast.stream().allMatch(d ->
                d.getMinTempC() != null && d.getMaxTempC() != null
                        && d.getMinTempC() <= d.getAvgTempC() && d.getAvgTempC() <= d.getMaxTempC()));
        check("日照为小时口径（<24）", forecast.stream().allMatch(d ->
                Double.parseDouble(d.getSunHour()) >= 0 && Double.parseDouble(d.getSunHour()) <= 24));

        List<HourlyWeather> hourly = forecast.getFirst().getHourly();
        check("首日逐小时 24 点", hourly != null && hourly.size() == 24);
        check("逐小时体感 feelsLikeC（修复前恒为 null）", hourly != null
                && hourly.stream().allMatch(h -> h.getFeelsLikeC() != null));
        check("逐小时基础字段未回归", hourly != null && hourly.stream().allMatch(h ->
                h.getTempC() != null && h.getHumidity() != null && h.getWindSpeedKmph() != null));
        check("当天逐小时已挂到 WeatherInfo.hourly", info.getHourly() != null && !info.getHourly().isEmpty());

        summary();
    }

    private static void summary() {
        System.out.println("[OpenMeteoWeatherFieldTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
