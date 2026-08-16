package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.face.FaceDetector;
import com.chua.deeplearning.support.model.PredictRectangle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 人脸检测能力示例。
 *
 * <p>通过 {@link FaceDetector#create(String)} 切换模型（scrfd-face-detector / ultra-face / yolo-face-detector 等）。</p>
 *
 * <pre>{@code
 *   FaceDetectorExample list
 *   FaceDetectorExample scrfd-face-detector face.jpg
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class FaceDetectorExample extends ExampleBase {

    private FaceDetectorExample() {
    }

    public static void main(String[] args) throws Exception {
        String model = args.length > 0 ? args[0] : null;
        String imagePath = args.length > 1 ? args[1] : null;

        if (model == null) {
            printModels("face-detect", "onnx", ModelRegistry.getAll().stream()
                    .filter(e -> e.capabilityInterface() == com.chua.deeplearning.support.face.FaceDetector.class)
                    .map(e -> com.chua.common.support.ai.chat.ModelDefinition.builder().id(e.modelId()).build())
                    .toList());
            return;
        }
        if (imagePath == null) {
            System.out.println("[face-detect] 需要图片路径");
            return;
        }
        byte[] img = Files.readAllBytes(Path.of(imagePath));
        FaceDetector detector = FaceDetector.create(model);
        long t0 = System.currentTimeMillis();
        List<PredictRectangle> boxes = detector.detect(img);
        System.out.println("[face-detect] model: " + model + " 图片: " + imagePath);
        System.out.println("       人脸数: " + boxes.size());
        for (PredictRectangle b : boxes) {
            System.out.println(String.format("       box: (%.0f,%.0f) %.0fx%.0f conf=%.2f",
                    b.x(), b.y(), b.width(), b.height(), b.confidence()));
        }
        printResult("face-detect", "onnx", model, t0);
    }
}
