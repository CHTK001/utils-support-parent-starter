package com.chua.deeplearning.support.onnx.example;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 打印车牌/识别模型 ONNX 输入输出结构。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OnnxShapeDiag {

    private OnnxShapeDiag() {
    }

    public static void main(String[] args) throws Exception {
        print("yolov5-plate-rec", "vision/detection/yolov5_plate/", "yolov5_plate_rec_color.onnx",
                OnnxShapeDiag.class.getClassLoader());
    }

    private static void print(String label, String base, String file, ClassLoader cl) throws Exception {
        Path tmp = Files.createTempDirectory("shape-");
        tmp.toFile().deleteOnExit();
        NativeLoader.of(label).from(cl).basePath(base).toTarget(tmp)
                .glob("*.onnx").withMd5(true).extractOnly(true).load();
        Path model = tmp.resolve(file);
        System.out.println("===== " + label + " -> " + model.getFileName() + " =====");
        try (OrtSession session = OrtEnvironment.getEnvironment().createSession(model.toString(),
                new OrtSession.SessionOptions())) {
            for (var e : session.getInputInfo().entrySet()) {
                System.out.println("  IN  " + e.getKey() + " -> " + e.getValue());
            }
            for (var e : session.getOutputInfo().entrySet()) {
                System.out.println("  OUT " + e.getKey() + " -> " + e.getValue());
            }
        }
    }
}