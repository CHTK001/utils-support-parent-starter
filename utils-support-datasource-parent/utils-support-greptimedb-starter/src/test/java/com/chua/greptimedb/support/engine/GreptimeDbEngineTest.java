package com.chua.greptimedb.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GreptimeDB 引擎单元测试（无需服务）。
 *
 * @author CH
 * @since 4.0.0.42
 */
class GreptimeDbEngineTest {

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

    /**
     * 内存旁路存储必须被拒绝：数据只能经 write() 真实落库。
     */
    @Test
    void store_is_rejected() {
        GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");
        assertThrows(UnsupportedOperationException.class,
                () -> engine.store("metric", java.util.Collections.emptyList()));
    }

    /**
     * 时序库无 UPDATE：相同 tag + 时间戳重复写入即为覆盖。
     */
    @Test
    void update_is_rejected_with_tsdb_semantics() {
        GreptimeDbEngine engine = (GreptimeDbEngine) Engine.create("greptimedb");
        // 终端方法 update() 触发 executeUpdate → 语义拒绝
        assertThrows(UnsupportedOperationException.class,
                () -> engine.update(Dummy.class)
                        .set(Dummy::getHost, "y")
                        .eq(Dummy::getHost, "x")
                        .update());
    }

    /**
     * 测试占位实体。
     */
    public static class Dummy {
        /**
         * 主机标识
         */
        private String host;

        /** 获取主机标识 */
        public String getHost() { return host; }
        /** 设置主机标识 */
        public void setHost(String host) { this.host = host; }
    }
}
