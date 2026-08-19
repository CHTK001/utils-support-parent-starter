package com.chua.deeplearning.support.onnx.example;

import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.deeplearning.support.face.FaceDetector;
import com.chua.deeplearning.support.model.PredictRectangle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 嵌入式模型验证：FacePlugin 开源 SDK 三件套（检测 + 68 关键点 + 256 维特征）。
 *
 * <p>来源 Faceplugin-ltd/Open-Source-Face-Recognition-SDK，PyTorch 权重转 ONNX，
 * 分别发布为三个独立模型 jar：detect-slim / landmark / feature。</p>
 *
 * <pre>{@code
 *   FacePluginExample G:\images\三个人.jpg
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class FacePluginExample extends ExampleBase {

    /** 创建 FacePluginExample 实例 */
    private FacePluginExample() {
    }

    /**
     * 入口。
     *
     * @param args 参数 0：图片路径（默认 G:\images\三个人.jpg）
     */
    public static void main(String[] args) throws Exception {
        String imagePath = args.length > 0 ? args[0] : "G:\\images\\三个人.jpg";
        byte[] img = Files.readAllBytes(Path.of(imagePath));

        // 1. 人脸检测（FacePlugin Mb-Tiny-FD slim @ 320x240）
        FaceDetector detector = FaceDetector.create("faceplugin-face-detect-slim");
        long t0 = System.currentTimeMillis();
        List<PredictRectangle> boxes = detector.detect(img);
        long tDetect = System.currentTimeMillis() - t0;
        System.out.println("[faceplugin-detect] 模型=faceplugin-face-detect-slim 图片=" + imagePath);
        System.out.println("       人脸数: " + boxes.size() + " 首次耗时(含加载)=" + tDetect + "ms");
        for (PredictRectangle b : boxes) {
            System.out.println(String.format("       box: (%.0f,%.0f) %.0fx%.0f conf=%.2f",
                    b.x(), b.y(), b.width(), b.height(), b.confidence()));
        }
        // 纯推理耗时（warmup 后连续 5 次，取平均）
        detector.detect(img);
        long sum = 0;
        for (int i = 0; i < 5; i++) {
            long s = System.currentTimeMillis();
            detector.detect(img);
            sum += System.currentTimeMillis() - s;
        }
        System.out.println("       纯推理平均耗时=" + (sum / 5) + "ms (warmup 后 5 次)");
        if (boxes.isEmpty()) {
            System.out.println("[faceplugin] 未检测到人脸，终止");
            return;
        }
        printResult("faceplugin-detect", "onnx", "faceplugin-face-detect-slim", t0);

        // 2. 68 关键点（FacePlugin MobileFaceNet @ 64x64 灰度）
        try (FeatureClient landmark = FeatureClient.create("onnx", "")) {
            float[] lm = landmark.model("faceplugin-face-landmark").extractImage(img);
            System.out.println("[faceplugin-landmark] 模型=faceplugin-face-landmark");
            System.out.println("       输出维度: " + lm.length + " (期望 136 = 68x2)");
            System.out.println("       前 6 个值: "
                    + String.format("%.3f %.3f %.3f %.3f %.3f %.3f",
                    lm[0], lm[1], lm[2], lm[3], lm[4], lm[5]));
            printResult("faceplugin-landmark", "onnx", "faceplugin-face-landmark", 0);
        }

        // 3. 256 维人脸特征（FacePlugin IRN50 @ 128x128）
        try (FeatureClient feature = FeatureClient.create("onnx", "")) {
            float[] vec = feature.model("faceplugin-face-feature").extractImage(img);
            System.out.println("[faceplugin-feature] 模型=faceplugin-face-feature");
            System.out.println("       输出维度: " + vec.length + " (期望 256)");
            float norm = 0f;
            for (float v : vec) {
                norm += v * v;
            }
            norm = (float) Math.sqrt(norm);
            System.out.println(String.format("       向量模长(L2): %.3f (应≈1.0, translator 内已归一化)", norm));
            printResult("faceplugin-feature", "onnx", "faceplugin-face-feature", 0);
        }
    }
}
