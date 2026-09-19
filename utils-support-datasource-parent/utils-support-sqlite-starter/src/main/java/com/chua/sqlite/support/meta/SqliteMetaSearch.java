package com.chua.sqlite.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.SearchFieldBuilder;
import com.chua.common.support.lang.datasource.meta.SearchIndexCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaSearch;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * sqlite 搜索引擎元数据操作实现。
 * <p>
 * 基于 {@link SqliteSearchEngineImpl}，通过 FTS5 虚拟表管理索引。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SqliteMetaSearch extends AbstractMetaSearch {

    /**
     * 搜索引擎实现
    */
    private final SqliteSearchEngineImpl searchEngine;

    /**
     * 构造方法。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    public SqliteMetaSearch(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
        this.searchEngine = new SqliteSearchEngineImpl((com.chua.sqlite.support.engine.SqliteEngine) engine);
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
        return new SqliteCreateIndexBuilder(indexName);
    }

    @Override
    public boolean drop(String indexName) {
        return searchEngine.deleteIndex(indexName);
    }

    /**
     * FTS5 为同步索引：写入虚表后立即可被检索，不存在 ES/Solr 的近实时可见性窗口，
     * 因此本方法只做存在性校验，不对不存在的索引返回成功。
     *
     * @param indexName 索引名
     * @return 索引存在返回 true
     */
    @Override
    public boolean refresh(String indexName) {
        return searchEngine.getIndex(indexName) != null;
    }

    /**
     * 执行 FTS5 段落合并（{@code INSERT INTO 表(表) VALUES('optimize')}）。
     *
     * @param indexName 索引名
     * @return 索引存在并完成合并返回 true
     */
    @Override
    public boolean optimize(String indexName) {
        return searchEngine.optimizeIndex(indexName);
    }

    // ==================== 内部构建器 ====================
    /**
     * sqlite创建索引构建器类。
     *
     * @author CH
     * @since 4.0.0
     */

    private class SqliteCreateIndexBuilder implements SearchIndexCreateBuilder {

        private final String indexName; // 索引名称
        private final List<SearchFieldDef> fields = new ArrayList<>(); // 字段
        private final Map<String, Object> settings = new LinkedHashMap<>(); // settings
        /** FTS5 无法落实、已在 execute() 中统一告警的选项 */
        private final List<String> unsupported = new ArrayList<>(); // 不支持项
        private int shards = 1; // shards
        private int replicas = 1; // replicas

        SqliteCreateIndexBuilder(String indexName) {
            this.indexName = indexName;
        }

        @Override
        public SearchIndexCreateBuilder shards(int shards) {
            if (shards != this.shards) {
                unsupported.add("shards=" + shards + "（FTS5 索引只能落在单个 sqlite 文件内）");
            }
            this.shards = shards;
            return this;
        }

        @Override
        public SearchIndexCreateBuilder replicas(int replicas) {
            if (replicas != this.replicas) {
                unsupported.add("replicas=" + replicas + "（FTS5 索引没有副本概念）");
            }
            this.replicas = replicas;
            return this;
        }

        @Override
        public SearchIndexCreateBuilder field(String name, String type) {
            SearchFieldDef field = new SearchFieldDef();
            field.setName(name);
            field.setType(type == null ? "text" : type.toLowerCase());
            fields.add(field);
            return this;
        }

        @Override
        public SearchIndexCreateBuilder field(String name, String type, Consumer<SearchFieldBuilder> config) {
            SearchFieldDef field = new SearchFieldDef();
            field.setName(name);
            field.setType(type == null ? "text" : type.toLowerCase());
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
            this.settings.putAll(settings);
            return this;
        }

        @Override
        public SearchIndexCreateBuilder mappings(Map<String, Object> mappings) {
            if (mappings != null && !mappings.isEmpty()) {
                throw new UnsupportedOperationException(
                        "SQLite FTS5 虚表没有字段映射层，列一律按文本索引；请改用 field(...) 声明列，"
                                + "数值/日期条件请放在普通表列上，索引名: " + indexName);
            }
            return this;
        }

        @Override
        public SearchIndexDef execute() {
            List<SearchFieldDef> columns = new ArrayList<>(fields);
            for (SearchFieldDef column : columns) {
                if (column == null) {
                    continue;
                }
                String type = column.getType();
                if (!isTextType(type)) {
                    unsupported.add("字段 " + column.getName() + " 的类型 " + type
                            + "（FTS5 列没有类型，一律按文本分词）");
                    column.setType("text");
                }
            }
            if (!unsupported.isEmpty()) {
                log.warn("SQLite FTS5 索引 {} 无法落实以下设置，已按文本索引降级建表: {}", indexName, unsupported);
            }
            SearchIndexDef def = new SearchIndexDef();
            def.setName(indexName);
            def.setFields(columns);
            def.setSettings(new LinkedHashMap<>(settings));
            def.setShards(shards);
            def.setReplicas(replicas);
            searchEngine.createIndex(def);
            return def;
        }

        /**
         * 判断类型是否可直接由 FTS5 文本列承载
         *
         * @param type 类型
         * @return 文本族返回 true
         * @author CH
         * @since 4.0.0
         */
        private static boolean isTextType(String type) {
            if (type == null || type.isEmpty()) {
                return true;
            }
            return type.startsWith("text") || type.equals("string") || type.equals("keyword");
        }
    }

    private static class SearchFieldBuilderImpl implements SearchFieldBuilder {

        /** FTS5 无法落实的字段级选项，由外层构建器汇总告警 */
        private final List<String> unsupported = new ArrayList<>(); // 不支持项
        private boolean indexed = true; // 索引
        private boolean stored = false; // 存储
        private String type; // 类型

        /**
         * 登记一个 FTS5 无法表达的选项。
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
            return drop("analyzer=" + analyzer + "（FTS5 分词器是虚表级 tokenize 选项）");
        }

        @Override
        public SearchFieldBuilder searchAnalyzer(String searchAnalyzer) {
            return drop("searchAnalyzer=" + searchAnalyzer + "（FTS5 没有独立的检索期分词器）");
        }

        @Override
        public SearchFieldBuilder index(boolean indexed) {
            this.indexed = indexed;
            return this;
        }

        @Override
        public SearchFieldBuilder store(boolean stored) {
            this.stored = stored;
            return drop("store=" + stored + "（FTS5 始终保留列原文）");
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
            return this;
        }

        @Override
        public SearchFieldBuilder nested() {
            this.type = "nested";
            return this;
        }

        @Override
        public SearchFieldBuilder weight(double weight) {
            return drop("weight=" + weight + "（FTS5 排序须用 bm25() 在查询期指定）");
        }

        @Override
        public SearchFieldBuilder ignoreAbove(int ignoreAbove) {
            return drop("ignoreAbove=" + ignoreAbove);
        }

        @Override
        public SearchFieldBuilder docValues(boolean docValues) {
            return drop("docValues=" + docValues + "（FTS5 没有列存概念）");
        }

        @Override
        public SearchFieldBuilder nullValue(String nullValue) {
            return drop("nullValue=" + nullValue);
        }
    }
}
