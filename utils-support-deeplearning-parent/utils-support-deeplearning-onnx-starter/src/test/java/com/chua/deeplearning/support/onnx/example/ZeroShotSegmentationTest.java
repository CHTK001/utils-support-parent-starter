package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.image.ImageSegmenter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 零样本分割端到端测试。
 *
 * <p>测试 CLIPSeg 零样本语义分割模型。</p>
 *
 * <p>运行方式：</p>
 * <pre>{@code
 *   # 测试 CLIPSeg 分割
 *   java ZeroShotSegmentationTest
 *
 *   # 指定图片
 *   java ZeroShotSegmentationTest clipseg-zero-shot D:/images/test.jpg
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ZeroShotSegmentationTest extends ExampleBase {

    /** 零样本分割模型列表 */
    private static final String[] MODELS = {
            "clipseg-zero-shot"
    };

    /** 默认测试图片路径 */
    private static final String DEFAULT_IMAGE = "D:/images/test.jpg";

    /** 创建 ZeroShotSegmentationTest 实例 */
    private ZeroShotSegmentationTest() {
    }

    /** Main */
    public static void main(String[] args) throws Exception {
        String model = args.length > 0 ? args[0] : null;
        String imagePath = args.length > 1 ? args[1] : DEFAULT_IMAGE;

        if ("list".equalsIgnoreCase(model)) {
            printModels("zero-shot-segment", "onnx",
                    java.util.Arrays.stream(MODELS)
                            .map(id -> com.chua.common.support.ai.chat.ModelDefinition.builder().id(id).build())
                            .toList());
            return;
        }

        // 读取测试图片
        Path imageFile = Path.of(imagePath);
        if (!Files.exists(imageFile)) {
            System.out.println("[ERROR] 测试图片不存在: " + imagePath);
            System.out.println("请提供有效的图片路径，例如: java ZeroShotSegmentationTest clipseg-zero-shot D:/images/test.jpg");
            return;
        }
        byte[] imageData = Files.readAllBytes(imageFile);
        System.out.println("[INFO] 测试图片: " + imagePath + " (" + imageData.length + " bytes)");
        System.out.println();

        if (model != null) {
            // 测试指定模型
            testModel(model, imageData);
        } else {
            // 测试所有模型
            for (String m : MODELS) {
                testModel(m, imageData);
            }
        }
    }

    /**
     * 测试单个零样本分割模型。
     *
     * @param modelId   模型 ID
     * @param imageData 图片数据
     */
    private static void testModel(String modelId, byte[] imageData) {
        System.out.println("===== [零样本分割] 模型: " + modelId + " =====");
        try {
            ImageSegmenter segmenter = ImageSegmenter.create(modelId);
            if (segmenter == null) {
                System.out.println("  ⚠️ 跳过 - 模型未注册: " + modelId);
                return;
            }
            long t0 = System.currentTimeMillis();
            byte[] mask = segmenter.segment(imageData);
            long elapsed = System.currentTimeMillis() - t0;

            if (mask != null && mask.length > 0) {
                System.out.println("  分割掩码: " + mask.length + " bytes (PNG)");
                // 统计前景像素比例
                int foregroundPixels = countForegroundPixels(mask);
                System.out.println("  前景像素占比: " + foregroundPixels + "%");
            } else {
                System.out.println("  分割掩码: (空)");
            }
            System.out.println("  耗时: " + elapsed + "ms");
            System.out.println("  ✅ 通过");
        } catch (Exception e) {
            System.out.println("  ❌ 失败: " + e.getMessage());
        }
        System.out.println();
    }

    /**
     * 统计 PNG 掩码中白色（前景）像素的大致比例。
     * <p>通过解码 PNG 图像计算非零像素占比。</p>
     *
     * @param mask 掩码字节数组（PNG 格式）
     * @return 前景像素百分比 (0-100)，解码失败返回 -1
     */
    private static int countForegroundPixels(byte[] mask) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(mask));
            if (img == null) {
                return -1;
            }
            int total = img.getWidth() * img.getHeight();
            int foreground = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int rgb = img.getRGB(x, y);
                    int alpha = (rgb >> 24) & 0xFF;
                    if (alpha > 0) {
                        foreground++;
                    }
                }
            }
            return (int) ((foreground * 100.0) / total);
        } catch (Exception e) {
            return -1;
        }
    }
}
