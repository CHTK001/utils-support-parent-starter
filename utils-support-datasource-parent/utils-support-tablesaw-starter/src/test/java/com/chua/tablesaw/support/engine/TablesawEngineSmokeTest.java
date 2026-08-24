package com.chua.tablesaw.support.engine;

import com.chua.common.support.lang.datasource.engine.Engine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tablesaw 引擎冒烟（内存 DataFrame 库，库语义即内存）。
 *
 * @author CH
 * @since 4.0.0.42
 */
class TablesawEngineSmokeTest {

    /**
     * SPI 创建与关闭。
     */
    @Test
    void spi_create_and_close() {
        Engine engine = Engine.create("tablesaw");
        assertNotNull(engine);
        assertDoesNotThrow(engine::close);
    }

    /**
     * 默认数据源管理可用。
     */
    @Test
    void datasource_registration() {
        TablesawEngine engine = (TablesawEngine) Engine.create("tablesaw");
        try {
            assertNull(engine.getDefaultDataSourceName());
            engine.setDefaultDataSourceName("t");
            assertEquals("t", engine.getDefaultDataSourceName());
        } finally {
            engine.close();
        }
    }
}
