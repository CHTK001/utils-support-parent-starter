package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 零样本检测端到端测试。
 *
 * <p>测试 YOLO-World (s/m/l)、OWLv2、GroundingDINO 零样本检测模型。</p>
 *
 * <p>运行方式：</p>
 * <pre>{@code
 *   # 测试所有零样本检测模型
 *   java ZeroShotDetectionTest
 *
 *   # 指定模型 + 图片
 *   java ZeroShotDetectionTest yolov8s-world D:/images/test.jpg
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ZeroShotDetectionTest extends ExampleBase {

    /** 零样本检测模型列表（按体积从小到大） */
    private static final String[] MODELS = {
            "yolov8s-world",
            "yolov8m-world",
            "yolov8l-world",
            "owlv2-zero-shot-detector",
            "grounding-dino"
    };

    /** 默认测试图片路径 */
    private static final String DEFAULT_IMAGE = "D:/images/test.jpg";

    /** 创建 ZeroShotDetectionTest 实例 */
    private ZeroShotDetectionTest() {
    }

    /** Main */
    public static void main(String[] args) throws Exception {
        String model = args.length > 0 ? args[0] : null;
        String imagePath = args.length > 1 ? args[1] : DEFAULT_IMAGE;

        if ("list".equalsIgnoreCase(model)) {
            printModels("zero-shot-detect", "onnx",
                    java.util.Arrays.stream(MODELS)
                            .map(id -> com.chua.common.support.ai.chat.ModelDefinition.builder().id(id).build())
                            .toList());
            return;
        }

        // 读取测试图片
        Path imageFile = Path.of(imagePath);
        if (!Files.exists(imageFile)) {
            System.out.println("[ERROR] 测试图片不存在: " + imagePath);
            System.out.println("请提供有效的图片路径，例如: java ZeroShotDetectionTest yolov8s-world D:/images/test.jpg");
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
     * 测试单个零样本检测模型。
     *
     * @param modelId   模型 ID
     * @param imageData 图片数据
     */
    private static void testModel(String modelId, byte[] imageData) {
        System.out.println("===== [零样本检测] 模型: " + modelId + " =====");
        try {
            ImageDetector detector = ImageDetector.create(modelId);
            if (detector == null) {
                System.out.println("  ⚠️ 跳过 - 模型未注册: " + modelId);
                return;
            }
            long t0 = System.currentTimeMillis();
            List<DetectionInfo> detections = detector.detect(imageData);
            long elapsed = System.currentTimeMillis() - t0;

            if (detections.isEmpty()) {
                System.out.println("  检测结果: (无检测到目标)");
            } else {
                System.out.println("  检测结果: " + detections.size() + " 个目标");
                for (DetectionInfo det : detections) {
                    System.out.printf("    - %s (%.2f%%) [%.1f, %.1f, %.1f, %.1f]%n",
                            det.label(),
                            det.confidence() * 100,
                            det.x(), det.y(),
                            det.width(), det.height());
                }
            }
            System.out.println("  耗时: " + elapsed + "ms");
            System.out.println("  ✅ 通过");
        } catch (Exception e) {
            System.out.println("  ❌ 失败: " + e.getMessage());
        }
        System.out.println();
    }
}
