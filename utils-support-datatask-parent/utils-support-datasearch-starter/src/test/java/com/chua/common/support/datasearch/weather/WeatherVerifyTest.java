package com.chua.common.support.datasearch.weather;

import com.chua.common.support.datasearch.weather.model.DailyForecast;
import com.chua.common.support.datasearch.weather.model.WeatherInfo;
import com.chua.common.support.datasearch.weather.spi.WeatherProvider;
import com.chua.common.support.datasearch.weather.spi.impl.WttrInWeatherProvider;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 天气真实数据验证:SPI 发现 + wttr.in 实时天气查询。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WeatherVerifyTest {

    /**
     * 运行验证。
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        WeatherProvider provider = ServiceProvider.of(WeatherProvider.class)
                .getExtension("wttr-in");
        System.out.println("SPI 发现 wttr-in: " + (provider != null));
        if (provider == null) {
            provider = new WttrInWeatherProvider();
        }
        for (String city : new String[]{"北京"}) {
            WeatherInfo info = provider.getWeather(city);
            if (info == null) {
                System.out.println(city + " -> 查询失败(null)");
                continue;
            }
            System.out.printf("%s -> 当前 %s°C(体感%s) 湿度%d%% %s 风速%skm/h%n",
                    city, info.getTempC(), info.getFeelsLikeC(), info.getHumidity(),
                    info.getWeatherDesc(), info.getWindSpeedKmph());
            // 当天逐小时(前 4 个点)
            if (info.getHourly() != null && !info.getHourly().isEmpty()) {
                System.out.println(" 当天逐小时(采样点):");
                info.getHourly().stream().limit(4).forEach(h ->
                        System.out.printf("   %s时 %s°C 湿度%d%% %s%n",
                                Integer.parseInt(h.getTime()) / 100, h.getTempC(),
                                h.getHumidity(), h.getWeatherDesc()));
            }
            // 未来几天预报
            if (info.getForecast() != null) {
                System.out.println(" 未来预报:");
                info.getForecast().forEach(f ->
                        System.out.printf("   %s 最高%s°C 最低%s°C 平均%s°C UV%s 日照%s时%n",
                                f.getDate(), f.getMaxTempC(), f.getMinTempC(),
                                f.getAvgTempC(), f.getUvIndex(), f.getSunHour()));
            }
        }

        // Open-Meteo:日期列表 + 每天 24 小时
        WeatherProvider om = ServiceProvider.of(WeatherProvider.class).getExtension("open-meteo");
        System.out.println("SPI 发现 open-meteo: " + (om != null));
        if (om != null) {
            WeatherInfo omInfo = om.getWeather("北京");
            if (omInfo == null) {
                System.out.println("open-meteo 北京 -> 查询失败(null)");
            } else {
                System.out.println("open-meteo 北京: 当前 " + omInfo.getTempC() + "°C "
                        + omInfo.getWeatherDesc() + " 湿度" + omInfo.getHumidity() + "%");
                if (omInfo.getForecast() != null) {
                    System.out.println(" 日期列表(每天 24 小时):");
                    for (DailyForecast f : omInfo.getForecast()) {
                        System.out.printf("   %s 最高%s°C 最低%s°C 小时点数=%d%n",
                                f.getDate(), f.getMaxTempC(), f.getMinTempC(),
                                f.getHourly() == null ? 0 : f.getHourly().size());
                    }
                }
            }
        }
    }
}
