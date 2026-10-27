package com.chua.solr.support.meta;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.datasource.meta.SearchFieldBuilder;
import com.chua.common.support.lang.datasource.meta.SearchIndexCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaSearch;
import com.chua.solr.support.engine.SolrEngine;
import org.apache.solr.client.solrj.SolrClient;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Solr 元数据操作实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SolrMeta extends AbstractMetaSearch {

    /**
     * 搜索引擎
    */
    private final SolrSearchEngine searchEngine;

    /**
     * 创建 Solrmeta 实例
     * @param metaData meta数据
     * @param engine Solrengine
     */
    public SolrMeta(AbstractMetaData metaData, SolrEngine engine) {
        super(metaData, engine);
        this.searchEngine = new SolrSearchEngine(engine);
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
        return new SolrCreateIndexBuilder(indexName);
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
        SolrClient client = ((SolrEngine) engine).getClient();
        if (client == null) {
            return false;
        }
        try {
            client.commit(indexName, true, true, true);
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
        SolrClient client = ((SolrEngine) engine).getClient();
        if (client == null) {
            return false;
        }
        try {
            client.optimize(indexName, true, true, 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private class SolrCreateIndexBuilder implements SearchIndexCreateBuilder {

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

        SolrCreateIndexBuilder(String indexName) {
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
            return this;
        }

        /**
         * 文本
        */
        @Override
        public SearchFieldBuilder text() {
            return this;
        }

        /**
         * Integer
        */
        @Override
        public SearchFieldBuilder integer() {
            return this;
        }

        /**
         * long类型
        */
        @Override
        public SearchFieldBuilder longType() {
            return this;
        }

        /**
         * float类型
        */
        @Override
        public SearchFieldBuilder floatType() {
            return this;
        }

        /**
         * double类型
        */
        @Override
        public SearchFieldBuilder doubleType() {
            return this;
        }

        /**
         * 日期
        */
        @Override
        public SearchFieldBuilder date() {
            return this;
        }

        /**
         * Bool
        */
        @Override
        public SearchFieldBuilder bool() {
            return this;
        }

        /**
         * 对象
        */
        @Override
        public SearchFieldBuilder object() {
            return this;
        }

        /**
         * 嵌套
        */
        @Override
        public SearchFieldBuilder nested() {
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
            return this;
        }

        /**
         * doc值
        */
        @Override
        public SearchFieldBuilder docValues(boolean docValues) {
            return this;
        }

        /**
         * 空值
        */
        @Override
        public SearchFieldBuilder nullValue(String nullValue) {
            return this;
        }
    }
}
