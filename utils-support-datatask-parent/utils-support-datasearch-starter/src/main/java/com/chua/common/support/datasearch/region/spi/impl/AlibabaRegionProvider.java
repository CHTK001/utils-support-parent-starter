package com.chua.common.support.datasearch.region.spi.impl;

import com.chua.common.support.datasearch.region.model.RegionInfo;
import com.chua.common.support.datasearch.region.spi.RegionProvider;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于阿里云 DataV GeoAtlas 的行政区划提供器（在线 API）。
 *
 * <p>数据源：<a href="https://geo.datav.aliyun.com/areas_v3/bound/{code}_full.json">
 * DataV GeoAtlas</a>，覆盖省 / 市 / 区 / 街道四级，每年随民政部调整同步更新。
 *
 * <p>支持「构造设置几级数据」：通过 {@code new AlibabaRegionProvider(level)} 设定默认层级，
 * 亦可调用 {@link #getRegions(int)} / {@link #getTree(int)} 显式指定。
 * 节点按 adcode 在会话内缓存，重复查询不重复请求。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("alibaba")
public class AlibabaRegionProvider implements RegionProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(AlibabaRegionProvider.class);

    /** Base */
    private static final String BASE = "https://geo.datav.aliyun.com/areas_v3/bound/";

    /** Mapper */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 会话级缓存：adcode -> 下级列表
     */
    private static final Map<String, List<RegionInfo>> CACHE = new ConcurrentHashMap<>();

    /**
     * 默认层级（由构造决定）
     */
    private final int defaultLevel;

    /** HTTP客户端 */
    private final HttpClient httpClient;

    public AlibabaRegionProvider() {
        this(2);
    }

    /**
     * 构造一个指定默认层级的提供器。
     *
     * @param defaultLevel 默认最大层级（1~4），建议 2（省+市）以保证响应速度
     */
    public AlibabaRegionProvider(int defaultLevel) {
        this.defaultLevel = defaultLevel;
        this.httpClient = HttpClientFactory.getClient();
    }

    @Override
    public String name() {
        return "alibaba";
    }

    @Override
    public List<RegionInfo> getRegions() {
        return getRegions(defaultLevel);
    }

    @Override
    public List<RegionInfo> getRegions(int maxLevel) {
        RegionInfo root = getTree(maxLevel);
        List<RegionInfo> flat = new ArrayList<>();
        flatten(root, flat);
        return flat;
    }

    @Override
    public List<RegionInfo> getChildren(String parentAdcode) {
        String code = (parentAdcode == null || parentAdcode.isEmpty()) ? "100000" : parentAdcode;
        return fetchChildren(code);
    }

    @Override
    public RegionInfo getTree(int maxLevel) {
        RegionInfo root = new RegionInfo("100000", "中国", 0, "country", null, 0, 0);
        build(root, 0, Math.max(1, maxLevel));
        return root;
    }

    private void build(RegionInfo node, int cur, int max) {
        if (cur >= max) {
            node.setChildren(Collections.emptyList());
            return;
        }
        List<RegionInfo> kids = fetchChildren(node.getAdcode());
        node.setChildren(kids);
        for (RegionInfo k : kids) {
            build(k, cur + 1, max);
        }
    }

    private void flatten(RegionInfo node, List<RegionInfo> out) {
        if (node.getAdcode() != null && !"100000".equals(node.getAdcode()) && node.getLevel() > 0) {
            out.add(node);
        }
        if (node.getChildren() != null) {
            for (RegionInfo c : node.getChildren()) {
                flatten(c, out);
            }
        }
    }

    private List<RegionInfo> fetchChildren(String adcode) {
        List<RegionInfo> cached = CACHE.get(adcode);
        if (cached != null) {
            return cached;
        }
        String url = BASE + adcode + "_full.json";
        try {
            ClientResponse resp = httpClient.get(url);
            if (!resp.isSuccess()) {
                log.warn("行政区划请求失败: {} -> {}", url, resp.getStatusCode());
                return Collections.emptyList();
            }
            List<RegionInfo> list = parse(resp.getBodyString());
            CACHE.put(adcode, list);
            return list;
        } catch (Exception e) {
            log.warn("行政区划获取异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<RegionInfo> parse(String json) {
        List<RegionInfo> list = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                return list;
            }
            for (JsonNode f : features) {
                JsonNode p = f.get("properties");
                if (p == null) {
                    continue;
                }
                String adcode = text(p, "adcode");
                String name = text(p, "name");
                String lv = text(p, "level");
                double[] center = parseCenter(p.get("center"));
                list.add(new RegionInfo(adcode, name, mapLevel(lv), lv, null, center[0], center[1]));
            }
        } catch (Exception e) {
            log.warn("行政区划解析失败: {}", e.getMessage());
        }
        return list;
    }

    private static int mapLevel(String lv) {
        if (lv == null) {
            return 0;
        }
        switch (lv) {
            case "province":
                return 1;
            case "city":
                return 2;
            case "district":
                return 3;
            case "street":
                return 4;
            default:
                return 0;
        }
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null ? "" : v.asText();
    }

    private static double[] parseCenter(JsonNode c) {
        if (c == null || !c.isArray() || c.size() < 2) {
            return new double[]{0, 0};
        }
        return new double[]{c.get(0).asDouble(), c.get(1).asDouble()};
    }
}
