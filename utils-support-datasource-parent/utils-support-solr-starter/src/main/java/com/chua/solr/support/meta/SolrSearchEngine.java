package com.chua.solr.support.meta;

import com.chua.common.support.lang.datasource.meta.SearchEngine;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.solr.support.engine.SolrEngine;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.util.NamedList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Solr 搜索引擎实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SolrSearchEngine implements SearchEngine {

    /**
     * 新建集合副本上线为异步过程，schema 字段注册的最大重试次数
     */
    private static final int SCHEMA_APPLY_RETRY = 20;

    /**
     * schema 字段注册重试间隔（毫秒）
     */
    private static final long SCHEMA_APPLY_RETRY_DELAY_MS = 500L;

    /**
     * 引擎
    */
    private final SolrEngine engine;

    /**
     * 创建 Solr搜索engine 实例
     * @param engine engine
     */
    public SolrSearchEngine(SolrEngine engine) {
        this.engine = engine;
    }

    @Override
    /**
     * 类型
    */
    public String type() {
        return "solr";
    }

    @Override
    /**
     * 列表索引
    */
    public List<String> listIndexes() {
        SolrClient client = engine.getClient();
        if (client == null) {
            return java.util.Collections.emptyList();
        }
        try {
            CollectionAdminRequest.List request = new CollectionAdminRequest.List();
            CollectionAdminResponse response = request.process(client);
            /* solrj9 LIST 响应体：{ collections: [名称...] } */
            Object names = response.getResponse().get("collections");
            if (names instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) names;
                return new ArrayList<>(list);
            }
            return java.util.Collections.emptyList();
        } catch (Exception e) {
            throw new RuntimeException("列出 Solr 索引失败", e);
        }
    }

    @Override
    /**
     * 获取索引
    */
    public SearchIndexDef getIndex(String indexName) {
        SolrClient client = engine.getClient();
        if (client == null) {
            return null;
        }
        try {
            SchemaRequest.Fields request = new SchemaRequest.Fields();
            SchemaResponse.FieldsResponse response = request.process(client, indexName);
            SearchIndexDef def = new SearchIndexDef();
            def.setName(indexName);
            List<SearchFieldDef> fields = new ArrayList<>();
            List<Map<String, Object>> fieldList = response.getFields();
            if (fieldList != null) {
                for (Map<String, Object> fieldMap : fieldList) {
                    SearchFieldDef searchField = new SearchFieldDef();
                    searchField.setName((String) fieldMap.get("name"));
                    Object type = fieldMap.get("type");
                    if (type != null) {
                        searchField.setType(type.toString());
                    }
                    fields.add(searchField);
                }
            }
            def.setFields(fields);
            return def;
        } catch (Exception e) {
            throw new RuntimeException("获取 Solr 索引定义失败: " + indexName, e);
        }
    }

    @Override
    /**
     * 创建索引
    */
    public boolean createIndex(SearchIndexDef indexDef) {
        SolrClient client = engine.getClient();
        if (client == null) {
            throw new IllegalStateException("Solr 客户端未初始化");
        }
        if (indexDef == null || indexDef.getName() == null) {
            throw new IllegalArgumentException("索引定义不能为空");
        }
        try {
            CollectionAdminResponse response = CollectionAdminRequest.createCollection(
                    indexDef.getName(),
                    "_default",
                    indexDef.getShards() != null ? indexDef.getShards() : 1,
                    indexDef.getReplicas() != null ? indexDef.getReplicas() : 1
            ).process(client);
            if (!response.isSuccess()) {
                throw new RuntimeException("创建 Solr 索引失败: " + indexDef.getName()
                        + " -> " + response.getResponse());
            }
            applySchemaFields(client, indexDef);
            return true;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("创建 Solr 索引失败: " + indexDef.getName(), e);
        }
    }

    /**
     * 将索引定义的字段写入集合的 managed schema。
     * <p>使用 replace-field 保证与 _default 已有字段（如 id）兼容；新建集合的副本
     * 上线是异步的，因此在超时窗口内重试。</p>
     *
     * @param client   客户端
     * @param indexDef 索引定义
     * @throws Exception 最后一次尝试仍失败时抛出
     */
    private void applySchemaFields(SolrClient client, SearchIndexDef indexDef) throws Exception {
        List<SearchFieldDef> fields = indexDef.getFields();
        if (fields == null || fields.isEmpty()) {
            return;
        }
        List<SchemaRequest.Update> updates = new ArrayList<>();
        for (SearchFieldDef f : fields) {
            if (f == null || f.getName() == null || f.getName().isEmpty()) {
                continue;
            }
            if (!f.getName().matches("[A-Za-z0-9_.\\-]+")) {
                throw new IllegalArgumentException("非法字段名: " + f.getName());
            }
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", f.getName());
            def.put("type", f.getType() == null || f.getType().isEmpty() ? "string" : f.getType());
            def.put("multiValued", false);
            if (!f.isIndexed()) {
                def.put("indexed", false);
            }
            if (f.isStored()) {
                def.put("stored", true);
            }
            if (f.getDocValues() != null) {
                def.put("docValues", f.getDocValues());
            }
            updates.add(new SchemaRequest.ReplaceField(def));
        }
        if (updates.isEmpty()) {
            return;
        }
        SchemaRequest.MultiUpdate multiUpdate = new SchemaRequest.MultiUpdate(updates);
        Exception last = null;
        for (int attempt = 0; attempt < SCHEMA_APPLY_RETRY; attempt++) {
            try {
                // schema API 失败（4xx/校验错误）会以 SolrException 抛出，返回即视为成功
                multiUpdate.process(client, indexDef.getName());
                return;
            } catch (Exception e) {
                last = e;
            }
            try {
                Thread.sleep(SCHEMA_APPLY_RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
        }
        throw last;
    }

    @Override
    /**
     * 删除索引
    */
    public boolean deleteIndex(String indexName) {
        SolrClient client = engine.getClient();
        if (client == null) {
            throw new IllegalStateException("Solr 客户端未初始化");
        }
        try {
            CollectionAdminResponse response = CollectionAdminRequest.deleteCollection(indexName).process(client);
            return response.isSuccess();
        } catch (Exception e) {
            throw new RuntimeException("删除 Solr 索引失败: " + indexName, e);
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
