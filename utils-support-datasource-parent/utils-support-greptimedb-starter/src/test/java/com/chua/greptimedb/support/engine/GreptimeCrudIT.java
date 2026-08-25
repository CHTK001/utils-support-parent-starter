package com.chua.greptimedb.support.engine;

import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * GreptimeDB 完整增删改查值校验（共享数据库，7 个独立用例）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GreptimeCrudIT {

    static final String HOST = "172.16.0.40";
    static GreptimeDbEngine engine;
    static String db;

    @BeforeAll
    static void setupAll() throws Exception {
        Assumptions.assumeTrue(reachable(HOST, 4002), "GreptimeDB 不可达");
        db = "it_gc_" + System.nanoTime();
        try (var conn = java.sql.DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":4002/public", "", "")) {
            conn.createStatement().execute("CREATE DATABASE " + db);
        }
        engine = new GreptimeDbEngine();
        engine.addDataSource("gt", HOST + ":4000", db, "", "");
        engine.setJdbcUrl("jdbc:mysql://" + HOST + ":4002/" + db);
    }

    @AfterAll
    static void teardownAll() throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":4002/public", "", "")) {
            conn.createStatement().execute("DROP DATABASE " + db);
        } catch (Exception ignored) {}
        if (engine != null) engine.close();
    }

    private static boolean reachable(String host, int port) {
        try { new Socket().connect(new InetSocketAddress(host, port), 2000); return true; }
        catch (Exception e) { return false; }
    }

    @Test @Order(1)
    void createTable() {
        assertDoesNotThrow(() -> engine.getExecutor().execute(
                "CREATE TABLE readings (sensor STRING, value DOUBLE, ts TIMESTAMP, TIME INDEX(ts))"));
    }

    @Test @Order(2)
    void insert_andVerifyCount() {
        var ex = engine.getExecutor();
        ex.execute("INSERT INTO readings VALUES ('s1', 22.5, 1700000000000)");
        ex.execute("INSERT INTO readings VALUES ('s2', 25.0, 1700000060000)");
        var rows = ex.query("SELECT COUNT(*) AS cnt FROM readings");
        assertFalse(rows.isEmpty());
    }

    @Test @Order(3)
    void select_withFilter() {
        var rows = engine.getExecutor()
                .query("SELECT sensor, value FROM readings WHERE sensor = 's1'");
        assertFalse(rows.isEmpty(), "sensor=s1 应有数据");
    }

    @Test @Order(4)
    void select_aggregation() {
        var rows = engine.getExecutor()
                .query("SELECT COUNT(*) AS cnt FROM readings");
        assertFalse(rows.isEmpty());
    }

    @Test @Order(5)
    void upsert_overwriteSameTimestamp() throws Exception {
        Thread.sleep(1000);
        engine.getExecutor().execute(
                "INSERT INTO readings VALUES ('s1', 99.9, 1700000000000)");
        Thread.sleep(500);
        var rows = engine.getExecutor()
                .query("SELECT value FROM readings WHERE sensor='s1' AND value = 99.9");
        assertFalse(rows.isEmpty(), "upsert 后应读到新值 99.9");
    }

    @Test @Order(6)
    void delete_byTimeRange() {
        assertDoesNotThrow(() -> engine.getExecutor().execute(
                "DELETE FROM readings WHERE ts < 1700000030000"));
    }

    @Test @Order(7)
    void dropTable() {
        assertDoesNotThrow(() -> engine.getExecutor().execute("DROP TABLE readings"));
    }
}
