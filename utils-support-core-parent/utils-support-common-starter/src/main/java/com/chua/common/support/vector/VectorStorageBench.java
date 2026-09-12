package com.chua.common.support.vector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 向量存储性能基准测试 - 独立运行入口。
 * @author CH
 * @since 4.0.0
 */
public class VectorStorageBench {

    private static final int DIMENSION = 128; // 维度
    private static final int COUNT = 100_000; // 数量
    private static final int TOP_K = 10; // TOP_K
    private static final int WARMUP_ROUNDS = 3; // WARMUP_ROUNDS
    private static final int MEASURE_ROUNDS = 5; // 测量rounds
    private static final int FLUSH_BATCH = 10_000; // FLUSH_批量

    private static final Random RANDOM = ThreadLocalRandom.current(); // 随机
    private static Path testDir; // 测试dir

    /**
     * main。
     * @param args 参数
     */
    public static void main(String[] args) throws Exception {
        testDir = Files.createTempDirectory("vector-bench-");
        System.out.println("========================================");
        System.out.println("向量存储性能测试");
        System.out.println("维度: " + DIMENSION + ", 数量: " + COUNT);
        System.out.println("测试目录: " + testDir.toAbsolutePath());
        System.out.println("========================================\n");

        testMemoryStorage();
        System.out.println();
        testDefaultHybrid();
        System.out.println();
        testLargeScale();

        // 清理
        deleteRecursively(testDir);
        System.out.println("\n测试目录已清理: " + testDir);
    }

    // ==================== 工具方法 ====================

