package com.chua.deeplearning.support.onnx.ocr.extractor;

import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.MatOfFloat;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * PP-OCRv6 文字识别 — 纯 OpenCV DNN 实现（无 ONNX Runtime 依赖）
 *
 * <p>使用 OpenCV DNN 模块直接加载 PaddleOCR v6 ONNX 识别模型，
 * 绕过 NativeLoader 对 onnxruntime_native.dll 的依赖。</p>
 *
 * <p>模型来源: {@code ocr/PP-OCRv6/tiny/rec_infer/inference.onnx}（来自 utils-support-models-onnx-paddleocrv6-tiny）</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class PpOcrOpencvTranslator {

    private static final int IMG_H = 48;
    private static final int IMG_W_MAX = 1920;
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    /** 字符字典（index 0 = blank） */
    private final List<String> dict;
    /** ONNX 模型路径（classpath 资源） */
    private final String modelResourcePath;

    private byte[] modelBytes;
    private Mat net;

    /**
     * 使用 PP-OCRv6 tiny 模型
     */
    public PpOcrOpencvTranslator() {
        this("ocr/PP-OCRv6/tiny/rec_infer/inference.onnx", "ocr/PP-OCRv6/tiny/rec_infer/inference.yml");
    }

    /**
     * @param modelResourcePath ONNX 模型在 classpath 中的路径
     * @param dictResourcePath  字典配置文件在 classpath 中的路径
     */
    public PpOcrOpencvTranslator(String modelResourcePath, String dictResourcePath) {
        this.modelResourcePath = modelResourcePath;
        this.dict = loadCharacterDict(dictResourcePath);
        log.info("[PpOcrOpencv] dict_size={}", dict.size());
    }

    /**
     * 从 inference.yml 中解析 character_dict
     */
    private static List<String> loadCharacterDict(String resourcePath) {
        List<String> chars = new ArrayList<>();
        try (InputStream is = PpOcrOpencvTranslator.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("[PpOcrOpencv] 未找到字典文件: {}", resourcePath);
                return List.of("blank");
            }
            byte[] data = is.readAllBytes();
            String content = new String(data);
            boolean inDict = false;
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.equals("character_dict:")) {
                    inDict = true;
                    continue;
                }
                if (inDict) {
                    if (trimmed.startsWith("- ")) {
                        String raw = trimmed.substring(2).trim();
                        String ch = raw;
                        if (ch.length() >= 2 && (ch.startsWith("'") || ch.startsWith("\""))) {
                            ch = ch.substring(1, ch.length() - 1);
                        }
                        chars.add(ch);
                    } else if (!trimmed.isEmpty() && !trimmed.startsWith("-")) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("[PpOcrOpencv] 字典加载失败: {}", e.getMessage());
        }
        List<String> table = new ArrayList<>(chars.size() + 1);
        table.add(0, "blank");
        table.addAll(chars);
        return table;
    }

    /**
     * 识别图片中的文字
     *
     * @param imageData JPEG/PNG 图片字节数组
     * @return 识别出的文本
     */
    public String recognize(byte[] imageData) {
        ensureModel();
        Mat src = decodeImage(imageData);
        if (src == null || src.empty()) {
            throw new IllegalArgumentException("无法解码图片");
        }
        try {
            int srcW = src.cols();
            int srcH = src.rows();
            float ratio = (float) srcH / IMG_H;
            int resizeW = Math.max(1, (int) Math.ceil(srcW / ratio));
            resizeW = Math.max(resizeW, 16);
            resizeW = Math.min(resizeW, IMG_W_MAX);

            Mat resized = new Mat();
            Imgproc.resize(src, resized, new Size(resizeW, IMG_H));

            // BGR -> normalize -> CHW
            float[] pixels = new float[3 * IMG_H * resizeW];
            for (int y = 0; y < IMG_H; y++) {
                for (int x = 0; x < resizeW; x++) {
                    double[] bgr = resized.get(y, x);
                    int idx = y * resizeW + x;
                    pixels[idx] = (((float) bgr[0] / 255.0f) - MEAN[0]) / STD[0];
                    pixels[idx + IMG_H * resizeW] = (((float) bgr[1] / 255.0f) - MEAN[1]) / STD[1];
                    pixels[idx + 2 * IMG_H * resizeW] = (((float) bgr[2] / 255.0f) - MEAN[2]) / STD[2];
                }
            }
            resized.release();
            src.release();

            // Input blob
            Mat inputBlob = new Mat(1, new int[]{1, 3, IMG_H, resizeW}, org.opencv.core.CvType.CV_32F);
            inputBlob.put(0, 0, pixels);

            // Forward
            List<Mat> outputs = new ArrayList<>();
            net.forward(outputs, net.getUnconnectedOutLayers());
            // PP-OCR rec model output: [1, seq, classes] or [1, classes, seq]
            // Take first output
            Mat result = outputs.get(0);

            // CTC decode
            String text = ctcDecode(result);
            log.debug("[PpOcrOpencv] 识别结果: [{}]", text);
            return text;
        } catch (Exception e) {
            throw new RuntimeException("[PpOcrOpencv] 识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * CTC 解码：取每步 argmax，去除重复和 blank
     */
    private String ctcDecode(Mat probs) {
        // probs shape: [1, seqLen, numClasses] or [1, numClasses, seqLen]
        int d = probs.dims();
        int h = (int) probs.size().get(1)[0];
        int w = (int) probs.size().get(1)[1];
        int c = (int) probs.size().get(1)[2];

        // Determine layout: if w > c, it's [1, seq, classes]; if c > w, it's [1, classes, seq]
        boolean seqFirst = w >= c;
        int seqLen = seqFirst ? w : h;
        int numClasses = seqFirst ? c : w;

        StringBuilder sb = new StringBuilder();
        int prevIdx = -1;
        for (int t = 0; t < seqLen; t++) {
            int maxIdx = 0;
            float bestVal = Float.NEGATIVE_INFINITY;
            for (int cls = 0; cls < numClasses; cls++) {
                float val;
                if (seqFirst) {
                    val = probs.get(0, t, cls)[0];
                } else {
                    val = probs.get(0, cls, t)[0];
                }
                if (val > bestVal) {
                    bestVal = val;
                    maxIdx = cls;
                }
            }
            if (maxIdx != prevIdx && maxIdx != 0 && maxIdx < dict.size()) {
                String ch = dict.get(maxIdx);
                if (ch != null && !ch.isBlank()) {
                    sb.append(ch);
                }
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
                    if (is == null) {
                        throw new IllegalStateException("模型文件未找到: " + modelResourcePath);
                    }
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
        try (org.opencv.core.MatOfByte mob = new org.opencv.core.MatOfByte(data)) {
            return Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_COLOR);
        }
    }

    public void close() {
        if (net != null) {
            net.close();
            net = null;
        }
        modelBytes = null;
    }
}
