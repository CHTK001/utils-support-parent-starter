package com.chua.h2.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.SearchFieldBuilder;
import com.chua.common.support.lang.datasource.meta.SearchIndexCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaSearch;
import com.chua.h2.support.engine.H2Engine;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * H2 搜索引擎元数据操作实现（H2 2.x）。
 * <p>
 * H2 2.x 已移除内置全文检索引擎（TEXT INDEX / CATSEARCH），
 * 检索门面降级为普通索引管理：索引通过 {@code CREATE INDEX} 创建，
 * 关键字查询由调用方以 {@code LIKE} 方式执行。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class H2MetaSearch extends AbstractMetaSearch {

    private final H2Engine engine;
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
            if (def != null) result.add(def);
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

    @Override
    public boolean refresh(String indexName) {
        return true;
    }

    @Override
    public boolean optimize(String indexName) {
        // H2 索引自动维护，无需在线优化
        return true;
    }

    // ==================== 内部构建器 ====================

    private class H2CreateIndexBuilder implements SearchIndexCreateBuilder {

        private final String indexName;
        private final List<SearchFieldDef> fields = new ArrayList<>();
        private final Map<String, Object> settings = new LinkedHashMap<>();
        private int shards = 1;
        private int replicas = 1;

        H2CreateIndexBuilder(String indexName) {
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
            return this;
        }

        @Override
        public SearchIndexDef execute() {
            SearchIndexDef def = new SearchIndexDef();
            def.setName(indexName);
            def.setFields(new ArrayList<>(fields));
            def.setSettings(new LinkedHashMap<>(settings));
            searchEngine.createIndex(def);
            return def;
        }

        private String normalizeFieldType(String type) {
            if (type == null) return "text";
            String t = type.toLowerCase();
            if (t.startsWith("text") || t.equals("string") || t.equals("keyword")) {
                return "text";
            }
            return t;
        }
    }

    private static class SearchFieldBuilderImpl implements SearchFieldBuilder {
        private boolean indexed = true;
        private boolean stored = false;

        @Override public SearchFieldBuilder analyzer(String analyzer) { return this; }
        @Override public SearchFieldBuilder searchAnalyzer(String searchAnalyzer) { return this; }
        @Override public SearchFieldBuilder index(boolean indexed) { this.indexed = indexed; return this; }
        @Override public SearchFieldBuilder store(boolean stored) { this.stored = stored; return this; }
        @Override public SearchFieldBuilder keyword() { return this; }
        @Override public SearchFieldBuilder text() { return this; }
        @Override public SearchFieldBuilder integer() { return this; }
        @Override public SearchFieldBuilder longType() { return this; }
        @Override public SearchFieldBuilder floatType() { return this; }
        @Override public SearchFieldBuilder doubleType() { return this; }
        @Override public SearchFieldBuilder date() { return this; }
        @Override public SearchFieldBuilder bool() { return this; }
        @Override public SearchFieldBuilder object() { return this; }
        @Override public SearchFieldBuilder nested() { return this; }
        @Override public SearchFieldBuilder weight(double weight) { return this; }
        @Override public SearchFieldBuilder ignoreAbove(int ignoreAbove) { return this; }
        @Override public SearchFieldBuilder docValues(boolean docValues) { return this; }
        @Override public SearchFieldBuilder nullValue(String nullValue) { return this; }
    }
}
