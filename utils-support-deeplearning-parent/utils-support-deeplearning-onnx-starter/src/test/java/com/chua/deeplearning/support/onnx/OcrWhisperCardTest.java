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
 * PP-OCRv6 + Card correction + Whisper 离线推理测试。
 * 每个模型输入 shape 不同（动态 batch 维度）。
 */
public class OcrWhisperCardTest {
    static int totalOk = 0;
    static int totalFail = 0;

    public static void main(String[] args) throws Exception {
        new OnnxModelRegistrar().register(null);
        ModelRegistry.discoverAll();
        OrtEnvironment env = OrtEnvironment.getEnvironment();

        // PP-OCRv6 检测模型：动态 batch (1,3,H,W) -> (1,1,H',W')
        runDynamic(env, "paddleocrv6-det",
            "ocr/PP-OCRv6/tiny/det_infer/inference.onnx",
            new long[]{1, 3, 640, 640}, "x",
            5.6f);
        runDynamic(env, "paddleocrv6-det",
            "ocr/PP-OCRv6/medium/det_infer/inference.onnx",
            new long[]{1, 3, 640, 640}, "x",
            60.6f);

        // PP-OCRv6 识别模型：动态 (1,3,48,W) -> (1,1,N)
        runDynamic(env, "paddleocrv6-rec",
            "ocr/PP-OCRv6/tiny/rec_infer/inference.onnx",
            new long[]{1, 3, 48, 320}, "x",
            4.4f);
        runDynamic(env, "paddleocrv6-rec",
            "ocr/PP-OCRv6/medium/rec_infer/inference.onnx",
            new long[]{1, 3, 48, 320}, "x",
            74.8f);

        // Card correction: (1,3,768,768) -> heatmap/wh/reg/cls 多输出
        runDynamic4Out(env, "card-correction-detector",
            "cv/card_correction/card_detection.onnx",
            new long[]{1, 3, 768, 768}, "image", 38.0f);

        System.out.println("\n========================================");
        System.out.println(String.format("汇总: %d 成功, %d 失败", totalOk, totalFail));
        System.out.println("========================================");
        System.exit(totalFail > 0 ? 1 : 0);
    }

    static void runDynamic(OrtEnvironment env, String name, String resource,
                           long[] shape, String inputName, float expectedMB) throws Exception {
        Path modelPath = ModelRegistry.resolveModelPath(name);
        if (modelPath == null || !Files.exists(modelPath)) {
            System.out.println("[" + name + "] 模型路径无效, 跳过");
            totalFail++;
            return;
        }
        long t0 = System.currentTimeMillis();
        try (OrtSession session = env.createSession(modelPath.toString())) {
            long loadMs = System.currentTimeMillis() - t0;
            long vol = shape[0] * shape[1] * shape[2] * shape[3];
            FloatBuffer buf = FloatBuffer.allocate((int)vol);
            float[] data = new float[(int)vol];
            for (int i = 0; i < vol; i++) data[i] = (float)((i % 256) / 255.0);
            buf.put(data).flip();

            try (OnnxTensor input = OnnxTensor.createTensor(env, buf, shape);
                 Result result = session.run(Collections.singletonMap(inputName, input))) {
                long inferMs = System.currentTimeMillis() - t0 - loadMs;
                StringBuilder outs = new StringBuilder();
                int i = 0;
                for (Map.Entry<String, OnnxValue> e : result) { OnnxValue v = e.getValue(); String keyName = e.getKey();
                    Object raw = v.getValue();
                    String desc;
                    if (raw instanceof float[][][]) {
                        float[][][] a = (float[][][]) raw;
                        desc = String.format("(%d,%d,%d)", a.length, a[0].length, a[0][0].length);
                    } else if (raw instanceof float[][]) {
                        float[][] a = (float[][]) raw;
                        desc = String.format("(%d,%d)", a.length, a[0].length);
                    } else if (raw instanceof float[]) {
                        desc = "len=" + ((float[])raw).length;
                    } else if (raw instanceof int[][][]) {
                        int[][][] a = (int[][][]) raw;
                        desc = String.format("int(%d,%d,%d)", a.length, a[0].length, a[0][0].length);
                    } else if (raw instanceof int[]) {
                        desc = "int[]=" + ((int[])raw).length;
                    } else {
                        desc = raw.getClass().getSimpleName();
                    }
                    outs.append(" ").append(keyName).append("=").append(desc);
                    i++;
                    v.close();
                }
                System.out.println(String.format(
                    "[OK] %s: file=%.1fMB load=%dms infer=%dms%s",
                    name, expectedMB, loadMs, inferMs, outs));
                totalOk++;
            }
        } catch (Throwable e) {
            System.out.println("[" + name + "] FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            totalFail++;
        }
    }

    // card-correction 有 4 输出 (hm/wh/reg/cls) 用各自名字
    static void runDynamic4Out(OrtEnvironment env, String name, String resource,
                               long[] shape, String inputName, float expectedMB) throws Exception {
        Path modelPath = ModelRegistry.resolveModelPath(name);
        if (modelPath == null || !Files.exists(modelPath)) {
            System.out.println("[" + name + "] 模型路径无效, 跳过");
            totalFail++;
            return;
        }
        long t0 = System.currentTimeMillis();
        try (OrtSession session = env.createSession(modelPath.toString())) {
            long loadMs = System.currentTimeMillis() - t0;
            long vol = shape[0] * shape[1] * shape[2] * shape[3];
            FloatBuffer buf = FloatBuffer.allocate((int)vol);
            float[] data = new float[(int)vol];
            for (int i = 0; i < vol; i++) data[i] = (float)((i % 256) / 255.0);
            buf.put(data).flip();

            try (OnnxTensor input = OnnxTensor.createTensor(env, buf, shape);
                 Result result = session.run(Collections.singletonMap(inputName, input))) {
                long inferMs = System.currentTimeMillis() - t0 - loadMs;
                StringBuilder outs = new StringBuilder();
                int i = 0;
                for (Map.Entry<String, OnnxValue> e : result) { OnnxValue v = e.getValue(); String keyName = e.getKey();
                    Object raw = v.getValue();
                    String desc;
                    if (raw instanceof float[][][][]) {
                        float[][][][] a = (float[][][][]) raw;
                        desc = String.format("(%d,%d,%d,%d)", a.length, a[0].length, a[0][0].length, a[0][0][0].length);
                    } else if (raw instanceof float[][][]) { float[][][] a = (float[][][]) raw; desc = String.format("(%d,%d,%d)", a.length, a[0].length, a[0][0].length);
                    } else if (raw instanceof float[][]) {
                        float[][] a = (float[][]) raw;
                        desc = String.format("(%d,%d)", a.length, a[0].length);
                    } else {
                        desc = raw.getClass().getSimpleName();
                    }
                    outs.append(" ").append(keyName).append("=").append(desc);
                    i++;
                    v.close();
                }
                System.out.println(String.format(
                    "[OK] %s: file=%.1fMB load=%dms infer=%dms%s",
                    name, expectedMB, loadMs, inferMs, outs));
                totalOk++;
            }
        } catch (Throwable e) {
            System.out.println("[" + name + "] FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            totalFail++;
        }
    }
}



