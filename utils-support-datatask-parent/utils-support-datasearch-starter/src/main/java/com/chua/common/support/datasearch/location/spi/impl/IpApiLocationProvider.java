package com.chua.common.support.datasearch.location.spi.impl;

import com.chua.common.support.datasearch.location.model.LocationInfo;
import com.chua.common.support.datasearch.location.spi.LocationProvider;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ip-api.com IP 定位数据源实现。
 *
 * <p>免费公开接口（无 key，免费版走 http）：</p>
 * <ul>
 *   <li>{@code http://ip-api.com/json/?lang=zh-CN} — 定位当前请求者（服务器自身）</li>
 *   <li>{@code http://ip-api.com/json/{ip}?lang=zh-CN} — 按 IP 定位</li>
 * </ul>
 *
 * <p>返回国家/省份/城市/经纬度/时区/运营商等。1 小时内存缓存（惰性刷新）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ip-api")
public class IpApiLocationProvider implements LocationProvider {

    private static final Logger log = LoggerFactory.getLogger(IpApiLocationProvider.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 查询地址模板（空 IP 时定位请求者自身） */
    private static final String LOCATE_URL = "http://ip-api.com/json/%s?lang=zh-CN";

    /** 缓存有效期（毫秒）：1 小时 */
    private static final long CACHE_TTL_MILLIS = 60 * 60 * 1000L;

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126 Safari/537.36";

    /** 缓存（key -> [定位, 时间戳]） */
    private final Map<String, LocationInfo> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> cachedAt = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "ip-api";
    }

    @Override
    public LocationInfo locateSelf() {
        return locate("");
    }

    @Override
    public LocationInfo locateIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        return locate(ip.trim());
    }

    /**
     * 执行定位（空 key 表示请求者自身）。
     *
     * @param key IP 或空串
     * @return 定位信息；失败返回 null
     */
    private LocationInfo locate(String key) {
        Long ts = cachedAt.get(key);
        if (ts != null && System.currentTimeMillis() - ts < CACHE_TTL_MILLIS) {
            return cache.get(key);
        }
        try {
            String json = HttpClientFactory.of(String.format(LOCATE_URL, key))
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .get().getBodyString();
            JsonNode root = MAPPER.readTree(json);
            if (!"success".equals(root.path("status").asText())) {
                return null;
            }
            LocationInfo info = new LocationInfo();
            info.setIp(root.path("query").asText(null));
            info.setCountry(root.path("country").asText(null));
            info.setCountryCode(root.path("countryCode").asText(null));
            info.setRegion(root.path("regionName").asText(null));
            info.setCity(root.path("city").asText(null));
            info.setZip(root.path("zip").asText(null));
            info.setLatitude(num(root.path("lat")));
            info.setLongitude(num(root.path("lon")));
            info.setTimezone(root.path("timezone").asText(null));
            info.setIsp(root.path("isp").asText(null));
            cache.put(key, info);
            cachedAt.put(key, System.currentTimeMillis());
            return info;
        } catch (Exception e) {
            log.warn("[ip-api] 定位失败: key={}, msg={}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 读取数值节点。
     *
     * @param node 数值节点
     * @return 数值；缺失/非数值返回 null
     */
    private Double num(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asDouble();
    }
}
