package com.chua.redis.support.meta;

import com.chua.common.support.lang.datasource.meta.SearchFieldBuilder;
import com.chua.common.support.lang.datasource.meta.SearchIndexCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaSearch;
import com.chua.redis.support.engine.RediSearchEngine;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Redis redi搜索 元数据操作实现。
 * <p>
 * 索引结构由 {@code FT.CREATE} 的 {@code ON HASH ... SCHEMA 字段 类型} 决定，
 * RediSearch 在写入命令内同步建索引，协议中没有刷新（refresh）与段合并（optimize）命令，
 * 因此 {@link #refresh(String)} 与 {@link #optimize(String)} 只做索引存在性校验。
 * </p>
 * <p>
 * 分片数、副本数、除 {@code prefix} 之外的索引设置以及字段级分词器、stored 等选项
 * 在 {@code FT.CREATE} 中没有对应物：链式调用继续可用，但会在 {@code execute()} 前
 * 汇总为一条告警；原始映射（mappings）会让索引定义声称并不存在的结构，直接抛
 * {@link UnsupportedOperationException}。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RedisSearchMeta extends AbstractMetaSearch {

    /**
     * 搜索引擎
    */
    private final RedisSearchEngineImpl searchEngine;

    /**
     * FT.CREATE 唯一会落实的索引设置项：文档键前缀
     */
    private static final String SETTINGS_PREFIX = "prefix";

    /**
     * 创建 redis搜索meta 实例
     * @param metaData meta数据
     * @param engine redi搜索engine
     * @param engine engine
     */
    public RedisSearchMeta(AbstractMetaData metaData, RediSearchEngine engine) {
        super(metaData, engine);
        this.searchEngine = new RedisSearchEngineImpl(engine);
    }

    @Override
    /**
     * 列表
    */
    public List<SearchIndexDef> list() {
        List<String> indexNames = searchEngine.listIndexes();
        List<SearchIndexDef> result = new ArrayList<>();
        for (String name : indexNames) {
            SearchIndexDef def = searchEngine.getIndex(name);
            if (def != null) {
                result.add(def);
            }
        }
        return result;
    }

    @Override
    /**
     * 获取
    */
    public SearchIndexDef get(String indexName) {
        return searchEngine.getIndex(indexName);
    }

    @Override
    /**
     * 创建
    */
    public SearchIndexCreateBuilder create(String indexName) {
        return new RedisSearchCreateIndexBuilder(indexName);
    }

    @Override
    /**
     * 掉落
    */
    public boolean drop(String indexName) {
        return searchEngine.deleteIndex(indexName);
    }

    /**
     * 刷新索引。
     * <p>
     * RediSearch 2.x 在写入命令内同步完成索引，文档落库后立即可被 FT.SEARCH 命中，
     * 不存在 Elasticsearch 的近实时可见性窗口，协议中也没有刷新命令；
     * 因此本方法只做存在性校验，不对不存在的索引返回成功。
     * </p>
     *
     * @param indexName 索引名
     * @return 索引存在返回 true
     * @throws IllegalArgumentException 索引名为空
     * @throws IllegalStateException    FT.INFO 不可用或执行失败（如服务端未加载 RediSearch 模块）
     */
    @Override
    public boolean refresh(String indexName) {
        return searchEngine.indexExists(indexName);
    }

    /**
     * 优化索引。
     * <p>
     * RediSearch 协议未向用户暴露段合并命令（1.x 的 FT.OPTIMIZE 已在 2.0 移除，
     * Jedis 的 SearchCommand 枚举中也不存在该命令），倒排索引的回收由模块后台 GC 按
     * {@code FT.CONFIG SET GCSIZE} 节流执行；因此本方法只做存在性校验，
     * 不对不存在的索引返回成功。
     * </p>
     *
     * @param indexName 索引名
     * @return 索引存在返回 true
     * @throws IllegalArgumentException 索引名为空
     * @throws IllegalStateException    FT.INFO 不可用或执行失败（如服务端未加载 RediSearch 模块）
     */
    @Override
    public boolean optimize(String indexName) {
        return searchEngine.indexExists(indexName);
    }

    private class RedisSearchCreateIndexBuilder implements SearchIndexCreateBuilder {

        /**
         * 索引名称
        */
        private final String indexName;
        /**
         * 字段
        */
        private final List<SearchFieldDef> fields = new ArrayList<>();
        /**
         * settings
        */
        private final Map<String, Object> settings = new LinkedHashMap<>();
        /**
         * FT.CREATE 无法落实、已在 execute() 中统一告警的选项
         */
        private final List<String> unsupported = new ArrayList<>();
        /**
         * Shards
        */
        private int shards = 1;
        /**
         * Replicas
        */
        private int replicas = 1;

        RedisSearchCreateIndexBuilder(String indexName) {
            this.indexName = indexName;
        }

        @Override
        /**
         * Shards
        */
        public SearchIndexCreateBuilder shards(int shards) {
            if (shards != this.shards) {
                unsupported.add("shards=" + shards + "（分区数由 Redis Enterprise 部署决定，FT.CREATE 不下发该参数）");
            }
            this.shards = shards;
            return this;
        }

        @Override
        /**
         * Replicas
        */
        public SearchIndexCreateBuilder replicas(int replicas) {
            if (replicas != this.replicas) {
                unsupported.add("replicas=" + replicas + "（副本数由 Redis Enterprise 部署决定，FT.CREATE 不下发该参数）");
            }
            this.replicas = replicas;
            return this;
        }

        @Override
        /**
         * 字段
        */
        public SearchIndexCreateBuilder field(String name, String type) {
            SearchFieldDef field = new SearchFieldDef();
            field.setName(name);
            field.setType(type);
            fields.add(field);
            return this;
        }

        @Override
        /**
         * 字段
        */
        public SearchIndexCreateBuilder field(String name, String type, Consumer<SearchFieldBuilder> config) {
            SearchFieldDef field = new SearchFieldDef();
            field.setName(name);
            field.setType(type);
            if (config != null) {
                SearchFieldBuilderImpl builder = new SearchFieldBuilderImpl();
                config.accept(builder);
                if (builder.weight != 1.0) {
                    field.setWeight(builder.weight);
                }
                for (String option : builder.unsupported) {
                    unsupported.add("字段 " + name + " 的 " + option);
                }
            }
            fields.add(field);
            return this;
        }

        @Override
        /**
         * 字段
        */
        public SearchIndexCreateBuilder fields(List<SearchFieldDef> fields) {
            for (SearchFieldDef field : fields) {
                if (field == null) {
                    continue;
                }
                if (!field.isIndexed()) {
                    unsupported.add("字段 " + field.getName() + " 的 indexed=false（FT.CREATE 不下发 NOINDEX）");
                }
                if (field.isStored()) {
                    unsupported.add("字段 " + field.getName() + " 的 stored=true（RediSearch 原文始终保存在 Hash 中）");
                }
                if (field.getAnalyzer() != null) {
                    unsupported.add("字段 " + field.getName() + " 的 analyzer=" + field.getAnalyzer()
                            + "（RediSearch 分词器是索引级 LANGUAGE 选项）");
                }
                if (field.getSearchAnalyzer() != null) {
                    unsupported.add("字段 " + field.getName() + " 的 searchAnalyzer=" + field.getSearchAnalyzer());
                }
            }
            this.fields.addAll(fields);
            return this;
        }

        @Override
        /**
         * Settings
        */
        public SearchIndexCreateBuilder settings(Map<String, Object> settings) {
            if (settings != null) {
                this.settings.putAll(settings);
            }
            return this;
        }

        @Override
        /**
         * Mappings
        */
        public SearchIndexCreateBuilder mappings(Map<String, Object> mappings) {
            if (mappings != null && !mappings.isEmpty()) {
                throw new UnsupportedOperationException(
                        "RediSearch 没有字段映射层，索引结构由 FT.CREATE 的 SCHEMA 决定；请改用 field(name, type)"
                                + "（type 取 TEXT/NUMERIC/TAG/GEO/VECTOR）声明字段，索引名: " + indexName);
            }
            return this;
        }

        @Override
        /**
         * 执行
         *
         * @return 执行的结果
         * @author CH
         * @since 4.0.0
         */
        public SearchIndexDef execute() {
            for (Map.Entry<String, Object> entry : settings.entrySet()) {
                if (!SETTINGS_PREFIX.equals(entry.getKey())) {
                    unsupported.add("索引设置 " + entry.getKey() + "=" + entry.getValue()
                            + "（FT.CREATE 仅支持 prefix 用于限定文档键前缀）");
                }
            }
            if (!unsupported.isEmpty()) {
                log.warn("RedisSearch 索引 {} 无法落实以下设置，FT.CREATE 已按剩余可用项执行: {}", indexName, unsupported);
            }
            SearchIndexDef def = new SearchIndexDef();
            def.setName(indexName);
            def.setShards(shards);
            def.setReplicas(replicas);
            def.setFields(new ArrayList<>(fields));
            def.setSettings(new LinkedHashMap<>(settings));
            searchEngine.createIndex(def);
            return def;
        }
    }

    private static class SearchFieldBuilderImpl implements SearchFieldBuilder {

        /**
         * FT.CREATE 未落实的字段级选项，由外层构建器汇总告警
         */
        private final List<String> unsupported = new ArrayList<>();
        /**
         * 权重
        */
        private double weight = 1.0;

        /**
         * 登记一个 FT.CREATE 未落实的选项。
         *
         * @param option 选项描述
         * @return 当前构建器
         */
        private SearchFieldBuilder drop(String option) {
            unsupported.add(option);
            return this;
        }

        @Override
        /**
         * Analyzer
        */
        public SearchFieldBuilder analyzer(String analyzer) {
            return drop("analyzer=" + analyzer + "（RediSearch 的分词由索引级 LANGUAGE 决定，字段级无独立分词器）");
        }

        @Override
        /**
         * 搜索Analyzer
        */
        public SearchFieldBuilder searchAnalyzer(String searchAnalyzer) {
            return drop("searchAnalyzer=" + searchAnalyzer + "（RediSearch 没有检索期分词器）");
        }

        @Override
        /**
         * 索引
        */
        public SearchFieldBuilder index(boolean indexed) {
            if (!indexed) {
                return drop("index=false（FT.CREATE 不下发 NOINDEX，该列仍会建立倒排）");
            }
            return this;
        }

        @Override
        /**
         * 存储
        */
        public SearchFieldBuilder store(boolean stored) {
            return drop("store=" + stored + "（RediSearch 原文始终保存在 Hash 文档中）");
        }

        @Override
        /**
         * Keyword
        */
        public SearchFieldBuilder keyword() {
            return drop("keyword 类型（RediSearch 精确匹配请用 field(name, \"TAG\")）");
        }

        @Override
        /**
         * 文本
        */
        public SearchFieldBuilder text() {
            return drop("text 类型（RediSearch 请在 field(name, \"TEXT\") 中直接给出）");
        }

        @Override
        /**
         * Integer
        */
        public SearchFieldBuilder integer() {
            return drop("integer 类型（RediSearch 请用 field(name, \"NUMERIC\")）");
        }

        @Override
        /**
         * long类型
        */
        public SearchFieldBuilder longType() {
            return drop("long 类型（RediSearch 请用 field(name, \"NUMERIC\")）");
        }

        @Override
        /**
         * float类型
        */
        public SearchFieldBuilder floatType() {
            return drop("float 类型（RediSearch 请用 field(name, \"NUMERIC\")）");
        }

        @Override
        /**
         * double类型
        */
        public SearchFieldBuilder doubleType() {
            return drop("double 类型（RediSearch 请用 field(name, \"NUMERIC\")）");
        }

        @Override
        /**
         * 日期
        */
        public SearchFieldBuilder date() {
            return drop("date 类型（RediSearch 请用 field(name, \"TAG\") 或 \"NUMERIC\" 时间戳）");
        }

        @Override
        /**
         * Bool
        */
        public SearchFieldBuilder bool() {
            return drop("boolean 类型（RediSearch 请用 field(name, \"TAG\")）");
        }

        @Override
        /**
         * 对象
        */
        public SearchFieldBuilder object() {
            return drop("object 类型（RediSearch 用 JSON 路径作为字段名，如 $.address.city）");
        }

        @Override
        /**
         * 嵌套
        */
        public SearchFieldBuilder nested() {
            return drop("nested 类型（RediSearch 没有嵌套映射，请用 JSON 路径字段）");
        }

        @Override
        /**
         * 权重
        */
        public SearchFieldBuilder weight(double weight) {
            this.weight = weight;
            return this;
        }

        @Override
        /**
         * ignoreabove
        */
        public SearchFieldBuilder ignoreAbove(int ignoreAbove) {
            return drop("ignoreAbove=" + ignoreAbove);
        }

        @Override
        /**
         * doc值
        */
        public SearchFieldBuilder docValues(boolean docValues) {
            return drop("docValues=" + docValues + "（RediSearch 数值/标签字段默认支持排序聚合）");
        }

        @Override
        /**
         * 空值
        */
        public SearchFieldBuilder nullValue(String nullValue) {
            return drop("nullValue=" + nullValue);
        }
    }
}
