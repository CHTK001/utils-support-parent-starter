package com.chua.greptimedb.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GreptimeDB 引擎单元测试
 *
 * @author CH
 * @since 4.0.0.42
 */
class GreptimeDbEngineTest {

    public static class Metric {
        private String host;
        private double cpuUtil;
        private long ts;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public double getCpuUtil() {
            return cpuUtil;
        }

        public void setCpuUtil(double cpuUtil) {
            this.cpuUtil = cpuUtil;
        }

        public long getTs() {
            return ts;
        }

        public void setTs(long ts) {
            this.ts = ts;
        }
    }

    /**
     * SPI 扩展键 "greptimedb" 应正确返回 GreptimeDbEngine 实例。
     */
    @Test
    void spi_create_returns_greptimedb_engine() {
        Engine engine = Engine.create("greptimedb");
        assertNotNull(engine);
        assertInstanceOf(GreptimeDbEngine.class, engine);
    }

    /**
     * 内存数据存储与 Lambda 链式查询（无服务器，走内存过滤）。
     */
    @Test
    void store_and_query_in_memory() {
        GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");
        Metric m1 = new Metric();
        m1.setHost("host1");
        m1.setCpuUtil(0.5);
        m1.setTs(System.currentTimeMillis());
        Metric m2 = new Metric();
        m2.setHost("host2");
        m2.setCpuUtil(0.9);
        m2.setTs(System.currentTimeMillis());
        engine.store("metric", Arrays.asList(m1, m2));

        List<Metric> all = engine.query(Metric.class).list();
        assertEquals(2, all.size());

        List<Metric> filtered = engine.query(Metric.class)
                .eq(Metric::getHost, "host1")
                .list();
        assertEquals(1, filtered.size());
        assertEquals("host1", filtered.get(0).getHost());
    }

    /**
     * 通过连接串添加数据源应能构建 gRPC 客户端（不触发写入）。
     */
    @Test
    void add_datasource_builds_client() {
        GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");
        try {
            engine.addDataSource("default", "127.0.0.1:4001", "public", "", "");
            assertNotNull(engine.client());
        } finally {
            // 立即关闭，避免不可达端点的 gRPC 通道在后台无限重连
            engine.close();
        }
    }
}
