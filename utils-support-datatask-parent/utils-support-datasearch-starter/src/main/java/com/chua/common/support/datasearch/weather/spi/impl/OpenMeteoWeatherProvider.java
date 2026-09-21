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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Open-Meteo 天气数据源实现（实况 + 逐小时 + 逐日）。
 *
 * <p>免费公开接口（无 key）：</p>
 * <ul>
 *   <li>城市 → 经纬度：{@code geocoding-api.open-meteo.com/v1/search?name={city}}</li>
 *   <li>预报：{@code api.open-meteo.com/v1/forecast}（{@code timezone=auto}，
 *       响应时间为当地时间）</li>
 * </ul>
 *
 * <p>该源提供 WMO 4677 天气码，是本系统天气契约的首选数据源。</p>
 *
 * <p>按查询条件缓存（LRU，容量 32，30 分钟过期，惰性刷新），不内置定时任务。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("open-meteo")
public class OpenMeteoWeatherProvider implements WeatherProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherProvider.class); // 日志

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器

    /**
     * 城市地理编码地址模板
    */
    private static final String GEO_URL =
            "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=1&language=zh";

    /**
     * 逐小时参数串
    */
    private static final String HOURLY_FIELDS = "temperature_2m,apparent_temperature,relative_humidity_2m,"
            + "weather_code,wind_speed_10m,wind_direction_10m,precipitation,precipitation_probability";

    /**
     * 逐日参数串
    */
    private static final String DAILY_FIELDS = "weather_code,temperature_2m_max,temperature_2m_min,"
            + "apparent_temperature_max,apparent_temperature_min,precipitation_sum,"
            + "precipitation_probability_max,wind_speed_10m_max,wind_direction_10m_dominant,"
            + "uv_index_max,sunrise,sunset";

    /**
     * 预报地址模板（经纬度 + 预报天数）
    */
    private static final String FORECAST_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,"
                    + "wind_speed_10m,wind_direction_10m,pressure_msl"
                    + "&hourly=" + HOURLY_FIELDS + "&daily=" + DAILY_FIELDS
                    + "&forecast_days=%s&timezone=auto";

    /**
     * 缓存有效期（毫秒）：30 分钟
    */
    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;

    /**
     * 缓存条目上限（超出按最近最少使用淘汰）
    */
    private static final int CACHE_MAX_SIZE = 32;

    /**
     * 默认预报天数
    */
    private static final int DEFAULT_DAYS = 7;

    /**
     * Open-Meteo 允许的最大预报天数
    */
    private static final int MAX_DAYS = 16;

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126 Safari/537.36";

    /**
     * 按查询条件缓存的天气（访问序 LRU，容量受限）
    */
    private final Map<String, CacheEntry> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            });

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
        return query("city:" + key.toLowerCase(), () -> {
            GeoPoint point = geocode(key);
            return point == null ? null : fetch(point.latitude(), point.longitude(), DEFAULT_DAYS, point.name());
        }, key);
    }

    @Override
    public WeatherInfo getWeather(Double latitude, Double longitude, Integer days) {
        if (latitude == null || longitude == null) {
            return null;
        }
        int forecastDays = days == null ? DEFAULT_DAYS : Math.max(1, Math.min(MAX_DAYS, days));
        return query(String.format("coord:%.4f,%.4f,%d", latitude, longitude, forecastDays),
                () -> fetch(latitude, longitude, forecastDays, null), null);
    }

    /**
     * 缓存优先执行取数动作。
     *
     * @param cacheKey    缓存键（含查询条件）
     * @param supplier    实际取数动作
     * @param fallbackKey 失败时回退使用的旧缓存键，可为 空
     * @return 天气信息；全部失败返回 空
     */
    private WeatherInfo query(String cacheKey, FetchAction supplier, String fallbackKey) {
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && now - cached.at() < CACHE_TTL_MILLIS) {
            return cached.info();
        }
        try {
            WeatherInfo info = supplier.fetch();
            if (info != null) {
                cache.put(cacheKey, new CacheEntry(info, now));
            }
            return info;
        } catch (Exception e) {
            log.warn("[open-meteo] 查询天气失败: key={}, msg={}", cacheKey, e.getMessage());
            CacheEntry stale = fallbackKey == null ? cached : cache.get(fallbackKey);
            return stale != null && now - stale.at() < 2 * CACHE_TTL_MILLIS ? stale.info() : null;
        }
    }

    /**
     * 缓存条目。
     *
     * @param info 天气信息
     * @param at   写入时间戳（毫秒）
     */
    private record CacheEntry(WeatherInfo info, long at) {
    }

    /**
     * 取数动作。
     */
    @FunctionalInterface
    private interface FetchAction {

        /**
         * 执行取数。
         *
         * @return 天气信息，可为 空
         * @throws Exception 网络或解析异常
         */
        WeatherInfo fetch() throws Exception;
    }

    /**
     * 地理编码结果。
     *
     * @param latitude  纬度
     * @param longitude 经度
     * @param name      解析后的地名
     */
    private record GeoPoint(double latitude, double longitude, String name) {
    }

    /**
     * 城市名解析为经纬度。
     *
     * @param city 城市名
     * @return 坐标点；未命中返回 空
     * @throws Exception 网络或解析异常
     */
    private GeoPoint geocode(String city) throws Exception {
        String geoJson = HttpClientFactory.of(String.format(GEO_URL, encode(city)))
                .header("User-Agent", DEFAULT_USER_AGENT)
                .get().getBodyString();
        JsonNode first = MAPPER.readTree(geoJson).path("results").path(0);
        if (first.isMissingNode()) {
            return null;
        }
        return new GeoPoint(first.path("latitude").asDouble(), first.path("longitude").asDouble(),
                first.path("name").asText(city));
    }

    /**
     * 按坐标抓取实况 + 逐小时 + 逐日并组装实体。
     *
     * @param lat    纬度
     * @param lon    经度
     * @param days   预报天数
     * @param city   已知地名，可为 空
     * @return 天气实体
     * @throws Exception 网络或解析异常
     */
    private WeatherInfo fetch(double lat, double lon, int days, String city) throws Exception {
        String json = HttpClientFactory.of(String.format(FORECAST_URL, lat, lon, days))
                .header("User-Agent", DEFAULT_USER_AGENT)
                .get().getBodyString();
        JsonNode root = MAPPER.readTree(json);

        WeatherInfo info = new WeatherInfo();
        info.setSource(name());
        info.setCity(city);
        info.setLatitude(root.path("latitude").asDouble(lat));
        info.setLongitude(root.path("longitude").asDouble(lon));

        JsonNode current = root.path("current");
        info.setTempC(num(current.path("temperature_2m")));
        info.setFeelsLikeC(num(current.path("apparent_temperature")));
        info.setHumidity(intVal(current.path("relative_humidity_2m")));
        info.setWeatherCode(intVal(current.path("weather_code")));
        info.setWeatherDesc(describeWmo(current.path("weather_code").asInt(-1)));
        info.setWindSpeedKmph(num(current.path("wind_speed_10m")));
        info.setWindDirection(num(current.path("wind_direction_10m")));
        info.setPressure(intVal(current.path("pressure_msl")));
        info.setObservationTime(text(current.path("time")));

        List<HourlyWeather> hourly = parseHourly(root.path("hourly"));
        List<DailyForecast> forecast = parseDaily(root.path("daily"));
        attachHourly(forecast, hourly);
        info.setHourly(hourly);
        info.setForecast(forecast);
        return info;
    }

    /**
     * 解析逐小时数组（各字段为同长度平行数组）。
     *
     * @param hourlyNode hourly 节点
     * @return 逐小时列表
     */
    private List<HourlyWeather> parseHourly(JsonNode hourlyNode) {
        List<HourlyWeather> result = new ArrayList<>();
        JsonNode times = hourlyNode.path("time");
        if (!times.isArray()) {
            return result;
        }
        for (int i = 0; i < times.size(); i++) {
            HourlyWeather hw = new HourlyWeather();
            hw.setTime(times.path(i).asText());
            hw.setTempC(num(hourlyNode.path("temperature_2m").path(i)));
            hw.setFeelsLikeC(num(hourlyNode.path("apparent_temperature").path(i)));
            hw.setHumidity(intVal(hourlyNode.path("relative_humidity_2m").path(i)));
            hw.setWeatherCode(intVal(hourlyNode.path("weather_code").path(i)));
            hw.setWeatherDesc(describeWmo(hourlyNode.path("weather_code").path(i).asInt(-1)));
            hw.setWindSpeedKmph(num(hourlyNode.path("wind_speed_10m").path(i)));
            hw.setWindDirection(num(hourlyNode.path("wind_direction_10m").path(i)));
            hw.setPrecipitation(num(hourlyNode.path("precipitation").path(i)));
            hw.setPrecipitationProbability(intVal(hourlyNode.path("precipitation_probability").path(i)));
            result.add(hw);
        }
        return result;
    }

    /**
     * 解析逐日数组（各字段为同长度平行数组）。
     *
     * @param dailyNode daily 节点
     * @return 逐日预报列表
     */
    private List<DailyForecast> parseDaily(JsonNode dailyNode) {
        List<DailyForecast> result = new ArrayList<>();
        JsonNode dates = dailyNode.path("time");
        if (!dates.isArray()) {
            return result;
        }
        for (int i = 0; i < dates.size(); i++) {
            DailyForecast forecast = new DailyForecast();
            forecast.setDate(dates.path(i).asText());
            forecast.setWeatherCode(intVal(dailyNode.path("weather_code").path(i)));
            forecast.setMaxTempC(num(dailyNode.path("temperature_2m_max").path(i)));
            forecast.setMinTempC(num(dailyNode.path("temperature_2m_min").path(i)));
            forecast.setFeelsLikeMaxC(num(dailyNode.path("apparent_temperature_max").path(i)));
            forecast.setFeelsLikeMinC(num(dailyNode.path("apparent_temperature_min").path(i)));
            forecast.setPrecipitation(num(dailyNode.path("precipitation_sum").path(i)));
            forecast.setPrecipitationProbability(intVal(dailyNode.path("precipitation_probability_max").path(i)));
            forecast.setWindSpeedKmph(num(dailyNode.path("wind_speed_10m_max").path(i)));
            forecast.setWindDirection(num(dailyNode.path("wind_direction_10m_dominant").path(i)));
            forecast.setUvIndex(num(dailyNode.path("uv_index_max").path(i)));
            forecast.setSunrise(text(dailyNode.path("sunrise").path(i)));
            forecast.setSunset(text(dailyNode.path("sunset").path(i)));
            result.add(forecast);
        }
        return result;
    }

    /**
     * 把逐小时点按日期归入对应的逐日预报。
     *
     * @param forecast 逐日列表（就地写入 hourly 子列表）
     * @param hourly   逐小时列表
     */
    private void attachHourly(List<DailyForecast> forecast, List<HourlyWeather> hourly) {
        if (forecast.isEmpty() || hourly.isEmpty()) {
            return;
        }
        Map<String, List<HourlyWeather>> byDay = new LinkedHashMap<>();
        for (HourlyWeather hw : hourly) {
            String time = hw.getTime();
            String day = time != null && time.length() >= 10 ? time.substring(0, 10) : time;
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(hw);
        }
        for (DailyForecast day : forecast) {
            day.setHourly(byDay.getOrDefault(day.getDate(), new ArrayList<>()));
        }
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
     * 读取文本节点。
     *
     * @param node 文本节点
     * @return 文本；缺失返回 空
     */
    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asText();
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
