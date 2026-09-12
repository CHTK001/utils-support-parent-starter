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
* H2 2.x 已移除内置全文检索引擎（文本 索引 / CATSEARCH），
* 检索门面降级为普通索引管理：索引通过 {@code CREATE INDEX} 创建，
* 关键字查询由调用方以 {@code LIKE} 方式执行。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public class H2MetaSearch extends AbstractMetaSearch {

    private final H2Engine engine; // engine
    private final H2SearchEngineImpl searchEngine; // 搜索engine

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
     /**
     * h2创建索引构建器类。
     *
     * @author CH
     * @since 4.0.0
      */
     * 搜索字段构建器impl类。
     *
      * @param type 类型
      * @return normalize字段类型的结果
      * @param mappings mappings
     */

    private class H2CreateIndexBuilder implements SearchIndexCreateBuilder {

        private final String indexName; // 索引名称
        private final List<SearchFieldDef> fields = new ArrayList<>(); // 字段
        private final Map<String, Object> settings = new LinkedHashMap<>(); // settings
        private int shards = 1; // shards
        private int replicas = 1; // replicas

        H2CreateIndexBuilder(String indexName) {
            this.indexName = indexName;
        /**
        * shards。
        * @param shards shards
        * @return shards的结果
         */
        }

        @Override
        public SearchIndexCreateBuilder shards(int shards) {
            this.shards = shards;
            return this;
        /**
        * replicas。
        * @param replicas replicas
        * @return replicas的结果
         */
        }

        @Override
        public SearchIndexCreateBuilder replicas(int replicas) {
            this.replicas = replicas;
            return this;
        /**
        * 字段。
        * @param name 名称
        * @param type 类型
        * @return 字段的结果
         */
        }

        @Override
        public SearchIndexCreateBuilder field(String name, String type) {
            SearchFieldDef field = new SearchFieldDef();
            field.setName(name);
            field.setType(normalizeFieldType(type));
            fields.add(field);
            return this;
        /**
        * 字段。
        * @param name 名称
        * @param type 类型
        * @param config 配置
        * @return 字段的结果
         */
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
        /**
        * 字段。
        * @param fields 字段
        * @return 字段的结果
         */
        }

        @Override
        public SearchIndexCreateBuilder fields(List<SearchFieldDef> fields) {
            this.fields.addAll(fields);
            return this;
        /**
        * settings。
        * @param settings settings
        * @return settings的结果
        * @author CH
        * @since 4.0.0
        * @param type 类型
        * @param mappings mappings
         */
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

    private static class SearchFieldBuilderImpl implements SearchFieldBuilder {
        private boolean indexed = true; // 索引
        private boolean stored = false; // 存储

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
