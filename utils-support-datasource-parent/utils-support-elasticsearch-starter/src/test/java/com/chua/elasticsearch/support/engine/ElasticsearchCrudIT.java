package com.chua.elasticsearch.support.engine;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.elasticsearch.support.meta.EsSearchEngineImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ElasticsearchEngine 完整 CRUD 值校验测试。
 * 覆盖：连接、索引生命周期、文档索引/搜索/过滤/排序、批量操作。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElasticsearchCrudIT {

    private static final String ES_URL = "http://172.16.0.40:9200";
    private static ElasticsearchEngine engine;

    @BeforeAll
    static void setup() {
        try {
            var s = new Socket();
            s.connect(new InetSocketAddress("172.16.0.40", 9200), 3000);
            s.close();
        } catch (Exception e) {
            Assumptions.abort("ES 不可达，跳过");
        }
        engine = new ElasticsearchEngine();
        engine.addDataSource("es", new EngineDataSource<String>() {
            @Override public String name() { return "es"; }
            @Override public String getSource() { return ES_URL; }
            @Override public com.chua.common.support.lang.datasource.dialect.Dialect getDialect() { return null; }
            @Override public EngineDataSource<String> setSource(Object src) { return this; }
            @Override public EngineDataSource<String> setDialect(com.chua.common.support.lang.datasource.dialect.Dialect d) { return this; }
            @Override public String url() { return ES_URL; }
            @Override public String username() { return null; }
            @Override public String password() { return null; }
        });
    }

    @AfterAll
    static void teardown() {
        if (engine != null) engine.close();
    }

    private static String uniqueIndex() {
        return "it_crud_" + System.nanoTime();
    }

    @Test
    @Order(1)
    void clusterInfo() throws Exception {
        var info = engine.getClient().info();
        assertNotNull(info);
        assertEquals("docker-cluster", info.clusterName());
    }

    @Test
    @Order(2)
    void indexDdl_fullCycle() throws Exception {
        MetaSearch search = engine.meta().search();
        String idx = uniqueIndex();

        /* CREATE index */
        SearchIndexDef def = SearchIndexDef.builder()
                .name(idx).shards(1).replicas(0)
                .fields(List.of(
                        SearchFieldDef.builder().name("title").type("text").build(),
                        SearchFieldDef.builder().name("views").type("integer").build()))
                .build();
        assertTrue(new EsSearchEngineImpl(engine).createIndex(def));

        /* READ */
        assertTrue(search.list().stream().anyMatch(d -> d.getName().equals(idx)));
        assertNotNull(search.get(idx));

        /* DROP */
        assertTrue(search.drop(idx));
    }

    @Test
    @Order(3)
    void bulkDoc_andSearchCount() throws Exception {
        var client = engine.getClient();
        String idx = uniqueIndex();

        for (int i = 1; i <= 5; i++) {
            final int v = i;
            client.index(op -> op.index(idx).id("bulk_" + v)
                    .document(java.util.Map.of("title", "Doc " + v, "views", v * 10)));
        }
        client.indices().refresh(r -> r.index(idx));

        var searchResp = client.search(s -> s.index(idx)
                .query(q -> q.matchAll(m -> m)), java.util.Map.class);
        assertEquals(5, searchResp.hits().total().value(), "应有 5 条");

        client.indices().delete(d -> d.index(idx));
    }

    @Test
    @Order(4)
    void filteredSearch_withSort() throws Exception {
        var client = engine.getClient();
        String idx = uniqueIndex();

        for (int i = 1; i <= 4; i++) {
            final int v = i;
            client.index(op -> op.index(idx).id("s_" + v)
                    .document(java.util.Map.of("title", "Item " + v, "views", v * 100)));
        }
        client.indices().refresh(r -> r.index(idx));

        /* range 过滤 views >= 200，降序排列 */
        var resp = client.search(s -> s.index(idx)
                .query(q -> q.range(r -> r.field("views")
                        .gte(co.elastic.clients.json.JsonData.of(200))))
                .sort(so -> so.field(f -> f.field("views")
                        .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))),
                java.util.Map.class);

        assertEquals(3, resp.hits().hits().size(), "views>=200 应命中 3 条");

        client.indices().delete(d -> d.index(idx));
    }
}
