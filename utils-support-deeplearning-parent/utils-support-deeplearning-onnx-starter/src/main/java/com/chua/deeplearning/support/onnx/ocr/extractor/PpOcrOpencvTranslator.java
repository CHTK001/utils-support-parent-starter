package com.chua.deeplearning.support.onnx.ocr.extractor;

import lombok.extern.slf4j.Slf4j;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PP-OCRv6 文字识别 — 纯 OpenCV DNN 实现（无 ONNX Runtime 依赖）
 *
 * <p>使用 OpenCV DNN 模块直接加载 PaddleOCR v6 ONNX 识别模型。</p>
 */
@Slf4j
public class PpOcrOpencvTranslator {

    private static final int IMG_H = 48;
    private static final int IMG_W_MAX = 1920;
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    private final List<String> dict;
    private final String modelResourcePath;
    private byte[] modelBytes;
    private Mat net;

    public PpOcrOpencvTranslator() {
        this("ocr/PP-OCRv6/tiny/rec_infer/inference.onnx", "ocr/PP-OCRv6/tiny/rec_infer/inference.yml");
    }

    public PpOcrOpencvTranslator(String modelResourcePath, String dictResourcePath) {
        this.modelResourcePath = modelResourcePath;
        this.dict = loadCharacterDict(dictResourcePath);
        log.info("[PpOcrOpencv] dict_size={}", dict.size());
    }

    private static List<String> loadCharacterDict(String resourcePath) {
        List<String> chars = new ArrayList<>();
        try (InputStream is = PpOcrOpencvTranslator.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return List.of("blank");
            }
            String content = new String(is.readAllBytes());
            boolean inDict = false;
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.equals("character_dict:")) { inDict = true; continue; }
                if (inDict) {
                    if (trimmed.startsWith("- ")) {
                        String raw = trimmed.substring(2).trim();
                        String ch = raw;
                        if (ch.length() >= 2 && (ch.startsWith("'") || ch.startsWith("\"")))
                            ch = ch.substring(1, ch.length() - 1);
                        chars.add(ch);
                    } else if (!trimmed.isEmpty() && !trimmed.startsWith("-")) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[PpOcrOpencv] 字典加载失败: {}", e.getMessage());
        }
        List<String> table = new ArrayList<>(chars.size() + 1);
        table.add(0, "blank");
        table.addAll(chars);
        return table;
    }

    public String recognize(byte[] imageData) {
        ensureModel();
        Mat src = decodeImage(imageData);
        if (src == null || src.empty()) throw new IllegalArgumentException("无法解码图片");
        try {
            int srcW = src.cols(), srcH = src.rows();
            float ratio = (float) srcH / IMG_H;
            int resizeW = Math.max(16, Math.min((int) Math.ceil(srcW / ratio), IMG_W_MAX));

            Mat resized = new Mat();
            Imgproc.resize(src, resized, new Size(resizeW, IMG_H));

            // BGR -> normalize -> flatten to [1,3,48,W]
            float[] pixels = new float[3 * IMG_H * resizeW];
            for (int y = 0; y < IMG_H; y++) {
                for (int x = 0; x < resizeW; x++) {
                    double[] bgr = resized.get(y, x);
                    int idx = y * resizeW + x;
                    pixels[idx] = ((float) bgr[0] / 255.0f - MEAN[0]) / STD[0];
                    pixels[idx + IMG_H * resizeW] = ((float) bgr[1] / 255.0f - MEAN[1]) / STD[1];
                    pixels[idx + 2 * IMG_H * resizeW] = ((float) bgr[2] / 255.0f - MEAN[2]) / STD[2];
                }
            }
            resized.release();
            src.release();

            Mat inputBlob = new Mat();
            inputBlob.create(new int[]{1, 3, IMG_H, resizeW}, CvType.CV_32F);
            inputBlob.put(0, 0, pixels);

            List<Mat> outputs = new ArrayList<>();
            net.forward(outputs, net.getUnconnectedOutLayers());
            Mat result = outputs.get(0);

            String text = ctcDecode(result);
            log.debug("[PpOcrOpencv] 识别结果: [{}]", text);
            return text;
        } catch (Exception e) {
            throw new RuntimeException("[PpOcrOpencv] 识别失败: " + e.getMessage(), e);
        }
    }

    private String ctcDecode(Mat probs) {
        int[] shape = new int[probs.total() > 0 ? (int) probs.total() : 4];
        probs.get(0, 0, shape);
        int seqLen = shape[2];
        int numClasses = shape[3];

        // OpenCV DNN output is typically [1, seqLen, numClasses] for PP-OCR
        // But can also be [1, numClasses, seqLen] — detect by checking values
        StringBuilder sb = new StringBuilder();
        int prevIdx = -1;
        for (int t = 0; t < seqLen; t++) {
            int maxIdx = 0;
            float bestVal = Float.NEGATIVE_INFINITY;
            for (int cls = 0; cls < numClasses; cls++) {
                float val = probs.get(0, t, cls)[0];
                if (val > bestVal) { bestVal = val; maxIdx = cls; }
            }
            if (maxIdx != prevIdx && maxIdx != 0 && maxIdx < dict.size()) {
                String ch = dict.get(maxIdx);
                if (ch != null && !ch.isBlank()) sb.append(ch);
            }
            prevIdx = maxIdx;
        }
        return sb.toString();
    }

    private void ensureModel() {
        if (net != null) return;
        try {
            if (modelBytes == null) {
                try (InputStream is = PpOcrOpencvTranslator.class.getClassLoader()
                        .getResourceAsStream(modelResourcePath)) {
                    if (is == null) throw new IllegalStateException("模型未找到: " + modelResourcePath);
                    modelBytes = is.readAllBytes();
                }
            }
            net = Dnn.readNetFromOnnx(modelBytes);
            log.info("[PpOcrOpencv] ONNX loaded: {} dict_size={}", modelResourcePath, dict.size());
        } catch (Exception e) {
            throw new RuntimeException("[PpOcrOpencv] 模型加载失败: " + e.getMessage(), e);
        }
    }

    private static Mat decodeImage(byte[] data) {
        try (Mat mob = new Mat()) {
            return Imgcodecs.imdecode(new org.opencv.core.MatOfByte(data), Imgcodecs.IMREAD_COLOR);
        }
    }

    public void close() {
        if (net != null) { net.close(); net = null; }
        modelBytes = null;
    }
}
