package com.chua.deeplearning.support.onnx;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.Result;
import ai.onnxruntime.OnnxTensor;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.onnx.OnnxModelRegistrar;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.awt.image.BufferedImage;
import java.awt.Color;
import javax.imageio.ImageIO;

/**
 * 离线端到端 ONNX Runtime 测试：直接用 OnnxRuntime Java API 验证 efficientnet-b1 推理。
 * 模型从 utils-support-models-onnx-efficientnet jar 中加载，无网络依赖。
 */
public class DirectOrtInferenceTest {
    public static void main(String[] args) throws Exception {
        // SPI 触发注册
        new OnnxModelRegistrar().register(null);
        ModelRegistry.discoverAll();

        // 1) 从 utils-support-models-onnx-efficientnet 加载
        Path modelPath = ModelRegistry.resolveModelPath("efficientnet-b1-classification");
        if (modelPath == null || !Files.exists(modelPath)) {
            System.out.println("[FAIL] efficientnet 模型未找到: " + modelPath);
            System.exit(2);
        }
        System.out.println("[OK] 模型: " + modelPath);
        System.out.println("[OK] 大小: " + (Files.size(modelPath) / 1024 / 1024) + " MB");

        // 2) 构造 224x224 测试图（彩色条带）
        BufferedImage img = new BufferedImage(224, 224, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 224; y++) {
            for (int x = 0; x < 224; x++) {
                int r = (x * 255 / 223);
                int g = (y * 255 / 223);
                int b = ((x + y) * 255 / 446);
                img.setRGB(x, y, new Color(r, g, b).getRGB());
            }
        }
        Path tmpPng = Files.createTempFile("ort-test-", ".png");
        ImageIO.write(img, "png", tmpPng.toFile());

        // 3) 准备 (1,3,224,224) input tensor
        int HW = 224 * 224;
        FloatBuffer buf = FloatBuffer.allocate(3 * HW);
        float[] data = new float[3 * HW];
        for (int y = 0; y < 224; y++) {
            for (int x = 0; x < 224; x++) {
                int rgb = img.getRGB(x, y);
                float r = ((rgb >> 16) & 0xff) / 255f;
                float g = ((rgb >> 8) & 0xff) / 255f;
                float b = (rgb & 0xff) / 255f;
                data[y * 224 + x] = r;
                data[HW + y * 224 + x] = g;
                data[2 * HW + y * 224 + x] = b;
            }
        }
        buf.put(data);
        buf.flip();

        // 4) 用 OnnxRuntime 跑推理
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        long t0 = System.currentTimeMillis();
        try (OrtSession session = env.createSession(modelPath.toString())) {
            long loadMs = System.currentTimeMillis() - t0;
            System.out.println("[OK] Session 创建: " + loadMs + "ms");
            System.out.println("[OK] 输入节点: " + session.getInputNames());
            System.out.println("[OK] 输出节点: " + session.getOutputNames());

            long[] shape = {1, 3, 224, 224};
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, buf, shape);
                 Result result = session.run(Collections.singletonMap("input", tensor))) {
                long inferMs = System.currentTimeMillis() - t0 - loadMs;
                System.out.println("[OK] 推理耗时: " + inferMs + "ms");
                try (var output = result.get(0)) {
                    float[][] logits = (float[][]) output.getValue();
                    System.out.println("[OK] 输出 shape: (" + logits.length + ", " + logits[0].length + ")");

                    // Top-5
                    int[] topIdx = {-1, -1, -1, -1, -1};
                    float[] topVal = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
                    for (int i = 0; i < logits[0].length; i++) {
                        float v = logits[0][i];
                        for (int j = 0; j < 5; j++) {
                            if (v > topVal[j]) {
                                for (int k = 4; k > j; k--) {
                                    topIdx[k] = topIdx[k-1]; topVal[k] = topVal[k-1];
                                }
                                topIdx[j] = i; topVal[j] = v; break;
                            }
                        }
                    }
                    System.out.println("[OK] Top-5 logits:");
                    for (int i = 0; i < 5; i++) {
                        System.out.println("    #" + (i+1) + " class_" + topIdx[i] +
                            " logit=" + String.format("%.4f", topVal[i]));
                    }
                }
            }
        }

        Files.deleteIfExists(tmpPng);
        System.out.println("\n=== Direct ONNX Runtime 离线推理 PASS ===");
        System.exit(0);
    }
}
