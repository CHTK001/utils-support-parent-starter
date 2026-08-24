package com.chua.hbase.support.engine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HBaseEngine standalone 单机真实容器测试。
 */
class HBaseEngineIT {

    private static final String ZK_QUORUM = "172.16.0.40:12181";

    @BeforeAll
    static void assumeReachable() {
        Assumptions.assumeTrue(reachable("172.16.0.40", 12181), "HBase ZooKeeper 不可达，跳过");
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void createTable_putGetScanDelete() {
        HBaseEngine engine = new HBaseEngine();
        engine.addDataSource("hbase", ZK_QUORUM);
        try {
            String table = "it_test";
            String family = "cf";

            /* 建表 */
            engine.createTable(table, family);

            /* put */
            engine.put(table, "row1", family, Map.of("name", "Alice", "age", "20"));
            engine.put(table, "row2", family, Map.of("name", "Bob", "age", "30"));

            /* get */
            Map<String, String> row = engine.get(table, "row1", family);
            assertNotNull(row);
            assertEquals("Alice", row.get("name"));
            assertEquals("20", row.get("age"));

            /* scan */
            var all = engine.scan(table, family, "row");
            assertTrue(all.size() >= 2);

            /* delete */
            engine.deleteRow(table, "row2");
            Map<String, String> after = engine.get(table, "row2", family);
            assertTrue(after == null || after.isEmpty(), "删除后应为空");
        } finally {
            engine.close();
        }
    }
}
