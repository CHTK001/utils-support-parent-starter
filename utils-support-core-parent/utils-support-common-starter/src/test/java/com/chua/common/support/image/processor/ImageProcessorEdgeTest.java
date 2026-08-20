package com.chua.common.support.image.processor;

import com.chua.common.support.image.ImageProcessor;
import com.chua.common.support.image.ImageProcessors;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ImageProcessor edge 操作全实现测试
 * 通过 SPI 发现所有可用实现并逐一测试 edge 操作
 *
 * @author CH
 */
public class ImageProcessorEdgeTest {

    public static void main(String[] args) throws Exception {
        String imagePath = args.length > 0 ? args[0] : "D:/images/test_1.jpg";
        System.out.println("===== ImageProcessor Edge 操作全实现测试 =====");
        System.out.println("测试图片: " + imagePath);

        byte[] imageData = Files.readAllBytes(new File(imagePath).toPath());
        System.out.println("图片大小: " + imageData.length + " bytes");
        System.out.println();

        // 通过 SPI 发现所有实现
        List<ImageProcessor> impls = getAvailableProcessors();
        System.out.println("发现 " + impls.size() + " 个可用 ImageProcessor 实现:");
        for (ImageProcessor p : impls) {
            System.out.println("  - " + p.name() + " (available=" + p.available() + ")");
        }
        System.out.println();

        if (impls.isEmpty()) {
            System.err.println("ERROR: 没有可用的 ImageProcessor 实现");
            System.exit(1);
        }

        int passCount = 0;
        int failCount = 0;

        for (ImageProcessor processor : impls) {
            System.out.println("--- 测试 " + processor.name() + " ---");
            String[] directions = {"both", "h", "v", null};
            for (String dir : directions) {
                String label = dir != null ? dir : "默认(both)";
                try {
                    Map<String, Object> params = new HashMap<>();
                    if (dir != null) {
                        params.put("direction", dir);
                    }
                    long start = System.nanoTime();
                    byte[] result = processor.process(imageData, "edge", params);
                    long elapsed = (System.nanoTime() - start) / 1_000_000;

                    if (result == null || result.length == 0) {
                        System.out.println("  FAIL: edge(" + label + ") -> 返回空结果");
                        failCount++;
                        continue;
                    }

                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(result));
                    if (img == null) {
                        System.out.println("  FAIL: edge(" + label + ") -> 输出不是有效图像");
                        failCount++;
                        continue;
                    }

                    System.out.println("  PASS: edge(" + label + ") -> " + result.length + " bytes, "
                            + img.getWidth() + "x" + img.getHeight() + ", " + elapsed + "ms");
                    passCount++;
                } catch (Exception e) {
                    System.out.println("  FAIL: edge(" + label + ") -> 异常: " + e.getMessage());
                    failCount++;
                }
            }
            System.out.println();
        }

        System.out.println("===== 测试结果: " + passCount + " PASS, " + failCount + " FAIL =====");
        if (failCount > 0) {
            System.exit(1);
        }
    }

    private static List<ImageProcessor> getAvailableProcessors() {
        List<ImageProcessor> result = new ArrayList<>();

        // 1. 通过 SPI 代理获取（自动降级）
        try {
            ImageProcessor proxy = ImageProcessors.getProcessor();
            if (proxy != null && proxy.available()) {
                result.add(proxy);
                System.out.println("SPI 代理: " + proxy.name());
            }
        } catch (Exception e) {
            System.out.println("SPI 代理发现异常: " + e.getMessage());
        }

        // 2. 直接实例化 JdkImageProcessor（兜底）
        try {
            JdkImageProcessor jdk = new JdkImageProcessor();
            if (jdk.available() && !result.stream().anyMatch(p -> "jdk".equals(p.name()))) {
                result.add(jdk);
            }
        } catch (Exception e) {
            // ignore
        }

        // 3. 尝试直接实例化 RustImageProcessor
        try {
            Class<?> rustClazz = Class.forName("com.chua.common.support.image.processor.RustImageProcessor");
            ImageProcessor rust = (ImageProcessor) rustClazz.getDeclaredConstructor().newInstance();
            if (rust.available() && !result.stream().anyMatch(p -> p.name().equals(rust.name()))) {
                result.add(rust);
            }
        } catch (Exception e) {
            // Rust 库不可用，跳过
        }

        if (result.isEmpty()) {
            result.add(new JdkImageProcessor());
        }
        return result;
    }
}