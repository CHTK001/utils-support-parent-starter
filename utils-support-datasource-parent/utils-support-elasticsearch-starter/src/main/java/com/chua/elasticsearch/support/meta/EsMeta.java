package com.chua.elasticsearch.support.meta;

import com.chua.common.support.collection.wrap.Wraps;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.chua.common.support.lang.datasource.meta.SearchFieldBuilder;
import com.chua.common.support.lang.datasource.meta.SearchIndexCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaSearch;
import com.chua.elasticsearch.support.engine.ElasticsearchEngine;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Elasticsearch 元数据操作实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class EsMeta extends AbstractMetaSearch {

    /**
     * 搜索引擎
    */
    private final EsSearchEngineImpl searchEngine;

    /**
     * 创建 esmeta 实例
     * @param metaData meta数据
     * @param engine elasticsearchengine
     */
    public EsMeta(AbstractMetaData metaData, ElasticsearchEngine engine) {
        super(metaData, engine);
        this.searchEngine = new EsSearchEngineImpl(engine);
    }

    /**
     * 列表
    */
    @Override
    public List<SearchIndexDef> list() {
        List<String> indexNames = searchEngine.listIndexes();
        List<SearchIndexDef> result = Wraps.list();
        for (String name : indexNames) {
            SearchIndexDef def = searchEngine.getIndex(name);
            if (def != null) {
                result.add(def);
            }
        }
        return result;
    }

    /**
     * 获取
    */
    @Override
    public SearchIndexDef get(String indexName) {
        return searchEngine.getIndex(indexName);
    }

    /**
     * 创建
    */
    @Override
    public SearchIndexCreateBuilder create(String indexName) {
        return new EsCreateIndexBuilder(indexName);
    }

    /**
     * 掉落
    */
    @Override
    public boolean drop(String indexName) {
        return searchEngine.deleteIndex(indexName);
    }

    /**
     * Refresh
    */
    @Override
    public boolean refresh(String indexName) {
        try {
            ElasticsearchClient client = ((ElasticsearchEngine) engine).getClient();
            client.indices().refresh(r -> r.index(indexName));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 优化
     *
     * @param indexName 索引名称
     * @return 优化的结果
     * @author CH
     * @since 4.0.0
     */
    @Override
    public boolean optimize(String indexName) {
        try {
            ElasticsearchClient client = ((ElasticsearchEngine) engine).getClient();
            client.indices().forcemerge(f -> f.index(indexName).maxNumSegments(1L));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private class EsCreateIndexBuilder implements SearchIndexCreateBuilder {

        /**
         * 索引名称
        */
        private final String indexName;
        /**
         * 字段
        */
        private final List<SearchFieldDef> fields = Wraps.list();
        /**
         * settings
        */
        private final Map<String, Object> settings = Wraps.linkedMap();
        /**
         * mappings
        */
        private final Map<String, Object> mappings = Wraps.linkedMap();
        /**
         * Shards
        */
        private int shards = 1;
        /**
         * Replicas
        */
        private int replicas = 1;

        /**
         * Es Create Index Builder
         * @param indexName 字符串
         * @return 返回值
        */
        EsCreateIndexBuilder(String indexName) {
            this.indexName = indexName;
        }

        /**
         * Shards
        */
        @Override
        public SearchIndexCreateBuilder shards(int shards) {
            this.shards = shards;
            return this;
        }

        /**
         * Replicas
        */
        @Override
        public SearchIndexCreateBuilder replicas(int replicas) {
            this.replicas = replicas;
            return this;
        }

        /**
         * 字段
        */
        @Override
        public SearchIndexCreateBuilder field(String name, String type) {
            SearchFieldDef field = new SearchFieldDef();
            field.setName(name);
            field.setType(type);
            fields.add(field);
            return this;
        }

        /**
         * 字段
        */
        @Override
        public SearchIndexCreateBuilder field(String name, String type, Consumer<SearchFieldBuilder> config) {
            SearchFieldDef field = new SearchFieldDef();
            field.setName(name);
            field.setType(type);
            if (config != null) {
                SearchFieldBuilderImpl builder = new SearchFieldBuilderImpl();
                config.accept(builder);
                field.setAnalyzer(builder.analyzer);
                field.setSearchAnalyzer(builder.searchAnalyzer);
                field.setIndexed(builder.indexed);
                field.setStored(builder.stored);
                field.setWeight(builder.weight);
                field.setIgnoreAbove(builder.ignoreAbove);
                field.setNullValue(builder.nullValue);
                field.setDocValues(builder.docValues);
                if (builder.typeOverride != null) {
                    field.setType(builder.typeOverride);
                }
            }
            fields.add(field);
            return this;
        }

        /**
         * 字段
        */
        @Override
        public SearchIndexCreateBuilder fields(List<SearchFieldDef> fields) {
            this.fields.addAll(fields);
            return this;
        }

        /**
         * Settings
        */
        @Override
        public SearchIndexCreateBuilder settings(Map<String, Object> settings) {
            this.settings.putAll(settings);
            return this;
        }

        /**
         * Mappings
        */
        @Override
        public SearchIndexCreateBuilder mappings(Map<String, Object> mappings) {
            this.mappings.putAll(mappings);
            return this;
        }

        /**
         * 执行
         *
         * @return 执行的结果
         * @author CH
         * @since 4.0.0
         */
        @Override
        public SearchIndexDef execute() {
            SearchIndexDef def = new SearchIndexDef();
            def.setName(indexName);
            def.setShards(shards);
            def.setReplicas(replicas);
            def.setFields(Wraps.list(fields));
            def.setSettings(Wraps.linkedMap(settings));
            def.setMappings(Wraps.linkedMap(mappings));
            searchEngine.createIndex(def);
            return def;
        }
    }

    private static class SearchFieldBuilderImpl implements SearchFieldBuilder {
        /**
         * Analyzer
        */
        private String analyzer;
        /**
         * Searchanalyzer
        */
        private String searchAnalyzer;
        /**
         * 索引
        */
        private boolean indexed = true;
        /**
         * Stored
        */
        private boolean stored;
        /**
         * 权重
        */
        private double weight = 1.0;
        /**
         * 类型覆盖（keyword()/text() 等快捷方法设置）
         */
        private String typeOverride;
        /**
         * ignore_above 长度
         */
        private Integer ignoreAbove;
        /**
         * null_value 占位
         */
        private String nullValue;
        /**
         * doc_values 开关
         */
        private Boolean docValues;

        /**
         * Analyzer
        */
        @Override
        public SearchFieldBuilder analyzer(String analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        /**
         * 搜索Analyzer
        */
        @Override
        public SearchFieldBuilder searchAnalyzer(String searchAnalyzer) {
            this.searchAnalyzer = searchAnalyzer;
            return this;
        }

        /**
         * 索引
        */
        @Override
        public SearchFieldBuilder index(boolean indexed) {
            this.indexed = indexed;
            return this;
        }

        /**
         * 存储
        */
        @Override
        public SearchFieldBuilder store(boolean stored) {
            this.stored = stored;
            return this;
        }

        /**
         * Keyword
        */
        @Override
        public SearchFieldBuilder keyword() {
            this.typeOverride = "KEYWORD";
            return this;
        }

        /**
         * 文本
        */
        @Override
        public SearchFieldBuilder text() {
            this.typeOverride = "TEXT";
            return this;
        }

        /**
         * Integer
        */
        @Override
        public SearchFieldBuilder integer() {
            this.typeOverride = "INTEGER";
            return this;
        }

        /**
         * long类型
        */
        @Override
        public SearchFieldBuilder longType() {
            this.typeOverride = "LONG";
            return this;
        }

        /**
         * float类型
        */
        @Override
        public SearchFieldBuilder floatType() {
            this.typeOverride = "FLOAT";
            return this;
        }

        /**
         * double类型
        */
        @Override
        public SearchFieldBuilder doubleType() {
            this.typeOverride = "DOUBLE";
            return this;
        }

        /**
         * 日期
        */
        @Override
        public SearchFieldBuilder date() {
            this.typeOverride = "DATE";
            return this;
        }

        /**
         * Bool
        */
        @Override
        public SearchFieldBuilder bool() {
            this.typeOverride = "BOOLEAN";
            return this;
        }

        /**
         * 对象
        */
        @Override
        public SearchFieldBuilder object() {
            this.typeOverride = "OBJECT";
            return this;
        }

        /**
         * 嵌套
        */
        @Override
        public SearchFieldBuilder nested() {
            this.typeOverride = "NESTED";
            return this;
        }

        /**
         * 权重
        */
        @Override
        public SearchFieldBuilder weight(double weight) {
            this.weight = weight;
            return this;
        }

        /**
         * ignoreabove
        */
        @Override
        public SearchFieldBuilder ignoreAbove(int ignoreAbove) {
            this.ignoreAbove = ignoreAbove;
            return this;
        }

        /**
         * doc值
        */
        @Override
        public SearchFieldBuilder docValues(boolean docValues) {
            this.docValues = docValues;
            return this;
        }

        /**
         * 空值
        */
        @Override
        public SearchFieldBuilder nullValue(String nullValue) {
            this.nullValue = nullValue;
            return this;
        }
    }
}
