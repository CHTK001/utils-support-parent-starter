package com.chua.vector.support;

import com.chua.common.support.vector.*;
import com.chua.jvector.support.configuration.JVectorStorageProperties;
import com.chua.vector.support.spi.VectorStorageProviderFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * 向量存储性能基准测试（精简版 — 快速出结果）。
 */
public class VectorPerfBench {

    private static final int DIM = 128;
    private static final int COUNT = 5_000;
    private static final int TOP_K = 10;
    private static final int MEASURE_ROUNDS = 2;
    private static final int QS = 20;
    private static final int FLUSH_BATCH = 5_000;

    private static final Random RND = new Random(20260829);
    private static Path benchDir;

    public static void main(String[] args) throws Exception {
        benchDir = Files.createTempDirectory("vector-perf-bench-");
        System.out.println("============================================================");
        System.out.println(" 向量存储性能基准测试");
        System.out.println(" 维度=" + DIM + " 数量=" + COUNT + " 目录=" + benchDir);
        System.out.println("============================================================\n");

        runAll();
        deleteRecursively(benchDir);
        System.out.println("\n测试目录已清理: " + benchDir);
    }

    private static void runAll() {
        printf("\n【MemoryVectorStorage】\n");
        bench("memory-cosine",     () -> VectorStorageBuilder.newBuilder().dimension(DIM).algorithm(VectorCompareAlgorithm.cosine()).build());
        bench("memory-euclidean",  () -> VectorStorageBuilder.newBuilder().dimension(DIM).algorithm(VectorCompareAlgorithm.euclidean()).build());
        bench("memory-dot",        () -> VectorStorageBuilder.newBuilder().dimension(DIM).algorithm(VectorCompareAlgorithm.dotProduct()).build());

        printf("\n【DefaultVectorStorage HYBRID】\n");
        bench("default-hyb-cosine", () -> DefaultVectorStorage.builder()
                .dimension(DIM).dir(resolve("default-hyb-cos")).mode(DefaultVectorStorage.Mode.HYBRID)
                .shardSize(FLUSH_BATCH).algorithm(VectorCompareAlgorithm.cosine()).build());
        bench("default-hyb-eucl",   () -> DefaultVectorStorage.builder()
                .dimension(DIM).dir(resolve("default-hyb-euc")).mode(DefaultVectorStorage.Mode.HYBRID)
                .shardSize(FLUSH_BATCH).algorithm(VectorCompareAlgorithm.euclidean()).build());

        printf("\n【DefaultVectorStorage MEMORY】\n");
        bench("default-mem-cosine", () -> DefaultVectorStorage.builder()
                .dimension(DIM).mode(DefaultVectorStorage.Mode.MEMORY)
                .algorithm(VectorCompareAlgorithm.cosine()).build());

        printf("\n【JVectorVectorStorage MEMORY】\n");
        bench("jvector-mem-cosine", () -> {
            var p = new JVectorStorageProperties(); p.setMode(JVectorStorageProperties.Mode.MEMORY);
            return VectorStorageProvider.of("jvector").dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.cosine()).properties(p).build();
        });
        bench("jvector-mem-eucl",   () -> {
            var p = new JVectorStorageProperties(); p.setMode(JVectorStorageProperties.Mode.MEMORY);
            return VectorStorageProvider.of("jvector").dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.euclidean()).properties(p).build();
        });
    }

    private static void bench(String name, StorageFactory factory) {
        try {
            float[] query = randomVector(DIM);
            // 预热
            for (int r = 0; r < 1; r++) {
                VectorStorage s = factory.create();
                for (int i = 0; i < COUNT / 5; i++) s.add("id_" + i, randomVector(DIM));
                s.search(query, TOP_K);
                if (s instanceof DefaultVectorStorage ds) ds.flush();
                s.close();
            }
            // 写入
            long writeNs = 0;
            for (int round = 0; round < MEASURE_ROUNDS; round++) {
                VectorStorage s = factory.create();
                long t0 = System.nanoTime();
                for (int i = 0; i < COUNT; i++) s.add("id_" + i, randomVector(DIM));
                try { if (s instanceof DefaultVectorStorage ds) ds.flush(); } catch (Exception ignored) {}
                writeNs += System.nanoTime() - t0;
                s.close();
            }
            long avgWriteNs = writeNs / MEASURE_ROUNDS;
            double writeWps = COUNT * 1_000_000.0 / avgWriteNs;
            // 搜索
            long searchNs = 0;
            for (int round = 0; round < MEASURE_ROUNDS; round++) {
                VectorStorage s = factory.create();
                for (int i = 0; i < COUNT; i++) s.add("id_" + i, randomVector(DIM));
                try { if (s instanceof DefaultVectorStorage ds) ds.flush(); } catch (Exception ignored) {}
                long t0 = System.nanoTime();
                for (int q = 0; q < QS; q++) s.search(randomVector(DIM), TOP_K);
                searchNs += System.nanoTime() - t0;
                s.close();
            }
            long avgSearchNs = searchNs / (MEASURE_ROUNDS * QS);
            double searchQps = 1_000_000_000.0 / avgSearchNs;
            System.out.printf("  %-22s 写入=%.1f万条/s  搜索=%dus/次  QPS=%,d\n",
                    name, writeWps, avgSearchNs / 1000, (long) searchQps);
        } catch (Exception e) {
            System.out.printf("  %-22s ERROR: %s\n", name, e.getMessage());
        }
    }

    private static float[] randomVector(int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = (float) (Math.random() * 2 - 1);
        return v;
    }

    private static Path resolve(String name) throws Exception {
        Path p = benchDir.resolve(name);
        Files.createDirectories(p);
        return p;
    }

    private static void deleteRecursively(Path dir) {
        try { Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} }); }
        catch (Exception ignored) {}
    }

    private static void printf(String fmt, Object... args) { System.out.printf(fmt, args); }

    @FunctionalInterface
    private interface StorageFactory { VectorStorage create() throws Exception; }
}
