package com.chua.common.support.datasearch.weather.spi.impl;

import com.chua.common.support.datasearch.weather.model.DailyForecast;
import com.chua.common.support.datasearch.weather.model.HourlyWeather;
import com.chua.common.support.datasearch.weather.model.WeatherInfo;
import com.chua.common.support.datasearch.weather.spi.WeatherProvider;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 打开-Meteo 天气数据源实现（逐小时 24 点）。
*
* <p>免费公开接口（无 key，无需经纬度由城市地理编码自动解析）：</p>
* <ul>
*   <li>城市 → 经纬度：{@code geocoding-api.open-meteo.com/v1/search?name={city}}</li>
*   <li>逐小时预报：{@code api.open-meteo.com/v1/forecast}（3 天 × 24 点/天）</li>
* </ul>
*
* <p>返回结构为日期列表（{@link DailyForecast}），每天子列表含 24 个
* {@link HourlyWeather} 逐小时点。30 分钟内存缓存（惰性刷新）。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("open-meteo")
public class OpenMeteoWeatherProvider implements WeatherProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherProvider.class); // 日志

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器

    /** 城市地理编码地址模板 */
    private static final String GEO_URL =
            "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=1&language=zh";

    /** 逐小时预报地址模板（3 天 × 24 点 + 当前实况） */
    private static final String FORECAST_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                    + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
                    + "&hourly=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
                    + "&forecast_days=3&timezone=Asia%%2FShanghai";

    /** 缓存有效期（毫秒）：30 分钟 */
    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126 Safari/537.36";

    /** 缓存的天气与城市 */
    private volatile String cachedCity;
    private volatile WeatherInfo cached; // 缓存

    /** 缓存时间戳 */
    private volatile long cachedAt;

    @Override
    public String name() {
        return "open-meteo";
    }

    @Override
    public WeatherInfo getWeather(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        String key = city.trim();
        if (cached != null && key.equalsIgnoreCase(cachedCity)
                && System.currentTimeMillis() - cachedAt < CACHE_TTL_MILLIS) {
            return cached;
        }
        try {
            WeatherInfo info = fetch(key);
            if (info != null) {
                cached = info;
                cachedCity = key;
                cachedAt = System.currentTimeMillis();
            }
            return info;
        } catch (Exception e) {
            log.warn("[open-meteo] 查询天气失败: city={}, msg={}", key, e.getMessage());
            return cached;
        }
    }

    /**
    * 抓取并组装天气：城市 → 经纬度 → 3 天逐小时。
    *
    * @param city 城市名
    * @return 天气实体；解析失败返回 空
    * @throws Exception 网络或解析异常
     */
    private WeatherInfo fetch(String city) throws Exception {
        // 1) 城市 → 经纬度
        String geoJson = HttpClientFactory.of(String.format(GEO_URL, encode(city)))
                .header("User-Agent", DEFAULT_USER_AGENT)
                .get().getBodyString();
        JsonNode geoRoot = MAPPER.readTree(geoJson);
        JsonNode first = geoRoot.path("results").path(0);
        if (first.isMissingNode()) {
            return null;
        }
        double lat = first.path("latitude").asDouble();
        double lon = first.path("longitude").asDouble();
        String resolvedName = first.path("name").asText(city);

        // 2) 逐小时预报
        String fcJson = HttpClientFactory.of(String.format(FORECAST_URL, lat, lon))
                .header("User-Agent", DEFAULT_USER_AGENT)
                .get().getBodyString();
        JsonNode fc = MAPPER.readTree(fcJson);

        WeatherInfo info = new WeatherInfo();
        info.setCity(resolvedName);
        // 当前实况
        JsonNode current = fc.path("current");
        if (!current.isMissingNode()) {
            info.setTempC(num(current.path("temperature_2m")));
            info.setHumidity(intVal(current.path("relative_humidity_2m")));
            info.setWindSpeedKmph(num(current.path("wind_speed_10m")));
            info.setWeatherDesc(describeWmo(current.path("weather_code").asInt(-1)));
        }
        // 按天分组(日期列表,每天 24 点)
        JsonNode hourly = fc.path("hourly");
        JsonNode times = hourly.path("time");
        JsonNode temps = hourly.path("temperature_2m");
        JsonNode hums = hourly.path("relative_humidity_2m");
        JsonNode codes = hourly.path("weather_code");
        JsonNode winds = hourly.path("wind_speed_10m");

        Map<String, DailyForecast> byDay = new LinkedHashMap<>();
        for (int i = 0; i < times.size(); i++) {
            String time = times.path(i).asText();
            String day = time.length() >= 10 ? time.substring(0, 10) : time;
            DailyForecast df = byDay.computeIfAbsent(day, d -> {
                DailyForecast f = new DailyForecast();
                f.setDate(d);
                f.setHourly(new ArrayList<>());
                return f;
            });
            HourlyWeather hw = new HourlyWeather();
            hw.setTime(time);
            hw.setTempC(num(temps.path(i)));
            hw.setHumidity(intVal(hums.path(i)));
            hw.setWindSpeedKmph(num(winds.path(i)));
            hw.setWeatherDesc(describeWmo(codes.path(i).asInt(-1)));
            df.getHourly().add(hw);
            // 当日最高/最低
            if (hw.getTempC() != null) {
                if (df.getMaxTempC() == null || hw.getTempC() > df.getMaxTempC()) {
                    df.setMaxTempC(hw.getTempC());
                }
                if (df.getMinTempC() == null || hw.getTempC() < df.getMinTempC()) {
                    df.setMinTempC(hw.getTempC());
                }
            }
        }
        List<DailyForecast> forecast = new ArrayList<>(byDay.values());
        info.setForecast(forecast);
        if (!forecast.isEmpty()) {
            info.setHourly(forecast.get(0).getHourly());
        }
        return info;
    }

    /**
    * URL 编码城市名。
    *
    * @param city 城市名
    * @return 编码结果
     */
    private String encode(String city) {
        try {
            return java.net.URLEncoder.encode(city, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return city;
        }
    }

    /**
    * WMO 天气代码转中文描述。
    *
    * @param code WMO 代码
    * @return 描述
     */
    private String describeWmo(int code) {
        if (code == 0) {
            return "晴";
        }
        if (code == 1) {
            return "基本晴";
        }
        if (code == 2) {
            return "少云";
        }
        if (code == 3) {
            return "阴";
        }
        if (code == 45 || code == 48) {
            return "雾";
        }
        if (code >= 51 && code <= 57) {
            return "毛毛雨";
        }
        if (code >= 61 && code <= 67) {
            return "雨";
        }
        if (code >= 71 && code <= 77) {
            return "雪";
        }
        if (code >= 80 && code <= 82) {
            return "阵雨";
        }
        if (code == 85 || code == 86) {
            return "阵雪";
        }
        if (code >= 95) {
            return "雷暴";
        }
        return "未知";
    }

    /**
    * 读取数值节点。
    *
    * @param node 数值节点
    * @return 数值；缺失/非数值返回 空
     */
    private Double num(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asDouble();
    }

    /**
    * 读取整数节点。
    *
    * @param node 数值节点
    * @return 整数；缺失/非数值返回 空
     */
    private Integer intVal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asInt();
    }
}
