package com.chua.h2.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.SearchFieldBuilder;
import com.chua.common.support.lang.datasource.meta.SearchIndexCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaSearch;
import com.chua.h2.support.engine.H2Engine;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * H2 搜索引擎元数据操作实现（H2 2.x）。
 * <p>
 * H2 2.x 已移除内置全文检索引擎（文本索引 / CATSEARCH），检索门面降级为普通索引管理：
 * 一次 {@code create(索引名).execute()} 会在与被索引表同名（即索引名）的表上执行
 * {@code CREATE INDEX}，因此该表必须已存在；关键字查询由调用方以 {@code LIKE} 方式执行。
 * </p>
 * <p>
 * 分片、副本、索引设置、字段类型与字段级选项在普通索引上没有对应物，
 * 构建器不静默丢弃：链式调用继续可用，但会在 {@code execute()} 前汇总为一条告警。
 * 原始映射（mappings）会让返回的索引定义声称并不存在的结构，直接抛
 * {@link UnsupportedOperationException}。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class H2MetaSearch extends AbstractMetaSearch {

    /**
     * H2 引擎实例
     */
    private final H2Engine engine;

    /**
     * 搜索引擎实现
     */
    private final H2SearchEngineImpl searchEngine;

    /**
     * 构造方法。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    public H2MetaSearch(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
        this.engine = (H2Engine) engine;
        this.searchEngine = new H2SearchEngineImpl(this.engine);
    }

    @Override
    public List<SearchIndexDef> list() {
        List<String> names = searchEngine.listIndexes();
        List<SearchIndexDef> result = new ArrayList<>();
        for (String name : names) {
            SearchIndexDef def = searchEngine.getIndex(name);
            if (def != null) {
                result.add(def);
            }
        }
        return result;
    }

    @Override
    public SearchIndexDef get(String indexName) {
        return searchEngine.getIndex(indexName);
    }

    @Override
    public SearchIndexCreateBuilder create(String indexName) {
        return new H2CreateIndexBuilder(indexName);
    }

    @Override
    public boolean drop(String indexName) {
        return searchEngine.deleteIndex(indexName);
    }

    /**
     * 校验索引存在。
     * <p>
     * H2 的索引随事务提交同步更新，写入后立即可被查询计划使用，
     * 不存在 ES/Solr 的近实时可见性窗口，也就没有可供调用的刷新命令；
     * 因此本方法只做存在性校验，不对不存在的索引返回成功。
     * </p>
     *
     * @param indexName 索引名
     * @return 索引存在返回 true
     * @throws IllegalArgumentException 索引名为空
     * @throws IllegalStateException    查询索引元数据失败
     */
    @Override
    public boolean refresh(String indexName) {
        return searchEngine.indexExists(indexName);
    }

    /**
     * 重算统计信息（执行 H2 库级 {@code ANALYZE}）。
     * <p>
     * H2 索引由存储引擎自动维护，没有段合并命令；可执行的维护动作只有
     * {@code ANALYZE}（H2 2.x 已取消按表分析的语法，命令作用于整个库）。
     * </p>
     *
     * @param indexName 索引名
     * @return 索引存在并完成统计信息重算返回 true；索引不存在返回 false
     * @throws IllegalArgumentException 索引名为空
     * @throws IllegalStateException    执行 ANALYZE 失败
     */
    @Override
    public boolean optimize(String indexName) {
        return searchEngine.analyzeIndex(indexName);
    }

    // ==================== 内部构建器 ====================

    /**
     * H2 创建索引构建器类。
     * <p>
     * H2 普通索引只承载“哪些列被索引”，其余抽象层能力在 DDL 中无对应物，
     * 统一登记到 {@link #unsupported} 并在 {@link #execute()} 中一次性告警。
     * </p>
     *
     * @author CH
     * @since 4.0.0
     */
    private class H2CreateIndexBuilder implements SearchIndexCreateBuilder {

        /**
         * 索引名称
         */
        private final String indexName;

        /**
         * 字段定义列表
         */
        private final List<SearchFieldDef> fields = new ArrayList<>();

        /**
         * 索引设置
         */
        private final Map<String, Object> settings = new LinkedHashMap<>();

        /**
         * H2 普通索引无法落实、已在 execute() 中统一告警的选项
         */
        private final List<String> unsupported = new ArrayList<>();

        /**
         * 主分片数
         */
        private int shards = 1;

        /**
         * 副本数
         */
        private int replicas = 1;

        /**
         * 构造 H2 创建索引构建器。
         *
         * @param indexName 索引名称
         */
        H2CreateIndexBuilder(String indexName) {
            this.indexName = indexName;
        }

        @Override
        public SearchIndexCreateBuilder shards(int shards) {
            if (shards != this.shards) {
                unsupported.add("shards=" + shards + "（H2 索引只存在于单个数据库内，没有分片概念）");
            }
            this.shards = shards;
            return this;
        }

        @Override
        public SearchIndexCreateBuilder replicas(int replicas) {
            if (replicas != this.replicas) {
                unsupported.add("replicas=" + replicas + "（H2 索引没有副本概念）");
            }
            this.replicas = replicas;
            return this;
        }

        @Override
        public SearchIndexCreateBuilder field(String name, String type) {
            SearchFieldDef field = new SearchFieldDef();
            field.setName(name);
            field.setType(normalizeFieldType(type));
            fields.add(field);
            return this;
        }

        @Override
        public SearchIndexCreateBuilder field(String name, String type, Consumer<SearchFieldBuilder> config) {
            SearchFieldDef field = new SearchFieldDef();
            field.setName(name);
            field.setType(normalizeFieldType(type));
            if (config != null) {
                SearchFieldBuilderImpl builder = new SearchFieldBuilderImpl();
                config.accept(builder);
                field.setIndexed(builder.indexed);
                field.setStored(builder.stored);
                if (builder.type != null) {
                    field.setType(builder.type);
                }
                for (String option : builder.unsupported) {
                    unsupported.add("字段 " + name + " 的 " + option);
                }
            }
            fields.add(field);
            return this;
        }

        @Override
        public SearchIndexCreateBuilder fields(List<SearchFieldDef> fields) {
            this.fields.addAll(fields);
            return this;
        }

        @Override
        public SearchIndexCreateBuilder settings(Map<String, Object> settings) {
            if (settings != null) {
                for (Map.Entry<String, Object> entry : settings.entrySet()) {
                    unsupported.add("索引设置 " + entry.getKey() + "=" + entry.getValue()
                            + "（H2 普通索引没有设置项）");
                }
                this.settings.putAll(settings);
            }
            return this;
        }

        @Override
        public SearchIndexCreateBuilder mappings(Map<String, Object> mappings) {
            if (mappings != null && !mappings.isEmpty()) {
                throw new UnsupportedOperationException(
                        "H2 普通索引没有字段映射层，被索引列的类型与约束由表 DDL 决定；请改用 field(...) 声明索引列，"
                                + "需要全文检索请自建 FTS 表或使用 Elasticsearch/Lucene 门面，索引名: " + indexName);
            }
            return this;
        }

        @Override
        public SearchIndexDef execute() {
            for (SearchFieldDef field : fields) {
                if (field == null) {
                    continue;
                }
                if (!field.isIndexed()) {
                    unsupported.add("字段 " + field.getName() + " 声明 index=false（该列不纳入索引列，"
                            + "H2 索引列一旦建立即参与检索）");
                    continue;
                }
                String type = field.getType();
                if (type != null && !type.isEmpty() && !"text".equals(type)) {
                    unsupported.add("字段 " + field.getName() + " 的类型 " + type
                            + "（H2 索引不记录类型，类型由被索引表的列决定）");
                }
            }
            if (!unsupported.isEmpty()) {
                log.warn("H2 索引 {} 无法落实以下设置，仅按普通二级索引创建: {}", indexName, unsupported);
            }
            SearchIndexDef def = new SearchIndexDef();
            def.setName(indexName);
            def.setFields(new ArrayList<>(fields));
            def.setSettings(new LinkedHashMap<>(settings));
            def.setShards(shards);
            def.setReplicas(replicas);
            searchEngine.createIndex(def);
            // H2 索引不承载类型与设置，返回物理读回的定义，避免声称并不存在的信息
            SearchIndexDef physical = searchEngine.getIndex(indexName);
            if (physical == null) {
                throw new IllegalStateException("H2 索引创建后在 INFORMATION_SCHEMA 中不可见: " + indexName);
            }
            return physical;
        }

        /**
         * 归一化字段类型为空值时的缺省处理。
         *
         * @param type 类型
         * @return 归一化后的类型
         * @author CH
         * @since 4.0.0
         */
        private String normalizeFieldType(String type) {
            if (type == null) {
                return "text";
            }
            String t = type.toLowerCase();
            if (t.startsWith("text") || t.equals("string") || t.equals("keyword")) {
                return "text";
            }
            return t;
        }
    }

    /**
     * H2 字段构建器。
     * <p>
     * 只有“是否纳入索引列”与“声明的类型”能被落地，其余选项登记为无法落实并统一告警。
     * </p>
     *
     * @author CH
     * @since 4.0.0
     */
    private static class SearchFieldBuilderImpl implements SearchFieldBuilder {

        /**
         * H2 无法落实的字段级选项，由外层构建器汇总告警
         */
        private final List<String> unsupported = new ArrayList<>();

        /**
         * 是否纳入索引列
         */
        private boolean indexed = true;

        /**
         * 是否存储原文
         */
        private boolean stored;

        /**
         * 声明的字段类型
         */
        private String type;

        /**
         * 登记一个 H2 无法表达的选项。
         *
         * @param option 选项描述
         * @return 当前构建器
         */
        private SearchFieldBuilder drop(String option) {
            unsupported.add(option);
            return this;
        }

        @Override
        public SearchFieldBuilder analyzer(String analyzer) {
            return drop("analyzer=" + analyzer + "（H2 普通索引不分词，关键字检索由 LIKE 执行）");
        }

        @Override
        public SearchFieldBuilder searchAnalyzer(String searchAnalyzer) {
            return drop("searchAnalyzer=" + searchAnalyzer + "（H2 没有独立的检索期分词器）");
        }

        @Override
        public SearchFieldBuilder index(boolean indexed) {
            this.indexed = indexed;
            return this;
        }

        @Override
        public SearchFieldBuilder store(boolean stored) {
            this.stored = stored;
            return drop("store=" + stored + "（原文始终保存在表列中，索引不额外存储）");
        }

        @Override
        public SearchFieldBuilder keyword() {
            this.type = "keyword";
            return this;
        }

        @Override
        public SearchFieldBuilder text() {
            this.type = "text";
            return this;
        }

        @Override
        public SearchFieldBuilder integer() {
            this.type = "integer";
            return this;
        }

        @Override
        public SearchFieldBuilder longType() {
            this.type = "long";
            return this;
        }

        @Override
        public SearchFieldBuilder floatType() {
            this.type = "float";
            return this;
        }

        @Override
        public SearchFieldBuilder doubleType() {
            this.type = "double";
            return this;
        }

        @Override
        public SearchFieldBuilder date() {
            this.type = "date";
            return this;
        }

        @Override
        public SearchFieldBuilder bool() {
            this.type = "boolean";
            return this;
        }

        @Override
        public SearchFieldBuilder object() {
            this.type = "object";
            return drop("object 类型（H2 索引列不支持对象结构）");
        }

        @Override
        public SearchFieldBuilder nested() {
            this.type = "nested";
            return drop("nested 类型（H2 索引列不支持嵌套结构）");
        }

        @Override
        public SearchFieldBuilder weight(double weight) {
            return drop("weight=" + weight + "（H2 普通索引没有打分权重）");
        }

        @Override
        public SearchFieldBuilder ignoreAbove(int ignoreAbove) {
            return drop("ignoreAbove=" + ignoreAbove + "（H2 索引列长度由表列定义决定）");
        }

        @Override
        public SearchFieldBuilder docValues(boolean docValues) {
            return drop("docValues=" + docValues + "（H2 为行存索引，没有列存概念）");
        }

        @Override
        public SearchFieldBuilder nullValue(String nullValue) {
            return drop("nullValue=" + nullValue + "（空值语义由表列的 NOT NULL 约束决定）");
        }
    }
}
