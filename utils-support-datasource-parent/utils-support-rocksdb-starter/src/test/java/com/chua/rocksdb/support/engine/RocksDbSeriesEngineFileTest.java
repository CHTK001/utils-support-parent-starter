package com.chua.rocksdb.support.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RocksDbSeriesEngine 真实文件读写测试：以临时目录作为 RocksDB 数据目录，
 * 覆盖写入、指标发现、曲线降采样、隔离性、保留期清理与重启后持久化。
 *
 * @author CH
 * @since 4.0.0.42
 */
class RocksDbSeriesEngineFileTest {

    /**
     * 临时数据目录
     */
    @TempDir
    Path tempDir;

    /**
     * 被测引擎
     */
    private RocksDbSeriesEngine engine;

    @BeforeEach
    void setUp() {
        engine = new RocksDbSeriesEngine(tempDir.resolve("series").toString(), "default");
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void writeThenQueryDownsamplesToWindowAverage() {
        // 采集点必须落在「过去」，series 以查询时刻为上界，未来点会被时间范围裁掉
        long base = System.currentTimeMillis() - 5000L;
        engine.writePoint("acc_t", 1L, "cpu_usage", 10.0, base);
        engine.writePoint("acc_t", 1L, "cpu_usage", 20.0, base + 1000L);
        engine.writePoint("acc_t", 1L, "cpu_usage", 30.0, base + 2000L);

        List<List<Object>> curve = engine.series("acc_t", 1L, "cpu_usage", 1, "1m");
        assertEquals(1, curve.size(), "同一分钟内的点应聚成一个桶: " + curve);
        assertEquals(20.0D, ((Number) curve.get(0).get(1)).doubleValue(), 0.0001D);
    }

    @Test
    void metricsDiscoversWrittenMetrics() {
        long now = System.currentTimeMillis();
        engine.writePoint("acc_t", 1L, "mem_usage", 55.0, now);
        engine.writePoint("acc_t", 1L, "cpu_usage", 12.5, now);

        assertEquals(List.of("cpu_usage", "mem_usage"), engine.metrics("acc_t", 1L));
    }

    @Test
    void seriesWithoutMetricAmbiguousThrowsIae() {
        long now = System.currentTimeMillis();
        engine.writePoint("acc_t", 1L, "mem_usage", 55.0, now);
        engine.writePoint("acc_t", 1L, "cpu_usage", 12.5, now);

        assertThrows(IllegalArgumentException.class, () -> engine.series("acc_t", 1L, null, 1, "1m"));
    }

    @Test
    void monitorsAndTargetsAreIsolated() {
        long now = System.currentTimeMillis();
        engine.writePoint("acc_t", 1L, "cpu_usage", 11.0, now);
        engine.writePoint("acc_t", 2L, "cpu_usage", 22.0, now);
        engine.writePoint("other_t", 1L, "cpu_usage", 33.0, now);

        List<List<Object>> monitor1 = engine.series("acc_t", 1L, "cpu_usage", 1, "1m");
        List<List<Object>> monitor2 = engine.series("acc_t", 2L, "cpu_usage", 1, "1m");
        List<List<Object>> other = engine.series("other_t", 1L, "cpu_usage", 1, "1m");

        assertEquals(11.0D, ((Number) monitor1.get(0).get(1)).doubleValue(), 0.0001D);
        assertEquals(22.0D, ((Number) monitor2.get(0).get(1)).doubleValue(), 0.0001D);
        assertEquals(33.0D, ((Number) other.get(0).get(1)).doubleValue(), 0.0001D);
    }

    @Test
    void purgeRemovesExpiredPointsAndReclaimsIndex() {
        long now = System.currentTimeMillis();
        long old = now - 10L * 86_400_000L;
        engine.writePoint("acc_t", 1L, "cpu_usage", 1.0, old);
        engine.writePoint("acc_t", 1L, "cpu_usage", 2.0, now);

        assertEquals(1, engine.purge("acc_t", 1L, "cpu_usage", now - 5L * 86_400_000L));
        assertEquals(List.of("cpu_usage"), engine.metrics("acc_t", 1L));

        // 再清掉剩余点，索引应同步摘除
        assertEquals(1, engine.purge("acc_t", 1L, "cpu_usage", now + 1000L));
        assertTrue(engine.metrics("acc_t", 1L).isEmpty());
    }

    @Test
    void purgeAcrossAllMonitors() {
        long now = System.currentTimeMillis();
        engine.writePoint("acc_t", 1L, "cpu_usage", 1.0, now);
        engine.writePoint("acc_t", 2L, "cpu_usage", 2.0, now);

        assertEquals(2, engine.purge("acc_t", null, null, now + 1000L));
        assertTrue(engine.metrics("acc_t", 1L).isEmpty());
        assertTrue(engine.metrics("acc_t", 2L).isEmpty());
    }

    @Test
    void dataSurvivesReopen() throws IOException {
        long now = System.currentTimeMillis();
        engine.writePoint("acc_t", 1L, "cpu_usage", 42.0, now);
        engine.close();

        // 重新打开同一目录（RocksDB 单进程独占，必须先关闭旧实例）
        engine = new RocksDbSeriesEngine(tempDir.resolve("series").toString(), "default");
        assertTrue(engine.isAvailable());
        List<List<Object>> curve = engine.series("acc_t", 1L, "cpu_usage", 1, "1m");
        assertFalse(curve.isEmpty());
        assertEquals(42.0D, ((Number) curve.get(0).get(1)).doubleValue(), 0.0001D);
    }
}
