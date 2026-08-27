package com.chua.common.support.vector;

import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 向量存储性能基准测试。
 */
public class VectorStorageBenchmark {

    private static final int DIMENSION = 128;
    private static final int COUNT = 100_000;
    private static final int TOP_K = 10;
    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASURE_ROUNDS = 5;
    private static final int FLUSH_BATCH = 10_000;

    private static final Random RANDOM = new ThreadLocalRandom();
    private static Path testDir;

    @BeforeAll
    static void setup() throws Exception {
        testDir = Files.createTempDirectory("vector-bench-");
        System.out.println("测试目录: " + testDir.toAbsolutePath());
    }

    @AfterAll
    static void teardown() {
        deleteRecursively(testDir);
    }

    private static void deleteRecursively(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { java.nio.file.Files.delete(p); } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }

    private static float[] randomVector(int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = RANDOM.nextFloat() * 2 - 1;
        float norm = 0f;
        for (float f : v) norm += f * f;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < dim; i++) v[i] /= norm;
        return v;
    }

    // ==================== MemoryVectorStorage ====================

    @Test
    @DisplayName("MemoryVectorStorage - 写入 + 搜索 性能测试")
    void testMemoryStorage() {
        int dim = DIMENSION;
        MemoryVectorStorage storage = new MemoryVectorStorage(dim, VectorCompareAlgorithm.cosine());

        // 预热
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            MemoryVectorStorage s = new MemoryVectorStorage(dim, VectorCompareAlgorithm.cosine());
            for (int i = 0; i < COUNT / 10; i++) {
                s.add("id_" + i, randomVector(dim));
            }
            s.search(randomVector(dim), TOP_K);
            s.close();
        }

        // 正式测试 - 写入
        long writeStart = System.nanoTime();
        for (int i = 0; i < COUNT; i++) {
            storage.add("id_" + i, randomVector(dim));
        }
        long writeMs = (System.nanoTime() - writeStart) / 1_000_000L;
        System.out.printf("[Memory] 写入 %d 条: %d ms (%.0f ops/s)%n",
                COUNT, writeMs, COUNT * 1000.0 / Math.max(writeMs, 1));

        // 正式测试 - 搜索（多次）
        float[] query = randomVector(dim);
        long searchTotalNs = 0;
        for (int round = 0; round < MEASURE_ROUNDS; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                storage.search(query, TOP_K);
            }
            searchTotalNs += (System.nanoTime() - start);
        }
        long searchNsPerCall = searchTotalNs / (MEASURE_ROUNDS * 100L);
        System.out.printf("[Memory] 单次搜索 %d 维 topK=%d: %d us (%.0f qps)%n",
                dim, TOP_K, searchNsPerCall / 1000, 1_000_000.0 / Math.max(searchNsPerCall, 1));

        storage.close();
        System.out.printf("[Memory] 总大小: %d%n", storage.size());
    }

    // ==================== DefaultVectorStorage - MEMORY ====================

    @Test
    @DisplayName("DefaultVectorStorage(MEMORY) - 写入 + 搜索 性能测试")
    void testDefaultMemory() {
        int dim = DIMENSION;
        DefaultVectorStorage storage = DefaultVectorStorage.builder()
                .dimension(dim)
                .mode(DefaultVectorStorage.Mode.MEMORY)
                .build();

        // 预热
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            DefaultVectorStorage s = DefaultVectorStorage.builder()
                    .dimension(dim).mode(DefaultVectorStorage.Mode.MEMORY).build();
            for (int i = 0; i < COUNT / 10; i++) s.add("id_" + i, randomVector(dim));
            s.search(randomVector(dim), TOP_K);
            s.close();
        }

        // 写入
        long writeStart = System.nanoTime();
        for (int i = 0; i < COUNT; i++) {
            storage.add("id_" + i, randomVector(dim));
        }
        long writeMs = (System.nanoTime() - writeStart) / 1_000_000L;
        System.out.printf("[Default-MEM] 写入 %d 条: %d ms (%.0f ops/s)%n",
                COUNT, writeMs, COUNT * 1000.0 / Math.max(writeMs, 1));

        // 搜索
        float[] query = randomVector(dim);
        long searchTotalNs = 0;
        for (int round = 0; round < MEASURE_ROUNDS; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                storage.search(query, TOP_K);
            }
            searchTotalNs += (System.nanoTime() - start);
        }
        long searchNsPerCall = searchTotalNs / (MEASURE_ROUNDS * 100L);
        System.out.printf("[Default-MEM] 单次搜索: %d us (%.0f qps)%n",
                searchNsPerCall / 1000, 1_000_000.0 / Math.max(searchNsPerCall, 1));

        storage.close();
    }

    // ==================== DefaultVectorStorage - HYBRID ====================

    @Test
    @DisplayName("DefaultVectorStorage(HYBRID) - 写入+刷盘+搜索 性能测试")
    void testDefaultHybrid() throws Exception {
        int dim = DIMENSION;
        DefaultVectorStorage storage = DefaultVectorStorage.builder()
                .dimension(dim)
                .dir(testDir.resolve("hybrid"))
                .mode(DefaultVectorStorage.Mode.HYBRID)
                .shardSize(FLUSH_BATCH)
                .build();

        // 预热
        DefaultVectorStorage warmup = DefaultVectorStorage.builder()
                .dimension(dim)
                .dir(testDir.resolve("hybrid-warmup"))
                .mode(DefaultVectorStorage.Mode.HYBRID)
                .shardSize(FLUSH_BATCH)
                .build();
        for (int i = 0; i < COUNT / 10; i++) warmup.add("id_" + i, randomVector(dim));
        warmup.flush();
        warmup.close();

        // 写入 + 分批刷盘
        long writeStart = System.nanoTime();
        for (int i = 0; i < COUNT; i++) {
            storage.add("id_" + i, randomVector(dim));
            if ((i + 1) % FLUSH_BATCH == 0) storage.flush();
        }
        storage.flush(); // 剩余热数据也刷盘
        long writeMs = (System.nanoTime() - writeStart) / 1_000_000L;
        System.out.printf("[Default-HYBRID] 写入 %d 条 + %d 次刷盘: %d ms (%.0f ops/s)%n",
                COUNT, COUNT / FLUSH_BATCH + 1, writeMs, COUNT * 1000.0 / Math.max(writeMs, 1));

        // 重新加载（模拟重启）
        storage.close();
        DefaultVectorStorage reloaded = DefaultVectorStorage.builder()
                .dimension(dim)
                .dir(testDir.resolve("hybrid"))
                .mode(DefaultVectorStorage.Mode.HYBRID)
                .shardSize(FLUSH_BATCH)
                .build();

        // 搜索
        float[] query = randomVector(dim);
        long searchTotalNs = 0;
        for (int round = 0; round < MEASURE_ROUNDS; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                reloaded.search(query, TOP_K);
            }
            searchTotalNs += (System.nanoTime() - start);
        }
        long searchNsPerCall = searchTotalNs / (MEASURE_ROUNDS * 100L);
        System.out.printf("[Default-HYBRID] 单次搜索(已加载): %d us (%.0f qps)%n",
                searchNsPerCall / 1000, 1_000_000.0 / Math.max(searchNsPerCall, 1));

        // 对比：未加载（纯 FILE 模式，每次重新读磁盘）
        DefaultVectorStorage fileOnly = DefaultVectorStorage.builder()
                .dimension(dim)
                .dir(testDir.resolve("hybrid"))
                .mode(DefaultVectorStorage.Mode.FILE)
                .shardSize(FLUSH_BATCH)
                .build();
        long searchNsFile = 0;
        for (int round = 0; round < MEASURE_ROUNDS; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                fileOnly.search(query, TOP_K);
            }
            searchNsFile += (System.nanoTime() - start);
        }
        long fileNsPerCall = searchNsFile / (MEASURE_ROUNDS * 100L);
        System.out.printf("[Default-FILE] 单次搜索(纯磁盘): %d us (%.0f qps)%n",
                fileNsPerCall / 1000, 1_000_000.0 / Math.max(fileNsPerCall, 1));
        System.out.printf("HYBRID 比 FILE 快 %.1f 倍%n",
                (double) fileNsPerCall / Math.max(searchNsPerCall, 1));

        reloaded.close();
        fileOnly.close();
        System.out.printf("[Default-HYBRID] 总大小: %d%n", reloaded.size());
    }

    // ==================== 大规模测试 ====================

    @Test
    @DisplayName("大规模 - 100万条向量搜索性能")
    void testLargeScale() {
        int dim = DIMENSION;
        int largeCount = 1_000_000;
        int largeFlushBatch = 20_000;

        DefaultVectorStorage storage = DefaultVectorStorage.builder()
                .dimension(dim)
                .dir(testDir.resolve("large"))
                .mode(DefaultVectorStorage.Mode.HYBRID)
                .shardSize(largeFlushBatch)
                .build();

        System.out.println("[Large] 写入 100 万条...");
        long t0 = System.nanoTime();
        for (int i = 0; i < largeCount; i++) {
            storage.add("id_" + i, randomVector(dim));
            if ((i + 1) % largeFlushBatch == 0) storage.flush();
        }
        storage.flush();
        long writeMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.printf("[Large] 写入完成: %d ms (%.0f ops/s)%n", writeMs, largeCount * 1000.0 / Math.max(writeMs, 1));

        storage.close();

        System.out.println("[Large] 重新加载并搜索...");
        DefaultVectorStorage loaded = DefaultVectorStorage.builder()
                .dimension(dim)
                .dir(testDir.resolve("large"))
                .mode(DefaultVectorStorage.Mode.HYBRID)
                .shardSize(largeFlushBatch)
                .build();
        System.out.printf("[Large] 加载后大小: %d%n", loaded.size());

        float[] query = randomVector(dim);
        long searchTotalNs = 0;
        for (int round = 0; round < 5; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < 10; i++) loaded.search(query, 10);
            searchTotalNs += (System.nanoTime() - start);
        }
        long nsPerCall = searchTotalNs / 50L;
        System.out.printf("[Large] 单次搜索 topK=10: %d us (%.0f qps)%n",
                nsPerCall / 1000, 1_000_000.0 / Math.max(nsPerCall, 1));

        loaded.close();
    }
}
