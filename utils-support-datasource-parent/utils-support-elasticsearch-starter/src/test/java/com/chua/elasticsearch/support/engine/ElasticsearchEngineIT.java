package com.chua.elasticsearch.support.engine;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.meta.MetaSearch;
import com.chua.common.support.lang.datasource.meta.model.SearchFieldDef;
import com.chua.common.support.lang.datasource.meta.model.SearchIndexDef;
import com.chua.elasticsearch.support.meta.EsSearchEngineImpl;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ElasticsearchEngine 真实容器集成测试（http://172.16.0.40:9200）。
 * 覆盖：连接、meta().search() 索引 DDL（SearchIndexDef/SearchFieldDef 定义体系）、
 * 创建/查询/删除索引。
 */
class ElasticsearchEngineIT {

    private static final String ES_URL = "http://172.16.0.40:9200";

    @BeforeAll
    static void assumeReachable() {
        Assumptions.assumeTrue(reachable("172.16.0.40", 9200), "ES 容器不可达，跳过");
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ElasticsearchEngine newEngine() {
        ElasticsearchEngine engine = new ElasticsearchEngine();
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
        return engine;
    }

    @Test
    void searchIndexDdl_createListGetDrop() throws Exception {
        ElasticsearchEngine engine = newEngine();
        try {
            MetaSearch search = engine.meta().search();
            String idx = "it_es_" + System.nanoTime();

            /* 通过统一定义体系创建索引（非裸字符串） */
            SearchIndexDef def = SearchIndexDef.builder()
                    .name(idx)
                    .shards(1)
                    .replicas(0)
                    .fields(List.of(
                            SearchFieldDef.builder().name("title").type("text").build(),
                            SearchFieldDef.builder().name("age").type("integer").build()))
                    .build();
            boolean created = new EsSearchEngineImpl(engine).createIndex(def);
            assertTrue(created);

            /* list / get */
            assertTrue(search.list().stream().anyMatch(d -> d.getName().equals(idx)));
            SearchIndexDef got = search.get(idx);
            assertNotNull(got);
            assertEquals(idx, got.getName());

            /* drop */
            assertTrue(search.drop(idx));
            assertFalse(search.list().stream().anyMatch(d -> d.getName().equals(idx)));
        } finally {
            engine.close();
        }
    }

    @Test
    void clientInfo_pingCluster() throws Exception {
        ElasticsearchEngine engine = newEngine();
        try {
            var info = engine.getClient().info();
            assertEquals("docker-cluster", info.clusterName());
        } finally {
            engine.close();
        }
    }
}
