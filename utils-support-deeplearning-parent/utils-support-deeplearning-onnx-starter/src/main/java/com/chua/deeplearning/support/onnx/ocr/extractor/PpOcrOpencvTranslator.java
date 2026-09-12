package com.chua.deeplearning.support.onnx.ocr.extractor;

import lombok.extern.slf4j.Slf4j;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfFloat;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
   * PP-ocrv6 文字识别 — 纯 打开cv DNN 实现（无 ONNX Runtime 依赖）
 *
 * <p>使用 OpenCV DNN 模块直接加载 PaddleOCR v6 ONNX 识别模型。</p>
 * @author CH
 * @since 4.0.0
 * @param imageData 镜像数据
 * @return recognize的结果
 */
@Slf4j
public class PpOcrOpencvTranslator {

    private static final int IMG_H = 48; // IMG_H
    private static final int IMG_W_MAX = 1920; // imgw最大
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f}; // MEAN
    private static final float[] STD = {0.5f, 0.5f, 0.5f}; // STD

    private final List<String> dict; // dict
    private final String modelResourcePath; // 模型resource路径
    private Path modelFile; // 模型文件
    /**
      * ppocropencvtranslator。
     */
    private Net net;

    /**
     * PpOcrOpencvTranslator。
     */
    public PpOcrOpencvTranslator() {
        this("ocr/PP-OCRv6/tiny/rec_infer/inference.onnx", "ocr/PP-OCRv6/tiny/rec_infer/inference.yml");
    /**
      * ppocropencvtranslator。
     * @param modelResourcePath 模型resource路径
     * @param dictResourcePath dictresource路径
     */
    }

    public PpOcrOpencvTranslator(String modelResourcePath, String dictResourcePath) {
        this.modelResourcePath = modelResourcePath;
        this.dict = loadCharacterDict(dictResourcePath);
        log.info("[PpOcrOpencv] dict_size={}", dict.size());
    /**
     * 加载characterdict。
     * @param resourcePath resource路径
     * @return 加载characterdict的结果
     * @param imageData 镜像数据
     */
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
        if (src == null || src.empty()) {
            throw new IllegalArgumentException("无法解码图片");
        }
        try {
            int srcW = src.cols(), srcH = src.rows();
            float ratio = (float) srcH / IMG_H;
            int resizeW = Math.max(16, Math.min((int) Math.ceil(srcW / ratio), IMG_W_MAX));

            Mat resized = new Mat();
            Imgproc.resize(src, resized, new Size(resizeW, IMG_H));

 // Extract BGR pixels, normalize, flatten 转为 [1,3,48,W]
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

 // 创建 输入 blob [1,3,48,W] CV_32F
            Mat inputBlob = new Mat();
            inputBlob.create(new int[]{1, 3, IMG_H, resizeW}, CvType.CV_32F);
            inputBlob.put(0, 0, pixels);

 // 远期 通过
            List<Mat> outputs = new ArrayList<>();
            net.forward(outputs);
            if (outputs.isEmpty()) {
                throw new RuntimeException("模型无输出");
            }
            Mat result = outputs.get(0);

            // Decode CTC
            String text = ctcDecode(result);
            log.debug("[PpOcrOpencv] 识别结果: [{}]", text);
            return text;
        } catch (Exception e) {
            throw new RuntimeException("[PpOcrOpencv] 识别失败: " + e.getMessage(), e);
        }
    }

    /**
      * ctcdecode。
     * @param probs probs
     * @return ctcDecode的结果
     */
    private String ctcDecode(Mat probs) {
 // 打开cv DNN 输出 shape: [1, seqlen, num类]
        // probs.total() = 1 * seqLen * numClasses
        long total = probs.total();
        if (total == 0) {
            return "";
        }
        int numClasses = dict.size();
 // Determine seqlen 从 total 和 num类
 // For PP-OCR rec: 输出 是否 [1, seqlen, num类]
        // But we don't know the exact shape, so infer from dict size
 // 尝试 转为 查找 the best seqlen
        int seqLen = 0;
        int bestRemainder = Integer.MAX_VALUE;
        for (int s = 1; s <= 200; s++) {
            int c = (int) (total / s);
            int rem = (int) (total % s);
            if (rem == 0 && c == numClasses && s > seqLen) {
                seqLen = s;
                bestRemainder = 0;
                break;
            }
        }
        if (seqLen == 0) {
 // 降级: assume [1, num类, seqlen] layout
            seqLen = (int) (total / numClasses);
        }

 // 读取 全部 值 into a flat array
        float[] values = new float[(int) total];
        probs.get(0, 0, values);

        StringBuilder sb = new StringBuilder();
        int prevIdx = -1;
        if (seqLen * numClasses == total) {
            // Layout: [1, seqLen, numClasses] — value at [0, t, c] = values[t * numClasses + c]
            for (int t = 0; t < seqLen; t++) {
                int maxIdx = 0;
                float bestVal = Float.NEGATIVE_INFINITY;
                int base = t * numClasses;
                for (int c = 0; c < numClasses; c++) {
                    float val = values[base + c];
                    if (val > bestVal) { bestVal = val; maxIdx = c; }
                }
                if (maxIdx != prevIdx && maxIdx != 0 && maxIdx < dict.size()) {
                    String ch = dict.get(maxIdx);
                    if (ch != null && !ch.isBlank()) {
                        sb.append(ch);
                    }
                }
                prevIdx = maxIdx;
            }
        } else if (numClasses * seqLen == total) {
            // Layout: [1, numClasses, seqLen] — value at [0, c, t] = values[c * seqLen + t]
            for (int t = 0; t < seqLen; t++) {
                int maxIdx = 0;
                float bestVal = Float.NEGATIVE_INFINITY;
                for (int c = 0; c < numClasses; c++) {
                    float val = values[c * seqLen + t];
                    if (val > bestVal) { bestVal = val; maxIdx = c; }
                }
                if (maxIdx != prevIdx && maxIdx != 0 && maxIdx < dict.size()) {
                    String ch = dict.get(maxIdx);
                    if (ch != null && !ch.isBlank()) {
                        sb.append(ch);
                    }
                }
                prevIdx = maxIdx;
            }
        }
        return sb.toString();
    }

    /**
     * ensure模型。
     */
    private void ensureModel() {
        if (net != null) {
            return;
        }
        try {
 // Extract ONNX 模型 从 类路径 转为 temp 文件
            modelFile = Files.createTempFile("ppocrv6_rec_", ".onnx");
            modelFile.toFile().deleteOnExit();
            try (InputStream is = PpOcrOpencvTranslator.class.getClassLoader()
                    .getResourceAsStream(modelResourcePath)) {
                if (is == null) {
                    throw new IllegalStateException("模型未找到: " + modelResourcePath);
                }
                Files.copy(is, modelFile);
            }
            net = Dnn.readNetFromONNX(modelFile.toString());
            log.info("[PpOcrOpencv] ONNX loaded: {} dict_size={}", modelFile.getFileName(), dict.size());
        } catch (Exception e) {
            throw new RuntimeException("[PpOcrOpencv] 模型加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * decode镜像。
     * @param data 数据
     * @return decode镜像的结果
     */
    private static Mat decodeImage(byte[] data) {
        Mat mob = new Mat();
        try {
            return Imgcodecs.imdecode(new org.opencv.core.MatOfByte(data), Imgcodecs.IMREAD_COLOR);
        } finally {
            mob.release();
        }
    }

    /**
     * 关闭。
     */
    public void close() {
        // net lifecycle managed by GC (OpenCV Java binding)
        if (modelFile != null) { modelFile.toFile().delete(); modelFile = null; }
    }
}
