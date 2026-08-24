package com.chua.solr.support.engine;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.lang.datasource.table.ColumnDef;
import com.chua.common.support.lang.datasource.table.TableDef;
import com.chua.solr.support.ddl.SolrDdlManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Solr DDL 管理器（TableDef/ColumnDef 体系）真实容器端到端测试。
 */
class SolrDdlIT {

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
        Assumptions.assumeTrue(baseUrl != null, "Solr 容器不可达，跳过");
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
        engine.addDataSource("solr", new EngineDataSource<String>() {
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
    void ddl_listAndGetTable_reflectRealSchema() throws Exception {
        SolrEngine engine = newEngine();
        try {
            var manager = engine.ddl();

            /* 自播种：触发 data-driven schema 收编字段 */
            try (var client = new org.apache.solr.client.solrj.impl.HttpSolrClient
                    .Builder(baseUrl + "/testcore").build()) {
                org.apache.solr.common.SolrInputDocument doc = new org.apache.solr.common.SolrInputDocument();
                doc.addField("id", "ddl-seed-1");
                doc.addField("name_s", "Seed");
                client.add(doc);
                client.commit();
            }

            /* listTables：应包含真实存在的 testcore 集合 */
            List<TableDef> tables = manager.listTables(null, null);
            assertTrue(tables.stream().anyMatch(t -> t.getName().equals("testcore")));

            /* getTable：读取 testcore 真实 schema 字段 → ColumnDef
               （此前 GroupBy 测试写入的 name_s/age_i/city_s 已被 data-driven schema 收编） */
            TableDef def = manager.getTable(null, null, "testcore");
            assertNotNull(def);
            assertEquals("testcore", def.getName());
            List<String> colNames = def.getColumns().stream()
                    .map(ColumnDef::getName).toList();
            assertTrue(colNames.contains("id"), "应有 id 字段: " + colNames);
            /* 注：name_s 等由动态字段 *\_s 承载，Solr Schema API 只显式列出
               已注册字段；显式新增字段的可见性在结构测试中验证 */
        } finally {
            engine.close();
        }
    }

    @Test
    void ddl_structural_createAddFieldDrop_e2e() throws Exception {
        SolrEngine engine = newEngine();
        try {
            SolrDdlManager manager = (SolrDdlManager) engine.ddl();
            String coll = "it_ddl_" + System.nanoTime();

            /* 结构性 DDL 执行：创建集合 */
            manager.createCollection(coll);
            Thread.sleep(1500);
            assertTrue(manager.listTables(null, null).stream()
                    .anyMatch(t -> t.getName().equals(coll)), "创建后应可见");

            /* ALTER 语义：追加字段后 schema 立即可见 */
            manager.addField(coll, "sku_code_s", "string");
            Thread.sleep(800);
            TableDef after = manager.getTable(null, null, coll);
            assertNotNull(after);
            assertTrue(after.getColumns().stream()
                    .anyMatch(c -> c.getName().equals("sku_code_s")), "新字段应出现在 schema");

            /* createTableDDL 文本生成（含真实字段） */
            String ddl = manager.createTableDDL(null, null, coll);
            assertTrue(ddl.contains("create-collection"));
            assertTrue(ddl.contains("sku_code_s"));

            /* 清理 */
            manager.dropCollection(coll);
            Thread.sleep(1200);
            assertFalse(manager.listTables(null, null).stream()
                    .anyMatch(t -> t.getName().equals(coll)));
        } finally {
            engine.close();
        }
    }
}
