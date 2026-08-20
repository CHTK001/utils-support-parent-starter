package com.chua.deeplearning.support.opencv;

import com.chua.common.support.image.ImageProcessor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenCV ImageProcessor edge 操作测试
 *
 * @author CH
 */
public class OpenCVImageProcessorEdgeTest {

    public static void main(String[] args) throws Exception {
        String imagePath = args.length > 0 ? args[0] : "D:/images/test_1.jpg";
        System.out.println("===== OpenCV ImageProcessor Edge 操作测试 =====");
        System.out.println("测试图片: " + imagePath);

        ImageProcessor processor = new OpenCVImageProcessor();
        System.out.println("实现名称: " + processor.name());
        System.out.println("可用状态: " + processor.available());

        if (!processor.available()) {
            System.err.println("ERROR: OpenCV 原生库不可用，跳过测试");
            System.exit(0);
        }

        byte[] imageData = Files.readAllBytes(new File(imagePath).toPath());
        System.out.println("图片大小: " + imageData.length + " bytes");
        System.out.println();

        int passCount = 0;
        int failCount = 0;

        // 测试 Canny 边缘检测（默认）
        String[] cannyTests = {"canny(默认)", "canny(自定义阈值)"};
        for (String label : cannyTests) {
            try {
                Map<String, Object> params = new HashMap<>();
                if (label.contains("自定义阈值")) {
                    params.put("threshold1", 30);
                    params.put("threshold2", 100);
                }
                long start = System.nanoTime();
                byte[] result = processor.process(imageData, "edge", params);
                long elapsed = (System.nanoTime() - start) / 1_000_000;

                if (result == null || result.length == 0) {
                    System.out.println("  FAIL: " + label + " -> 返回空结果");
                    failCount++;
                    continue;
                }

                BufferedImage img = ImageIO.read(new ByteArrayInputStream(result));
                if (img == null) {
                    System.out.println("  FAIL: " + label + " -> 输出不是有效图像");
                    failCount++;
                    continue;
                }

                System.out.println("  PASS: " + label + " -> " + result.length + " bytes, "
                        + img.getWidth() + "x" + img.getHeight() + ", " + elapsed + "ms");
                passCount++;
            } catch (Exception e) {
                System.out.println("  FAIL: " + label + " -> 异常: " + e.getMessage());
                e.printStackTrace();
                failCount++;
            }
        }

        // 测试 Sobel 边缘检测
        String[] sobelDirections = {"both", "h", "v"};
        for (String dir : sobelDirections) {
            String label = "sobel(direction=" + dir + ")";
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("method", "sobel");
                params.put("direction", dir);
                long start = System.nanoTime();
                byte[] result = processor.process(imageData, "edge", params);
                long elapsed = (System.nanoTime() - start) / 1_000_000;

                if (result == null || result.length == 0) {
                    System.out.println("  FAIL: " + label + " -> 返回空结果");
                    failCount++;
                    continue;
                }

                BufferedImage img = ImageIO.read(new ByteArrayInputStream(result));
                if (img == null) {
                    System.out.println("  FAIL: " + label + " -> 输出不是有效图像");
                    failCount++;
                    continue;
                }

                System.out.println("  PASS: " + label + " -> " + result.length + " bytes, "
                        + img.getWidth() + "x" + img.getHeight() + ", " + elapsed + "ms");
                passCount++;
            } catch (Exception e) {
                System.out.println("  FAIL: " + label + " -> 异常: " + e.getMessage());
                e.printStackTrace();
                failCount++;
            }
        }

        System.out.println();
        System.out.println("===== 测试结果: " + passCount + " PASS, " + failCount + " FAIL =====");
        if (failCount > 0) {
            System.exit(1);
        }
    }
}