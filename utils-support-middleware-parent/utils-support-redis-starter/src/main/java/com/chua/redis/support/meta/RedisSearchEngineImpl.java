package com.chua.redis.support.meta;

import com.chua.common.support.lang.datasource.meta.SearchEngine;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaSearch;
import com.chua.redis.support.engine.RediSearchEngine;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.util.SafeEncoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Redis redi搜索 搜索引擎实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RedisSearchEngineImpl implements SearchEngine {

    /**
     * FT.INFO 命令，用于读取索引信息并判断索引是否存在
     */
    private static final ProtocolCommand FT_INFO = () -> SafeEncoder.encode("FT.INFO");

    /**
     * FT.CREATE 命令，用于创建索引
     */
    private static final ProtocolCommand FT_CREATE = () -> SafeEncoder.encode("FT.CREATE");

    /**
     * FT.DROPINDEX 命令，用于删除索引
     */
    private static final ProtocolCommand FT_DROPINDEX = () -> SafeEncoder.encode("FT.DROPINDEX");

    /**
     * FT._LIST 命令，用于列出索引名
     */
    private static final ProtocolCommand FT_LIST = () -> SafeEncoder.encode("FT._LIST");

    /**
     * 单参数名称允许的字符：Redis 键名常见字符全集，不含空白与控制字符
     */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:\\-\\[\\]{}()*+?~^$|&%#@/]{1,128}");

    /**
     * {@code FT.CREATE} 已消费的索引设置键，其余键只告警不下发
     */
    private static final Set<String> KNOWN_SETTINGS =
            Set.of("on", "prefix", "filter", "language", "score", "temporary", "stopwords");

    /**
     * 引擎
    */
    private final RediSearchEngine engine;

    /**
     * 创建 redis搜索engineimpl 实例
     * @param engine engine
     */
    public RedisSearchEngineImpl(RediSearchEngine engine) {
        this.engine = engine;
    }

    @Override
    /**
     * 类型
    */
    public String type() {
        return "redis";
    }

    @Override
    /**
     * 列表索引
    */
    public List<String> listIndexes() {
        List<String> indexes = new ArrayList<>();
        try (Jedis jedis = engine.getPoolPublic(engine.getDefaultDataSourceName()).getResource()) {
            Object response = jedis.sendCommand(FT_LIST);
            if (response == null) {
                return indexes;
            }
            if (!(response instanceof List<?> list)) {
                throw new IllegalStateException("列出 RedisSearch 索引失败: FT._LIST 回复结构非预期 ("
                        + response.getClass().getName() + ")");
            }
            for (Object item : list) {
                String name = stringValue(item);
                if (name != null && !name.isEmpty()) {
                    indexes.add(name);
                }
            }
        } catch (JedisDataException | JedisConnectionException e) {
            throw new IllegalStateException("列出 RedisSearch 索引失败, 原因: " + e.getMessage()
                    + (isUnknownCommandReply(e.getMessage()) ? "（服务端未加载 RediSearch 模块）" : ""), e);
        }
        return indexes;
    }

    @Override
    /**
     * 获取索引
    */
    public SearchIndexDef getIndex(String indexName) {
        requireIndexName(indexName);
        Object response = ftInfo(indexName);
        if (response == null) {
            return null;
        }
        if (!(response instanceof List<?> infoList)) {
            throw new IllegalStateException("获取 RedisSearch 索引定义失败: " + indexName + ", FT.INFO 回复结构非预期");
        }
        SearchIndexDef def = new SearchIndexDef();
        def.setName(indexName);
        def.setFields(parseAttributes(infoList));
        def.setSettings(parseIndexSettings(infoList));
        return def;
    }

    /**
     * 解析 FT.INFO 回复中的 attributes 段。
     * <p>
     * RediSearch 2.x 把每个字段回成扁平的键值数组（{@code identifier/attribute/type/weight}），
     * 1.x 回成位置数组（{@code 名称 类型 权重}）；这里按段内容判别，两种形状都能读出正确的字段名与类型。
     * </p>
     *
     * @param infoList FT.INFO 回复
     * @return 索引字段列表
     */
    private static List<SearchFieldDef> parseAttributes(List<?> infoList) {
        List<SearchFieldDef> fields = new ArrayList<>();
        List<?> attrs = flatValue(infoList, "attributes");
        if (attrs == null) {
            return fields;
        }
        for (Object attr : attrs) {
            if (!(attr instanceof List<?> attrList) || attrList.isEmpty()) {
                continue;
            }
            SearchFieldDef field = new SearchFieldDef();
            Map<String, Object> kv = toFlatMap(attrList);
            if (kv.containsKey("type")) {
                String identifier = firstText(kv.get("attribute"), kv.get("identifier"));
                field.setName(identifier == null ? "" : identifier);
                field.setType(stringValue(kv.get("type")));
                String weight = stringValue(kv.get("weight"));
                if (weight != null) {
                    try {
                        field.setWeight(Double.parseDouble(weight.trim()));
                    } catch (NumberFormatException e) {
                        log.warn("RedisSearch 索引字段 {} 的 weight 回复非数值: {}，按默认权重处理",
                                field.getName(), weight);
                    }
                }
                if (kv.containsKey("noindex")) {
                    field.setIndexed(false);
                }
            } else {
                // 1.x 位置数组：名称 类型 [权重]
                field.setName(stringValue(attrList.get(0)));
                field.setType(attrList.size() > 1 ? stringValue(attrList.get(1)) : null);
                if (attrList.size() > 2) {
                    try {
                        field.setWeight(Double.parseDouble(stringValue(attrList.get(2)).trim()));
                    } catch (NumberFormatException | NullPointerException e) {
                        log.warn("RedisSearch 索引字段 {} 的 weight 回复非数值，按默认权重处理", field.getName());
                    }
                }
            }
            fields.add(field);
        }
        return fields;
    }

    /**
     * 解析 FT.INFO 回复中的 index_definition 段。
     * <p>
     * 只回填 RediSearch 真正保存的键类型与键前缀，抽象层没有的选项不凭空补值。
     * </p>
     *
     * @param infoList FT.INFO 回复
     * @return 索引设置，缺段时返回空 Map
     */
    private static Map<String, Object> parseIndexSettings(List<?> infoList) {
        Map<String, Object> settings = new LinkedHashMap<>();
        List<?> definition = flatValue(infoList, "index_definition");
        if (definition == null) {
            return settings;
        }
        Map<String, Object> kv = toFlatMap(definition);
        String keyType = stringValue(kv.get("key_type"));
        if (keyType != null) {
            settings.put("on", keyType);
        }
        if (kv.get("prefixes") instanceof List<?> prefixes) {
            List<String> values = new ArrayList<>();
            for (Object prefix : prefixes) {
                values.add(stringValue(prefix));
            }
            settings.put("prefix", values);
        }
        String filter = stringValue(kv.get("filters"));
        if (filter != null) {
            settings.put("filter", filter);
        }
        return settings;
    }

    /**
     * 判断 RediSearch 索引是否存在。
     * <p>
     * RediSearch 对不存在的索引回 {@code Unknown Index name} / {@code no such index} 错误，
     * 这里据此区分"索引不存在"与"命令不可用"：后者（服务端未加载 RediSearch 模块、
     * 连接失败等）抛 {@link IllegalStateException}，不伪装成索引不存在。
     * </p>
     *
     * @param indexName 索引名，不能为空
     * @return 索引存在返回 true
     * @throws IllegalArgumentException 索引名为空
     * @throws IllegalStateException    命令不可用或执行失败
     */
    public boolean indexExists(String indexName) {
        requireIndexName(indexName);
        return ftInfo(indexName) != null;
    }

    @Override
    /**
     * 创建索引
    */
    public boolean createIndex(SearchIndexDef indexDef) {
        if (indexDef == null || indexDef.getName() == null || indexDef.getName().isBlank()) {
            throw new IllegalArgumentException("索引定义与索引名不能为空");
        }
        String indexName = indexDef.getName();
        Map<String, Object> settings = indexDef.getSettings();
        List<byte[]> argv = new ArrayList<>();
        argv.add(enc(token(indexName, "索引名")));
        argv.add(enc("ON"));
        argv.add(enc(keyType(settings)));
        appendIndexSettings(argv, indexName, settings);
        if (indexDef.getFields() == null || indexDef.getFields().isEmpty()) {
            throw new IllegalArgumentException("RedisSearch 索引至少需要一个字段, 索引名: " + indexName);
        }
        argv.add(enc("SCHEMA"));
        for (SearchFieldDef field : indexDef.getFields()) {
            appendField(argv, indexName, field);
        }
        try (Jedis jedis = engine.getPoolPublic(engine.getDefaultDataSourceName()).getResource()) {
            Object reply = jedis.sendCommand(FT_CREATE, argv.toArray(new byte[0][]));
            // FT.CREATE 成功回 +OK，失败由 Jedis 抛 JedisDataException
            return "OK".equalsIgnoreCase(stringValue(reply));
        } catch (JedisDataException | JedisConnectionException e) {
            throw new IllegalStateException("创建 RedisSearch 索引失败: " + indexName + ", 原因: " + e.getMessage(), e);
        }
    }

    @Override
    /**
     * 删除索引
    */
    public boolean deleteIndex(String indexName) {
        requireIndexName(indexName);
        if (!indexExists(indexName)) {
            return false;
        }
        byte[] name = enc(token(indexName, "索引名"));
        try (Jedis jedis = engine.getPoolPublic(engine.getDefaultDataSourceName()).getResource()) {
            jedis.sendCommand(FT_DROPINDEX, name);
            return true;
        } catch (JedisDataException | JedisConnectionException e) {
            throw new IllegalStateException("删除 RedisSearch 索引失败: " + indexName + ", 原因: " + e.getMessage(), e);
        }
    }

    /**
     * 解析索引键类型。
     *
     * @param settings 索引设置
     * @return {@code HASH} 或 {@code JSON}，缺省 {@code HASH}
     * @throws IllegalArgumentException 取值非 HASH/JSON
     */
    private static String keyType(Map<String, Object> settings) {
        Object value = settings == null ? null : settings.get("on");
        if (value == null) {
            return "HASH";
        }
        String on = value.toString().trim().toUpperCase(Locale.ROOT);
        if (!"HASH".equals(on) && !"JSON".equals(on)) {
            throw new IllegalArgumentException("索引设置 on 只能是 HASH 或 JSON, 当前: " + value);
        }
        return on;
    }

    /**
     * 追加 RediSearch 支持的索引级设置。
     * <p>
     * 未识别的设置键只告警不落入命令，避免把抽象层的其它选项猜成 RediSearch 参数。
     * </p>
     *
     * @param argv      命令参数
     * @param indexName 索引名
     * @param settings  索引设置，可为空
     */
    private static void appendIndexSettings(List<byte[]> argv, String indexName, Map<String, Object> settings) {
        List<String> prefixes = new ArrayList<>();
        Object prefix = settings == null ? null : settings.get("prefix");
        if (prefix instanceof Collection<?> collection) {
            for (Object item : collection) {
                prefixes.add(token(String.valueOf(item), "prefix"));
            }
        } else if (prefix != null && !String.valueOf(prefix).isBlank()) {
            prefixes.add(token(String.valueOf(prefix), "prefix"));
        }
        if (prefixes.isEmpty()) {
            // RediSearch 要求索引必须有键前缀，缺省按 "索引名:" 约定生成
            prefixes.add(token(indexName + ":", "prefix"));
        }
        argv.add(enc("PREFIX"));
        argv.add(enc(Integer.toString(prefixes.size())));
        for (String item : prefixes) {
            argv.add(enc(item));
        }
        if (settings == null) {
            return;
        }
        Object filter = settings.get("filter");
        if (filter != null) {
            argv.add(enc("FILTER"));
            argv.add(enc(String.valueOf(filter)));
        }
        Object language = settings.get("language");
        if (language != null) {
            argv.add(enc("LANGUAGE"));
            argv.add(enc(token(String.valueOf(language), "language")));
        }
        Object score = settings.get("score");
        if (score != null) {
            argv.add(enc("SCORE"));
            argv.add(enc(numericText(score, "score")));
        }
        Object temporary = settings.get("temporary");
        if (temporary != null) {
            argv.add(enc("TEMPORARY"));
            argv.add(enc(numericText(temporary, "temporary")));
        }
        Object stopwords = settings.get("stopwords");
        if (stopwords != null) {
            List<String> words = new ArrayList<>();
            if (stopwords instanceof Collection<?> collection) {
                for (Object item : collection) {
                    words.add(token(String.valueOf(item), "stopwords"));
                }
            } else {
                for (String item : String.valueOf(stopwords).split(",")) {
                    if (!item.isBlank()) {
                        words.add(token(item.trim(), "stopwords"));
                    }
                }
            }
            argv.add(enc("STOPWORDS"));
            argv.add(enc(Integer.toString(words.size())));
            for (String item : words) {
                argv.add(enc(item));
            }
        }
        for (String key : settings.keySet()) {
            if (!KNOWN_SETTINGS.contains(key)) {
                log.warn("RedisSearch 索引 {} 的设置 {} 没有对应的 FT.CREATE 参数，已忽略", indexName, key);
            }
        }
    }

    /**
     * 追加单个字段的 SCHEMA 片段。
     *
     * @param argv      命令参数
     * @param indexName 索引名
     * @param field     字段定义
     */
    private static void appendField(List<byte[]> argv, String indexName, SearchFieldDef field) {
        if (field == null || field.getName() == null || field.getName().isBlank()) {
            throw new IllegalArgumentException("字段名不能为空, 索引名: " + indexName);
        }
        String type = redisType(field);
        argv.add(enc(token(field.getName(), "字段名")));
        argv.add(enc(type));
        if (!field.isIndexed()) {
            if ("TEXT".equals(type) || "TAG".equals(type)) {
                argv.add(enc("NOINDEX"));
            } else {
                throw new UnsupportedOperationException("RediSearch 的 " + type + " 字段没有 NOINDEX 选项"
                        + "（索引 " + indexName + " 的字段 " + field.getName() + "）");
            }
        }
        if ("TEXT".equals(type)) {
            if (field.getWeight() != 1.0) {
                argv.add(enc("WEIGHT"));
                argv.add(enc(Double.toString(field.getWeight())));
            }
        } else if (field.getWeight() != 1.0) {
            log.warn("RediSearch 索引 {} 的字段 {} 为 {} 类型，没有 WEIGHT 参数，权重 {} 已忽略",
                    indexName, field.getName(), type, field.getWeight());
        }
        if (field.getAnalyzer() != null) {
            log.warn("RediSearch 索引 {} 的字段 {} 没有字段级分词器，analyzer={} 已忽略；"
                    + "全文语言由索引级 settings.language 指定", indexName, field.getName(), field.getAnalyzer());
        }
    }

    /**
     * 把抽象层的字段类型映射为 RediSearch 字段类型。
     *
     * @param field 字段定义
     * @return RediSearch 字段类型
     * @throws UnsupportedOperationException 类型在 RediSearch 中没有对应物
     */
    private static String redisType(SearchFieldDef field) {
        String type = field.getType() == null ? "text" : field.getType().trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "text", "string" -> "TEXT";
            case "keyword", "tag", "boolean", "bool" -> "TAG";
            case "integer", "int", "long", "float", "double", "numeric", "number", "date" -> "NUMERIC";
            case "geo", "geopoint" -> "GEO";
            default -> throw new UnsupportedOperationException("RediSearch 无法表达字段类型 " + field.getType()
                    + "（字段 " + field.getName() + "）：可用类型为 text/keyword/integer/double/date/geo；"
                    + "object 与 nested 需要 JSON 文档索引（settings.on=JSON），"
                    + "vector 还需指定 type/algo/distance_metric/dim 等参数，本抽象层未承载");
        };
    }

    /**
     * 校验 RediSearch 单参数 token。
     * <p>
     * 索引名、字段名与前缀都会被原样拼成 RESP 参数，含空白或控制字符即可注入额外参数，
     * 因此一律拒绝。
     * </p>
     *
     * @param value 取值
     * @param label 名称，用于报错定位
     * @return 校验通过的取值
     * @throws IllegalArgumentException 取值为空、含空白/控制字符或超长
     */
    private static String token(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String name = value.trim();
        if (name.length() > 128 || !TOKEN.matcher(name).matches()) {
            throw new IllegalArgumentException(label + "含空白或非法字符: " + value);
        }
        return name;
    }

    /**
     * 校验数值型设置并转为字符串。
     *
     * @param value 设置值
     * @param label 名称
     * @return 数值文本
     * @throws IllegalArgumentException 取值不是数值
     */
    private static String numericText(Object value, String label) {
        if (value instanceof Number number) {
            return number.toString();
        }
        try {
            return Double.toString(Double.parseDouble(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("索引设置 " + label + " 必须是数值, 当前: " + value, e);
        }
    }

    /**
     * 取第一个非空文本。
     *
     * @param primary   首选值
     * @param secondary 备选值
     * @return 文本值，都为空时返回 {@code null}
     */
    private static String firstText(Object primary, Object secondary) {
        String text = stringValue(primary);
        return text == null || text.isEmpty() ? stringValue(secondary) : text;
    }

    /**
     * 解码 RESP 批量回复。
     * <p>
     * Jedis 的原生 {@code sendCommand} 回 {@code byte[]} 而非 {@code String}，
     * 两种形态都要处理，否则回复会被静默判空。
     * </p>
     *
     * @param value 回复元素
     * @return 文本值，非文本形态返回 {@code null}
     */
    private static String stringValue(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * 编码命令参数。
     *
     * @param value 文本值
     * @return RESP 批量参数
     */
    private static byte[] enc(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 在扁平键值数组中取出指定键后面的值列表。
     *
     * @param list 扁平键值数组
     * @param key  键名
     * @return 值列表，键不存在或值非数组时返回 {@code null}
     */
    private static List<?> flatValue(List<?> list, String key) {
        for (int i = 0; i + 1 < list.size(); i++) {
            if (key.equalsIgnoreCase(stringValue(list.get(i)))) {
                return list.get(i + 1) instanceof List<?> nested ? nested : null;
            }
        }
        return null;
    }

    /**
     * 把扁平键值数组转成有序 Map。
     * <p>
     * 末尾落单的开关键按 {@code null} 值收录，以便 {@code containsKey} 判定其存在。
     * </p>
     *
     * @param list 扁平键值数组
     * @return 键值映射，键统一为小写文本
     */
    private static Map<String, Object> toFlatMap(List<?> list) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i += 2) {
            String key = stringValue(list.get(i));
            if (key == null) {
                continue;
            }
            Object value = i + 1 < list.size() ? list.get(i + 1) : null;
            map.put(key.toLowerCase(Locale.ROOT), value);
        }
        return map;
    }

    @Override
    /**
     * 获取客户端
    */
    public Object getClient() {
        return engine.getPoolPublic(engine.getDefaultDataSourceName());
    }

    /**
     * 执行 FT.INFO 并返回原始回复。
     * <p>
     * RediSearch 对不存在的索引回错误而非空回复，这里把"索引不存在"识别为 {@code null}，
     * 其余失败（服务端未加载模块、连接不可用等）以 {@link IllegalStateException} 抛出。
     * </p>
     *
     * @param indexName 索引名
     * @return FT.INFO 回复；索引不存在返回 {@code null}
     * @throws IllegalStateException 命令不可用或执行失败
     */
    private Object ftInfo(String indexName) {
        try (Jedis jedis = engine.getPoolPublic(engine.getDefaultDataSourceName()).getResource()) {
            return jedis.sendCommand(FT_INFO, SafeEncoder.encode(indexName));
        } catch (JedisDataException e) {
            String reply = e.getMessage();
            if (isMissingIndexReply(reply)) {
                return null;
            }
            throw new IllegalStateException("访问 RedisSearch 索引失败: " + indexName + ", " + reply
                    + (isUnknownCommandReply(reply) ? "（服务端未加载 RediSearch 模块）" : ""), e);
        } catch (JedisConnectionException e) {
            throw new IllegalStateException("连接 Redis 失败，无法读取索引信息: " + indexName + ", 原因: " + e.getMessage(), e);
        }
    }

    /**
     * 校验索引名。
     *
     * @param indexName 索引名
     * @throws IllegalArgumentException 索引名为空
     */
    private static void requireIndexName(String indexName) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("索引名不能为空");
        }
    }

    /**
     * 判断错误回复是否表示索引不存在。
     *
     * @param reply 服务端错误回复
     * @return 索引不存在返回 true
     */
    private static boolean isMissingIndexReply(String reply) {
        if (reply == null) {
            return false;
        }
        String message = reply.toLowerCase(Locale.ROOT);
        return message.contains("unknown index") || message.contains("no such index");
    }

    /**
     * 判断错误回复是否表示命令不存在（服务端未加载 RediSearch 模块）。
     *
     * @param reply 服务端错误回复
     * @return 命令不存在返回 true
     */
    private static boolean isUnknownCommandReply(String reply) {
        if (reply == null) {
            return false;
        }
        String message = reply.toLowerCase(Locale.ROOT);
        return message.startsWith("err unknown command") || message.startsWith("unknown command");
    }
}