    /**
     * 随机向量。
     * @param dim dim
     * @return 随机向量的结果
     */
    private static float[] randomVector(int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = RANDOM.nextFloat() * 2 - 1;
        }
        float norm = 0f;
        for (float f : v) {
            norm += f * f;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dim; i++) {
                v[i] /= norm;
            }
        }
        return v;
    }

    /**
     * 删除recursively。
     * @param dir dir
     */
    private static void deleteRecursively(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    /**
     * print吞吐量。
     * @param label 标签
     * @param ops ops
     * @param ms ms
     */
    private static void printThroughput(String label, long ops, long ms) {
        System.out.printf("[%s] %.0f ops/s (%.0f MSOPS)%n", label, ops * 1000.0 / Math.max(ms, 1),
                ops * 1000.0 / Math.max(ms, 1) / 1_000_000.0);
    }

    // ==================== MemoryVectorStorage ====================

    /**
     * 测试内存storage。
     */
    private static void testMemoryStorage() {
        System.out.println("【1. MemoryVectorStorage】");
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

        // 写入
        long writeStart = System.nanoTime();
        for (int i = 0; i < COUNT; i++) {
            storage.add("id_" + i, randomVector(dim));
        }
        long writeMs = (System.nanoTime() - writeStart) / 1_000_000L;
        printThroughput("写入", COUNT, writeMs);

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
        long nsPerCall = searchTotalNs / (MEASURE_ROUNDS * 100L);
        System.out.printf("  单次搜索 topK=%d: %d us, %.0f QPS%n", TOP_K, nsPerCall / 1000,
                1_000_000.0 / Math.max(nsPerCall, 1));
        System.out.printf("  存储大小: %d 条%n", storage.size());
        storage.close();
    }

    // ==================== DefaultVectorStorage - HYBRID ====================

    /**
     * 测试默认hybrid。
     */
    private static void testDefaultHybrid() throws Exception {
        System.out.println("【2. DefaultVectorStorage (HYBRID)】");
        int dim = DIMENSION;

        // 预热
        DefaultVectorStorage warmup = DefaultVectorStorage.builder()
                .dimension(dim).dir(testDir.resolve("warmup"))
                .mode(DefaultVectorStorage.Mode.HYBRID).shardSize(FLUSH_BATCH).build();
        for (int i = 0; i < COUNT / 10; i++) {
            warmup.add("id_" + i, randomVector(dim));
        }
        warmup.flush(); warmup.close();

        // 写入 + 分批刷盘
        DefaultVectorStorage storage = DefaultVectorStorage.builder()
                .dimension(dim).dir(testDir.resolve("hybrid"))
                .mode(DefaultVectorStorage.Mode.HYBRID).shardSize(FLUSH_BATCH).build();

        long writeStart = System.nanoTime();
        for (int i = 0; i < COUNT; i++) {
            storage.add("id_" + i, randomVector(dim));
            if ((i + 1) % FLUSH_BATCH == 0) {
                storage.flush();
            }
        }
        storage.flush();
        long writeMs = (System.nanoTime() - writeStart) / 1_000_000L;
        printThroughput("写入+刷盘", COUNT, writeMs);

        // 重新加载（模拟重启）
        storage.close();
        DefaultVectorStorage loaded = DefaultVectorStorage.builder()
                .dimension(dim).dir(testDir.resolve("hybrid"))
                .mode(DefaultVectorStorage.Mode.HYBRID).shardSize(FLUSH_BATCH).build();
        System.out.printf("  加载后大小: %d 条, 分片数: %d%n", loaded.size(),
                ((DefaultVectorStorage) loaded).getShardCount());

        // 搜索
        float[] query = randomVector(dim);
        long searchTotalNs = 0;
        for (int round = 0; round < MEASURE_ROUNDS; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                loaded.search(query, TOP_K);
            }
            searchTotalNs += (System.nanoTime() - start);
        }
        long nsPerCall = searchTotalNs / (MEASURE_ROUNDS * 100L);
        System.out.printf("  单次搜索 topK=%d (已缓存): %d us, %.0f QPS%n", TOP_K, nsPerCall / 1000,
                1_000_000.0 / Math.max(nsPerCall, 1));

 // 对比纯 文件 模式（每次重读磁盘）
        DefaultVectorStorage fileOnly = DefaultVectorStorage.builder()
                .dimension(dim).dir(testDir.resolve("hybrid"))
                .mode(DefaultVectorStorage.Mode.FILE).shardSize(FLUSH_BATCH).build();
        long fileTotalNs = 0;
        for (int round = 0; round < MEASURE_ROUNDS; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                fileOnly.search(query, TOP_K);
            }
            fileTotalNs += (System.nanoTime() - start);
        }
        long fileNsPerCall = fileTotalNs / (MEASURE_ROUNDS * 100L);
        System.out.printf("  单次搜索 topK=%d (纯磁盘): %d us, %.0f QPS%n", TOP_K, fileNsPerCall / 1000,
                1_000_000.0 / Math.max(fileNsPerCall, 1));
        System.out.printf("  HYBRID 比 FILE 快 %.1fx%n", (double) fileNsPerCall / Math.max(nsPerCall, 1));

        loaded.close();
        fileOnly.close();
    }

    // ==================== 大规模测试 ====================

    /**
     * 测试largescale。
     */
    private static void testLargeScale() throws Exception {
        System.out.println("【3. 大规模 - 100万条向量】");
        int dim = DIMENSION;
        int largeCount = 1_000_000;
        int largeFlushBatch = 20_000;

        DefaultVectorStorage storage = DefaultVectorStorage.builder()
                .dimension(dim).dir(testDir.resolve("large"))
                .mode(DefaultVectorStorage.Mode.HYBRID).shardSize(largeFlushBatch).build();

        System.out.println("  写入 100 万条...");
        long t0 = System.nanoTime();
        for (int i = 0; i < largeCount; i++) {
            storage.add("id_" + i, randomVector(dim));
            if ((i + 1) % largeFlushBatch == 0) {
                storage.flush();
            }
        }
        storage.flush();
        long writeMs = (System.nanoTime() - t0) / 1_000_000L;
        printThroughput("写入+刷盘", largeCount, writeMs);

        // 统计分片数
        System.out.printf("  分片文件数: %d, 总大小: %d 条%n",
                ((DefaultVectorStorage) storage).getShardCount(), storage.size());

        storage.close();

        // 重新加载
        System.out.println("  重新加载并搜索...");
        DefaultVectorStorage loaded = DefaultVectorStorage.builder()
                .dimension(dim).dir(testDir.resolve("large"))
                .mode(DefaultVectorStorage.Mode.HYBRID).shardSize(largeFlushBatch).build();
        System.out.printf("  加载后大小: %d 条, 分片数: %d%n", loaded.size(),
                ((DefaultVectorStorage) loaded).getShardCount());

        float[] query = randomVector(dim);
        long searchTotalNs = 0;
        for (int round = 0; round < 5; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                loaded.search(query, 10);
            }
            searchTotalNs += (System.nanoTime() - start);
        }
        long nsPerCall = searchTotalNs / 50L;
        System.out.printf("  单次搜索 topK=10: %d us, %.0f QPS%n", nsPerCall / 1000,
                1_000_000.0 / Math.max(nsPerCall, 1));

 // 纯 文件 模式对比
        DefaultVectorStorage fileOnly = DefaultVectorStorage.builder()
                .dimension(dim).dir(testDir.resolve("large"))
                .mode(DefaultVectorStorage.Mode.FILE).shardSize(largeFlushBatch).build();
        long fileTotalNs = 0;
        for (int round = 0; round < 5; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                fileOnly.search(query, 10);
            }
            fileTotalNs += (System.nanoTime() - start);
        }
        long fileNsPerCall = fileTotalNs / 50L;
        System.out.printf("  纯FILE单次搜索 topK=10: %d us, %.0f QPS%n", fileNsPerCall / 1000,
                1_000_000.0 / Math.max(fileNsPerCall, 1));
        System.out.printf("  HYBRID 比 FILE 快 %.1fx%n", (double) fileNsPerCall / Math.max(nsPerCall, 1));

        loaded.close();
        fileOnly.close();
    }
}
