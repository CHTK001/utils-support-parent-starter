package com.chua.common.support.datasearch.geocode.spi.impl;

import com.chua.common.support.datasearch.geocode.spi.GeocodeProvider;
import com.chua.common.support.datasearch.location.model.LocationInfo;
import com.chua.common.support.datasearch.location.spi.impl.IpApiLocationProvider;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * big数据cloud 逆地理编码实现。
 *
 * <p>经纬度 → 行政地址：{@code https://api.bigdatacloud.net/data/reverse-geocode-client}
 * （免费、无需 键、支持中文，返回国家/省/市/区）。</p>
 *
 * <p>IP → 物理地址：先经 {@link IpApiLocationProvider} 定位到经纬度再逆编码，
 * 定位失败时回退为「城市 + 省份 + 国家」行政信息拼接。</p>
 *
 * <p>24 小时内存缓存（惰性刷新）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("bigdatacloud")
public class BigDataCloudGeocodeProvider implements GeocodeProvider {

    private static final Logger log = LoggerFactory.getLogger(BigDataCloudGeocodeProvider.class); // 日志

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器

    /**
     * 逆地理编码地址模板
    */
    private static final String REVERSE_URL =
            "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%s&longitude=%s&localityLanguage=zh";

    /**
     * 缓存有效期（毫秒）：24 小时
    */
    private static final long CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L;

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126 Safari/537.36";

    private final IpApiLocationProvider locationProvider = new IpApiLocationProvider(); // 位置提供者

    /**
     * 逆编码缓存（"lat,lon" -> 地址）
    */
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> cachedAt = new ConcurrentHashMap<>(); // 缓存at

    @Override
    public String name() {
        return "bigdatacloud";
    }

    @Override
    public String reverseGeocode(double latitude, double longitude) {
        String key = latitude + "," + longitude;
        Long ts = cachedAt.get(key);
        if (ts != null && System.currentTimeMillis() - ts < CACHE_TTL_MILLIS) {
            return cache.get(key);
        }
        try {
            String json = HttpClientFactory.of(String.format(REVERSE_URL, latitude, longitude))
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .get().getBodyString();
            JsonNode root = MAPPER.readTree(json);
            String address = buildAddress(root);
            if (address != null) {
                cache.put(key, address);
                cachedAt.put(key, System.currentTimeMillis());
            }
            return address;
        } catch (Exception e) {
            log.warn("[bigdatacloud] 逆地理编码失败: {}, {}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public String ipToAddress(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        LocationInfo info = locationProvider.locateIp(ip.trim());
        if (info == null) {
            return null;
        }
        // 优先经纬度逆编码；定位不到经纬度时回退行政信息拼接
        if (info.getLatitude() != null && info.getLongitude() != null) {
            String address = reverseGeocode(info.getLatitude(), info.getLongitude());
            if (address != null) {
                return address;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (info.getCity() != null && !info.getCity().isBlank()) {
            sb.append(info.getCity());
        }
        if (info.getRegion() != null && !info.getRegion().isBlank()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(info.getRegion());
        }
        if (info.getCountry() != null && !info.getCountry().isBlank()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(info.getCountry());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 按「区/市/省/国家」顺序拼接行政地址。
     *
     * @param root 逆编码响应
     * @return 地址；全部缺失时返回 空
     */
    private String buildAddress(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        append(sb, root.path("locality").asText(null));
        append(sb, root.path("city").asText(null));
        append(sb, root.path("principalSubdivision").asText(null));
        append(sb, root.path("countryName").asText(null));
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 拼接非空地址段。
     *
     * @param sb   拼接器
     * @param part 地址段
     */
    private void append(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(part);
    }
}
