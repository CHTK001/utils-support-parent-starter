package com.chua.vector.support;

import com.chua.common.support.vector.RuntimeDetector;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.vector.support.configuration.VectorStorageProperties;
import com.chua.vector.support.spi.VectorStorageProviderFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * vector-starter 运行测试（无 GPU 环境验证 jvector fallback）。
 */
@Slf4j
public class VectorStarterTest {

    private static final int DIM = 4;
    private static final float[] Q1 = {1.0f, 0.0f, 0.0f, 0.0f};
    private static final float[] Q2 = {0.0f, 1.0f, 0.0f, 0.0f};
    private static final float[] Q3 = {0.1f, 0.9f, 0.0f, 0.0f};

    public static void main(String[] args) {
        int passed = 0, failed = 0;

        // TC-1: SPI 注册验证
        if (testSpiRegistration()) { passed++; } else { failed++; }

        // TC-2: AUTO 自动检测（无 GPU 应降级 jvector）
        if (testAutoDetect()) { passed++; } else { failed++; }

        // TC-3: forceCpu=true 强制 CPU
        if (testForceCpu()) { passed++; } else { failed++; }

        // TC-4: requireGpu=true 无 GPU 应抛异常
        if (testRequireGpuThrows()) { passed++; } else { failed++; }

        // TC-5: add + search 正确性
        if (testAddSearch()) { passed++; } else { failed++; }

        // TC-6: remove + update
        if (testRemoveUpdate()) { passed++; } else { failed++; }

        // TC-7: clear
        if (testClear()) { passed++; } else { failed++; }

        log.info("========== 测试结果: {} 通过, {} 失败 ==========", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    private static boolean testSpiRegistration() {
        log.info("[TC-1] SPI 注册验证");
        try {
            var factory = new VectorStorageProviderFactory();
            if (!"vector".equals(factory.name())) {
                log.info("  ✗ name() 返回: {}", factory.name());
                return false;
            }
            List<RuntimeDetector> detectors = com.chua.common.support.spi.ServiceProvider
                    .of(RuntimeDetector.class).collect();
            log.info("  检测到 {} 个 RuntimeDetector", detectors.size());
            for (RuntimeDetector d : detectors) {
                log.info("    - {} (priority={}, available={})",
                        d.name(), d.priority(), d.isAvailable());
            }
            log.info("  ✓ 通过");
            return true;
        } catch (Exception e) {
            log.info("  ✗ 失败: {}", e.getMessage());
            return false;
        }
    }

    private static boolean testAutoDetect() {
        log.info("[TC-2] AUTO 自动检测（无 GPU 应降级 jvector）");
        try {
            var storage = VectorStorageProvider.of("vector")
                    .dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.cosine())
                    .build();
            log.info("  后端: {}", storage.getClass().getSimpleName());
            if (!(storage instanceof com.chua.vector.support.storage.JvectorVectorStorageDelegate)) {
                log.info("  ✗ 预期 jvector 后端，实际: {}", storage.getClass().getName());
                storage.close();
                return false;
            }
            storage.add("a", new float[]{1.0f, 0.0f, 0.0f, 0.0f});
            storage.add("b", new float[]{0.0f, 1.0f, 0.0f, 0.0f});
            storage.add("c", new float[]{0.0f, 0.0f, 1.0f, 0.0f});
            List<Vector> results = storage.search(Q1, 2);
            log.info("  search(q1, 2) -> size={}, ids={}",
                    results.size(), results.stream().map(Vector::id).toList());
            storage.close();
            log.info("  ✓ 通过");
            return true;
        } catch (Exception e) {
            log.info("  ✗ 失败: {}", e.getMessage());
            return false;
        }
    }

    private static boolean testForceCpu() {
        log.info("[TC-3] forceCpu=true 强制 CPU");
        try {
            var props = new VectorStorageProperties().forceCpu(true);
            var storage = VectorStorageProvider.of("vector")
                    .dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.cosine())
                    .properties(props)
                    .build();
            log.info("  后端: {}", storage.getClass().getSimpleName());
            if (!(storage instanceof com.chua.vector.support.storage.JvectorVectorStorageDelegate)) {
                log.info("  ✗ 预期 jvector 后端，实际: {}", storage.getClass().getName());
                storage.close();
                return false;
            }
            storage.add("x", new float[]{1.0f, 0.0f, 0.0f, 0.0f});
            List<Vector> results = storage.search(Q1, 1);
            storage.close();
            log.info("  ✓ 通过");
            return true;
        } catch (Exception e) {
            log.info("  ✗ 失败: {}", e.getMessage());
            return false;
        }
    }

