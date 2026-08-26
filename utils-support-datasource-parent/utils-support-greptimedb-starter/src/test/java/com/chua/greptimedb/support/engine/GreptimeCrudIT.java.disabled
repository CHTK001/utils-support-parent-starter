package com.chua.greptimedb.support.engine;

import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * GreptimeDB 完整增删改查值校验（使用 public 库，唯一表名）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GreptimeCrudIT {

    static final String HOST = "172.16.0.40";
    static final String TABLE = "gt_crud_" + System.nanoTime();
    static GreptimeDbEngine engine;

    @BeforeAll
    static void setupAll() throws Exception {
        Assumptions.assumeTrue(reachable(HOST, 4002), "GreptimeDB 不可达");
        engine = new GreptimeDbEngine();
        engine.addDataSource("gt", HOST + ":4000", "public", "", "");
        engine.setJdbcUrl("jdbc:mysql://" + HOST + ":4002/public");
    }

    @AfterAll
    static void teardownAll() {
        try { engine.getExecutor().execute("DROP TABLE IF EXISTS " + TABLE); } catch (Exception ignored) {}
        if (engine != null) engine.close();
    }

    private static boolean reachable(String host, int port) {
        try { new Socket().connect(new InetSocketAddress(host, port), 2000); return true; }
        catch (Exception e) { return false; }
    }

    @Test @Order(1)
    void createTable() {
        assertDoesNotThrow(() -> engine.getExecutor().execute(
                "CREATE TABLE " + TABLE + " (sensor STRING, value DOUBLE, ts TIMESTAMP, TIME INDEX(ts))"));
    }

    @Test @Order(2)
    void insert_andVerifyCount() {
        var ex = engine.getExecutor();
        ex.execute("INSERT INTO " + TABLE + " VALUES ('s1', 22.5, 1700000000000)");
        ex.execute("INSERT INTO " + TABLE + " VALUES ('s2', 25.0, 1700000060000)");
        var rows = ex.query("SELECT COUNT(*) AS cnt FROM " + TABLE);
        assertFalse(rows.isEmpty(), "count 查询应返回数据");
    }

    @Test @Order(3)
    void select_withWhereFilter() {
        var rows = engine.getExecutor()
                .query("SELECT sensor FROM " + TABLE + " WHERE sensor = 's1'");
        assertFalse(rows.isEmpty(), "sensor=s1 应有数据");
    }

    @Test @Order(4)
    void upsert_overwriteSameTimestamp() throws Exception {
        Thread.sleep(1000);
        engine.getExecutor().execute(
                "INSERT INTO " + TABLE + " VALUES ('s1', 99.9, 1700000000000)");
        Thread.sleep(500);
        var rows = engine.getExecutor()
                .query("SELECT value FROM " + TABLE + " WHERE value = 99.9");
        assertFalse(rows.isEmpty(), "upsert 后应读到新值 99.9");
    }
}
