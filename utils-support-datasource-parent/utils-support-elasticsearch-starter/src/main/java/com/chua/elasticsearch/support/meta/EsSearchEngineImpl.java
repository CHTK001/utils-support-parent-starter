package com.chua.elasticsearch.support.meta;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.PropertyBuilders;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.GetIndexResponse;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import com.chua.common.support.lang.datasource.meta.SearchEngine;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.elasticsearch.support.engine.ElasticsearchEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 搜索引擎实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class EsSearchEngineImpl implements SearchEngine {

    /**
     * 引擎
    */
    private final ElasticsearchEngine engine;

    /**
     * 创建 es搜索engineimpl 实例
     * @param engine engine
     */
    public EsSearchEngineImpl(ElasticsearchEngine engine) {
        this.engine = engine;
    }

    @Override
    /**
     * 类型
    */
    public String type() {
        return "elasticsearch";
    }

    @Override
    /**
     * 列表索引
    */
    public List<String> listIndexes() {
        try {
            var response = engine.getClient().indices().get(i -> i.index("*"));
            return new ArrayList<>(response.result().keySet());
        } catch (Exception e) {
            throw new RuntimeException("列出 ES 索引失败", e);
        }
    }

    @Override
    /**
     * 获取索引
    */
    public SearchIndexDef getIndex(String indexName) {
        try {
            GetIndexResponse indexResponse = engine.getClient().indices().get(i -> i.index(indexName));
            GetMappingResponse mappingResponse = engine.getClient().indices().getMapping(m -> m.index(indexName));

            SearchIndexDef def = new SearchIndexDef();
            def.setName(indexName);

            if (indexResponse.result().containsKey(indexName)) {
                var indexSettings = indexResponse.result().get(indexName).settings();
                Map<String, Object> settings = new LinkedHashMap<>();
                if (indexSettings.index() != null) {
                    if (indexSettings.index().numberOfShards() != null) {
                        settings.put("number_of_shards", indexSettings.index().numberOfShards());
                    }
                    if (indexSettings.index().numberOfReplicas() != null) {
                        settings.put("number_of_replicas", indexSettings.index().numberOfReplicas());
                    }
                }
                def.setSettings(settings);
            }

            if (mappingResponse.result().containsKey(indexName)) {
                TypeMapping mapping = mappingResponse.result().get(indexName).mappings();
                List<SearchFieldDef> fields = new ArrayList<>();
                if (mapping.properties() != null) {
                    mapping.properties().forEach((name, prop) -> {
                        SearchFieldDef field = new SearchFieldDef();
                        field.setName(name);
                        // Kind 枚举名带下划线后缀（Long_/Float_），与 createIndex 的类型词表对齐
                        field.setType(prop._kind().name().replaceAll("_+$", ""));
                        fields.add(field);
                    });
                }
                def.setFields(fields);
            }

            return def;
        } catch (Exception e) {
            throw new RuntimeException("获取 ES 索引定义失败: " + indexName, e);
        }
    }

    @Override
    /**
     * 创建索引
    */
    public boolean createIndex(SearchIndexDef indexDef) {
        if (indexDef == null || indexDef.getName() == null) {
            throw new IllegalArgumentException("索引定义不能为空");
        }
        try {
            engine.getClient().indices().create(c -> c
                    .index(indexDef.getName())
                    .settings(s -> {
                        if (indexDef.getShards() != null) {
                            s.numberOfShards(String.valueOf(indexDef.getShards()));
                        }
                        if (indexDef.getReplicas() != null) {
                            s.numberOfReplicas(String.valueOf(indexDef.getReplicas()));
                        }
                        if (indexDef.getSettings() != null) {
                            for (Map.Entry<String, Object> entry : indexDef.getSettings().entrySet()) {
                                switch (entry.getKey()) {
                                    case "number_of_shards" -> s.numberOfShards(String.valueOf(entry.getValue()));
                                    case "number_of_replicas" -> s.numberOfReplicas(String.valueOf(entry.getValue()));
                                    default -> {
                                    }
                                }
                            }
                        }
                        return s;
                    })
                    .mappings(m -> {
                        if (indexDef.getFields() != null && !indexDef.getFields().isEmpty()) {
                            Map<String, Property> properties = new LinkedHashMap<>();
                            for (SearchFieldDef field : indexDef.getFields()) {
                                properties.put(field.getName(), buildProperty(field));
                            }
                            m.properties(properties);
                        }
                        return m;
                    })
            );
            return true;
        } catch (Exception e) {
            throw new RuntimeException("创建 ES 索引失败: " + indexDef.getName(), e);
        }
    }

    /**
     * 按字段定义构建 ES mapping 属性，落实 analyzer/index/store/ignore_above/null_value/doc_values。
     *
     * @param field 字段定义
     * @return ES 属性
     */
    private Property buildProperty(SearchFieldDef field) {
        String type = field.getType() == null ? "" : field.getType().toUpperCase();
        boolean indexed = field.isIndexed();
        boolean stored = field.isStored();
        return switch (type) {
            case "KEYWORD" -> PropertyBuilders.keyword(b -> {
                b.index(indexed).store(stored);
                if (field.getIgnoreAbove() != null) {
                    b.ignoreAbove(field.getIgnoreAbove());
                }
                if (field.getNullValue() != null) {
                    b.nullValue(field.getNullValue());
                }
                if (field.getDocValues() != null) {
                    b.docValues(field.getDocValues());
                }
                return b;
            });
            case "INTEGER" -> PropertyBuilders.integer(b -> {
                numberShape(b::index, b::store, b::ignoreAbove, indexed, stored, field);
                return b;
            });
            case "LONG" -> PropertyBuilders.long_(b -> {
                numberShape(b::index, b::store, b::ignoreAbove, indexed, stored, field);
                return b;
            });
            case "FLOAT" -> PropertyBuilders.float_(b -> {
                numberShape(b::index, b::store, b::ignoreAbove, indexed, stored, field);
                return b;
            });
            case "DOUBLE" -> PropertyBuilders.double_(b -> {
                numberShape(b::index, b::store, b::ignoreAbove, indexed, stored, field);
                return b;
            });
            case "BOOLEAN" -> PropertyBuilders.boolean_(b -> {
                b.index(indexed).store(stored);
                if (field.getDocValues() != null) {
                    b.docValues(field.getDocValues());
                }
                return b;
            });
            case "DATE" -> PropertyBuilders.date(b -> {
                b.store(stored);
                if (field.getDocValues() != null) {
                    b.docValues(field.getDocValues());
                }
                return b;
            });
            case "OBJECT" -> PropertyBuilders.object(b -> b.enabled(indexed));
            case "NESTED" -> PropertyBuilders.nested(b -> b);
            case "BINARY" -> PropertyBuilders.binary(b -> b.store(stored));
            case "IP" -> PropertyBuilders.ip(b -> b);
            case "COMPLETION" -> PropertyBuilders.completion(b -> b);
            default -> PropertyBuilders.text(b -> {
                b.index(indexed).store(stored);
                if (field.getAnalyzer() != null) {
                    b.analyzer(field.getAnalyzer());
                }
                if (field.getSearchAnalyzer() != null) {
                    b.searchAnalyzer(field.getSearchAnalyzer());
                }
                return b;
            });
        };
    }

    /**
     * 数值类型公共形状：index/store/ignore_above。
     *
     * @param indexFn 方法入参 indexFn
     * @param storeFn 方法入参 storeFn
     * @param ignoreAboveFn 方法入参 ignoreAboveFn
     * @param indexed 方法入参 indexed
     * @param stored 方法入参 stored
     * @param field 方法入参 field
     */
    private static void numberShape(java.util.function.Consumer<Boolean> indexFn,
                                    java.util.function.Consumer<Boolean> storeFn,
                                    java.util.function.Consumer<Integer> ignoreAboveFn,
                                    boolean indexed, boolean stored, SearchFieldDef field) {
        indexFn.accept(indexed);
        storeFn.accept(stored);
        if (field.getIgnoreAbove() != null) {
            ignoreAboveFn.accept(field.getIgnoreAbove());
        }
    }

    @Override
    /**
     * 删除索引
    */
    public boolean deleteIndex(String indexName) {
        try {
            engine.getClient().indices().delete(d -> d.index(indexName));
            return true;
        } catch (Exception e) {
            throw new RuntimeException("删除 ES 索引失败: " + indexName, e);
        }
    }

    @Override
    /**
     * 获取客户端
    */
    public Object getClient() {
        return engine.getClient();
    }
}

