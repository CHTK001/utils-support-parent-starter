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

    private final ElasticsearchEngine engine;

    public EsSearchEngineImpl(ElasticsearchEngine engine) {
        this.engine = engine;
    }

    @Override
    public String type() {
        return "elasticsearch";
    }

    @Override
    public List<String> listIndexes() {
        try {
            var response = engine.getClient().indices().get(i -> i.index("*"));
            return new ArrayList<>(response.result().keySet());
        } catch (Exception e) {
            throw new RuntimeException("列出 ES 索引失败", e);
        }
    }

    @Override
    public SearchIndexDef getIndex(String indexName) {
        try {
            GetIndexResponse indexResponse = engine.getClient().indices().get(i -> i.index(indexName));
            GetMappingResponse mappingResponse = engine.getClient().indices().getMapping(m -> m.index(indexName));

            SearchIndexDef def = new SearchIndexDef();
            def.setName(indexName);

            if (indexResponse.result().containsKey(indexName)) {
                var indexSettings = indexResponse.result().get(indexName).settings();
                def.setSettings(Map.of(
                        "number_of_shards", indexSettings.index().numberOfShards(),
                        "number_of_replicas", indexSettings.index().numberOfReplicas()
                ));
            }

            if (mappingResponse.result().containsKey(indexName)) {
                TypeMapping mapping = mappingResponse.result().get(indexName).mappings();
                List<SearchFieldDef> fields = new ArrayList<>();
                if (mapping.properties() != null) {
                    mapping.properties().forEach((name, prop) -> {
                        SearchFieldDef field = new SearchFieldDef();
                        field.setName(name);
                        field.setType(prop._kind().name());
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
                                properties.put(field.getName(), buildProperty(field.getType()));
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

    private Property buildProperty(String type) {
        if (type == null) {
            return PropertyBuilders.text(b -> b);
        }
        return switch (type.toUpperCase()) {
            case "TEXT" -> PropertyBuilders.text(b -> b);
            case "KEYWORD" -> PropertyBuilders.keyword(b -> b);
            case "INTEGER" -> PropertyBuilders.integer(b -> b);
            case "LONG" -> PropertyBuilders.long_(b -> b);
            case "FLOAT" -> PropertyBuilders.float_(b -> b);
            case "DOUBLE" -> PropertyBuilders.double_(b -> b);
            case "BOOLEAN" -> PropertyBuilders.boolean_(b -> b);
            case "DATE" -> PropertyBuilders.date(b -> b);
            case "OBJECT" -> PropertyBuilders.object(b -> b);
            case "NESTED" -> PropertyBuilders.nested(b -> b);
            case "BINARY" -> PropertyBuilders.binary(b -> b);
            case "IP" -> PropertyBuilders.ip(b -> b);
            case "COMPLETION" -> PropertyBuilders.completion(b -> b);
            default -> PropertyBuilders.text(b -> b);
        };
    }

    @Override
    public boolean deleteIndex(String indexName) {
        try {
            engine.getClient().indices().delete(d -> d.index(indexName));
            return true;
        } catch (Exception e) {
            throw new RuntimeException("删除 ES 索引失败: " + indexName, e);
        }
    }

    @Override
    public Object getClient() {
        return engine.getClient();
    }
}

