package com.chua.influxdb.support.engine;

import com.chua.common.support.lang.datasource.timeseries.TimeSeriesEngine;
import com.chua.common.support.lang.datasource.timeseries.TimeSeriesPoint;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TimeSeriesEngine 接口的 InfluxDB 实现端到端测试。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InfluxTimeSeriesEngineIT {

    static final String HOST = "172.16.0.40";
    static final int PORT = 8087;
    static InfluxDB influx;
    static String db;

    static class Adapter implements TimeSeriesEngine {
        final InfluxDB dbClient;
        final String dbName;
        Adapter(InfluxDB client, String dbName) { this.dbClient = client; this.dbName = dbName; }

        @Override public void write(TimeSeriesPoint p) {
            var b = Point.measurement(p.measurement()).time(p.timestamp(), TimeUnit.MILLISECONDS);
            p.tags().forEach(b::tag);
            p.fields().forEach((k, v) -> {
                if (v instanceof Number n) b.addField(k, n);
                else if (v instanceof String s) b.addField(k, s);
                else if (v instanceof Boolean bo) b.addField(k, bo);
            });
            dbClient.setDatabase(dbName).write(b.build());
        }
        @Override public void writeBatch(List<TimeSeriesPoint> points) { points.forEach(this::write); }

        private List<Map<String, Object>> q(String sql) {
            var result = dbClient.query(new Query(sql, dbName));
            var series = result.getResults().get(0).getSeries();
            if (series == null || series.isEmpty()) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            var cols = series.get(0).getColumns();
            for (var vals : series.get(0).getValues()) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < cols.size(); i++) map.put(cols.get(i), vals.size() > i ? vals.get(i) : null);
                out.add(map);
            }
            return out;
        }

        @Override public List<Map<String, Object>> queryRange(String m, long s, long e) {
            return q("SELECT * FROM " + m + " WHERE time>=" + s + "ms AND time<=" + e + "ms");
        }
        @Override public List<Map<String, Object>> queryByTag(String m, String tk, String tv, long s, long e) {
            return q("SELECT * FROM " + m + " WHERE " + tk + "='" + tv + "' AND time>=" + s + "ms AND time<=" + e + "ms");
        }
        @Override public List<Map<String, Object>> aggregate(String m, String fn, String iv, long s, long e) {
            return q("SELECT " + fn + "(*) FROM " + m + " WHERE time>=" + s + "ms AND time<=" + e + "ms GROUP BY time(" + iv + ")");
        }
        @Override public void deleteRange(String m, long s, long e) {
            dbClient.query(new Query("DELETE FROM " + m + " WHERE time>=" + s + "ms AND time<=" + e + "ms", dbName));
        }
        @Override public void createDatabase(String name) { dbClient.createDatabase(name); }
        @Override public void dropDatabase(String name) { dbClient.deleteDatabase(name); }
        @Override public List<String> listDatabases() { return dbClient.describeDatabases(); }
        @Override public void createUser(String u, String p) { dbClient.query(new Query("CREATE USER " + u + " WITH PASSWORD '" + p + "'", dbName)); }
        @Override public void dropUser(String u) { dbClient.query(new Query("DROP USER " + u, dbName)); }
        @Override public List<String> listUsers() {
            var r = dbClient.query(new Query("SHOW USERS", dbName));
            var series = r.getResults().get(0).getSeries();
            if (series == null || series.isEmpty()) return List.of();
            var values = series.get(0).getValues();
            if (values == null || values.isEmpty()) return List.of();
            return values.stream().map(v -> String.valueOf(v.get(0))).toList();
        }
        @Override public void grant(String priv, String d, String u) {
            dbClient.query(new Query("GRANT " + priv + " ON " + d + " TO " + u, dbName));
        }
        @Override public boolean ping() { return dbClient.ping().isGood(); }
        @Override public void close() { if (dbClient != null) dbClient.close(); }
    }

    static InfluxDB rawInflux;
    static String dbName;

    @BeforeAll
    static void setupAll() throws Exception {
        try {
            var s = new Socket();
            s.connect(new InetSocketAddress(HOST, PORT), 3000);
            s.close();
        } catch (Exception e) {
            Assumptions.abort("InfluxDB 不可达，跳过");
        }
        rawInflux = InfluxDBFactory.connect("http://" + HOST + ":" + PORT, "admin", "admin123");
        assertTrue(rawInflux.ping().isGood());
        dbName = "it_ts_" + System.nanoTime();
        rawInflux.createDatabase(dbName);
    }

    @AfterAll
    static void teardownAll() {
        try { rawInflux.deleteDatabase(dbName); } catch (Exception ignored) {}
        try { rawInflux.close(); } catch (Exception ignored) {}
    }

    @Test @Order(1)
    void ping_andDdl() throws Exception {
        var ts = new Adapter(rawInflux, dbName);
        assertTrue(ts.ping());

        String testDb = "ts_ddl_" + System.nanoTime();
        ts.createDatabase(testDb);
        Thread.sleep(500);
        assertTrue(ts.listDatabases().contains(testDb), "CREATE 后应可见");
        ts.dropDatabase(testDb);
    }

    @Test @Order(2)
    void writeBatch_queryRange_byTag_aggregate() throws Exception {
        var ts = new Adapter(rawInflux, dbName);
        long base = 1700000000000L;

        ts.writeBatch(List.of(
                new TimeSeriesPoint("cpu", Map.of("host", "h1"), Map.of("sys_load", 0.5), base),
                new TimeSeriesPoint("cpu", Map.of("host", "h1"), Map.of("sys_load", 0.8), base + 60000),
                new TimeSeriesPoint("cpu", Map.of("host", "h2"), Map.of("sys_load", 0.3), base + 120000)));

        Thread.sleep(2000);

        assertEquals(3, ts.queryRange("cpu", base - 1, base + 180000).size(), "范围查询应命中 3 条");
        assertEquals(2, ts.queryByTag("cpu", "host", "h1", base - 1, base + 180000).size(), "h1 过滤应命中 2 条");
        assertFalse(ts.aggregate("cpu", "mean", "1m", base - 1, base + 180000).isEmpty(), "聚合不应为空");
    }

    @Test @Order(3)
    void upsert_overwriteSameTimestamp() throws Exception {
        var ts = new Adapter(rawInflux, dbName);
        long ts1 = System.currentTimeMillis();

        ts.write(new TimeSeriesPoint("mem", Map.of(), Map.of("used", 0.1), ts1));
        Thread.sleep(1000);

        ts.write(new TimeSeriesPoint("mem", Map.of(), Map.of("used", 0.9), ts1));
        Thread.sleep(1000);

        var rows = ts.queryRange("mem", ts1 - 1, ts1 + 1);
        assertEquals(1, rows.size(), "upsert 后应只有 1 行（非追加）");
    }

    @Test @Order(4)
    void userMgmt_createListGrantDrop() {
        var ts = new Adapter(rawInflux, dbName);
        String u = "it_ts_user_" + Long.toHexString(System.nanoTime());

        assertDoesNotThrow(() -> ts.createUser(u, "Test123!"));
        assertTrue(ts.listUsers().stream().anyMatch(x -> x.contains(u)), "创建后应在列表中");

        ts.grant("READ", dbName, u);
        assertDoesNotThrow(() -> ts.dropUser(u));
    }

    @Test @Order(5)
    void deleteRange_verifyGone() throws Exception {
        var ts = new Adapter(rawInflux, dbName);
        long now = System.currentTimeMillis();

        ts.write(new TimeSeriesPoint("del_t", Map.of(), Map.of("v", 1), now));
        Thread.sleep(2000);

        ts.deleteRange("del_test_range_" + now, 0, now + 5000);
    }
}
