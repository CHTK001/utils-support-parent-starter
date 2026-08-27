package com.chua.vector.support;

import com.chua.common.support.vector.RuntimeDetector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageProvider;
import com.chua.vector.support.configuration.VectorStorageProperties;
import com.chua.vector.support.spi.VectorStorageProviderFactory;

import java.util.List;

/**
 * vector-starter 运行测试（无 GPU 环境验证 jvector fallback）。
 */
public class VectorStarterTest {

    private static final int DIM = 4;

    public static void main(String[] args) {
        int passed = 0, failed = 0;

        if (testSpiRegistration()) { passed++; } else { failed++; }
        if (testFactoryName()) { passed++; } else { failed++; }
        if (testAutoDetectBackend()) { passed++; } else { failed++; }
        if (testForceCpuBackend()) { passed++; } else { failed++; }
        if (testRequireGpuThrows()) { passed++; } else { failed++; }

        System.out.println("========== Results: " + passed + " passed, " + failed + " failed ==========");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static boolean testSpiRegistration() {
        System.out.println("[TC-1] SPI registration");
        try {
            List<RuntimeDetector> detectors = com.chua.common.support.spi.ServiceProvider
                    .of(RuntimeDetector.class).collect();
            System.out.println("  found " + detectors.size() + " RuntimeDetector(s)");
            for (RuntimeDetector d : detectors) {
                System.out.println("    - " + d.name() + " prio=" + d.priority() + " avail=" + d.isAvailable());
            }
            boolean hasCuvs = detectors.stream().anyMatch(d -> "cuvs".equals(d.name()));
            System.out.println(hasCuvs ? "  PASS" : "  PASS (no cuvs, expected on this machine)");
            return true;
        } catch (Exception e) {
            System.out.println("  FAIL: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testFactoryName() {
        System.out.println("[TC-2] Factory name");
        try {
            var factory = new VectorStorageProviderFactory();
            String name = factory.name();
            System.out.println("  name=" + name);
            boolean ok = "vector".equals(name);
            System.out.println(ok ? "  PASS" : "  FAIL");
            return ok;
        } catch (Exception e) {
            System.out.println("  FAIL: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testAutoDetectBackend() {
        System.out.println("[TC-3] AUTO detect -> jvector");
        try {
            var storage = VectorStorageProvider.of("vector")
                    .dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.cosine())
                    .build();
            String cls = storage.getClass().getSimpleName();
            System.out.println("  backend: " + cls);
            boolean ok = cls.equals("JvectorVectorStorageDelegate");
            storage.close();
            System.out.println(ok ? "  PASS" : "  FAIL");
            return ok;
        } catch (Exception e) {
            System.out.println("  FAIL: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testForceCpuBackend() {
        System.out.println("[TC-4] forceCpu=true -> jvector");
        try {
            var props = new VectorStorageProperties().forceCpu(true);
            var storage = VectorStorageProvider.of("vector")
                    .dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.cosine())
                    .properties(props)
                    .build();
            String cls = storage.getClass().getSimpleName();
            System.out.println("  backend: " + cls);
            boolean ok = cls.equals("JvectorVectorStorageDelegate");
            storage.close();
            System.out.println(ok ? "  PASS" : "  FAIL");
            return ok;
        } catch (Exception e) {
            System.out.println("  FAIL: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testRequireGpuThrows() {
        System.out.println("[TC-5] requireGpu=true -> throws");
        try {
            var props = new VectorStorageProperties().requireGpu(true);
            var storage = VectorStorageProvider.of("vector")
                    .dimension(DIM)
                    .properties(props)
                    .build();
            System.out.println("  FAIL: no exception thrown");
            storage.close();
            return false;
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage().substring(0, Math.min(60, e.getMessage().length())) : "null";
            System.out.println("  OK: " + e.getClass().getSimpleName() + ": " + msg);
            System.out.println("  PASS");
            return true;
        } catch (Exception e) {
            System.out.println("  FAIL: wrong type: " + e.getClass().getSimpleName());
            e.printStackTrace();
            return false;
        }
    }
}
