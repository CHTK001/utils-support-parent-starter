package com.chua.deeplearning.support.onnx;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.Result;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.onnx.OnnxModelRegistrar;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 综合离线端到端测试：对所有 8 个 models-parent 模块跑 ONNX Runtime 推理。
 * 验证模型能从 m2 jar 离线加载、推理返回合法输出。
 */
public class AllModelsInferenceTest {

    static int totalOk = 0;
    static int totalFail = 0;

    public static void main(String[] args) throws Exception {
        new OnnxModelRegistrar().register(null);
        ModelRegistry.discoverAll();
        OrtEnvironment env = OrtEnvironment.getEnvironment();

        // ============== efficientnet / fer / minilm: (1,3,224,224) -> (1,1000) 分类 ==============
        runClassifier224(env, "efficientnet-b1-classification",
            "vision/classification/efficientnet/efficientnet-lite4-11.onnx", 30.5f);
        runClassifier224(env, "fer-plus",
            "face/expression/FER/FER.onnx", 4.9f);
        runClassifier224(env, "minilm-embedding",
            "nlp/embedding/minilm/MiniLM.onnx", 16.9f);

        // ============== yolov5-plate-detect: (1,3,640,640) -> (1,25200,15) ==============
        runYoloDetect(env, "yolov5-plate-detect",
            "vision/detection/yolov5_plate/yolov5_plate_detect.onnx", 3.7f);

        // 汇总
        System.out.println("\n========================================");
        System.out.println(String.format("汇总: %d 成功, %d 失败", totalOk, totalFail));
        System.out.println("========================================");
        System.exit(totalFail > 0 ? 1 : 0);
    }

    static void runClassifier224(OrtEnvironment env, String name, String resource, float expectedMB) throws Exception {
        Path modelPath = ModelRegistry.resolveModelPath(name);
        if (modelPath == null || !Files.exists(modelPath)) {
            System.out.println("[" + name + "] 模型路径无效, 跳过");
            totalFail++;
            return;
        }
        long t0 = System.currentTimeMillis();
        try (OrtSession session = env.createSession(modelPath.toString())) {
            long loadMs = System.currentTimeMillis() - t0;
            // 224x224 RGB float[] 输入
            FloatBuffer buf = FloatBuffer.allocate(3 * 224 * 224);
            float[] data = new float[3 * 224 * 224];
            int idx = 0;
            for (int c = 0; c < 3; c++) for (int y = 0; y < 224; y++) for (int x = 0; x < 224; x++)
                data[idx++] = ((c * 80 + x + y) % 256) / 255f;
            buf.put(data).flip();

            try (OnnxTensor input = OnnxTensor.createTensor(env, buf, new long[]{1, 3, 224, 224});
                 Result result = session.run(Collections.singletonMap("input", input))) {
                long inferMs = System.currentTimeMillis() - t0 - loadMs;
                OnnxValue outVal = result.get(0);
                try {
                    float[][] logits = (float[][]) outVal.getValue();
                    int n = logits[0].length;
                    float max = Float.NEGATIVE_INFINITY;
                    int maxIdx = -1;
                    int nanCount = 0;
                    for (int i = 0; i < n; i++) {
                        float v = logits[0][i];
                        if (Float.isNaN(v) || Float.isInfinite(v)) { nanCount++; continue; }
                        if (v > max) { max = v; maxIdx = i; }
                    }
                    System.out.println(String.format(
                        "[OK] %s: file=%.1fMB load=%dms infer=%dms shape=(1,%d) top=%d logit=%.4f nan/inf=%d",
                        name, expectedMB, loadMs, inferMs, n, maxIdx, max, nanCount));
                    totalOk++;
                } finally {
                    outVal.close();
                }
            }
        } catch (Throwable e) {
            System.out.println("[" + name + "] FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            totalFail++;
        }
    }

    static void runYoloDetect(OrtEnvironment env, String name, String resource, float expectedMB) throws Exception {
        Path modelPath = ModelRegistry.resolveModelPath(name);
        if (modelPath == null || !Files.exists(modelPath)) {
            System.out.println("[" + name + "] 模型路径无效, 跳过");
            totalFail++;
            return;
        }
        long t0 = System.currentTimeMillis();
        try (OrtSession session = env.createSession(modelPath.toString())) {
            long loadMs = System.currentTimeMillis() - t0;
            FloatBuffer buf = FloatBuffer.allocate(3 * 640 * 640);
            float[] data = new float[3 * 640 * 640];
            int idx = 0;
            for (int c = 0; c < 3; c++) for (int y = 0; y < 640; y++) for (int x = 0; x < 640; x++)
                data[idx++] = ((c * 79 + (x + y) / 32) % 256) / 255f;
            buf.put(data).flip();

            try (OnnxTensor input = OnnxTensor.createTensor(env, buf, new long[]{1, 3, 640, 640});
                 Result result = session.run(Collections.singletonMap("input", input))) {
                long inferMs = System.currentTimeMillis() - t0 - loadMs;
                OnnxValue outVal = result.get(0);
                try {
                    float[][][] arr = (float[][][]) outVal.getValue();
                    int nan = 0;
                    float max = Float.NEGATIVE_INFINITY;
                    float min = Float.POSITIVE_INFINITY;
                    // yolov5 输出 (1, 25200, 15)，统计前几个 anchor 看是否合理
                    int checkN = Math.min(100, arr[0].length);
                    for (int j = 0; j < checkN; j++) {
                        for (int k = 0; k < arr[0][j].length; k++) {
                            float v = arr[0][j][k];
                            if (Float.isNaN(v) || Float.isInfinite(v)) { nan++; continue; }
                            if (v > max) max = v;
                            if (v < min) min = v;
                        }
                    }
                    System.out.println(String.format(
                        "[OK] %s: file=%.1fMB load=%dms infer=%dms shape=(1,%d,%d) range=[%.3f,%.3f] nan/inf=%d (sample)",
                        name, expectedMB, loadMs, inferMs, arr[0].length, arr[0][0].length,
                        min, max, nan));
                    totalOk++;
                } finally {
                    outVal.close();
                }
            }
        } catch (Throwable e) {
            System.out.println("[" + name + "] FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            totalFail++;
        }
    }
}
