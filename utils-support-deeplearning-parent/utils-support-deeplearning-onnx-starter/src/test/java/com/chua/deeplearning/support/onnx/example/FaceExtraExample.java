package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.face.FaceDetector;
import com.chua.deeplearning.support.liveness.LivenessDetector;
import com.chua.deeplearning.support.model.PredictRectangle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 嵌入式模型验证：动漫人脸检测 + FLRGB 活体检测。
 *
 * <pre>{@code
 *   FaceExtraExample anime-face-detector G:\images\anime_test.jpg
 *   FaceExtraExample face-liveness-flrgb G:\images\黑白人物.jpg
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class FaceExtraExample extends ExampleBase {

    /** 创建 FaceExtraExample 实例 */
    private FaceExtraExample() {
    }

    /** Main */
    public static void main(String[] args) throws Exception {
        String model = args.length > 0 ? args[0] : "anime-face-detector";
        String imagePath = args.length > 1 ? args[1] : "G:\\images\\anime_test.jpg";
        byte[] img = Files.readAllBytes(Path.of(imagePath));

        if ("liveness".equals(model)) {
            String livenessModel = args.length > 2 ? args[2] : "face-liveness-flrgb";
            LivenessDetector detector = LivenessDetector.create(livenessModel);
            long t0 = System.currentTimeMillis();
            float score = detector.liveScore(img);
            System.out.println("[liveness] 模型=" + livenessModel + " 图片=" + imagePath);
            System.out.println("       活体分数=" + String.format("%.3f", score)
                    + " (" + (score >= 0.5 ? "活体" : "疑似假体") + ")");
            printResult("liveness", "onnx", livenessModel, t0);
            return;
        }

        FaceDetector detector = FaceDetector.create(model);
        long t0 = System.currentTimeMillis();
        List<PredictRectangle> boxes = detector.detect(img);
        System.out.println("[anime-face] 模型=" + model + " 图片=" + imagePath);
        System.out.println("       动漫人脸数: " + boxes.size());
        for (PredictRectangle b : boxes) {
            System.out.println(String.format("       box: (%.0f,%.0f) %.0fx%.0f conf=%.2f",
                    b.x(), b.y(), b.width(), b.height(), b.confidence()));
        }
        printResult("anime-face", "onnx", model, t0);
    }
}
