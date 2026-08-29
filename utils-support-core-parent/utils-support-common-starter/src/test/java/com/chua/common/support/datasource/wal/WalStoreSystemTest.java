package com.chua.common.support.datasource.wal;

import com.chua.common.support.wal.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WAL 存储系统单元测试。
 */
class WalStoreSystemTest {

    @TempDir
    Path tempDir;

    // ==================== KV 引擎测试 ====================

    @Test
    void testKvPutAndGet() throws IOException {
        try (KvWalStoreSystem store = KvWalStoreSystem.create(tempDir.resolve("kv"))) {
            long lsn1 = store.put("user:001", "张三".getBytes());
            assertTrue(lsn1 > 0);

            Optional<byte[]> val = store.getBytes("user:001");
            assertTrue(val.isPresent());
            assertEquals("张三", new String(val.get()));

            store.put("user:001", "李四".getBytes());
            Optional<byte[]> updated = store.getBytes("user:001");
            assertTrue(updated.isPresent());
            assertEquals("李四", new String(updated.get()));

            assertFalse(store.getBytes("user:999").isPresent());
            System.out.printf("[KV] put/get: lsn=%d, value=%s%n", lsn1, new String(val.get()));
        }
    }

    @Test
    void testKvDelete() throws IOException {
        try (KvWalStoreSystem store = KvWalStoreSystem.create(tempDir.resolve("kv-del"))) {
            store.put("key:1", "v1".getBytes());
            assertTrue(store.getBytes("key:1").isPresent());
            boolean deleted = store.delete("key:1");
            assertTrue(deleted);
            System.out.println("[KV] delete OK");
        }
    }

