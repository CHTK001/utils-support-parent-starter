package com.chua.common.support.image.processor;

import com.chua.common.support.image.ImageProcessor;
import com.chua.common.support.image.ImageProcessors;
import com.chua.common.support.spi.ServiceProvider;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * ImageProcessor 性能基准测试 — 对比 rust v0.2 / opencv / jdk 三种实现
 * 运行方式: java --enable-preview ImageProcessorBenchmark [image_path] [iterations]
 */
public class ImageProcessorBenchmark {

    private static final String DEFAULT_INPUT = "D:/images/test_1.jpg";
    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASURE_ITERATIONS = 5;

    public static void main(String[] args) throws Exception {
        String inputPath = args.length > 0 ? args[0] : DEFAULT_INPUT;
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : MEASURE_ITERATIONS;

        System.out.println("===== ImageProcessor 性能基准测试 v0.2 =====");
        System.out.println("输入图片: " + inputPath);
        System.out.println("预热: " + WARMUP_ITERATIONS + " 次, 测量: " + iterations + " 次");
        System.out.println();

        // 读取测试图片
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
            System.out.println("  - " + p.name() + " (available=" + p.available() + ", class=" + p.getClass().getSimpleName() + ")");
        }
        System.out.println();

        // 测试操作列表
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

        // 对每个实现运行基准测试
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

                // 预热
                for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                    try {
                        impl.process(imageData, opName, opParams);
                    } catch (Exception e) {
                        break;
                    }
                }

                // 测量
                long[] times = new long[iterations];
                boolean success = true;
                for (int i = 0; i < iterations; i++) {
                    long t0 = System.nanoTime();
                    try {
                        byte[] result = impl.process(imageData, opName, opParams);
                        times[i] = (System.nanoTime() - t0) / 1_000_000;
                        if (result == null || result.length == 0) {
                            success = false;
                            break;
                        }
                    } catch (Exception e) {
                        success = false;
                        break;
                    }
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

        // 汇总对比
        System.out.println("===== 性能对比汇总 =====");
        for (ImageProcessor impl : impls) {
            if (!impl.available()) continue;
            // 简单resize对比
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