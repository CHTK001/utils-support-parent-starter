package com.chua.deeplearning.support.onnx.ocr.extractor;
import com.chua.deeplearning.support.utils.ImageUtils;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
   * PP-ocrv6 文字识别（ORT 原生 + 打开cv）。
 *
 * <p>替代 DJL 版（djl-onnx 不支持 NDArray 张量运算）。模型 + dict 由 jar
 * {@code utils-support-models-onnx-paddleocrv6-tiny} 提供，NativeLoader 解压。
 * 字符表从 {@code inference.yml} 的 {@code character_dict} 提取（与模型 6906 类对齐，
   * 而非精简的 dict.txt 6623 行）。输入 {@code x [1,3,48,W]}（打开cv resize 高 48、
 * 按宽高比缩放、归一化；宽度动态，上限 1920），输出 {@code fetch_name_0 [1,seq,classes]}，
 * CTC 解码 → 识别文本。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PpWordExtractorTranslator implements ITranslator<byte[], String> {

    /** 图像高度 */
    /** Img_h */
    private static final int IMG_H = 48;
    /** 图像最大宽度 */
    /** Img_w_最大 */
    private static final int IMG_W_MAX = 1920;
    /** 均值数组 */
    /** Mean */
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    /** 标准差数组 */
    /** STD */
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    /** 模型文件路径 */
    /** 模型_文件 */
    private static final String MODEL_FILE = "inference.onnx";
    /** 配置文件路径 */
    /** 配置_文件 */
    private static final String CONFIG_FILE = "inference.yml";

    /**
     * 模型资源目录（tiny / medium 通用）。
     */
    private final String resourceBase;

    /**
      * 模型名称（用于 NAT加载 缓存隔离）。
     */
    private final String modelName;

    /** ONNX 运行时环境 */
    /** ORTENV */
    private OrtEnvironment ortEnv;
    /** 会话 */
    private OrtSession session;
    /** 词典列表 */
    /** Dict */
    private List<String> dict;

    /**
      * 默认使用 PP-ocrv6 tiny 资源。
     */
    public PpWordExtractorTranslator() {
        this("ocr/PP-OCRv6/tiny/rec_infer/", "paddleocrv6-rec");
    }

    /**
     * 指定资源目录构造。
     *
     * @param resourceBase 模型资源目录（jar 内路径）
     * @param modelName    模型名称
     */
    public PpWordExtractorTranslator(String resourceBase, String modelName) {
        this.resourceBase = resourceBase;
        this.modelName = modelName;
    }

    /** Prepare */
    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }
        Path tmpDir = Files.createTempDirectory("paddleocrv6-rec-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("rec");
        Files.createDirectories(modelDir);
        NativeLoader.of(modelName)
                .from(PpWordExtractorTranslator.class.getClassLoader())
                .basePath(resourceBase)
                .toTarget(modelDir)
                .glob("*")
                .withMd5(true)
                .extractOnly(true)
                .load();
        Path modelPath = modelDir.resolve(MODEL_FILE);
        Path configPath = modelDir.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(modelPath) || !Files.isRegularFile(configPath)) {
            throw new IllegalArgumentException("OCR 资源缺失: model=" + modelPath + " config=" + configPath);
        }
        dict = loadCharacterDict(configPath);
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        log.info("[PaddleOCRv6-rec] ONNX loaded: {} dict_size={}", modelPath.getFileName(), dict.size());
    }

    /**
      * 从 推理.yml 的 character_dict 提取完整字符表（含 blank 前缀，与模型类数对齐）。
     *
     * @param ymlPath 推理.yml 路径
     * @return 字符表（index 0 为 blank，其余为字符）
     */
    private static List<String> loadCharacterDict(Path ymlPath) throws Exception {
        List<String> lines = Files.readAllLines(ymlPath);
        List<String> chars = new ArrayList<>();
        boolean inDict = false;
        for (String line : lines) {
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
                    if (ch.equals("\\")) {
                        ch = "\\";
                    }
                    chars.add(ch);
                } else if (!trimmed.isEmpty() && !trimmed.startsWith("-")) {
                    break;
                }
            }
        }
        List<String> table = new ArrayList<>(chars.size() + 1);
        table.add("blank");
        table.addAll(chars);
        return table;
    }

    @Override
    /** 名称 */
    public String name() {
        return modelName;
    }

    @Override
    /** Translate */
    public String translate(byte[] imageData) {
        try {
            prepare();
            return recognize(imageData);
        } catch (Exception e) {
            throw new RuntimeException("[paddleocrv6-rec] 文字识别失败: " + e.getMessage(), e);
        }
    }

    /**
     * Recognize
     *
     * @param imageData 镜像数据
     * @return recognize的结果
     */
    private String recognize(byte[] imageData) {
        try {
            ImageUtils.load();
            Mat src = ImageUtils.decode(imageData);
            if (src == null || src.empty()) {
                throw new IllegalArgumentException("无法解码图像");
            }
            try {
                int srcW = src.cols();
                int srcH = src.rows();
                float ratio = (float) srcH / IMG_H;
                int resizeW = Math.max(1, (int) Math.ceil(srcW / ratio));
                resizeW = Math.max(resizeW, 16);
                // 宽度动态（模型 shape=[-1,3,48,-1]），保留原始宽高比，仅限制上限防止超长行 OOM
                resizeW = Math.min(resizeW, IMG_W_MAX);

                Mat resized = ImageUtils.resize(src, resizeW, IMG_H, Imgproc.INTER_LINEAR);

                float[] pixels = new float[3 * IMG_H * resizeW];
                for (int y = 0; y < IMG_H; y++) {
                    for (int x = 0; x < resizeW; x++) {
                        double[] bgr = resized.get(y, x);
                        int idx = y * resizeW + x;
                        // PP-OCR 输入 BGR 顺序（img_mode=BGR），归一化 (v/255 - 0.5) / 0.5
                        pixels[idx] = (((float) bgr[0] / 255.0f) - MEAN[0]) / STD[0];
                        pixels[idx + IMG_H * resizeW] = (((float) bgr[1] / 255.0f) - MEAN[1]) / STD[1];
                        pixels[idx + 2 * IMG_H * resizeW] = (((float) bgr[2] / 255.0f) - MEAN[2]) / STD[2];
                    }
                }
                resized.release();

                long[] shape = {1, 3, IMG_H, resizeW};
                try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("x", tensor);
                    try (OrtSession.Result result = session.run(inputs)) {
                        Object out = result.get(0).getValue();
                        float[][][] probs;
                        if (out instanceof float[][][]) {
                            probs = (float[][][]) out;
                        } else if (out instanceof float[][][][]) {
                            probs = ((float[][][][]) out)[0];
                        } else {
                            throw new IllegalArgumentException("OCR 输出格式不识别: " + out.getClass());
                        }
                        return decode(probs[0]);
                    }
                }
            } finally {
                src.release();
            }
        } catch (Exception e) {
            throw new RuntimeException("[paddleocrv6-rec] 文字识别失败: " + e.getMessage(), e);
        }
    }

    /**
      * CTC 解码：每步取 argmax，去掉连续重复和 blank（索引 0）。
     * @param seqProbs seqprobs
     * @return decode的结果
     */
    private String decode(float[][] seqProbs) {
        StringBuilder sb = new StringBuilder();
        int prevIdx = -1;
        for (float[] step : seqProbs) {
            int maxIdx = argMax(step);
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

    /**
     * 参数最大值
     *
     * @param arr arr
     * @return 参数最大的结果
     */
    private int argMax(float[] arr) {
        int idx = 0;
        float best = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > best) {
                best = arr[i];
                idx = i;
            }
        }
        return idx;
    }

    /**
      * 关闭底层 ONNX 会话。
     */
    public synchronized void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignore) {
        }
        session = null;
        ortEnv = null;
        dict = null;
    }
}
