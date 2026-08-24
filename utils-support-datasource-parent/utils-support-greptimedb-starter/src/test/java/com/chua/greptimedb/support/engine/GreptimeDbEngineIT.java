package com.chua.greptimedb.support.engine;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GreptimeDB 真实容器测试（gRPC :4000 + MySQL 协议 :4002）。
 */
class GreptimeDbEngineIT {

    private static final String HOST = "172.16.0.40";

    @BeforeAll
    static void assumeReachable() {
        Assumptions.assumeTrue(reachable(HOST, 4002), "GreptimeDB MySQL 端口不可达，跳过");
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void timeSeries_createInsertQuery() throws Exception {
        String db = "it_gt_" + System.nanoTime();
        /* 先经 MySQL 协议建库 */
        try (var conn = java.sql.DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":4002/public", "", "");
             var st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + db);
        }

        GreptimeDbEngine engine = new GreptimeDbEngine();
        /* gRPC 4000 建连；MySQL 4002 执行查询 */
        engine.addDataSource("gt", HOST + ":4000", db, "", "");
        engine.setJdbcUrl("jdbc:mysql://" + HOST + ":4002/" + db);
        try {
            var executor = engine.getExecutor();
            executor.execute("CREATE TABLE metrics (" +
                    "host STRING, sys_load DOUBLE, ts TIMESTAMP, TIME INDEX(ts))");

            executor.execute("INSERT INTO metrics (host, sys_load, ts) VALUES ('h1', 0.5, 1700000000000)");
            executor.execute("INSERT INTO metrics (host, sys_load, ts) VALUES ('h2', 0.9, 1700000060000)");

            List<Map<String, Object>> rows =
                    executor.query("SELECT host, sys_load FROM metrics ORDER BY host");
            assertEquals(2, rows.size());
            assertTrue(rows.stream().anyMatch(r -> "h1".equals(String.valueOf(r.get("host")))));
            assertTrue(rows.stream().anyMatch(r -> "h2".equals(String.valueOf(r.get("host")))));
        } finally {
            engine.close();
        }
    }
}
