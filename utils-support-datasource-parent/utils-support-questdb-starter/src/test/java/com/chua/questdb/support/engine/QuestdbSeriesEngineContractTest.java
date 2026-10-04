package com.chua.questdb.support.engine;

import com.chua.common.support.lang.datasource.series.SeriesEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QuestdbSeriesEngine 契约分支测试：不依赖真实 QuestDB 服务，覆盖必填校验、
 * 非法标识符、不可用与关闭后的行为。
 *
 * @author CH
 * @since 4.0.0.42
 */
class QuestdbSeriesEngineContractTest {

    /**
     * 构造一个未配置（url 为空）的引擎。
     *
     * @return 未配置引擎
     */
    private QuestdbSeriesEngine unconfigured() {
        return new QuestdbSeriesEngine(new Properties());
    }

    @Test
    void engineNameIsQuestdb() {
        assertEquals("questdb", unconfigured().engine());
    }

    @Test
    void unconfiguredIsUnavailable() {
        assertFalse(unconfigured().isAvailable());
    }

    @Test
    void writePointMissingRequiredArgumentsThrowsIae() {
        QuestdbSeriesEngine engine = unconfigured();
        assertThrows(IllegalArgumentException.class, () -> engine.writePoint(null, 1L, "cpu", 1.0, 0L));
        assertThrows(IllegalArgumentException.class, () -> engine.writePoint("acc_t", null, "cpu", 1.0, 0L));
        assertThrows(IllegalArgumentException.class, () -> engine.writePoint("acc_t", 1L, null, 1.0, 0L));
    }

    @Test
    void writePointInvalidIdentifiersThrowsIae() {
        QuestdbSeriesEngine engine = unconfigured();
        assertThrows(IllegalArgumentException.class, () -> engine.writePoint("t;drop", 1L, "cpu", 1.0, 0L));
        assertThrows(IllegalArgumentException.class, () -> engine.writePoint("acc_t", 1L, "cpu-usage", 1.0, 0L));
    }

    @Test
    void writePointUnavailableThrowsIse() {
        QuestdbSeriesEngine engine = unconfigured();
        assertThrows(IllegalStateException.class, () -> engine.writePoint("acc_t", 1L, "cpu", 1.0, 0L));
    }

    @Test
    void metricsValidationAndUnavailable() {
        QuestdbSeriesEngine engine = unconfigured();
        assertThrows(IllegalArgumentException.class, () -> engine.metrics(null, 1L));
        assertThrows(IllegalArgumentException.class, () -> engine.metrics("acc-t", 1L));
        List<String> metrics = engine.metrics("acc_t", 1L);
        assertNotNull(metrics);
        assertTrue(metrics.isEmpty());
    }

    @Test
    void seriesValidationAndUnavailable() {
        QuestdbSeriesEngine engine = unconfigured();
        assertThrows(IllegalArgumentException.class, () -> engine.series(null, 1L, "cpu", 2, "1m"));
        assertThrows(IllegalArgumentException.class, () -> engine.series("acc_t", null, "cpu", 2, "1m"));
        assertThrows(IllegalArgumentException.class, () -> engine.series("acc_t", 1L, "cpu", 0, "1m"));
        assertThrows(IllegalArgumentException.class, () -> engine.series("acc_t", 1L, "cpu", -1, "1m"));
        assertThrows(IllegalArgumentException.class, () -> engine.series("acc-t", 1L, "cpu", 2, "1m"));
        assertThrows(IllegalArgumentException.class, () -> engine.series("acc_t", 1L, "cpu-usage", 2, "1m"));
        List<List<Object>> series = engine.series("acc_t", 1L, "cpu", 2, "1m");
        assertNotNull(series);
        assertTrue(series.isEmpty());
    }

    @Test
    void purgeValidationAndUnavailable() {
        QuestdbSeriesEngine engine = unconfigured();
        assertThrows(IllegalArgumentException.class, () -> engine.purge(null, 1L, "cpu", 0L));
        assertThrows(IllegalArgumentException.class, () -> engine.purge("acc-t", 1L, "cpu", 0L));
        assertThrows(IllegalArgumentException.class, () -> engine.purge("acc_t", 1L, "cpu-usage", 0L));
        assertEquals(0, engine.purge("acc_t", 1L, "cpu", 0L));
    }

    @Test
    void closeIsIdempotentAndDisablesEngine() {
        QuestdbSeriesEngine engine = new QuestdbSeriesEngine("http://127.0.0.1:9000", null, null, null);
        assertTrue(engine.isAvailable());
        engine.close();
        assertFalse(engine.isAvailable());
        assertDoesNotThrow(engine::close);
        assertThrows(IllegalStateException.class, () -> engine.writePoint("acc_t", 1L, "cpu", 1.0, 0L));
    }

    @Test
    void spiExtensionIsDiscoverable() {
        SeriesEngine engine = com.chua.common.support.spi.ServiceProvider.of(SeriesEngine.class)
                .getNewExtension("questdb", new Properties());
        assertNotNull(engine);
        assertEquals("questdb", engine.engine());
        assertFalse(engine.isAvailable());
        if (engine instanceof AutoCloseable closeable) {
            assertDoesNotThrow(() -> closeable.close());
        }
    }
}
