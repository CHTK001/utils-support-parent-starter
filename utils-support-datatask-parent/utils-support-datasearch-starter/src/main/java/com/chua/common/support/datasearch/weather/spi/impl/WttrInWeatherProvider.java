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
import java.util.List;

/**
 * wttr.in 天气数据源实现。
 *
 * <p>通过 {@code HttpClientFactory} 调用免费公开接口
 * {@code https://wttr.in/{city}?format=j1}（无需 key，按城市名查询），
 * 解析 current_condition 实时天气与 3 天预报。</p>
 *
 * <p>该源没有 WMO 天气码口径，{@code weatherCode} 留空，
 * 由上层在多个数据源中优先选择带码的结果。</p>
 *
 * <p>30 分钟内存缓存（惰性刷新，不内置定时任务）。城市路径动态，故使用
 * HttpClientFactory 实体请求而非 httpinvoker 声明式代理（后者面向固定 URL 接口）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("wttr-in")
public class WttrInWeatherProvider implements WeatherProvider {

    private static final Logger log = LoggerFactory.getLogger(WttrInWeatherProvider.class); // 日志

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器

    /**
     * 查询地址模板（城市名进入路径）
    */
    private static final String WEATHER_URL = "https://wttr.in/%s?format=j1";

    /**
     * 缓存有效期（毫秒）：30 分钟
    */
    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126 Safari/537.36";

    /**
     * 缓存的城市名与天气
    */
    private volatile String cachedCity;
    private volatile WeatherInfo cached; // 缓存

    /**
     * 缓存时间戳
    */
    private volatile long cachedAt;

    @Override
    public String name() {
        return "wttr-in";
    }

    /**
     * 查询指定城市的实时天气。
     *
     * @param city 城市名（如 "北京"、"Beijing"）
     * @return 天气信息；数据源不可达或城市不存在时返回 空
     */
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
            String json = HttpClientFactory.of(String.format(WEATHER_URL, key))
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .get().getBodyString();
            WeatherInfo info = parse(json, key);
            if (info != null) {
                cached = info;
                cachedCity = key;
                cachedAt = System.currentTimeMillis();
            }
            return info;
        } catch (Exception e) {
            log.warn("[wttr-in] 查询天气失败: city={}, msg={}", key, e.getMessage());
            return cached;
        }
    }

    /**
     * 解析 wttr.入 j1 响应为天气实体。
     *
     * @param json 响应 JSON
     * @param city 请求城市
     * @return 天气实体；解析失败返回 空
     */
    private WeatherInfo parse(String json, String city) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode current = root.path("current_condition").path(0);
            if (current.isMissingNode()) {
                return null;
            }
            JsonNode desc = current.path("weatherDesc").path(0);
            // 未来数日预报(3 天)与当天逐小时采样
            var forecast = parseForecast(root.path("weather"));
            var hourly = forecast != null && !forecast.isEmpty()
                    ? forecast.getFirst().getHourly() : null;
            return new WeatherInfo(city, null, null,
                    num(current.path("temp_C")), num(current.path("FeelsLikeC")),
                    intVal(current.path("humidity")), intVal(current.path("cloudcover")),
                    desc.isMissingNode() ? null : desc.path("value").asText(null),
                    null, num(current.path("windspeedKmph")), num(current.path("winddirDegree")),
                    intVal(current.path("pressure")), current.path("observation_time").asText(null),
                    name(), hourly, forecast);
        } catch (Exception e) {
            log.debug("[wttr-in] 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析未来数日预报（weather 数组，3 天）。
     *
     * @param weatherNode weather 数组节点
     * @return 预报列表；非数组时返回空列表
     */
    private List<DailyForecast> parseForecast(JsonNode weatherNode) {
        List<DailyForecast> result = new ArrayList<>();
        if (weatherNode == null || !weatherNode.isArray()) {
            return result;
        }
        for (JsonNode day : weatherNode) {
            DailyForecast forecast = new DailyForecast();
            forecast.setDate(day.path("date").asText(null));
            forecast.setMaxTempC(num(day.path("maxtempC")));
            forecast.setMinTempC(num(day.path("mintempC")));
            forecast.setAvgTempC(num(day.path("avgtempC")));
            forecast.setUvIndex(num(day.path("uvIndex")));
            forecast.setSunHour(day.path("sunHour").asText(null));
            forecast.setHourly(parseHourly(day.path("hourly"), forecast.getDate()));
            result.add(forecast);
        }
        return result;
    }

    /**
     * 解析逐小时天气采样（8 个点，3 小时间隔）。
     *
     * @param hourlyNode hourly 数组节点
     * @param date       所属日期（yyyy-MM-dd），用于拼出统一时间格式
     * @return 逐小时列表；非数组时返回空列表
     */
    private List<HourlyWeather> parseHourly(JsonNode hourlyNode, String date) {
        List<HourlyWeather> result = new ArrayList<>();
        if (hourlyNode == null || !hourlyNode.isArray()) {
            return result;
        }
        for (JsonNode h : hourlyNode) {
            HourlyWeather hw = new HourlyWeather();
            hw.setTime(toIsoTime(date, h.path("time").asText(null)));
            hw.setTempC(num(h.path("tempC")));
            hw.setFeelsLikeC(num(h.path("FeelsLikeC")));
            hw.setHumidity(intVal(h.path("humidity")));
            hw.setWindSpeedKmph(num(h.path("windspeedKmph")));
            hw.setWindDirection(num(h.path("winddirDegree")));
            hw.setPrecipitation(num(h.path("precipMM")));
            hw.setPrecipitationProbability(intVal(h.path("chanceofrain")));
            JsonNode desc = h.path("weatherDesc").path(0);
            if (!desc.isMissingNode()) {
                hw.setWeatherDesc(desc.path("value").asText(null));
            }
            result.add(hw);
        }
        return result;
    }

    /**
     * wttr.in 的 3 小时间隔采样戳（0/300/…/2100）换算为 {@code yyyy-MM-ddTHH:mm}。
     *
     * @param date 所属日期
     * @param raw  原始采样戳
     * @return 统一时间格式；无法换算时返回 空
     */
    private String toIsoTime(String date, String raw) {
        if (date == null || raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int stamp = Integer.parseInt(raw.trim());
            return String.format("%sT%02d:00", date, stamp / 100);
        } catch (NumberFormatException e) {
            return null;
        }
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
