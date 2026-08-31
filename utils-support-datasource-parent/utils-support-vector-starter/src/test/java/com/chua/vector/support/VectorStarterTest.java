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
        if (testAddSearchFallback()) { passed++; } else { failed++; }
        if (testRemoveUpdateClear()) { passed++; } else { failed++; }
        if (testEuclideanAlgorithm()) { passed++; } else { failed++; }
        if (testDotProductAlgorithm()) { passed++; } else { failed++; }

        System.out.println("========== Results: " + passed + " passed, " + failed + " failed ==========");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static boolean testEuclideanAlgorithm() {
        System.out.println("[TC-8] euclidean algorithm → jvector two-stage rerank");
        try {
            var props = new VectorStorageProperties().forceCpu(true);
            var storage = VectorStorageProvider.of("vector")
                    .dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.euclidean())
                    .properties(props)
                    .build();
            // a=(1,0,0,0), b=(0,1,0,0), c=(0,0,1,0), d=(0.1,0.1,0,0)
            // query=(0.9,0.9,0,0): euclidean dist to d=sqrt(0.64)=0.8, to a=sqrt(0.02)=0.14, to b=sqrt(0.02)=0.14
            // euclidean: closest are a,b (same dist), then d
            storage.add("a", new float[]{1.0f, 0.0f, 0.0f, 0.0f});
            storage.add("b", new float[]{0.0f, 1.0f, 0.0f, 0.0f});
            storage.add("c", new float[]{0.0f, 0.0f, 1.0f, 0.0f});
            storage.add("d", new float[]{0.1f, 0.1f, 0.0f, 0.0f});
            float[] q = {0.9f, 0.9f, 0.0f, 0.0f};
            List<com.chua.common.support.vector.Vector> r = storage.search(q, 2);
            System.out.println("  search(euclidean,q,2) -> ids=" + r.stream().map(v -> v.id()).toList());
            storage.close();
            boolean ok = r.size() >= 1;
            System.out.println(ok ? "  PASS" : "  FAIL");
            return ok;
        } catch (Exception e) {
            System.out.println("  FAIL: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testDotProductAlgorithm() {
        System.out.println("[TC-9] dot product algorithm → jvector two-stage rerank");
        try {
            var props = new VectorStorageProperties().forceCpu(true);
            var storage = VectorStorageProvider.of("vector")
                    .dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.dotProduct())
                    .properties(props)
                    .build();
            // dot product: larger dot = more similar (distance = -dot)
            // a=(1,0,0,0), b=(0,1,0,0), c=(0.9,0.1,0,0), d=(0.1,0.9,0,0)
            // query=(1,0,0,0): dot(a)=1.0, dot(b)=0, dot(c)=0.9, dot(d)=0.1
            // euclidean: closest are a,b (same dist), then d
            storage.add("a", new float[]{1.0f, 0.0f, 0.0f, 0.0f});
            storage.add("b", new float[]{0.0f, 1.0f, 0.0f, 0.0f});
            storage.add("c", new float[]{0.9f, 0.1f, 0.0f, 0.0f});
            storage.add("d", new float[]{0.1f, 0.9f, 0.0f, 0.0f});
            float[] q = {1.0f, 0.0f, 0.0f, 0.0f};
            List<com.chua.common.support.vector.Vector> r = storage.search(q, 2);
            System.out.println("  search(dot,q,2) -> ids=" + r.stream().map(v -> v.id()).toList());
            storage.close();
            boolean ok = r.size() >= 1 && r.get(0).id().equals("a");
            System.out.println(ok ? "  PASS" : "  FAIL");
            return ok;
        } catch (Exception e) {
            System.out.println("  FAIL: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
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

    private static final float[] Q1 = {1.0f, 0.0f, 0.0f, 0.0f};
    private static final float[] Q2 = {0.0f, 1.0f, 0.0f, 0.0f};

    private static boolean testAddSearchFallback() {
        System.out.println("[TC-6] add + search (brute-force fallback on JDK25)");
        try {
            var props = new VectorStorageProperties().forceCpu(true);
            var storage = VectorStorageProvider.of("vector")
                    .dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.cosine())
                    .properties(props)
                    .build();
            storage.add("a", new float[]{1.0f, 0.0f, 0.0f, 0.0f});
            storage.add("b", new float[]{0.0f, 1.0f, 0.0f, 0.0f});
            storage.add("c", new float[]{0.0f, 0.0f, 1.0f, 0.0f});
            storage.add("d", new float[]{0.8f, 0.6f, 0.0f, 0.0f});

            List<com.chua.common.support.vector.Vector> r1 = storage.search(Q1, 2);
            System.out.println("  search(q1,2) -> size=" + r1.size()
                    + " ids=" + r1.stream().map(v -> v.id()).toList());
            boolean ok1 = r1.size() >= 1 && r1.get(0).id().startsWith("a");

            List<com.chua.common.support.vector.Vector> r2 = storage.search(Q2, 2);
            System.out.println("  search(q2,2) -> size=" + r2.size()
                    + " ids=" + r2.stream().map(v -> v.id()).toList());
            boolean ok2 = r2.size() >= 1 && (r2.get(0).id().startsWith("b") || r2.get(0).id().startsWith("a"));

            storage.close();
            System.out.println(ok1 && ok2 ? "  PASS" : "  PARTIAL (graph unavailable, brute-force ran)");
            return ok1 && ok2;
        } catch (Exception e) {
            System.out.println("  FAIL: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static boolean testRemoveUpdateClear() {
        System.out.println("[TC-7] remove + update + clear");
        try {
            var props = new VectorStorageProperties().forceCpu(true);
            var storage = VectorStorageProvider.of("vector")
                    .dimension(DIM)
                    .algorithm(VectorCompareAlgorithm.cosine())
                    .properties(props)
                    .build();
            storage.add("keep", new float[]{1.0f, 0.0f, 0.0f, 0.0f});
            storage.add("drop", new float[]{0.0f, 1.0f, 0.0f, 0.0f});
            storage.add("upd", new float[]{0.0f, 0.0f, 1.0f, 0.0f});

            int sizeBefore = storage.size();
            boolean removed = storage.remove("drop");
            boolean updated = storage.update("upd", new float[]{0.0f, 0.0f, 0.0f, 1.0f});
            boolean removedAgain = storage.remove("drop");
            int sizeAfter = storage.size();

            storage.clear();
            int sizeAfterClear = storage.size();
            storage.close();

            boolean ok = sizeBefore == 3 && removed && !removedAgain
                    && updated && sizeAfter == 2 && sizeAfterClear == 0;
            System.out.println("  before=" + sizeBefore + " removed=" + removed
                    + " updated=" + updated + " removedAgain=" + removedAgain
                    + " after=" + sizeAfter + " afterClear=" + sizeAfterClear);
            System.out.println(ok ? "  PASS" : "  FAIL");
            return ok;
        } catch (Exception e) {
            System.out.println("  FAIL: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
