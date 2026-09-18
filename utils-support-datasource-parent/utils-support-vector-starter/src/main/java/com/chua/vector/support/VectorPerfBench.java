package com.chua.vector.support;

import com.chua.common.support.vector.*;
import com.chua.jvector.support.configuration.JVectorStorageProperties;
import com.chua.vector.support.spi.VectorStorageProviderFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
* 向量存储性能基准测试（精简版）。
* @author CH
* @since 4.0.0
* @param name 名称
* @param factory 工厂
 */
public class VectorPerfBench {

    private static final int DIM = 128; // DIM
    private static final int COUNT = 5_000; // 数量
    private static final int TOP_K = 10; // TOP_K
    private static final int MEASURE_ROUNDS = 2; // 测量rounds
    private static final int QS = 20; // Q
    private static final int FLUSH_BATCH = 5_000; // FLUSH_批量

    private static final Random RND = new Random(20260829); // RND
    /**
    * main。
    * @param args 参数
    */
    private static Path benchDir;

    /**
    * main。
    * @param args 参数
    */
    public static void main(String[] args) throws Exception {
        benchDir = Files.createTempDirectory("vector-perf-bench-");
        System.out.println("============================================================");
        System.out.println(" 向量存储性能基准测试");
        System.out.println(" 维度=" + DIM + " 数量=" + COUNT + " 目录=" + benchDir);
        System.out.println("============================================================\n");
        runAll();
        deleteRecursively(benchDir);
        System.out.println("\n测试目录已清理: " + benchDir);
    /**
    * 运行全部。
    * @param name 名称
    * @param factory 工厂
    */
    }

    /**
     * 运行全部。
     */
    private static void runAll() {
        printf("\n【MemoryVectorStorage】\n");
        bench("memory-cosine",      () -> VectorStorageBuilder.newBuilder().dimension(DIM).algorithm(VectorCompareAlgorithm.cosine()).build());
        bench("memory-euclidean",   () -> VectorStorageBuilder.newBuilder().dimension(DIM).algorithm(VectorCompareAlgorithm.euclidean()).build());
        bench("memory-dot",         () -> VectorStorageBuilder.newBuilder().dimension(DIM).algorithm(VectorCompareAlgorithm.dotProduct()).build());

        printf("\n【DefaultVectorStorage HYBRID】\n");
        bench("default-hyb-cosine", () -> DefaultVectorStorage.builder()
                .dimension(DIM).dir(resolve("dh-cos")).mode(DefaultVectorStorage.Mode.HYBRID)
                .shardSize(FLUSH_BATCH).algorithm(VectorCompareAlgorithm.cosine()).build());
        bench("default-hyb-eucl",   () -> DefaultVectorStorage.builder()
                .dimension(DIM).dir(resolve("dh-euc")).mode(DefaultVectorStorage.Mode.HYBRID)
                .shardSize(FLUSH_BATCH).algorithm(VectorCompareAlgorithm.euclidean()).build());

        printf("\n【JVectorVectorStorage MEMORY】\n");
        bench("jvector-mem-cosine", () -> {
            var p = new JVectorStorageProperties();
            p.setMode(JVectorStorageProperties.Mode.MEMORY);
            return VectorStorageProvider.of("jvector").dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.cosine()).properties(p).build();
        });
        bench("jvector-mem-eucl",   () -> {
            var p = new JVectorStorageProperties();
            p.setMode(JVectorStorageProperties.Mode.MEMORY);
            return VectorStorageProvider.of("jvector").dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.euclidean()).properties(p).build();
        });
    }

    /**
     * bench。
     *
     * @param name 名称，不允许为 null
     * @param factory 工厂，不允许为 null
     */
    private static void bench(String name, StorageFactory factory) {
        try {
            VectorStorage storage = factory.create();
            try {
                float[] query = randomVector(DIM);
                // 预热
                for (int r = 0; r < 1; r++) {
                    VectorStorage ws = factory.create();
                    for (int i = 0; i < COUNT / 5; i++) {
                        ws.add("id_" + i, randomVector(DIM));
                    }
                    ws.search(query, TOP_K);
                    safeFlush(ws);
                    ws.close();
                }
                // 写入
                long writeNs = 0;
                for (int round = 0; round < MEASURE_ROUNDS; round++) {
                    VectorStorage s = factory.create();
                    long t0 = System.nanoTime();
                    for (int i = 0; i < COUNT; i++) {
                        s.add("id_" + i, randomVector(DIM));
                    }
                    safeFlush(s);
                    writeNs += System.nanoTime() - t0;
                    s.close();
                }
                long avgWriteNs = writeNs / MEASURE_ROUNDS;
                double writeWps = COUNT * 1_000_000.0 / avgWriteNs;
                // 搜索
    /**
    * storage工厂接口。
    *
    * @author CH
    * @since 4.0.0
    * @param fmt fmt
    * @param args 参数
    * @param s s
    */
                long searchNs = 0;
                for (int round = 0; round < MEASURE_ROUNDS; round++) {
                    VectorStorage s = factory.create();
                    for (int i = 0; i < COUNT; i++) {
                        s.add("id_" + i, randomVector(DIM));
                    }
                    safeFlush(s);
                    long t0 = System.nanoTime();
                    for (int q = 0; q < QS; q++) {
                        s.search(randomVector(DIM), TOP_K);
                    }
                    searchNs += System.nanoTime() - t0;
                    s.close();
                }
                long avgSearchNs = searchNs / (MEASURE_ROUNDS * QS);
                double searchQps = 1_000_000_000.0 / avgSearchNs;
                System.out.printf("  %-22s 写入=%.1f万条/s  搜索=%dus/次  QPS=%,d\n",
                        name, writeWps, avgSearchNs / 1000, (long) searchQps);
            } finally {
                storage.close();
            }
        } catch (Exception e) {
            System.out.printf("  %-22s ERROR: %s\n", name, e.getMessage());
        }
    }

    /**
     * safe刷写。
     *
     * @param s 方法入参 s
     */
    private static void safeFlush(VectorStorage s) {
        try {
            if (s instanceof DefaultVectorStorage ds) {
                ds.flush();
            }
        } catch (Exception ignored) {}
    /**
    * 随机向量。
    * @param dim dim
    * @return 随机向量的结果
    */
    }

    /**
     * randomVector。
     *
     * @param dim 方法入参 dim
     * @return 结果值
     */
    private static float[] randomVector(int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = (float) (Math.random() * 2 - 1);
        }
        return v;
    /**
    * resolve。
    * @param name 名称
    * @return resolve的结果
    */
    }

    /**
     * 解析。
     *
     * @param name 名称，不允许为 null
     * @return 路径 对象
     * @throws Exception 当执行过程不满足前置条件时
     */
    private static Path resolve(String name) throws Exception {
        Path p = benchDir.resolve(name);
        Files.createDirectories(p);
        return p;
    /**
    * 删除recursively。
    * @param dir dir
    * @author CH
    * @since 4.0.0
    * @param fmt fmt
    * @param args 参数
    */
    }

    /**
     * 删除Recursively。
     *
     * @param dir 目录，不允许为 null
     */
    private static void deleteRecursively(Path dir) {
        try {
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (Exception ignored) {}
                    });
        } catch (Exception ignored) {}
    }

    private static void printf(String fmt, Object... args) { System.out.printf(fmt, args); }

    @FunctionalInterface
    private interface StorageFactory { VectorStorage create() throws Exception; }
}
