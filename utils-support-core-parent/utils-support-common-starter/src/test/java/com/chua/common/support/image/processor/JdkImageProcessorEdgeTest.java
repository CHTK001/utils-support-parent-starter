package com.chua.common.support.image.processor;

import com.chua.common.support.image.ImageProcessor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * JdkImageProcessor edge 操作单元测试
 *
 * @author CH
 */
public class JdkImageProcessorEdgeTest {

    public static void main(String[] args) throws Exception {
        String imagePath = args.length > 0 ? args[0] : "D:/images/test_1.jpg";
        System.out.println("===== JdkImageProcessor Edge 操作测试 =====");
        System.out.println("测试图片: " + imagePath);

        byte[] imageData = Files.readAllBytes(new File(imagePath).toPath());
        System.out.println("图片大小: " + imageData.length + " bytes");

        ImageProcessor processor = new JdkImageProcessor();
        System.out.println("处理器名称: " + processor.name());
        System.out.println("处理器可用: " + processor.available());
        System.out.println();

        // 测试1: edge - both 方向（默认）
        testEdge(processor, imageData, "both", null);
        // 测试2: edge - 水平方向
        testEdge(processor, imageData, "h", null);
        // 测试3: edge - 垂直方向
        testEdge(processor, imageData, "v", null);
        // 测试4: edge - 不指定方向（默认both）
        testEdgeDefault(processor, imageData);

        System.out.println();
        System.out.println("===== 所有测试通过 =====");
    }

    private static void testEdge(ImageProcessor processor, byte[] imageData, String direction, String expectedLabel) {
        Map<String, Object> params = new HashMap<>();
        params.put("direction", direction);
        String label = expectedLabel != null ? expectedLabel : direction;

        long start = System.nanoTime();
        byte[] result = processor.process(imageData, "edge", params);
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        if (result == null || result.length == 0) {
            throw new RuntimeException("FAIL: edge(" + label + ") 返回空结果");
        }

        // 验证输出是有效图像
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(result));
            if (img == null) {
                throw new RuntimeException("FAIL: edge(" + label + ") 输出不是有效图像");
            }
            System.out.println("  PASS: edge(direction=" + label + ") -> " + result.length + " bytes, "
                    + img.getWidth() + "x" + img.getHeight() + ", " + elapsed + "ms");
        } catch (IOException e) {
            throw new RuntimeException("FAIL: edge(" + label + ") 图像解析失败: " + e.getMessage());
        }
    }

    private static void testEdgeDefault(ImageProcessor processor, byte[] imageData) {
        Map<String, Object> params = new HashMap<>();
        long start = System.nanoTime();
        byte[] result = processor.process(imageData, "edge", params);
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        if (result == null || result.length == 0) {
            throw new RuntimeException("FAIL: edge(默认) 返回空结果");
        }

        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(result));
            if (img == null) {
                throw new RuntimeException("FAIL: edge(默认) 输出不是有效图像");
            }
            System.out.println("  PASS: edge(默认direction=both) -> " + result.length + " bytes, "
                    + img.getWidth() + "x" + img.getHeight() + ", " + elapsed + "ms");
        } catch (IOException e) {
            throw new RuntimeException("FAIL: edge(默认) 图像解析失败: " + e.getMessage());
        }
    }
}