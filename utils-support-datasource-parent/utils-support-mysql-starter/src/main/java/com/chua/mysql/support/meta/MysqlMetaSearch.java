package com.chua.mysql.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.SearchFieldBuilder;
import com.chua.common.support.lang.datasource.meta.SearchIndexCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaSearch;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * MySQL 搜索引擎元数据操作实现，索引即表上的 FULLTEXT 索引。
 * <p>
 * MySQL 的全文索引必须挂在已存在的表上，创建时必须通过
 * {@code settings(Map.of(MysqlSearchEngineImpl.SETTING_TABLE, "表名"))} 指明目标表，
 * 需要中文分词时再带上 {@code SETTING_PARSER=ngram}。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MysqlMetaSearch extends AbstractMetaSearch {

    /**
     * 搜索引擎实现
     */
    private final MysqlSearchEngineImpl searchEngine;

    /**
     * 构造方法。
     *
     * @param metaData   元数据入口
     * @param engine     引擎实例
     * @param dataSource MySQL 数据源
     */
    public MysqlMetaSearch(AbstractMetaData metaData, Engine engine, DataSource dataSource) {
        super(metaData, engine);
        this.searchEngine = new MysqlSearchEngineImpl(dataSource);
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
        return new MysqlCreateIndexBuilder(indexName);
    }

    @Override
    public boolean drop(String indexName) {
        return searchEngine.deleteIndex(indexName);
    }

    /**
     * MySQL 全文索引在写入后即可被 {@code MATCH} 命中，没有近实时可见性窗口，
     * 因此本方法只做存在性校验。
     *
     * @param indexName 索引名
     * @return 索引存在返回 true
     */
    @Override
    public boolean refresh(String indexName) {
        return searchEngine.getIndex(indexName) != null;
    }

    /**
     * 通过 {@code OPTIMIZE TABLE} 合并索引所在表的全文倒排段。
     *
     * @param indexName 索引名
     * @return 索引存在并完成优化返回 true
     */
    @Override
    public boolean optimize(String indexName) {
        return searchEngine.optimizeIndex(indexName);
    }

    // ==================== 内部构建器 ====================

    /**
     * MySQL 全文索引创建构建器。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private class MysqlCreateIndexBuilder implements SearchIndexCreateBuilder {

        private final String indexName; // 索引名称
        private final List<SearchFieldDef> fields = new ArrayList<>(); // 字段
        private final Map<String, Object> settings = new LinkedHashMap<>(); // settings
        /** 无法落到 MySQL 全文索引、统一在 execute() 告警的选项 */
        private final List<String> unsupported = new ArrayList<>(); // 不支持项
        private int shards = 1; // shards
        private int replicas = 1; // replicas

        MysqlCreateIndexBuilder(String indexName) {
            this.indexName = indexName;
        }

        @Override
        public SearchIndexCreateBuilder shards(int shards) {
            if (shards != this.shards) {
                unsupported.add("shards=" + shards + "（全文索引随表存储，没有分片概念）");
            }
            this.shards = shards;
            return this;
        }

        @Override
        public SearchIndexCreateBuilder replicas(int replicas) {
            if (replicas != this.replicas) {
                unsupported.add("replicas=" + replicas + "（副本由 MySQL 复制拓扑决定）");
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
                MysqlFieldBuilderImpl builder = new MysqlFieldBuilderImpl();
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
                        "MySQL 全文索引直接建在表列上，没有独立映射；列类型由表结构决定，"
                                + "解析器请用 settings 的 " + MysqlSearchEngineImpl.SETTING_PARSER + " 指定，索引名: " + indexName);
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
                if (type == null || type.isEmpty()) {
                    column.setType("text");
                } else if (!type.startsWith("text") && !type.equals("string") && !type.equals("keyword")) {
                    unsupported.add("字段 " + column.getName() + " 的类型 " + type
                            + "（全文索引只能建在 CHAR/VARCHAR/TEXT 列上）");
                    column.setType("text");
                }
            }
            if (!unsupported.isEmpty()) {
                log.warn("MySQL 全文索引 {} 无法落实以下设置: {}", indexName, unsupported);
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
    }

    /**
     * MySQL 字段级构建器：只有列的纳入/排除可落实，其余为全文索引不支持项。
     *
     * @author CH
     * @since 4.0.0.42
     */
    private static class MysqlFieldBuilderImpl implements SearchFieldBuilder {

        /** 无法落实的字段级选项 */
        private final List<String> unsupported = new ArrayList<>(); // 不支持项
        private boolean indexed = true; // 索引
        private boolean stored = false; // 存储
        private String type; // 类型

        /**
         * 登记一个 MySQL 全文索引无法表达的选项。
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
            return drop("analyzer=" + analyzer + "（解析器是索引级 WITH PARSER，请用 settings 指定）");
        }

        @Override
        public SearchFieldBuilder searchAnalyzer(String searchAnalyzer) {
            return drop("searchAnalyzer=" + searchAnalyzer + "（MySQL 没有检索期分词器）");
        }

        @Override
        public SearchFieldBuilder index(boolean indexed) {
            this.indexed = indexed;
            return this;
        }

        @Override
        public SearchFieldBuilder store(boolean stored) {
            this.stored = stored;
            return drop("store=" + stored + "（列原文始终由表保存）");
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
            return drop("weight=" + weight + "（排序请用 MATCH() 相关度）");
        }

        @Override
        public SearchFieldBuilder ignoreAbove(int ignoreAbove) {
            return drop("ignoreAbove=" + ignoreAbove);
        }

        @Override
        public SearchFieldBuilder docValues(boolean docValues) {
            return drop("docValues=" + docValues + "（列存由存储引擎决定）");
        }

        @Override
        public SearchFieldBuilder nullValue(String nullValue) {
            return drop("nullValue=" + nullValue);
        }
    }
}
