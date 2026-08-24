package com.chua.hbase.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HBase 引擎单元测试（无需服务）。
 *
 * @author CH
 * @since 4.0.0.42
 */
class HBaseEngineTest {

    /**
     * SPI 键可用。
     */
    @Test
    void spi_create_works() {
        Engine engine = Engine.create("hbase");
        assertNotNull(engine);
        assertInstanceOf(HBaseEngine.class, engine);
    }

    /**
     * ORM 语义显式拒绝：HBase 使用领域 API。
     */
    @Test
    void orm_semantics_rejected() {
        HBaseEngine engine = (HBaseEngine) Engine.create("hbase");
        assertThrows(UnsupportedOperationException.class,
                () -> engine.store("t", java.util.List.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> engine.query(HBaseEngineTest.class).list());
        assertThrows(UnsupportedOperationException.class,
                () -> engine.executeDelete(null));
    }
}
