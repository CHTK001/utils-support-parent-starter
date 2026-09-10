package com.chua.common.support.datasearch.horoscope.spi.impl;

import com.chua.common.support.datasearch.horoscope.model.HoroscopeInfo;
import com.chua.common.support.datasearch.horoscope.spi.HoroscopeProvider;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 基于在线接口的星座运势提供器（在线 API + 内置兜底）。
 *
 * <p>在线数据源使用 vvhan 星座运势接口（无需 key）：
 * {@code https://api.vvhan.com/api/horoscope?type=today&astro=白羊座}，
 * 返回结构 {@code {"success":true,"data":{"all":..., "love":..., ...}}}。
 *
 * <p>在线请求失败或接口不可达时，自动回退到内置的十二星座运势库，
 * 保证核心能力可用。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("vvhan")
public class OnlineHoroscopeProvider implements HoroscopeProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(OnlineHoroscopeProvider.class);

    /** 在线接口地址模板 */
    private static final String DEFAULT_URL_TEMPLATE =
            "https://api.vvhan.com/api/horoscope?type=%s&astro=%s";

    /** Mapper */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HTTP客户端 */
    private final HttpClient httpClient;

    /** 在线接口地址模板 */
    private final String urlTemplate;

    /** 十二星座列表 */
    private static final String[] SIGNS = {
            "白羊座", "金牛座", "双子座", "巨蟹座", "狮子座", "处女座",
            "天秤座", "天蝎座", "射手座", "摩羯座", "水瓶座", "双鱼座"
    };

    /** 内置兜底运势库：sign -> type -> 运势 */
    private static final Map<String, Map<String, HoroscopeInfo>> FALLBACK = buildFallback();

    /** 创建 OnlineHoroscopeProvider 实例 */
    public OnlineHoroscopeProvider() {
        this(DEFAULT_URL_TEMPLATE);
    }

    /**
     * 构造一个指定接口地址模板的提供器。
     *
     * @param urlTemplate 含 {@code %s}（type）与 {@code %s}（sign）占位符的地址，
     *                    如 {@code https://host/api?type=%s&astro=%s}
     */
    public OnlineHoroscopeProvider(String urlTemplate) {
        this.urlTemplate = urlTemplate;
        this.httpClient = HttpClientFactory.getClient();
    }

    @Override
    /** Name */
    public String name() {
        return "vvhan";
    }

    @Override
    /** 获取运势 */
    public HoroscopeInfo get(String sign, String type) {
        if (sign == null || type == null) {
            return null;
        }
        String t = normalizeType(type);
        HoroscopeInfo online = fetchOnline(sign, t);
        if (online != null) {
            return online;
        }
        Map<String, HoroscopeInfo> bySign = FALLBACK.get(sign.trim());
        if (bySign == null) {
            return null;
        }
        return bySign.get(t);
    }

    /** 在线查询 */
    private HoroscopeInfo fetchOnline(String sign, String type) {
        try {
            ClientResponse resp = httpClient.get(String.format(urlTemplate, type, sign));
            if (!resp.isSuccess()) {
                return null;
            }
            JsonNode root = MAPPER.readTree(resp.getBodyString());
            JsonNode data = root.path("data");
            if (data == null || !data.isObject()) {
                return null;
            }
            return new HoroscopeInfo(sign, type,
                    num(data, "all"),
                    num(data, "love"),
                    num(data, "career"),
                    num(data, "wealth"),
                    num(data, "health"),
                    text(data, "luckyNumber"),
                    text(data, "luckyColor"),
                    text(data, "summary"));
        } catch (Exception e) {
            log.warn("[vvhan] 星座运势在线查询失败: {}", e.getMessage());
            return null;
        }
    }

    /** 归一化周期 */
    private static String normalizeType(String type) {
        switch (type.trim().toLowerCase()) {
            case "week":
            case "weekly":
            case "week_love":
                return "week";
            case "month":
            case "monthly":
                return "month";
            case "today":
            case "day":
            default:
                return "today";
        }
    }

    /** 读取数值指数 */
    private static int num(JsonNode n, String k) {
        JsonNode v = n.get(k);
        if (v == null || v.isNull()) {
            return 0;
        }
        if (v.isNumber()) {
            return v.asInt();
        }
        String s = v.asText().replaceAll("\\D+", "");
        return s.isEmpty() ? 0 : Integer.parseInt(s);
    }

    /** Text */
    private static String text(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null ? "" : v.asText();
    }

    /**
     * 内置十二星座运势兜底库（在线不可用时的核心数据）。
     */
    private static Map<String, Map<String, HoroscopeInfo>> buildFallback() {
        Map<String, Map<String, HoroscopeInfo>> map = new HashMap<>();
        put(map, "白羊座", 88, 85, 90, 80, 86, "3", "红色", "今天精力充沛，适合开启新计划，主动出击会有不错收获。");
        put(map, "金牛座", 82, 78, 85, 90, 84, "6", "绿色", "财运稳步提升，工作按部就班，保持耐心终有回报。");
        put(map, "双子座", 86, 90, 82, 78, 80, "5", "蓝色", "社交运旺盛，沟通顺畅，灵感频现，适合头脑风暴。");
        put(map, "巨蟹座", 80, 88, 76, 75, 82, "2", "白色", "家庭与感情和睦，宜关心家人，事业上稳中求进。");
        put(map, "狮子座", 90, 84, 92, 85, 88, "1", "金色", "领导力显现，适合主导项目，注意倾听他人意见。");
        put(map, "处女座", 84, 76, 88, 82, 90, "7", "紫色", "细节把控出色，适合精细工作，健康状态良好。");
        put(map, "天秤座", 85, 89, 80, 83, 84, "4", "粉色", "人缘极佳，合作运势强，适合谈判与签约。");
        put(map, "天蝎座", 87, 92, 84, 88, 82, "9", "黑色", "直觉敏锐，洞察力强，投资理财有意外惊喜。");
        put(map, "射手座", 89, 83, 86, 87, 86, "8", "橙色", "行动力十足，适合旅行与学习，好运常伴左右。");
        put(map, "摩羯座", 83, 74, 89, 86, 80, "5", "棕色", "事业运走高，脚踏实地必有回报，注意劳逸结合。");
        put(map, "水瓶座", 85, 80, 84, 79, 86, "4", "青色", "创意独特，适合创新项目，朋友聚会带来好心情。");
        put(map, "双鱼座", 86, 94, 78, 81, 84, "2", "海蓝色", "感情运势极佳，感性而浪漫，宜多表达心意。");
        return map;
    }

    /** 填充兜底运势库（today/week/month 使用同一条兜底数据） */
    private static void put(Map<String, Map<String, HoroscopeInfo>> map, String sign, int overall, int love,
                            int career, int wealth, int health, String luckyNumber, String luckyColor, String description) {
        Map<String, HoroscopeInfo> inner = new HashMap<>();
        inner.put("today", new HoroscopeInfo(sign, "today", overall, love, career, wealth, health, luckyNumber, luckyColor, description));
        inner.put("week", new HoroscopeInfo(sign, "week", overall, love, career, wealth, health, luckyNumber, luckyColor, description));
        inner.put("month", new HoroscopeInfo(sign, "month", overall, love, career, wealth, health, luckyNumber, luckyColor, description));
        map.put(sign, inner);
    }
}