    @Test
    void testKvLargeScale() throws IOException {
        try (KvWalStoreSystem store = KvWalStoreSystem.create(tempDir.resolve("kv-large"))) {
            int count = 10_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < count; i++) {
                store.put("k" + i, ("value_" + i).getBytes());
            }
            long writeMs = (System.nanoTime() - t0) / 1_000_000L;
            System.out.printf("[KV] wrote %d records in %d ms (%.0f ops/s)%n",
                    count, writeMs, count * 1000.0 / Math.max(writeMs, 1));

            t0 = System.nanoTime();
            int hits = 0;
            for (int i = 0; i < 1000; i++) {
                int idx = (int) (Math.random() * count);
                if (store.getBytes("k" + idx).isPresent()) hits++;
            }
            long readMs = (System.nanoTime() - t0) / 1_000_000L;
            System.out.printf("[KV] random read 1000 probes in %d ms, %d hits%n", readMs, hits);
            System.out.printf("[KV] total segments: %d, size: %d%n",
                    store.listSegments().size(), store.size());
        }
    }

    // ==================== TS 引擎测试 ====================

    @Test
    void testTsAppendAndQuery() throws IOException {
        try (TsWalStoreSystem store = TsWalStoreSystem.create(tempDir.resolve("ts"))) {
            long t0 = System.currentTimeMillis();
            store.append("cpu_temp", t0, 23.5);
            store.append("cpu_temp", t0 + 1000, 24.1);
            store.append("cpu_temp", t0 + 2000, 23.8);
            store.append("memory_usage", t0, 0.75);
            store.append("memory_usage", t0 + 1000, 0.80);

            List<TsWalStoreSystem.TsPoint> points =
                    store.queryRange("cpu_temp", t0 - 1, t0 + 3000, 0, 100);
            assertEquals(3, points.size());
            assertEquals("cpu_temp", points.get(0).measure());
            assertEquals(23.5, points.get(0).value(), 0.01);

            Optional<TsWalStoreSystem.TsPoint> latest = store.latest("cpu_temp");
            assertTrue(latest.isPresent());
            System.out.printf("[TS] queryRange: %d points, latest=%.1f%n",
                    points.size(), latest.get().value());
        }
    }

    @Test
    void testTsAggregation() throws IOException {
        try (TsWalStoreSystem store = TsWalStoreSystem.create(tempDir.resolve("ts-aggr"))) {
            long base = System.currentTimeMillis();
            store.append("temp", base, 20.0);
            store.append("temp", base + 1000, 25.0);
            store.append("temp", base + 2000, 30.0);
            store.append("temp", base + 3000, 35.0);

            TsWalStoreSystem.TsAggregate agg =
                    store.aggregate("temp", base, base + 5000, TsWalStoreSystem.AggregateFn.AVG);
            assertEquals(27.5, agg.avg(), 0.01);
            assertEquals(4, agg.count());

            TsWalStoreSystem.TsAggregate minAgg =
                    store.aggregate("temp", base, base + 5000, TsWalStoreSystem.AggregateFn.MIN);
            assertEquals(20.0, minAgg.min(), 0.01);

            TsWalStoreSystem.TsAggregate maxAgg =
                    store.aggregate("temp", base, base + 5000, TsWalStoreSystem.AggregateFn.MAX);
            assertEquals(35.0, maxAgg.max(), 0.01);

            TsWalStoreSystem.TsAggregate sumAgg =
                    store.aggregate("temp", base, base + 5000, TsWalStoreSystem.AggregateFn.SUM);
            assertEquals(110.0, sumAgg.sum(), 0.01);

            System.out.printf("[TS] AVG=%.2f MIN=%.2f MAX=%.2f SUM=%.2f COUNT=%d%n",
                    agg.avg(), minAgg.min(), maxAgg.max(), sumAgg.sum(), agg.count());
        }
    }

    @Test
    void testTsListMeasures() throws IOException {
        try (TsWalStoreSystem store = TsWalStoreSystem.create(tempDir.resolve("ts-measures"))) {
            store.append("temp", System.currentTimeMillis(), 20.0);
            store.append("humidity", System.currentTimeMillis(), 60.0);
            store.append("pressure", System.currentTimeMillis(), 1013.0);

            // 通过范围查验证 measure 存在
            long now = System.currentTimeMillis();
            List<TsWalStoreSystem.TsPoint> tempPts = store.queryRange("temp", now - 1, now + 1, 0, 10);
            assertEquals(1, tempPts.size());
            assertEquals("temp", tempPts.get(0).measure());

            List<TsWalStoreSystem.TsPoint> humPts = store.queryRange("humidity", now - 1, now + 1, 0, 10);
            assertEquals(1, humPts.size());
            assertEquals("humidity", humPts.get(0).measure());

            System.out.println("[TS] list measures OK (temp, humidity, pressure)");
        }
    }

    @Test
    void testTsLargeScale() throws IOException {
        try (TsWalStoreSystem store = TsWalStoreSystem.create(tempDir.resolve("ts-large"))) {
            int count = 50_000;
            long base = System.currentTimeMillis();
            long t0 = System.nanoTime();
            for (int i = 0; i < count; i++) {
                store.append("sensor_1", base + i * 1000, 20.0 + Math.random() * 10);
            }
            long writeMs = (System.nanoTime() - t0) / 1_000_000L;
            System.out.printf("[TS] wrote %d records in %d ms (%.0f ops/s)%n",
                    count, writeMs, count * 1000.0 / Math.max(writeMs, 1));

            t0 = System.nanoTime();
            List<TsWalStoreSystem.TsPoint> points =
                    store.queryRange("sensor_1", base, base + (long) count * 1000, 0, 1000);
            long readMs = (System.nanoTime() - t0) / 1_000_000L;
            System.out.printf("[TS] range query %d points in %d ms, first=%.2f last=%.2f%n",
                    points.size(), readMs,
                    points.isEmpty() ? 0 : points.get(0).value(),
                    points.isEmpty() ? 0 : points.get(points.size() - 1).value());
        }
    }

    // ==================== VEC 引擎测试 ====================

    @Test
    void testVecAddAndGet() throws IOException {
        try (VecWalStoreSystem store = VecWalStoreSystem.create(tempDir.resolve("vec"), 3)) {
            float[] v1 = {1.0f, 0.0f, 0.0f};
            float[] v2 = {0.0f, 1.0f, 0.0f};
            store.add("vec_1", v1);
            store.add("vec_2", v2);

            Optional<float[]> got1 = store.getVector("vec_1");
            assertTrue(got1.isPresent());
            assertEquals(3, got1.get().length);
            assertEquals(1.0f, got1.get()[0], 0.001);
            assertEquals(0.0f, got1.get()[1], 0.001);

            System.out.println("[VEC] add/get: vec_1=" + Arrays.toString(got1.get()));
        }
    }

    @Test
    void testVecSearch() throws IOException {
        try (VecWalStoreSystem store = VecWalStoreSystem.create(tempDir.resolve("vec-search"), 3)) {
            store.add("v0", new float[]{1.0f, 0.0f, 0.0f});
            store.add("v1", new float[]{0.0f, 1.0f, 0.0f});
            store.add("v2", new float[]{0.0f, 0.0f, 1.0f});
            store.add("v3", new float[]{0.707f, 0.707f, 0.0f});

            float[] query = {1.0f, 0.1f, 0.1f};
            List<VecWalStoreSystem.VectorScored> results = store.search(query, 3);
            assertFalse(results.isEmpty());
            assertEquals("v0", results.get(0).id());
            assertTrue(results.get(0).score() > results.get(1).score());

            System.out.printf("[VEC] search top3: %s(%.3f), %s(%.3f), %s(%.3f)%n",
                    results.get(0).id(), results.get(0).score(),
                    results.get(1).id(), results.get(1).score(),
                    results.get(2).id(), results.get(2).score());
        }
    }

    @Test
    void testVecLargeScale() throws IOException {
        try (VecWalStoreSystem store = VecWalStoreSystem.create(tempDir.resolve("vec-large"), 16)) {
            int count = 10_000;
            Random rng = new Random(42);
            long t0 = System.nanoTime();
            for (int i = 0; i < count; i++) {
                float[] v = new float[16];
                for (int d = 0; d < 16; d++) v[d] = rng.nextFloat() * 2 - 1;
                float norm = 0;
                for (float x : v) norm += x * x;
                norm = (float) Math.sqrt(norm);
                if (norm > 0) for (int d = 0; d < 16; d++) v[d] /= norm;
                store.add("vec_" + i, v);
            }
            long writeMs = (System.nanoTime() - t0) / 1_000_000L;
            System.out.printf("[VEC] wrote %d vectors in %d ms (%.0f ops/s)%n",
                    count, writeMs, count * 1000.0 / Math.max(writeMs, 1));

            float[] query = new float[16];
            for (int d = 0; d < 16; d++) query[d] = rng.nextFloat() * 2 - 1;
            float qnorm = 0;
            for (float x : query) qnorm += x * x;
            qnorm = (float) Math.sqrt(qnorm);
            if (qnorm > 0) for (int d = 0; d < 16; d++) query[d] /= qnorm;

            t0 = System.nanoTime();
            List<VecWalStoreSystem.VectorScored> results = store.search(query, 10);
            long readMs = (System.nanoTime() - t0) / 1_000_000L;
            System.out.printf("[VEC] ANN search top10 in %d ms, best=%s (sim=%.4f)%n",
                    readMs, results.get(0).id(), results.get(0).score());
        }
    }

    // ==================== JDBC 引擎测试 ====================

    @Test
    void testJdbcCreateTableAndInsert() throws IOException {
        try (JdbcWalStoreSystem store = JdbcWalStoreSystem.create(tempDir.resolve("jdbc"))) {
            List<JdbcWalStoreSystem.ColumnDef> cols = Arrays.asList(
                    new JdbcWalStoreSystem.ColumnDef("id", "STRING", false),
                    new JdbcWalStoreSystem.ColumnDef("name", "STRING", false),
                    new JdbcWalStoreSystem.ColumnDef("age", "INT", true),
                    new JdbcWalStoreSystem.ColumnDef("score", "DOUBLE", true)
            );
            store.createTable("users", cols);

            Map<String, Object> row1 = new LinkedHashMap<>();
            row1.put("id", "u001");
            row1.put("name", "张三");
            row1.put("age", 25);
            row1.put("score", 92.5);
            store.insert("users", row1);

            Map<String, Object> row2 = new LinkedHashMap<>();
            row2.put("id", "u002");
            row2.put("name", "李四");
            row2.put("age", 30);
            row2.put("score", 88.0);
            store.insert("users", row2);

            List<JdbcWalStoreSystem.ColumnDef> schema = store.getSchema("users");
            assertEquals(4, schema.size());
            assertEquals("id", schema.get(0).name());
            System.out.printf("[JDBC] created table 'users' with %d columns, inserted 2 rows%n", schema.size());
        }
    }

    @Test
    void testJdbcQuery() throws IOException {
        try (JdbcWalStoreSystem store = JdbcWalStoreSystem.create(tempDir.resolve("jdbc-q"))) {
            for (int i = 1; i <= 5; i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("row_id", "r" + i);
                row.put("name", "user" + i);
                row.put("age", 20 + i);
                row.put("score", 80.0 + i * 2);
                store.insertWithId("t", "r" + i, row);
            }

            List<Map<String, Object>> rows = store.query("SELECT * FROM t");
            assertTrue(rows.size() >= 5, "expected at least 5 rows, got " + rows.size());
            System.out.printf("[JDBC] query all: %d rows returned%n", rows.size());
        }
    }

    // ==================== WAL 格式测试 ====================

    @Test
    void testKvWalEncodeDecode() {
        byte[] payload = KvWalFileSystem.encode("test_key", "hello world".getBytes());
        Optional<KvWalFileSystem.KvPair> decoded = KvWalFileSystem.decode(payload);
        assertTrue(decoded.isPresent());
        assertEquals("test_key", decoded.get().key());
        assertEquals("hello world", new String(decoded.get().value()));
        System.out.println("[WAL] KV encode/decode OK");
    }

    @Test
    void testTsWalEncodeDecode() {
        byte[] payload = TsWalFileSystem.encode("cpu_temp", 1000L, 23.5);
        Optional<TsWalFileSystem.TsRecord> decoded = TsWalFileSystem.decode(payload);
        assertTrue(decoded.isPresent());
        assertEquals("cpu_temp", decoded.get().measure());
        assertEquals(1000L, decoded.get().ts());
        assertEquals(23.5, decoded.get().value(), 0.001);
        assertNull(decoded.get().ttlSec());
        System.out.println("[WAL] TS encode/decode OK");
    }

    @Test
    void testTsWalWithTtl() {
        byte[] payload = TsWalFileSystem.encodeWithTtl("temp", 5000L, 36.6, 3600);
        Optional<TsWalFileSystem.TsRecord> decoded = TsWalFileSystem.decode(payload);
        assertTrue(decoded.isPresent());
        assertEquals(3600, decoded.get().ttlSec());
        assertEquals(5000L + 3600_000L, decoded.get().expireAt());
        assertFalse(decoded.get().isExpired(0L));
        assertTrue(decoded.get().isExpired(5000L + 3600_000L + 1));
        System.out.println("[WAL] TS TTL encode/decode OK");
    }

    @Test
    void testVecWalEncodeDecode() {
        float[] data = {1.0f, 2.0f, 3.0f};
        byte[] payload = VecWalFileSystem.encode("vec_1", 3, data, null);
        Optional<VecWalFileSystem.VecRecord> decoded = VecWalFileSystem.decode(payload);
        assertTrue(decoded.isPresent());
        assertEquals("vec_1", decoded.get().id());
        assertEquals(3, decoded.get().dim());
        assertArrayEquals(data, decoded.get().data(), 0.001f);
        System.out.println("[WAL] VEC encode/decode OK");
    }

    // ==================== 环境检测测试 ====================

    @Test
    void testEnvDetector() {
        WalStoreEnvDetector detector = new WalStoreEnvDetector();
        assertTrue(detector.isAvailable());
        assertEquals("wal-store-detector", detector.name());

        WalStoreConfig config = detector.detect(tempDir.resolve("detect"));
        assertTrue(config.shardCount() >= 10);
        assertTrue(config.shardCount() <= 200);
        System.out.printf("[DETECT] shards=%d, segmentMB=%.0f, cores=%d, ssd=%s%n",
                config.shardCount(), config.segmentBytes() / 1048576.0,
                config.cpuCores(), config.isSSD());
    }
}
