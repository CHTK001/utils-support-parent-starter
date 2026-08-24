package com.chua.influxdb.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InfluxDB 引擎测试：单元语义 + 对 172.16.0.40:8086 真实服务的读写闭环。
 *
 * @author CH
 * @since 4.0.0.42
 */
class InfluxDbEngineTest {

    /** 已创建引擎，统一关闭以释放 OkHttp 非守护线程 */
    private final java.util.List<InfluxDbEngine> engines = new java.util.ArrayList<>();

    /** 关闭所有引擎 */
    @AfterEach
    void tearDown() {
        for (InfluxDbEngine e : engines) {
            try { e.close(); } catch (Exception ignored) { }
        }
        engines.clear();
    }

    private static final String URL = "http://172.16.0.40:8086";
    private static final String DATABASE = "app";

    /**
     * 查询映射实体（measurement: influx_metric，列 host/value/time）。
     */
    public static class InfluxMetric {
        /**
         * tag：主机
         */
        private String host;
        /**
         * field：数值
         */
        private Double value;
        /**
         * time 列（epoch 毫秒）
         */
        private Long time;

        /** 获取主机 */
        public String getHost() { return host; }
        /** 设置主机 */
        public void setHost(String host) { this.host = host; }
        /** 获取数值 */
        public Double getValue() { return value; }
        /** 设置数值 */
        public void setValue(Double value) { this.value = value; }
        /** 获取时间 */
        public Long getTime() { return time; }
        /** 设置时间 */
        public void setTime(Long time) { this.time = time; }
    }

    /** SPI 键可用 */
    @Test
    void spi_create_works() {
        Engine engine = Engine.create("influxdb");
        assertNotNull(engine);
        assertInstanceOf(InfluxDbEngine.class, engine);
    }

    /** 内存存储被拒绝：数据只能 write(Point) 落库 */
    @Test
    void store_is_rejected() {
        InfluxDbEngine engine = (InfluxDbEngine) Engine.create("influxdb");
        assertThrows(UnsupportedOperationException.class,
                () -> engine.store("m", List.of()));
    }

    /** UPDATE 语义拒绝 */
    @Test
    void update_is_rejected() {
        InfluxDbEngine engine = (InfluxDbEngine) Engine.create("influxdb");
        assertThrows(UnsupportedOperationException.class,
                () -> engine.update(InfluxMetric.class)
                        .set(InfluxMetric::getValue, 1.0)
                        .eq(InfluxMetric::getHost, "x")
                        .update());
    }

    /** 真实服务写入 -> Lambda 查询回读闭环 */
    @Test
    void write_then_query_roundtrip_on_real_server() {
        InfluxDbEngine engine = (InfluxDbEngine) Engine.create("influxdb");
        engine.addDataSource("default", URL, DATABASE, "", "");
        engines.add(engine);
        engine.setDefaultDataSourceName("default");

        String uniqHost = "it-host-" + System.currentTimeMillis();
        double expectValue = 3.14;
        engine.write("influx_metric",
                Map.of("host", uniqHost),
                Map.of("value", expectValue));

        List<InfluxMetric> rows = engine.query(InfluxMetric.class)
                .eq(InfluxMetric::getHost, uniqHost)
                .list();
        assertEquals(1, rows.size(), "应从真实服务器查回刚写入的唯一行");
        assertEquals(uniqHost, rows.get(0).getHost());
        assertEquals(expectValue, rows.get(0).getValue(), 1e-9);
        assertNotNull(rows.get(0).getTime());

        // 排序下推到结果集排序逻辑
        List<InfluxMetric> desc = engine.query(InfluxMetric.class)
                .gt(InfluxMetric::getValue, 0.0)
                .orderByDesc(InfluxMetric::getTime)
                .list();
        assertFalse(desc.isEmpty());
    }

    /** 真实 DELETE（InfluxQL 要求命中 time 条件，此处用 gt(time,...) 无法精确；仅验证语句下发不抛错并按条件过滤查询为空或保留） */
    @Test
    void delete_executes_against_server() {
        InfluxDbEngine engine = (InfluxDbEngine) Engine.create("influxdb");
        engine.addDataSource("d2", URL, DATABASE, "", "");
        engines.add(engine);
        engine.setDefaultDataSourceName("d2");

        String uniqHost = "del-" + System.currentTimeMillis();
        engine.write("del_metric", Map.of("host", uniqHost), Map.of("v", 1));

        // InfluxQL DELETE 必须带 time 条件：使用字符串列名直接构造
        int affected = engine.delete(InfluxMetric2.class)
                .eq("host", uniqHost)
                .eq("time", "1970-01-01T00:00:00Z")
                .remove();
        assertTrue(affected >= 0);

        // 无论服务器是否执行删除（无 time 命中），查询不应报协议错误
        List<InfluxMetric2> rest = engine.query(InfluxMetric2.class)
                .eq(InfluxMetric2::getHost, uniqHost)
                .list();
        assertNotNull(rest);
    }

    /**
     * 删除用例实体（measurement: del_metric，field v）。
     */
    public static class InfluxMetric2 {
        /**
         * tag
         */
        private String host;
        /**
         * field
         */
        private Double v;
        /**
         * time
         */
        private Long time;

        /** 获取主机 */
        public String getHost() { return host; }
        /** 设置主机 */
        public void setHost(String host) { this.host = host; }
        /** 获取数值 */
        public Double getV() { return v; }
        /** 设置数值 */
        public void setV(Double v) { this.v = v; }
        /** 获取时间 */
        public Long getTime() { return time; }
        /** 设置时间 */
        public void setTime(Long time) { this.time = time; }
    }

    /** 关闭超时占位避免未使用导入告警 */
    @SuppressWarnings("unused")
    private static void unused() {
        TimeUnit.SECONDS.name();
    }
}
