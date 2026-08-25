package com.chua.greptimedb.support.engine;

import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GreptimeDB 完整增删改查值校验测试（时序数据库）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GreptimeCrudIT {

    private static final String HOST = "172.16.0.40";
    private static GreptimeDbEngine engine;
    private static String db;

    @BeforeAll
    static void setup() throws Exception {
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
    static void teardown() throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":4002/public", "", "")) {
            conn.createStatement().execute("DROP DATABASE " + db);
        } catch (Exception ignored) {}
        engine.close();
    }

    private static boolean reachable(String host, int port) {
        try { new Socket().connect(new InetSocketAddress(host, port), 2000); return true; }
        catch (Exception e) { return false; }
    }

    @Test @Order(1)
    void create_table() {
        var ex = engine.getExecutor();
        assertDoesNotThrow(() -> ex.execute(
                "CREATE TABLE temp_readings (sensor STRING, value DOUBLE, ts TIMESTAMP, TIME INDEX(ts))"));
    }

    @Test @Order(2)
    void insert_multipleRows() {
        var ex = engine.getExecutor();
        assertDoesNotThrow(() -> {
            ex.execute("INSERT INTO temp_readings VALUES ('s1', 22.5, 1700000000000)");
            ex.execute("INSERT INTO temp_readings VALUES ('s2', 25.0, 1700000060000)");
            ex.execute("INSERT INTO temp_readings VALUES ('s1', 23.1, 1700000120000)");
        });
    }

    @Test @Order(3)
    void select_all_verifyCountAndValues() {
        var rows = engine.getExecutor()
                .query("SELECT sensor, value FROM temp_readings ORDER BY ts");
        assertEquals(3, rows.size());
        assertEquals("s1", rows.get(0).get("sensor"));
        assertEquals(22.5, ((Number) rows.get(0).get("value")).doubleValue(), 0.01);
    }

    @Test @Order(4)
    void select_withWhereFilter() {
        var rows = engine.getExecutor()
                .query("SELECT sensor, value FROM temp_readings WHERE sensor = 's1'");
        assertEquals(2, rows.size(), "s1 应有 2 条读数");
    }

    @Test @Order(5)
    void select_aggregation() {
        var rows = engine.getExecutor()
                .query("SELECT COUNT(*) AS cnt FROM temp_readings");
        assertEquals(1, rows.size());
        assertEquals(3L, ((Number) rows.get(0).get("cnt")).longValue());
    }

    @Test @Order(6)
    void update_upsertSameTimestamp() {
        /* 时序库 upsert：同 ts 写入覆盖旧值 */
        var ex = engine.getExecutor();
        assertDoesNotThrow(() ->
                ex.execute("INSERT INTO temp_readings VALUES ('s1', 99.9, 1700000000000)"));
        var rows = ex.query("SELECT value FROM temp_readings WHERE sensor='s1' AND ts=1700000000000");
        assertFalse(rows.isEmpty());
        assertEquals(99.9, ((Number) rows.get(0).get("value")).doubleValue(), 0.01,
                "同 timestamp 重写应覆盖旧值");
    }

    @Test @Order(7)
    void dropTable() {
        var ex = engine.getExecutor();
        assertDoesNotThrow(() -> ex.execute("DROP TABLE temp_readings"));
        var rows = ex.query("SELECT * FROM temp_readings");
        assertTrue(rows.isEmpty(), "删除表后查询应返回空");
    }

    /* Lambda 实体 */
    public static class Reading {
        private String sensor;
        private Double value;
        public String getSensor() { return sensor; }
        public void setSensor(String s) { sensor = s; }
        public Double getValue() { return value; }
        public void setValue(Double v) { value = v; }
    }
}
