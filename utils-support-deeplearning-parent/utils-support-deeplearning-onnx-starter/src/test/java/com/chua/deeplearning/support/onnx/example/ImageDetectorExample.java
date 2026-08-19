package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 目标检测能力示例。
 *
 * <p>通过 {@link ImageDetector#create(String)} 切换模型（yolov8s / yolo26n / scrfd 等）。</p>
 *
 * <pre>{@code
 *   ImageDetectorExample list
 *   ImageDetectorExample yolov8s scene.jpg
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class ImageDetectorExample extends ExampleBase {

    private ImageDetectorExample() {
    }

    public static void main(String[] args) throws Exception {
        String model = args.length > 0 ? args[0] : null;
        String imagePath = args.length > 1 ? args[1] : null;

        if (model == null) {
            printModels("detect", "onnx", ModelRegistry.getAll().stream()
                    .filter(e -> e.capabilityInterface() == com.chua.deeplearning.support.image.ImageDetector.class)
                    .map(e -> com.chua.common.support.ai.chat.ModelDefinition.builder().id(e.modelId()).build())
                    .toList());
            return;
        }
        if (imagePath == null) {
            System.out.println("[detect] 需要图片路径");
            return;
        }
        byte[] img = Files.readAllBytes(Path.of(imagePath));
        ImageDetector detector = ImageDetector.create(model);
        long t0 = System.currentTimeMillis();
        List<DetectionInfo> results = detector.detect(img);
        System.out.println("[detect] model: " + model + " 图片: " + imagePath);
        System.out.println("       目标数: " + results.size());
        for (DetectionInfo d : results) {
            System.out.println(String.format("       %s: (%.0f,%.0f) %.0fx%.0f conf=%.2f",
                    d.label(), d.x(), d.y(), d.width(), d.height(), d.confidence()));
        }
        printResult("detect", "onnx", model, t0);
    }
}