    private static boolean testRequireGpuThrows() {
        log.info("[TC-4] requireGpu=true 无 GPU 应抛异常");
        try {
            var props = new VectorStorageProperties().requireGpu(true);
            var storage = VectorStorageProvider.of("vector")
                    .dimension(DIM)
                    .properties(props)
                    .build();
            // 无 GPU 时应该抛出异常
            log.info("  ✗ 预期抛异常，但未抛出");
            storage.close();
            return false;
        } catch (RuntimeException e) {
            log.info("  正确抛出异常: {}", e.getMessage() != null ? e.getMessage().substring(0, Math.min(60, e.getMessage().length())) : "null message");
            log.info("  ✓ 通过");
            return true;
        } catch (Exception e) {
            log.info("  ✗ 异常类型不对: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean testAddSearch() {
        log.info("[TC-5] add + search 正确性");
        try {
            var storage = VectorStorageProvider.of("vector")
                    .dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.cosine())
                    .properties(new VectorStorageProperties().forceCpu(true))
                    .build();
            storage.add("sim_a", new float[]{1.0f, 0.0f, 0.0f, 0.0f});
            storage.add("sim_b", new float[]{0.8f, 0.6f, 0.0f, 0.0f});
            storage.add("sim_c", new float[]{0.0f, 1.0f, 0.0f, 0.0f});
            storage.add("sim_d", new float[]{0.0f, 0.0f, 1.0f, 0.0f});

            // 查询与 a 最相似
            List<Vector> r1 = storage.search(Q1, 2);
            boolean ok1 = r1.size() == 2
                    && r1.get(0).id().startsWith("sim_a");
            log.info("  search(q1,2) -> ids={}", r1.stream().map(Vector::id).toList());

            // 查询与 b 最相似
            List<Vector> r2 = storage.search(Q2, 2);
            boolean ok2 = r2.size() == 2
                    && r2.get(0).id().startsWith("sim_b") || r2.get(0).id().startsWith("sim_a");
            log.info("  search(q2,2) -> ids={}", r2.stream().map(Vector::id).toList());

            storage.close();
            log.info("  {}", ok1 && ok2 ? "✓ 通过" : "✗ 部分结果不满足");
            return ok1 && ok2;
        } catch (Exception e) {
            log.info("  ✗ 失败: {}", e.getMessage());
            return false;
        }
    }

    private static boolean testRemoveUpdate() {
        log.info("[TC-6] remove + update");
        try {
            var storage = VectorStorageProvider.of("vector")
                    .dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.cosine())
                    .properties(new VectorStorageProperties().forceCpu(true))
                    .build();
            storage.add("keep", new float[]{1.0f, 0.0f, 0.0f, 0.0f});
            storage.add("drop", new float[]{0.0f, 1.0f, 0.0f, 0.0f});
            storage.add("upd", new float[]{0.0f, 0.0f, 1.0f, 0.0f});
            int sizeBefore = storage.size();
            boolean removed = storage.remove("drop");
            boolean updated = storage.update("upd", new float[]{0.0f, 0.0f, 0.0f, 1.0f});
            boolean removedAgain = storage.remove("drop");
            storage.close();
            boolean ok = sizeBefore == 3 && removed && !removedAgain
                    && updated && storage.size() == 2;
            log.info("  sizeBefore={}, removed={}, updated={}, removedAgain={}, afterSize={}",
                    sizeBefore, removed, updated, removedAgain, storage.size());
            log.info("  {}", ok ? "✓ 通过" : "✗ 失败");
            return ok;
        } catch (Exception e) {
            log.info("  ✗ 失败: {}", e.getMessage());
            return false;
        }
    }

    private static boolean testClear() {
        log.info("[TC-7] clear");
        try {
            var storage = VectorStorageProvider.of("vector")
                    .dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.cosine())
                    .properties(new VectorStorageProperties().forceCpu(true))
                    .build();
            for (int i = 0; i < 5; i++) {
                storage.add("item-" + i, randomVec(i));
            }
            int before = storage.size();
            storage.clear();
            int after = storage.size();
            storage.close();
            boolean ok = before == 5 && after == 0;
            log.info("  before={}, after={}", before, after);
            log.info("  {}", ok ? "✓ 通过" : "✗ 失败");
            return ok;
        } catch (Exception e) {
            log.info("  ✗ 失败: {}", e.getMessage());
            return false;
        }
    }

    private static float[] randomVec(int seed) {
        float[] v = new float[DIM];
        java.util.Random rnd = new java.util.Random(seed);
        for (int i = 0; i < DIM; i++) v[i] = rnd.nextFloat();
        return v;
    }
}
