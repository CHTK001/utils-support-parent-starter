package com.chua.solr.support.meta;

import com.chua.common.support.lang.datasource.meta.SearchFieldBuilder;
import com.chua.common.support.lang.datasource.meta.SearchIndexCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaSearch;
import com.chua.solr.support.engine.SolrEngine;
import org.apache.solr.client.solrj.SolrClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private final SolrSearchEngine searchEngine;

    public SolrMeta(AbstractMetaData metaData, SolrEngine engine) {
        super(metaData, engine);
        this.searchEngine = new SolrSearchEngine(engine);
    }

    @Override
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
    public SearchIndexDef get(String indexName) {
        return searchEngine.getIndex(indexName);
    }

    @Override
    public SearchIndexCreateBuilder create(String indexName) {
        return new SolrCreateIndexBuilder(indexName);
    }

    @Override
    public boolean drop(String indexName) {
        return searchEngine.deleteIndex(indexName);
    }

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

        private final String indexName;
        private final List<SearchFieldDef> fields = new ArrayList<>();
        private final Map<String, Object> settings = new LinkedHashMap<>();
        private final Map<String, Object> mappings = new LinkedHashMap<>();
        private int shards = 1;
        private int replicas = 1;

        SolrCreateIndexBuilder(String indexName) {
            this.indexName = indexName;
        }

        @Override
        public SearchIndexCreateBuilder shards(int shards) {
            this.shards = shards;
            return this;
        }

        @Override
        public SearchIndexCreateBuilder replicas(int replicas) {
            this.replicas = replicas;
            return this;
        }

        @Override
        public SearchIndexCreateBuilder field(String name, String type) {
            SearchFieldDef field = new SearchFieldDef();
            field.setName(name);
            field.setType(type);
            fields.add(field);
            return this;
        }

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
            this.mappings.putAll(mappings);
            return this;
        }

        @Override
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
        private String analyzer;
        private String searchAnalyzer;
        private boolean indexed = true;
        private boolean stored;
        private double weight = 1.0;

        @Override
        public SearchFieldBuilder analyzer(String analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        @Override
        public SearchFieldBuilder searchAnalyzer(String searchAnalyzer) {
            this.searchAnalyzer = searchAnalyzer;
            return this;
        }

        @Override
        public SearchFieldBuilder index(boolean indexed) {
            this.indexed = indexed;
            return this;
        }

        @Override
        public SearchFieldBuilder store(boolean stored) {
            this.stored = stored;
            return this;
        }

        @Override
        public SearchFieldBuilder keyword() {
            return this;
        }

        @Override
        public SearchFieldBuilder text() {
            return this;
        }

        @Override
        public SearchFieldBuilder integer() {
            return this;
        }

        @Override
        public SearchFieldBuilder longType() {
            return this;
        }

        @Override
        public SearchFieldBuilder floatType() {
            return this;
        }

        @Override
        public SearchFieldBuilder doubleType() {
            return this;
        }

        @Override
        public SearchFieldBuilder date() {
            return this;
        }

        @Override
        public SearchFieldBuilder bool() {
            return this;
        }

        @Override
        public SearchFieldBuilder object() {
            return this;
        }

        @Override
        public SearchFieldBuilder nested() {
            return this;
        }

        @Override
        public SearchFieldBuilder weight(double weight) {
            this.weight = weight;
            return this;
        }

        @Override
        public SearchFieldBuilder ignoreAbove(int ignoreAbove) {
            return this;
        }

        @Override
        public SearchFieldBuilder docValues(boolean docValues) {
            return this;
        }

        @Override
        public SearchFieldBuilder nullValue(String nullValue) {
            return this;
        }
    }
}
