package com.chua.solr.support.engine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SolrEngine 真实容器集成测试（http://172.16.0.40:8984，core=testcore）。
 * 覆盖：连接、meta().search() 索引元数据（SearchIndexDef 定义体系）、refresh/optimize。
 *
 * <p>当前 Docker 宿主机资源受限（JVM pthread_create EPERM）导致 Solr 容器无法启动时，
 * 本测试自动跳过；容器可用后无需改动即可运行。</p>
 */
class SolrEngineIT {

    private static final int[] PORTS = {8984, 8983};

    private static String baseUrl;

    @BeforeAll
    static void resolveBase() {
        for (int port : PORTS) {
            if (reachable("172.16.0.40", port)) {
                baseUrl = "http://172.16.0.40:" + port + "/solr";
                break;
            }
        }
        assumeTrue(baseUrl != null, "Solr 容器不可达（宿主机资源受限），跳过");
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private SolrEngine newEngine() {
        SolrEngine engine = new SolrEngine();
        engine.addDataSource("solr", new com.chua.common.support.lang.datasource.engine.EngineDataSource<String>() {
            @Override public String name() { return "solr"; }
            @Override public String getSource() { return baseUrl; }
            @Override public com.chua.common.support.lang.datasource.dialect.Dialect getDialect() { return null; }
            @Override public EngineDataSource<String> setSource(Object source) { return this; }
            @Override public EngineDataSource<String> setDialect(com.chua.common.support.lang.datasource.dialect.Dialect dialect) { return this; }
            @Override public String url() { return baseUrl; }
            @Override public String username() { return null; }
            @Override public String password() { return null; }
        });
        return engine;
    }

    @Test
    void searchMeta_listGetRefreshOptimize() {
        SolrEngine engine = newEngine();
        try {
            var search = engine.meta().search();

            /* 预创建 core：testcore */
            assertTrue(search.list().stream().anyMatch(d -> d.getName().equals("testcore")));
            assertNotNull(search.get("testcore"));

            assertTrue(search.refresh("testcore"));
            assertDoesNotThrow(() -> search.optimize("testcore"));
        } finally {
            engine.close();
        }
    }

    @Test
    void searchIndexDdl_createAndDrop() {
        SolrEngine engine = newEngine();
        try {
            var search = engine.meta().search();
            String idx = "it_solr_" + System.nanoTime();

            var created = search.create(idx)
                    .shards(1)
                    .replicas(0)
                    .execute();
            assertNotNull(created);

            assertTrue(search.list().stream().anyMatch(d -> d.getName().equals(idx)));
            assertTrue(search.drop(idx));
        } finally {
            engine.close();
        }
    }
}
