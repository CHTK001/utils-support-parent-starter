package com.chua.elasticsearch.support.meta;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.chua.common.support.lang.datasource.meta.SearchFieldBuilder;
import com.chua.common.support.lang.datasource.meta.SearchIndexCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaSearch;
import com.chua.elasticsearch.support.engine.ElasticsearchEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /** Search引擎 */
    private final EsSearchEngineImpl searchEngine;

    /**
     * 创建 EsMeta 实例
     * @param metaData metaData
     * @param ElasticsearchEngine ElasticsearchEngine
     */
    public EsMeta(AbstractMetaData metaData, ElasticsearchEngine engine) {
        super(metaData, engine);
        this.searchEngine = new EsSearchEngineImpl(engine);
    }

    @Override
    /** List */
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
    /** 获取 */
    public SearchIndexDef get(String indexName) {
        return searchEngine.getIndex(indexName);
    }

    @Override
    /** 创建 */
    public SearchIndexCreateBuilder create(String indexName) {
        return new EsCreateIndexBuilder(indexName);
    }

    @Override
    /** Drop */
    public boolean drop(String indexName) {
        return searchEngine.deleteIndex(indexName);
    }

    @Override
    /** Refresh */
    public boolean refresh(String indexName) {
        try {
            ElasticsearchClient client = ((ElasticsearchEngine) engine).getClient();
            client.indices().refresh(r -> r.index(indexName));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    /** Optimize */
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

        /** 索引名称 */
        private final String indexName;
        /** Fields */
        private final List<SearchFieldDef> fields = new ArrayList<>();
        /** settings */
        private final Map<String, Object> settings = new LinkedHashMap<>();
        /** mappings */
        private final Map<String, Object> mappings = new LinkedHashMap<>();
        /** Shards */
        private int shards = 1;
        /** Replicas */
        private int replicas = 1;

        EsCreateIndexBuilder(String indexName) {
            this.indexName = indexName;
        }

        @Override
        /** Shards */
        public SearchIndexCreateBuilder shards(int shards) {
            this.shards = shards;
            return this;
        }

        @Override
        /** Replicas */
        public SearchIndexCreateBuilder replicas(int replicas) {
            this.replicas = replicas;
            return this;
        }

        @Override
        /** Field */
        public SearchIndexCreateBuilder field(String name, String type) {
            SearchFieldDef field = new SearchFieldDef();
            field.setName(name);
            field.setType(type);
            fields.add(field);
            return this;
        }

        @Override
        /** Field */
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
            }
            fields.add(field);
            return this;
        }

        @Override
        /** Fields */
        public SearchIndexCreateBuilder fields(List<SearchFieldDef> fields) {
            this.fields.addAll(fields);
            return this;
        }

        @Override
        /** Settings */
        public SearchIndexCreateBuilder settings(Map<String, Object> settings) {
            this.settings.putAll(settings);
            return this;
        }

        @Override
        /** Mappings */
        public SearchIndexCreateBuilder mappings(Map<String, Object> mappings) {
            this.mappings.putAll(mappings);
            return this;
        }

        @Override
        /** 执行 */
        public SearchIndexDef execute() {
            SearchIndexDef def = new SearchIndexDef();
            def.setName(indexName);
            def.setShards(shards);
            def.setReplicas(replicas);
            def.setFields(new ArrayList<>(fields));
            def.setSettings(new LinkedHashMap<>(settings));
            def.setMappings(new LinkedHashMap<>(mappings));
            searchEngine.createIndex(def);
            return def;
        }
    }

    private static class SearchFieldBuilderImpl implements SearchFieldBuilder {
        /** Analyzer */
        private String analyzer;
        /** Searchanalyzer */
        private String searchAnalyzer;
        /** Indexed */
        private boolean indexed = true;
        /** Stored */
        private boolean stored;
        /** 权重 */
        private double weight = 1.0;

        @Override
        /** Analyzer */
        public SearchFieldBuilder analyzer(String analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        @Override
        /** 搜索Analyzer */
        public SearchFieldBuilder searchAnalyzer(String searchAnalyzer) {
            this.searchAnalyzer = searchAnalyzer;
            return this;
        }

        @Override
        /** Index */
        public SearchFieldBuilder index(boolean indexed) {
            this.indexed = indexed;
            return this;
        }

        @Override
        /** Store */
        public SearchFieldBuilder store(boolean stored) {
            this.stored = stored;
            return this;
        }

        @Override
        /** Keyword */
        public SearchFieldBuilder keyword() {
            return this;
        }

        @Override
        /** Text */
        public SearchFieldBuilder text() {
            return this;
        }

        @Override
        /** Integer */
        public SearchFieldBuilder integer() {
            return this;
        }

        @Override
        /** LongType */
        public SearchFieldBuilder longType() {
            return this;
        }

        @Override
        /** FloatType */
        public SearchFieldBuilder floatType() {
            return this;
        }

        @Override
        /** DoubleType */
        public SearchFieldBuilder doubleType() {
            return this;
        }

        @Override
        /** Date */
        public SearchFieldBuilder date() {
            return this;
        }

        @Override
        /** Bool */
        public SearchFieldBuilder bool() {
            return this;
        }

        @Override
        /** Object */
        public SearchFieldBuilder object() {
            return this;
        }

        @Override
        /** Nested */
        public SearchFieldBuilder nested() {
            return this;
        }

        @Override
        /** Weight */
        public SearchFieldBuilder weight(double weight) {
            this.weight = weight;
            return this;
        }

        @Override
        /** IgnoreAbove */
        public SearchFieldBuilder ignoreAbove(int ignoreAbove) {
            return this;
        }

        @Override
        /** DocValues */
        public SearchFieldBuilder docValues(boolean docValues) {
            return this;
        }

        @Override
        /** NullValue */
        public SearchFieldBuilder nullValue(String nullValue) {
            return this;
        }
    }
}
