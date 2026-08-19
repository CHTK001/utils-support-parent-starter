package com.chua.deeplearning.support.onnx.example;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * OCR 检测诊断：打印概率图统计（对照 Python 验证 Java 端预处理一致性）。
 *
 * @since 4.0.0.42
 */
public final class OcrDetDiag {

    private OcrDetDiag() {
    }

    public static void main(String[] args) throws Exception {
        String imagePath = args.length > 0 ? args[0] : "G:\\images\\车票.png";
        byte[] data = Files.readAllBytes(Path.of(imagePath));

        // 对照 1：ImageDetector 门面（注释掉以隔离状态污染）
        // try {
        //     com.chua.deeplearning.support.image.ImageDetector detector =
        //             com.chua.deeplearning.support.image.ImageDetector.create("paddleocrv6-medium-det");
        //     java.util.List<com.chua.deeplearning.support.model.DetectionInfo> boxes = detector.detect(data);
        //     System.out.println("[ImageDetector] 目标数: " + boxes.size());
        // } catch (Exception e) {
        //     System.out.println("[ImageDetector] 异常: " + e.getMessage());
        // }

        nu.pattern.OpenCV.loadLocally();
        Mat src = Imgcodecs.imdecode(new MatOfByte(data), Imgcodecs.IMREAD_COLOR);
        System.out.println("src: " + src.cols() + "x" + src.rows());

        int srcW = src.cols();
        int srcH = src.rows();
        int w = srcW, h = srcH;
        float ratio = Math.min(1.0f, 960f / Math.max(w, h));
        if (ratio < 1.0f) {
            w = Math.max(1, Math.round(w * ratio));
            h = Math.max(1, Math.round(h * ratio));
        }
        w = Math.max(32, (w / 32) * 32);
        h = Math.max(32, (h / 32) * 32);
        System.out.println("resized: " + w + "x" + h);

        Mat resized = new Mat();
        Imgproc.resize(src, resized, new Size(w, h), 0, 0, Imgproc.INTER_LINEAR);
        System.out.println("resized type: " + resized.type() + " (CV_8UC3=" + org.opencv.core.CvType.CV_8UC3 + ")");

        float[] mean = {0.485f, 0.456f, 0.406f};
        float[] std = {0.229f, 0.224f, 0.225f};
        float[] pixels = new float[3 * h * w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double[] bgr = resized.get(y, x);
                int idx = y * w + x;
                pixels[idx] = (((float) bgr[2] / 255.0f) - mean[0]) / std[0];
                pixels[idx + h * w] = (((float) bgr[1] / 255.0f) - mean[1]) / std[1];
                pixels[idx + 2 * h * w] = (((float) bgr[0] / 255.0f) - mean[2]) / std[2];
            }
        }

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        try (OrtSession session = env.createSession(
                "G:\\work\\models\\ocr\\PP-OCRv6\\medium\\det_infer\\inference.onnx", opts)) {
            long[] shape = {1, 3, h, w};
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), shape)) {
                try (OrtSession.Result result = session.run(Map.of("x", tensor))) {
                    Object out = result.get(0).getValue();
                    System.out.println("output class: " + out.getClass().getName());
                    float pmax = 0;
                    int over03 = 0;
                    int total = 0;
                    if (out instanceof float[][][][] d4) {
                        float[][][] pm = d4[0];
                        System.out.println("d4 dims: " + pm.length + "x" + pm[0].length + "x" + pm[0][0].length);
                        for (float[][] c : pm) {
                            for (float[] row : c) {
                                for (float v : row) {
                                    pmax = Math.max(pmax, v);
                                    if (v > 0.3f) { over03++; }
                                    total++;
                                }
                            }
                        }
                    } else if (out instanceof float[][][] d3) {
                        for (float[] row : d3[0]) {
                            for (float v : row) {
                                pmax = Math.max(pmax, v);
                                if (v > 0.3f) { over03++; }
                                total++;
                            }
                        }
                    }
                    System.out.println("probMax=" + pmax + " pct>0.3=" + (total > 0 ? (float) over03 / total : 0));

                    // 复现 boxesFromProbMap 逻辑
                    float[][] probs;
                    if (out instanceof float[][][][] d4) {
                        probs = d4[0][0];
                    } else {
                        float[][][] d3 = (float[][][]) out;
                        probs = d3[0];
                    }
                    float pMax2 = 0;
                    int over03b = 0;
                    for (float[] row : probs) {
                        for (float v : row) {
                            pMax2 = Math.max(pMax2, v);
                            if (v > 0.3f) over03b++;
                        }
                    }
                    System.out.println("probs dims=" + probs.length + "x" + probs[0].length
                            + " max=" + pMax2 + " >0.3=" + over03b + " total=" + (probs.length * probs[0].length));
                    int mapH = probs.length;
                    int mapW = probs[0].length;
                    Mat binary = new Mat(mapH, mapW, org.opencv.core.CvType.CV_8UC1);
                    byte[] binData = new byte[mapH * mapW];
                    int white = 0;
                    for (int y = 0; y < mapH; y++) {
                        for (int x = 0; x < mapW; x++) {
                            if (probs[y][x] >= 0.3f) {
                                binData[y * mapW + x] = (byte) 255;
                                white++;
                            }
                        }
                    }
                    binary.put(0, 0, binData);
                    System.out.println("binary white pixels: " + white + " of " + (mapH * mapW));
                    Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2));
                    Mat dilated = new Mat();
                    Imgproc.dilate(binary, dilated, kernel);
                    java.util.List<MatOfPoint> contours = new java.util.ArrayList<>();
                    Mat hierarchy = new Mat();
                    Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
                    System.out.println("contours found: " + contours.size());
                    int kept = 0;
                    for (MatOfPoint c : contours) {
                        org.opencv.core.Rect r = Imgproc.boundingRect(c);
                        if (r.width >= 5 && r.height >= 5 && r.area() >= 25) {
                            kept++;
                        }
                    }
                    System.out.println("contours kept: " + kept);
                }
            }
        }
    }
}
