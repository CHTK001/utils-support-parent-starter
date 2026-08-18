package com.chua.common.support.datasearch.holiday.spi.impl;

import com.chua.common.support.datasearch.holiday.model.HolidayInfo;
import com.chua.common.support.datasearch.holiday.spi.HolidayProvider;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于公开 JSON 的法定节假日提供器（在线 API + 内置兜底）。
 *
 * <p>在线数据源默认使用 <a href="https://github.com/NateScarlet/holiday-cn">holiday-cn</a>
 * 的年度 JSON（结构：{@code "2026-01-01": {"name":"元旦","type":"holiday"}}）。
 * 当在线获取失败（如离线 / 网络受限）时，自动回退到内置的 2026 年官方放假安排，
 * 保证核心能力可用。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("online")
public class OnlineHolidayProvider implements HolidayProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(OnlineHolidayProvider.class);

    /** Default_url */
    private static final String DEFAULT_URL = "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/%d.json";

    /** Mapper */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, HolidayInfo> FALLBACK_2026 = build2026();

    /** URL模板 */
    private final String urlTemplate;

    /** HTTP客户端 */
    private final HttpClient httpClient;

    private final Map<Integer, Map<String, HolidayInfo>> cache = new ConcurrentHashMap<>();

    public OnlineHolidayProvider() {
        this(DEFAULT_URL);
    }

    /**
     * 构造一个指定数据源地址模板的提供器。
     *
     * @param urlTemplate 含 {@code %d} 年份占位符的 JSON 地址，如 {@code https://host/%d.json}
     */
    public OnlineHolidayProvider(String urlTemplate) {
        this.urlTemplate = urlTemplate;
        this.httpClient = HttpClientFactory.getClient();
    }

    @Override
    public String name() {
        return "online";
    }

    @Override
    public boolean isHoliday(LocalDate date) {
        HolidayInfo info = resolve(date);
        return info != null && "holiday".equals(info.getType());
    }

    @Override
    public boolean isWorkday(LocalDate date) {
        HolidayInfo info = resolve(date);
        if (info != null) {
            return "work".equals(info.getType());
        }
        DayOfWeek dw = date.getDayOfWeek();
        return dw != DayOfWeek.SATURDAY && dw != DayOfWeek.SUNDAY;
    }

    @Override
    public HolidayInfo getHoliday(LocalDate date) {
        return resolve(date);
    }

    @Override
    public List<HolidayInfo> getHolidays(int year) {
        return new ArrayList<>(load(year).values());
    }

    private HolidayInfo resolve(LocalDate date) {
        return load(date.getYear()).get(date.toString());
    }

    private Map<String, HolidayInfo> load(int year) {
        Map<String, HolidayInfo> cached = cache.get(year);
        if (cached != null) {
            return cached;
        }
        Map<String, HolidayInfo> map = new HashMap<>();
        try {
            String url = String.format(urlTemplate, year);
            ClientResponse resp = httpClient.get(url);
            if (resp.isSuccess()) {
                JsonNode root = MAPPER.readTree(resp.getBodyString());
                Iterator<Map.Entry<String, JsonNode>> it = root.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    JsonNode v = e.getValue();
                    if (v == null || v.isNull()) {
                        continue;
                    }
                    String name = text(v, "name");
                    String type = text(v, "type");
                    try {
                        map.put(e.getKey(), new HolidayInfo(LocalDate.parse(e.getKey()), name, type, "holiday".equals(type)));
                    } catch (Exception ignore) {
                        // 跳过非法日期键
                    }
                }
            }
        } catch (Exception e) {
            log.warn("节假日在线获取失败, 将使用内置数据: {}", e.getMessage());
        }
        // 内置兜底：2026 年官方安排始终合并，确保离线可用
        if (year == 2026) {
            map.putAll(FALLBACK_2026);
        }
        cache.put(year, map);
        return map;
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null ? "" : v.asText();
    }

    /**
     * 内置 2026 年法定节假日与调休补班（国务院办公厅 2025-11-04 发布）。
     */
    private static Map<String, HolidayInfo> build2026() {
        Map<String, HolidayInfo> m = new LinkedHashMap<>();
        add(m, "2026-01-01", "2026-01-03", "元旦", "holiday");
        add(m, "2026-02-15", "2026-02-23", "春节", "holiday");
        add(m, "2026-04-04", "2026-04-06", "清明节", "holiday");
        add(m, "2026-05-01", "2026-05-05", "劳动节", "holiday");
        add(m, "2026-06-19", "2026-06-21", "端午节", "holiday");
        add(m, "2026-09-25", "2026-09-27", "中秋节", "holiday");
        add(m, "2026-10-01", "2026-10-07", "国庆节", "holiday");

        add(m, "2026-01-04", "2026-01-04", "元旦调休上班", "work");
        add(m, "2026-02-14", "2026-02-14", "春节调休上班", "work");
        add(m, "2026-02-28", "2026-02-28", "春节调休上班", "work");
        add(m, "2026-05-09", "2026-05-09", "劳动节调休上班", "work");
        add(m, "2026-09-20", "2026-09-20", "国庆调休上班", "work");
        add(m, "2026-10-10", "2026-10-10", "国庆调休上班", "work");
        return m;
    }

    private static void add(Map<String, HolidayInfo> m, String start, String end, String name, String type) {
        LocalDate s = LocalDate.parse(start);
        LocalDate e = LocalDate.parse(end);
        for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1)) {
            m.put(d.toString(), new HolidayInfo(d, name, type, "holiday".equals(type)));
        }
    }
}
