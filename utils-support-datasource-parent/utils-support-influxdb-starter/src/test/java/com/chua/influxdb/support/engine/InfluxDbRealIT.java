package com.chua.influxdb.support.engine;

import com.chua.influxdb.support.datasource.InfluxDbEngineDataSource;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InfluxDB 真实容器集成测试（http://172.16.0.40:8087）。
 * 覆盖：连接 ping、database DDL（create/retention policy/drop）、
 * 用户管理（CREATE/SHOW/GRANT/DROP USER）、写入与查询、引擎 write(Point) 真实链路。
 */
class InfluxDbRealIT {

    private static final String URL = "http://172.16.0.40:8087";
    private static final String ADMIN = "admin";
    private static final String PASS = "admin123";

    @BeforeAll
    static void assumeReachable() {
        Assumptions.assumeTrue(reachable("172.16.0.40", 8087), "InfluxDB 容器不可达，跳过");
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
    void databaseDdl_createWriteQueryDrop() throws Exception {
        InfluxDB influx = InfluxDBFactory.connect(URL, ADMIN, PASS);
        try {
            assertTrue(influx.ping().isGood());

            /* DDL：建库 */
            String db = "it_influx_" + System.nanoTime();
            influx.createDatabase(db);
            assertTrue(influx.describeDatabases().contains(db));

            /* 写入 + 查询（轮询吸收可见性延迟） */
            Point p = Point.measurement("cpu")
                    .time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                    .addField("load", (Number) 0.42).build();
            influx.setDatabase(db).write(p);
            boolean found = false;
            for (int i = 0; i < 10 && !found; i++) {
                Thread.sleep(500);
                QueryResult qr = influx.query(new Query("SELECT load FROM cpu", db));
                List<QueryResult.Series> rows = qr.getResults().get(0).getSeries();
                found = rows != null && !rows.isEmpty();
            }
            assertTrue(found, "写入后应能查到 cpu 数据");

            /* DDL：保留策略 */
            influx.query(new Query("CREATE RETENTION POLICY it_rp ON " + db
                    + " DURATION 1h REPLICATION 1", db));
            QueryResult rps = influx.query(new Query("SHOW RETENTION POLICIES ON " + db, db));
            assertFalse(rps.getResults().get(0).getSeries().isEmpty());

            /* DDL：删库 */
            influx.deleteDatabase(db);
            assertFalse(influx.describeDatabases().contains(db));
        } finally {
            influx.close();
        }
    }

    @Test
    void userAdmin_createShowGrantDrop() {
        InfluxDB influx = InfluxDBFactory.connect(URL, ADMIN, PASS);
        try {
            String u = "it_u_" + Long.toHexString(System.nanoTime());
            influx.query(new Query("CREATE USER " + u + " WITH PASSWORD 'pwd12345'", "testdb"));
            influx.query(new Query("GRANT READ ON testdb TO " + u, "testdb"));

            QueryResult users = influx.query(new Query("SHOW USERS", "testdb"));
            boolean found = users.getResults().get(0).getSeries().get(0).getValues().stream()
                    .anyMatch(v -> v.get(0).equals(u));
            assertTrue(found);

            influx.query(new Query("DROP USER " + u, "testdb"));
        } finally {
            influx.close();
        }
    }

    @Test
    void engineWritePoint_throughRealClient() throws Exception {
        InfluxDB influx = InfluxDBFactory.connect(URL, ADMIN, PASS);
        String db = "it_eng_" + System.nanoTime();
        influx.createDatabase(db);
        try {
            InfluxDbEngine engine = new InfluxDbEngine();
            engine.addDataSource("influx",
                    new InfluxDbEngineDataSource("influx", URL, ADMIN, PASS, db, influx));

            Point p = Point.measurement("mem")
                    .time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                    .addField("used", (Number) 0.7).build();
            engine.write(p);

            boolean found = false;
            for (int i = 0; i < 10 && !found; i++) {
                Thread.sleep(500);
                QueryResult qr = influx.query(new Query("SELECT used FROM mem", db));
                List<QueryResult.Series> rows = qr.getResults().get(0).getSeries();
                found = rows != null && !rows.isEmpty();
            }
            assertTrue(found, "engine.write 后应能查到 mem 数据");
        } finally {
            influx.deleteDatabase(db);
            influx.close();
        }
    }
}
