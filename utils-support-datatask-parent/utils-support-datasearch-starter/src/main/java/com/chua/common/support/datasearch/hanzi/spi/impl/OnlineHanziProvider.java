package com.chua.common.support.datasearch.hanzi.spi.impl;

import com.chua.common.support.datasearch.hanzi.model.HanziInfo;
import com.chua.common.support.datasearch.hanzi.spi.HanziProvider;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 基于 chinese-xinhua 字库的汉字字典提供器（在线 JSON + 内置兜底）。
 *
 * <p>在线数据源：<a href="https://github.com/pwxcoo/chinese-xinhua">chinese-xinhua</a>
 * 的 word.json（约 3 万字），结构为 {@code {"word":..., "oldword":..., "strokes":...,
 * "pinyin":..., "radicals":..., "explanation":..., "more":...}}。
 *
 * <p>首次查询时惰性拉取并建立字索引，后续查询复用内存索引；
 * 在线获取失败（离线 / 网络受限）时自动回退到内置常见汉字，保证核心能力可用。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("chinese-xinhua")
public class OnlineHanziProvider implements HanziProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(OnlineHanziProvider.class);

    /** 在线数据源地址 */
    private static final String DEFAULT_URL =
            "https://cdn.jsdelivr.net/gh/pwxcoo/chinese-xinhua@master/data/word.json";

    /** Mapper */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HTTP客户端 */
    private final HttpClient httpClient;

    /** 在线数据源地址 */
    private final String url;

    /** 内存索引：character -> 汉字信息（惰性加载） */
    private volatile Map<String, HanziInfo> index = Collections.emptyMap();

    /** 内置兜底数据 */
    private static final List<HanziInfo> FALLBACK = buildFallback();

    /** 创建 OnlineHanziProvider 实例 */
    public OnlineHanziProvider() {
        this(DEFAULT_URL);
    }

    /**
     * 构造一个指定数据源地址的提供器。
     *
     * @param url 汉字 JSON 数据源地址
     */
    public OnlineHanziProvider(String url) {
        this.url = url;
        this.httpClient = HttpClientFactory.getClient();
    }

    @Override
    /** Name */
    public String name() {
        return "chinese-xinhua";
    }

    @Override
    /** 获取Hanzi */
    public HanziInfo get(String character) {
        if (character == null || character.isBlank()) {
            return null;
        }
        return loadIndex().get(character.trim());
    }

    @Override
    /** 搜索 */
    public List<HanziInfo> search(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        String kw = keyword.trim();
        List<HanziInfo> result = new ArrayList<>();
        for (HanziInfo info : loadIndex().values()) {
            if (contains(info.getPinyin(), kw) || contains(info.getRadicals(), kw)
                    || contains(info.getExplanation(), kw) || contains(info.getMore(), kw)) {
                result.add(info);
                if (limit > 0 && result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    @Override
    /** 随机 */
    public HanziInfo random() {
        Map<String, HanziInfo> idx = loadIndex();
        if (idx.isEmpty()) {
            return null;
        }
        List<HanziInfo> values = new ArrayList<>(idx.values());
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }

    /** 判断是否包含关键词 */
    private static boolean contains(String text, String kw) {
        return text != null && text.contains(kw);
    }

    /** 加载索引（惰性 + 在线失败回退内置） */
    private Map<String, HanziInfo> loadIndex() {
        Map<String, HanziInfo> cached = index;
        if (!cached.isEmpty()) {
            return cached;
        }
        synchronized (this) {
            if (!index.isEmpty()) {
                return index;
            }
            Map<String, HanziInfo> map = new LinkedHashMap<>();
            try {
                ClientResponse resp = httpClient.get(url);
                if (resp.isSuccess()) {
                    JsonNode root = MAPPER.readTree(resp.getBodyString());
                    if (root.isArray()) {
                        for (JsonNode n : root) {
                            HanziInfo info = parse(n);
                            if (info != null && info.getCharacter() != null && !info.getCharacter().isBlank()) {
                                map.put(info.getCharacter(), info);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[chinese-xinhua] 汉字在线获取失败, 将使用内置数据: {}", e.getMessage());
            }
            if (map.isEmpty()) {
                for (HanziInfo info : FALLBACK) {
                    map.put(info.getCharacter(), info);
                }
            }
            index = map;
            log.info("[chinese-xinhua] 汉字索引加载完成: {}", map.size());
            return index;
        }
    }

    /** 解析 */
    private static HanziInfo parse(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        return new HanziInfo(
                text(n, "word"),
                text(n, "pinyin"),
                text(n, "radicals"),
                text(n, "strokes"),
                text(n, "explanation"),
                text(n, "more")
        );
    }

    /** Text */
    private static String text(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null ? "" : v.asText();
    }

    /**
     * 内置常见汉字兜底数据（在线不可用时的核心词条）。
     */
    private static List<HanziInfo> buildFallback() {
        List<HanziInfo> list = new ArrayList<>();
        add(list, "中", "zhōng", "丨", "4", "方位词，跟四周的距离相等；中心。", "中间、中心");
        add(list, "华", "huá", "十", "6", "光辉、光彩；指中华民族或中国。", "华夏、中华");
        add(list, "人", "rén", "人", "2", "能制造工具并使用工具进行劳动的高等动物。", "人民、人类");
        add(list, "水", "shuǐ", "水", "4", "最简单的氢氧化合物，化学式 H2O。", "河流、水面");
        add(list, "火", "huǒ", "火", "4", "物体燃烧时发出的光和焰。", "火焰、火光");
        add(list, "木", "mù", "木", "4", "树类植物的通称。", "树木、木材");
        add(list, "金", "jīn", "金", "8", "金属的通称；黄金。", "金属、金色");
        add(list, "土", "tǔ", "土", "3", "地面上的泥沙混合物。", "土地、泥土");
        add(list, "天", "tiān", "大", "4", "在地面以上的高空。", "天空、天气");
        add(list, "地", "dì", "土", "6", "地球表面人类生活的地方。", "大地、地方");
        return list;
    }

    /** 添加兜底词条 */
    private static void add(List<HanziInfo> list, String character, String pinyin, String radicals,
                            String strokes, String explanation, String more) {
        list.add(new HanziInfo(character, pinyin, radicals, strokes, explanation, more));
    }
}
