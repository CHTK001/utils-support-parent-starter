package com.chua.common.support.datasearch.idiom.spi.impl;

import com.chua.common.support.datasearch.idiom.model.IdiomInfo;
import com.chua.common.support.datasearch.idiom.spi.IdiomProvider;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
* 基于 chinese-xinhua 语料库的成语提供器（在线 JSON + 内置兜底）。
*
* <p>在线数据源：<a href="https://github.com/pwxcoo/chinese-xinhua">chinese-xinhua</a>
* 的 idiom.json（约 3 万词条），结构为 {@编码 {"word":..., "pinyin":..., "abbreviation":...,
* "derivation":..., "解释":..., "example":...}}。
*
* <p>首次查询时惰性拉取并建立词形索引，后续查询复用内存索引；
* 在线获取失败（离线 / 网络受限）时自动回退到内置常见成语，保证核心能力可用。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("chinese-xinhua")
public class OnlineIdiomProvider implements IdiomProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(OnlineIdiomProvider.class);

    /** 在线数据源地址 */
    private static final String DEFAULT_URL =
            "https://cdn.jsdelivr.net/gh/pwxcoo/chinese-xinhua@master/data/idiom.json";

    /** 映射器 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HTTP客户端 */
    private final HttpClient httpClient;

    /** 在线数据源地址模板 */
    private final String url;

    /** 内存索引：word -> 成语信息（惰性加载） */
    private volatile Map<String, IdiomInfo> index = Collections.emptyMap();

    /** 首字索引：首字符 -> 成语列表（惰性加载） */
    private volatile Map<String, List<IdiomInfo>> firstCharIndex = Collections.emptyMap();

    /** 内置兜底数据 */
    private static final List<IdiomInfo> FALLBACK = buildFallback();

    /** 创建 onlineidiom提供者 实例 */
    public OnlineIdiomProvider() {
        this(DEFAULT_URL);
    }

    /**
    * 构造一个指定数据源地址的提供器。
    *
    * @param url 成语 JSON 数据源地址
     */
    public OnlineIdiomProvider(String url) {
        this.url = url;
        this.httpClient = HttpClientFactory.getClient();
    }

    @Override
    /** 名称 */
    public String name() {
        return "chinese-xinhua";
    }

    @Override
    /** 获取Idiom */
    public IdiomInfo get(String word) {
        if (word == null || word.isBlank()) {
            return null;
        }
        return loadIndex().get(word.trim());
    }

    @Override
    /** 搜索 */
    public List<IdiomInfo> search(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        String kw = keyword.trim();
        List<IdiomInfo> result = new ArrayList<>();
        for (IdiomInfo info : loadIndex().values()) {
            if (contains(info.getWord(), kw) || contains(info.getExplanation(), kw) || contains(info.getPinyin(), kw)) {
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
    public IdiomInfo random() {
        Map<String, IdiomInfo> idx = loadIndex();
        if (idx.isEmpty()) {
            return null;
        }
        List<IdiomInfo> values = new ArrayList<>(idx.values());
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }

    @Override
    /** 成语接龙 */
    public List<IdiomInfo> chain(String text, int limit) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        String trimmed = text.trim();
        // 输入为单个汉字时直接作为首字；否则取末字
        String firstChar = trimmed.length() == 1 ? trimmed : trimmed.substring(trimmed.length() - 1);
        return findByFirstChar(firstChar, limit);
    }

    @Override
    /** 按首字查找 */
    public List<IdiomInfo> findByFirstChar(String firstChar, int limit) {
        if (firstChar == null || firstChar.isBlank()) {
            return Collections.emptyList();
        }
        List<IdiomInfo> candidates = loadFirstCharIndex().get(firstChar.trim().substring(0, 1));
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        if (limit <= 0 || candidates.size() <= limit) {
            return new ArrayList<>(candidates);
        }
        return new ArrayList<>(candidates.subList(0, limit));
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
    * 加载索引（惰性 + 在线失败回退内置）
    *
    * @return 加载索引的结果
     */
    private Map<String, IdiomInfo> loadIndex() {
        Map<String, IdiomInfo> cached = index;
        if (!cached.isEmpty()) {
            return cached;
        }
        synchronized (this) {
            if (!index.isEmpty()) {
                return index;
            }
            Map<String, IdiomInfo> map = new LinkedHashMap<>();
            try {
                ClientResponse resp = httpClient.get(url);
                if (resp.isSuccess()) {
                    JsonNode root = MAPPER.readTree(resp.getBodyString());
                    if (root.isArray()) {
                        for (JsonNode n : root) {
                            IdiomInfo info = parse(n);
                            if (info != null && info.getWord() != null && !info.getWord().isBlank()) {
                                map.put(info.getWord(), info);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[chinese-xinhua] 成语在线获取失败, 将使用内置数据: {}", e.getMessage());
            }
            if (map.isEmpty()) {
                for (IdiomInfo info : FALLBACK) {
                    map.put(info.getWord(), info);
                }
            }
            index = map;
            log.info("[chinese-xinhua] 成语索引加载完成: {}", map.size());
            return index;
        }
    }

    /**
    * 加载首字索引（基于已加载的词形索引）
    *
    * @return 加载第一个char索引的结果
     */
    private Map<String, List<IdiomInfo>> loadFirstCharIndex() {
        Map<String, List<IdiomInfo>> cached = firstCharIndex;
        if (!cached.isEmpty()) {
            return cached;
        }
        synchronized (this) {
            if (!firstCharIndex.isEmpty()) {
                return firstCharIndex;
            }
            Map<String, List<IdiomInfo>> map = new ConcurrentHashMap<>();
            for (IdiomInfo info : loadIndex().values()) {
                String word = info.getWord();
                if (word == null || word.isEmpty()) {
                    continue;
                }
                String first = word.substring(0, 1);
                map.computeIfAbsent(first, k -> new ArrayList<>()).add(info);
            }
            firstCharIndex = map;
            log.info("[chinese-xinhua] 成语首字索引加载完成: {} 个首字", map.size());
            return firstCharIndex;
        }
    }

    /**
    * 解析
    *
    * @param n n
    * @return 解析的结果
     */
    private static IdiomInfo parse(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        return new IdiomInfo(
                text(n, "word"),
                text(n, "pinyin"),
                text(n, "abbreviation"),
                text(n, "derivation"),
                text(n, "explanation"),
                text(n, "example")
        );
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
    * 内置常见成语兜底数据（在线不可用时的核心词条）。
    * @return 构建降级的结果
     */
    private static List<IdiomInfo> buildFallback() {
        List<IdiomInfo> list = new ArrayList<>();
        add(list, "守株待兔", "shǒu zhū dài tù", "szdt", "《韩非子·五蠹》", "比喻不主动努力，而存万一的侥幸心理，希望得到意外的收获。", "学习不能守株待兔，需要脚踏实地。");
        add(list, "亡羊补牢", "wáng yáng bǔ láo", "wybl", "《战国策·楚策四》", "羊丢失了才去修补羊圈。比喻出了问题以后想办法补救，可以防止继续受损失。", "出了问题不要紧，亡羊补牢为时未晚。");
        add(list, "画蛇添足", "huà shé tiān zú", "hstz", "《战国策·齐策二》", "画蛇时给蛇添上脚。比喻做了多余的事，非但无益，反而不合适。", "这篇文章到此已经很完整，再补充反而是画蛇添足。");
        add(list, "掩耳盗铃", "yǎn ěr dào líng", "yedl", "《吕氏春秋·自知》", "掩住自己的耳朵去偷铃铛。比喻自欺欺人。", "这种掩耳盗铃的做法骗得了别人骗不了自己。");
        add(list, "井底之蛙", "jǐng dǐ zhī wā", "jdzw", "《庄子·秋水》", "井底的蛙只能看到井口那么大的一块天。比喻见识短浅的人。", "只有走出舒适区，才能摆脱井底之蛙的局限。");
        add(list, "胸有成竹", "xiōng yǒu chéng zhú", "xycz", "宋·苏轼《文与可画筼筜谷偃竹记》", "原指画竹子要在心里有一幅竹子的形象。后比喻在做事之前已经拿定主意。", "经过充分准备，他答题时胸有成竹。");
        add(list, "对牛弹琴", "duì niú tán qín", "dntq", "汉·牟融《理惑论》", "比喻对不懂道理的人讲道理，对外行人说内行话。", "和他讲这些专业术语无异于对牛弹琴。");
        add(list, "卧薪尝胆", "wò xīn cháng dǎn", "wxcd", "《史记·越王勾践世家》", "睡觉睡在柴草上，吃饭睡觉都尝一尝苦胆。形容人刻苦自励，发奋图强。", "他卧薪尝胆多年，终于东山再起。");
        add(list, "闻鸡起舞", "wén jī qǐ wǔ", "wjqw", "《晋书·祖逖传》", "听到鸡叫就起来舞剑。比喻有志报国的人及时奋起。", "他坚持闻鸡起舞，每天清晨锻炼身体。");
        add(list, "程门立雪", "chéng mén lì xuě", "cmlx", "《宋史·杨时传》", "旧指学生恭敬受教。比喻尊师重道，诚心求学。", "程门立雪的典故至今仍是尊师重教的典范。");
        return list;
    }

    /** 添加兜底词条 */
    private static void add(List<IdiomInfo> list, String word, String pinyin, String abbreviation,
                            String derivation, String explanation, String example) {
        list.add(new IdiomInfo(word, pinyin, abbreviation, derivation, explanation, example));
    }
}
