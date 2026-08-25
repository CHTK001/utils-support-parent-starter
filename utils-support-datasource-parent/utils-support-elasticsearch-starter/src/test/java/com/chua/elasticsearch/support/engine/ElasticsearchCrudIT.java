package com.chua.elasticsearch.support.engine;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.elasticsearch.support.meta.EsSearchEngineImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
            @Override public EngineDataSource<String> setSource(Object source) { return this; }
            @Override public EngineDataSource<String> setDialect(com.chua.common.support.lang.datasource.dialect.Dialect dialect) { return this; }
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
    void clusterInfo_andVersion() throws Exception {
        var info = engine.getClient().info();
        assertNotNull(info);
        assertEquals("docker-cluster", info.clusterName());
        assertNotNull(info.version().number());
    }

    @Test
    @Order(2)
    void indexDdl_createReadDrop() throws Exception {
        MetaSearch search = engine.meta().search();
        String idx = uniqueIndex();

        SearchIndexDef def = SearchIndexDef.builder()
                .name(idx).shards(1).replicas(0)
                .fields(List.of(
                        SearchFieldDef.builder().name("title").type("text").build(),
                        SearchFieldDef.builder().name("views").type("integer").build()))
                .build();
        assertTrue(new EsSearchEngineImpl(engine).createIndex(def), "createIndex");

        assertTrue(search.list().stream().anyMatch(d -> d.getName().equals(idx)), "list 应包含新索引");
        assertNotNull(search.get(idx), "get 应返回索引定义");

        assertTrue(search.drop(idx), "drop 应回 true");
        assertFalse(search.list().stream().anyMatch(d -> d.getName().equals(idx)));
    }

    @Test
    @Order(3)
    void docCrud_indexGetDelete() throws Exception {
        var client = engine.getClient();
        String idx = uniqueIndex();

        /* CREATE：写入文档 */
        client.index(i -> i.index(idx).id("d1")
                .document(java.util.Map.of("title", "Test Doc", "views", 42)));

        /* READ：搜索验证 count + 内容 */
        client.indices().refresh(r -> r.index(idx));
        var resp = client.search(s -> s.index(idx).query(q -> q.matchAll(m -> m)),
                java.util.Map.class);
        assertEquals(1, resp.hits().total().value(), "应有 1 条文档");
        assertNotNull(resp.hits().hits().get(0).source(), "source 不应为 null");

        /* DELETE doc */
        client.delete(d -> d.index(idx).id("d1"));
        client.indices().refresh(r -> r.index(idx));
        var empty = client.search(s -> s.index(idx).query(q -> q.matchAll(m -> m)),
                java.util.Map.class);
        assertEquals(0, empty.hits().total().value(), "删除后应为 0 条");

        /* DROP index */
        client.indices().delete(d -> d.index(idx));
    }

    @Test
    @Order(4)
    void bulkWrite_andCount() throws Exception {
        var client = engine.getClient();
        String idx = uniqueIndex();

        for (int i = 1; i <= 5; i++) {
            final int v = i;
            client.index(op -> op.index(idx).id("b_" + v)
                    .document(java.util.Map.of("title", "Bulk " + v)));
        }
        client.indices().refresh(r -> r.index(idx));

        var resp = client.search(s -> s.index(idx)
                .query(q -> q.matchAll(m -> m)), java.util.Map.class);
        assertEquals(5, resp.hits().total().value(), "批量写入后应搜到 5 条");

        client.indices().delete(d -> d.index(idx));
    }
}
