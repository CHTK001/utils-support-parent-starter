package com.chua.influxdb.support.engine;

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
 * InfluxDB upsert 语义验证：相同 measurement+tag+timestamp 写入即覆盖。
 */
class InfluxUpsertIT {

    private static final String URL = "http://172.16.0.40:8087";
    private static final String ADMIN = "admin";
    private static final String PASS = "admin123";

    @BeforeAll
    static void assumeReachable() {
        try {
            var s = new Socket();
            s.connect(new InetSocketAddress("172.16.0.40", 8087), 3000);
            s.close();
        } catch (Exception e) {
            Assumptions.abort("InfluxDB 不可达，跳过");
        }
    }

    @Test
    void upsert_semantics_writeOverwrites() throws Exception {
        InfluxDB influx = InfluxDBFactory.connect(URL, ADMIN, PASS);
        String db = "it_ups_" + System.nanoTime();
        influx.createDatabase(db);
        try {
            long ts = System.currentTimeMillis();

            /* 第一次写入：load=0.1 */
            influx.setDatabase(db).write(Point.measurement("cpu")
                    .time(ts, TimeUnit.MILLISECONDS)
                    .addField("usage", (Number) 0.1).build());

            Thread.sleep(500);
            QueryResult r1 = influx.query(new Query("SELECT usage FROM cpu", db));
            var series1 = r1.getResults().get(0).getSeries();
            assertNotNull(series1, "首次写入后应有数据");

            /* 第二次写入：同 timestamp，不同 value → 覆盖（upsert 语义）*/
            influx.setDatabase(db).write(Point.measurement("cpu")
                    .time(ts, TimeUnit.MILLISECONDS)
                    .addField("usage", (Number) 0.99).build());

            Thread.sleep(500);

            /* 验证值已覆盖为 0.99 */
            QueryResult r2 = influx.query(new Query("SELECT usage FROM cpu", db));
            var values = r2.getResults().get(0).getSeries().get(0).getValues();
            assertFalse(values.isEmpty());
            Object val = values.get(0).get(1);
            double actual = ((Number) val).doubleValue();
            assertEquals(0.99, actual, 0.001, "同 timestamp 重写应覆盖旧值");

            /* 确认没有重复行（upsert 不是 insert） */
            assertEquals(1, values.size(), "upsert 后应只有 1 行（非追加）");
        } finally {
            influx.deleteDatabase(db);
            influx.close();
        }
    }
}
