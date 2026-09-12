package com.chua.common.support.datasearch.poetry.spi.impl;

import com.chua.common.support.datasearch.poetry.model.PoetryInfo;
import com.chua.common.support.datasearch.poetry.spi.PoetryProvider;
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
import java.util.concurrent.ThreadLocalRandom;

/**
* 基于 chinese-Poetry 全唐诗语料的诗词提供器（在线 JSON + 内置兜底）。
*
* <p>在线数据源：<a href="https://github.com/chinese-poetry/chinese-poetry">chinese-poetry</a>
* 全唐诗 {@code poet.tang.0.json}，结构为 {@code {"author":..., "title":..., "paragraphs":[...]}}，
* 单文件约 1000 首。默认加载前 5 个分卷文件（约 5000 首），也可通过构造指定分卷范围。
*
* <p>首次查询时惰性拉取并缓存，在线获取失败（离线 / 网络受限）时自动回退到内置经典名篇。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("chinese-poetry")
public class OnlinePoetryProvider implements PoetryProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(OnlinePoetryProvider.class);

    /** 在线数据源地址模板（%d 为分卷序号） */
    private static final String DEFAULT_URL_TEMPLATE =
            "https://cdn.jsdelivr.net/gh/chinese-poetry/chinese-poetry@master/全唐诗/poet.tang.%d.json";

    /** 默认分卷数 */
    private static final int DEFAULT_VOLUME_COUNT = 5;

    /** 映射器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HTTP客户端 */
    private final HttpClient httpClient;

    /** 在线数据源地址模板 */
    private final String urlTemplate;

    /** 加载的分卷数 */
    private final int volumeCount;

    /** 内存缓存（惰性加载） */
    private volatile List<PoetryInfo> cached = Collections.emptyList();

    /** 内置兜底数据 */
    private static final List<PoetryInfo> FALLBACK = buildFallback();

    /** 创建 onlinepoetry提供者 实例 */
    public OnlinePoetryProvider() {
        this(DEFAULT_URL_TEMPLATE, DEFAULT_VOLUME_COUNT);
    }

    /**
    * 构造一个指定数据源地址模板与分卷数的提供器。
    *
    * @param urlTemplate 含 {@code %d} 分卷占位符的地址，如 {@code https://host/poet.tang.%d.json}
    * @param volumeCount 加载的分卷数量（从 0 开始）
     */
    public OnlinePoetryProvider(String urlTemplate, int volumeCount) {
        this.urlTemplate = urlTemplate;
        this.volumeCount = Math.max(1, volumeCount);
        this.httpClient = HttpClientFactory.getClient();
    }

    @Override
    /** 名称 */
    public String name() {
        return "chinese-poetry";
    }

    @Override
    /** 随机 */
    public PoetryInfo random() {
        List<PoetryInfo> list = load();
        if (list.isEmpty()) {
            return null;
        }
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    @Override
    /** by作者 */
    public List<PoetryInfo> byAuthor(String author, int limit) {
        if (author == null || author.isBlank()) {
            return Collections.emptyList();
        }
        List<PoetryInfo> result = new ArrayList<>();
        for (PoetryInfo p : load()) {
            if (author.trim().equals(p.getAuthor())) {
                result.add(p);
                if (limit > 0 && result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    @Override
    /** 搜索 */
    public List<PoetryInfo> search(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        String kw = keyword.trim();
        List<PoetryInfo> result = new ArrayList<>();
        for (PoetryInfo p : load()) {
            if (contains(p.getTitle(), kw) || contains(p.getContent(), kw) || contains(p.getAuthor(), kw)) {
                result.add(p);
                if (limit > 0 && result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    /**
    * 判断是否包含关键词
    *
    * @param text 文本
    * @param kw kw
    * @return contains的结果
     */
    private static boolean contains(String text, String kw) {
        return text != null && text.contains(kw);
    }

    /**
    * 加载语料（惰性 + 在线失败回退内置）
    *
    * @return 加载的结果
     */
    private List<PoetryInfo> load() {
        List<PoetryInfo> result = cached;
        if (!result.isEmpty()) {
            return result;
        }
        synchronized (this) {
            if (!cached.isEmpty()) {
                return cached;
            }
            List<PoetryInfo> list = new ArrayList<>();
            for (int i = 0; i < volumeCount; i++) {
                try {
                    ClientResponse resp = httpClient.get(String.format(urlTemplate, i));
                    if (resp.isSuccess()) {
                        JsonNode root = MAPPER.readTree(resp.getBodyString());
                        if (root.isArray()) {
                            for (JsonNode n : root) {
                                PoetryInfo info = parse(n);
                                if (info != null) {
                                    list.add(info);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[chinese-poetry] 分卷 {} 获取失败: {}", i, e.getMessage());
                }
            }
            if (list.isEmpty()) {
                list.addAll(FALLBACK);
            }
            cached = list;
            log.info("[chinese-poetry] 诗词语料加载完成: {}", list.size());
            return cached;
        }
    }

    /**
    * 解析
    *
    * @param n n
    * @return 解析的结果
     */
    private static PoetryInfo parse(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        String title = text(n, "title");
        String author = text(n, "author");
        JsonNode paragraphsNode = n.get("paragraphs");
        List<String> paragraphs = new ArrayList<>();
        if (paragraphsNode != null && paragraphsNode.isArray()) {
            for (JsonNode p : paragraphsNode) {
                paragraphs.add(p.asText());
            }
        }
        if (title.isBlank() || author.isBlank()) {
            return null;
        }
        return new PoetryInfo(title, author, "唐代", paragraphs);
    }

    /**
    * 文本
    *
    * @param n n
    * @param k k
    * @return 文本的结果
     */
    private static String text(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null ? "" : v.asText();
    }

    /**
    * 内置经典名篇兜底数据（在线不可用时的核心词条）。
    * @return 构建降级的结果
     */
    private static List<PoetryInfo> buildFallback() {
        List<PoetryInfo> list = new ArrayList<>();
        add(list, "静夜思", "李白", "床前明月光，疑是地上霜。", "举头望明月，低头思故乡。");
        add(list, "登鹳雀楼", "王之涣", "白日依山尽，黄河入海流。", "欲穷千里目，更上一层楼。");
        add(list, "春晓", "孟浩然", "春眠不觉晓，处处闻啼鸟。", "夜来风雨声，花落知多少。");
        add(list, "悯农", "李绅", "锄禾日当午，汗滴禾下土。", "谁知盘中餐，粒粒皆辛苦。");
        add(list, "咏鹅", "骆宾王", "鹅，鹅，鹅，曲项向天歌。", "白毛浮绿水，红掌拨清波。");
        add(list, "望庐山瀑布", "李白", "日照香炉生紫烟，遥看瀑布挂前川。", "飞流直下三千尺，疑是银河落九天。");
        add(list, "相思", "王维", "红豆生南国，春来发几枝。", "愿君多采撷，此物最相思。");
        add(list, "江雪", "柳宗元", "千山鸟飞绝，万径人踪灭。", "孤舟蓑笠翁，独钓寒江雪。");
        return list;
    }

    /**
    * 添加兜底词条
    *
    * @param list 列表
    * @param title title
    * @param author 作者
    * @param lines 线
     */
    private static void add(List<PoetryInfo> list, String title, String author, String... lines) {
        List<String> paragraphs = new ArrayList<>();
        for (String line : lines) {
            paragraphs.add(line);
        }
        list.add(new PoetryInfo(title, author, "唐代", paragraphs));
    }
}
