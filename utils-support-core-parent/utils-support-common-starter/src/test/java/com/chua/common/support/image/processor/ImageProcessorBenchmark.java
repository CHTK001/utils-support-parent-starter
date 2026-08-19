package com.chua.common.support.image.processor;

import com.chua.common.support.image.ImageProcessor;
import com.chua.common.support.image.ImageProcessors;
import com.chua.common.support.spi.ServiceProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ImageProcessor 性能基准测试 + 并发压力测试
 * <p>
 * 模式1 - 基准测试: java --enable-preview ImageProcessorBenchmark [image_path] [iterations]
 * 模式2 - 并发压力: java --enable-preview ImageProcessorBenchmark stress [image_path] [threads] [total_ops]
 * <p>
 * 并发压力测试模拟多线程同时调用 process()，验证:
 * - Rust原生库线程安全性（FFM SymbolLookup是否线程安全）
 * - 共享内存协议在并发下的正确性
 * - 多线程吞吐量与延迟分布
 */
public class ImageProcessorBenchmark {

    private static final String DEFAULT_INPUT = "D:/images/test_1.jpg";
    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASURE_ITERATIONS = 5;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "stress".equalsIgnoreCase(args[0])) {
            // 并发压力测试模式
            String inputPath = args.length > 1 ? args[1] : DEFAULT_INPUT;
            int threads = args.length > 2 ? Integer.parseInt(args[2]) : 4;
            int totalOps = args.length > 3 ? Integer.parseInt(args[3]) : 100;
            runStressTest(inputPath, threads, totalOps);
        } else {
            // 基准测试模式
            String inputPath = args.length > 0 ? args[0] : DEFAULT_INPUT;
            int iterations = args.length > 1 ? Integer.parseInt(args[1]) : MEASURE_ITERATIONS;
            runBenchmark(inputPath, iterations);
        }
    }

    // ==================== 并发压力测试 ====================

    private static void runStressTest(String inputPath, int threads, int totalOps) throws Exception {
        System.out.println("===== ImageProcessor 并发压力测试 =====");
        System.out.println("输入图片: " + inputPath);
        System.out.println("并发线程: " + threads);
        System.out.println("总操作数: " + totalOps);
        System.out.println();

        byte[] imageData = readImage(inputPath);
        if (imageData == null) {
            System.err.println("无法读取测试图片: " + inputPath);
            System.exit(1);
        }
        System.out.println("图片大小: " + imageData.length + " bytes");
        System.out.println();

        // 发现所有实现
        List<ImageProcessor> impls = getUniqueExtensions();
        System.out.println("发现 " + impls.size() + " 个 ImageProcessor 实现:");
        for (ImageProcessor p : impls) {
            System.out.println("  - " + p.name() + " (available=" + p.available() + ")");
        }
        System.out.println();

        // 测试操作列表（用于混合负载）
        String[] opNames = {"resize", "grayscale", "rotate", "blur", "flip", "brightness", "contrast", "binarize", "erode", "dilate"};
        Map<String, Object>[] opParams = new Map[]{
                params("width", 200, "height", 150),   // resize
                Map.of(),                                // grayscale
                params("angle", 90),                     // rotate
                params("sigma", 5),                      // blur
                params("axis", "h"),                     // flip
                params("value", 50),                     // brightness
                params("value", 30),                     // contrast
                params("threshold", 128),                // binarize
                params("kernel", 3),                     // erode
                params("kernel", 3),                     // dilate
        };

        // 预热
        System.out.println("----- 预热 -----");
        for (ImageProcessor impl : impls) {
            if (!impl.available()) continue;
            for (int i = 0; i < 5; i++) {
                try { impl.process(imageData, "resize", params("width", 200, "height", 150)); } catch (Exception e) { break; }
            }
        }
        System.out.println("预热完成");
        System.out.println();

        // 对每个实现运行并发压力测试
        for (ImageProcessor impl : impls) {
            if (!impl.available()) {
                System.out.println("[" + impl.name() + "] 不可用，跳过");
                continue;
            }

            System.out.println("===== " + impl.name() + " 并发压力测试 =====");
            System.out.println("线程数: " + threads + ", 总操作数: " + totalOps);

            // 1. 单操作并发测试 — resize
            runSingleOpStress(impl, imageData, "resize", params("width", 200, "height", 150), threads, totalOps);

            // 2. 混合操作并发测试 — 随机选择操作
            runMixedOpStress(impl, imageData, opNames, opParams, threads, totalOps);

            System.out.println();
        }

        // 对比汇总
        System.out.println("===== 并发性能对比汇总 =====");
        System.out.printf("%-10s %-8s %-12s %-12s %-12s %-12s %-10s%n",
                "实现", "线程", "总操作", "总耗时(ms)", "吞吐(op/s)", "avg(ms)", "P99(ms)");
        for (ImageProcessor impl : impls) {
            if (!impl.available()) continue;
            for (int t : new int[]{1, threads}) {
                StressResult r = runStressInternal(impl, imageData, "resize",
                        params("width", 200, "height", 150), t, Math.min(totalOps, t * 50));
                System.out.printf("%-10s %-8d %-12d %-12d %-12d %-10d %-10d%n",
                        impl.name(), t, r.totalOps, r.totalMs, r.throughput, r.avgMs, r.p99Ms);
            }
        }
    }

    private static void runSingleOpStress(ImageProcessor impl, byte[] imageData,
                                           String opName, Map<String, Object> opParams,
                                           int threads, int totalOps) {
        System.out.println("  [单操作] " + opName + " — " + threads + "线程 × " + totalOps + "次");
        StressResult result = runStressInternal(impl, imageData, opName, opParams, threads, totalOps);
        printStressResult(result);
    }

    private static void runMixedOpStress(ImageProcessor impl, byte[] imageData,
                                          String[] opNames, Map<String, Object>[] opParams,
                                          int threads, int totalOps) {
        System.out.println("  [混合操作] 随机10种操作 — " + threads + "线程 × " + totalOps + "次");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        Random random = new Random();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(totalOps);
        long startMs = System.currentTimeMillis();

        for (int i = 0; i < totalOps; i++) {
            executor.submit(() -> {
                try {
                    int opIdx = random.nextInt(opNames.length);
                    long t0 = System.nanoTime();
                    byte[] result = impl.process(imageData, opNames[opIdx], opParams[opIdx]);
                    long dt = (System.nanoTime() - t0) / 1_000_000;
                    if (result != null && result.length > 0) {
                        successCount.incrementAndGet();
                        totalLatency.addAndGet(dt);
                        latencies.add(dt);
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdown();

        long elapsed = System.currentTimeMillis() - startMs;
        int successes = successCount.get();
        int failures = failCount.get();

        if (successes == 0) {
            System.out.println("    全部失败! failures=" + failures);
            return;
        }

        Collections.sort(latencies);
        long avg = totalLatency.get() / successes;
        long p50 = latencies.get((int) (successes * 0.50));
        long p90 = latencies.get((int) (successes * 0.90));
        long p99 = latencies.get((int) (successes * 0.99));
        long min = latencies.get(0);
        long max = latencies.get(latencies.size() - 1);
        int throughput = (int) ((double) successes / elapsed * 1000);

        System.out.printf("    成功=%d 失败=%d 总耗时=%dms 吞吐=%dop/s%n", successes, failures, elapsed, throughput);
        System.out.printf("    延迟: avg=%dms min=%dms P50=%dms P90=%dms P99=%dms max=%dms%n",
                avg, min, p50, p90, p99, max);
    }

    private static StressResult runStressInternal(ImageProcessor impl, byte[] imageData,
                                                   String opName, Map<String, Object> opParams,
                                                   int threads, int totalOps) {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(totalOps);
        long startMs = System.currentTimeMillis();

        for (int i = 0; i < totalOps; i++) {
            executor.submit(() -> {
                try {
                    long t0 = System.nanoTime();
                    byte[] result = impl.process(imageData, opName, opParams);
                    long dt = (System.nanoTime() - t0) / 1_000_000;
                    if (result != null && result.length > 0) {
                        successCount.incrementAndGet();
                        totalLatency.addAndGet(dt);
                        latencies.add(dt);
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdown();

        long elapsed = System.currentTimeMillis() - startMs;
        int successes = successCount.get();

        if (successes == 0) {
            return new StressResult(totalOps, 0, elapsed, 0, 0, 0, 0, 0, 0, 0);
        }

        Collections.sort(latencies);
        long avg = totalLatency.get() / successes;
        long p50 = latencies.get((int) (successes * 0.50));
        long p90 = latencies.get((int) (successes * 0.90));
        long p99 = latencies.get((int) (successes * 0.99));
        long min = latencies.get(0);
        long max = latencies.get(latencies.size() - 1);
        int throughput = (int) ((double) successes / elapsed * 1000);

        return new StressResult(totalOps, successes, elapsed, throughput, avg, min, p50, p90, p99, max);
    }

    private static void printStressResult(StressResult r) {
        if (r.successes == 0) {
            System.out.println("    全部失败! failures=" + (r.totalOps - r.successes));
            return;
        }
        System.out.printf("    成功=%d 失败=%d 总耗时=%dms 吞吐=%dop/s%n",
                r.successes, r.totalOps - r.successes, r.totalMs, r.throughput);
        System.out.printf("    延迟: avg=%dms min=%dms P50=%dms P90=%dms P99=%dms max=%dms%n",
                r.avgMs, r.minMs, r.p50Ms, r.p90Ms, r.p99Ms, r.maxMs);
    }

    record StressResult(int totalOps, int successes, long totalMs, int throughput,
                         long avgMs, long minMs, long p50Ms, long p90Ms, long p99Ms, long maxMs) {}

    // ==================== 基准测试 ====================

    private static void runBenchmark(String inputPath, int iterations) throws Exception {
        System.out.println("===== ImageProcessor 性能基准测试 v0.2 =====");
        System.out.println("输入图片: " + inputPath);
        System.out.println("预热: " + WARMUP_ITERATIONS + " 次, 测量: " + iterations + " 次");
        System.out.println();

        byte[] imageData = readImage(inputPath);
        if (imageData == null) {
            System.err.println("无法读取测试图片: " + inputPath);
            System.exit(1);
        }
        System.out.println("图片大小: " + imageData.length + " bytes");
        System.out.println();

        List<ImageProcessor> impls = getUniqueExtensions();
        System.out.println("发现 " + impls.size() + " 个 ImageProcessor 实现:");
        for (ImageProcessor p : impls) {
            System.out.println("  - " + p.name() + " (available=" + p.available() + ", class=" + p.getClass().getSimpleName() + ")");
        }
        System.out.println();

        Map<String, Map<String, Object>> operations = new LinkedHashMap<>();
        operations.put("resize(200x150)", params("width", 200, "height", 150));
        operations.put("grayscale", Map.of());
        operations.put("rotate(90)", params("angle", 90));
        operations.put("blur(5)", params("sigma", 5));
        operations.put("flip(h)", params("axis", "h"));
        operations.put("brightness(50)", params("value", 50));
        operations.put("contrast(30)", params("value", 30));
        operations.put("binarize(128)", params("threshold", 128));
        operations.put("erode(3)", params("kernel", 3));
        operations.put("dilate(3)", params("kernel", 3));

        for (ImageProcessor impl : impls) {
            if (!impl.available()) {
                System.out.println("[" + impl.name() + "] 不可用，跳过");
                continue;
            }

            System.out.println("===== " + impl.name() + " =====");
            long implTotal = 0;

            for (Map.Entry<String, Map<String, Object>> op : operations.entrySet()) {
                String opName = op.getKey().contains("(") ? op.getKey().substring(0, op.getKey().indexOf("(")) : op.getKey();
                Map<String, Object> opParams = op.getValue();

                for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                    try { impl.process(imageData, opName, opParams); } catch (Exception e) { break; }
                }

                long[] times = new long[iterations];
                boolean success = true;
                for (int i = 0; i < iterations; i++) {
                    long t0 = System.nanoTime();
                    try {
                        byte[] result = impl.process(imageData, opName, opParams);
                        times[i] = (System.nanoTime() - t0) / 1_000_000;
                        if (result == null || result.length == 0) { success = false; break; }
                    } catch (Exception e) { success = false; break; }
                }

                if (!success) {
                    System.out.printf("  %-20s SKIP%n", op.getKey());
                } else {
                    long avg = Arrays.stream(times).sum() / times.length;
                    long min = Arrays.stream(times).min().orElse(0);
                    long max = Arrays.stream(times).max().orElse(0);
                    implTotal += avg;
                    System.out.printf("  %-20s avg=%3dms  min=%3dms  max=%3dms%n", op.getKey(), avg, min, max);
                }
            }

            System.out.println("  ---");
            System.out.println("  " + impl.name() + " 总计: " + implTotal + "ms");
            System.out.println();
        }

        System.out.println("===== 性能对比汇总 =====");
        for (ImageProcessor impl : impls) {
            if (!impl.available()) continue;
            long[] times = new long[iterations];
            boolean ok = true;
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                try { impl.process(imageData, "resize", params("width", 200, "height", 150)); } catch (Exception e) { ok = false; break; }
            }
            if (!ok) { System.out.println(impl.name() + ": FAILED"); continue; }
            for (int i = 0; i < iterations; i++) {
                long t0 = System.nanoTime();
                impl.process(imageData, "resize", params("width", 200, "height", 150));
                times[i] = (System.nanoTime() - t0) / 1_000_000;
            }
            long avg = Arrays.stream(times).sum() / times.length;
            System.out.printf("  %-10s resize(200x150) avg=%3dms%n", impl.name(), avg);
        }
    }

    // ==================== 工具方法 ====================

    private static byte[] readImage(String path) {
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p)) return null;
            return Files.readAllBytes(p);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<ImageProcessor> getUniqueExtensions() {
        ServiceProvider<ImageProcessor> provider = ServiceProvider.of(ImageProcessor.class);
        List<ImageProcessor> raw = provider.getNewExtensions("image-processor");
        Map<String, ImageProcessor> unique = new LinkedHashMap<>();
        for (ImageProcessor p : raw) {
            if (!unique.containsKey(p.name())) {
                unique.put(p.name(), p);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i].toString(), keyValues[i + 1]);
        }
        return map;
    }
}