package com.chua.deeplearning.support.onnx.layout.doclaynet;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.NMSUtils;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * DocLayNet YOLOv8 文档版面分析 Translator。
 *
 * <p>基于 YOLOv8 (n/s/m) 在 DocLayNet 11 类数据集上微调的文档版面检测模型。
 * 输出格式为标准 YOLOv8 [1, 4+nc, N]（含 sigmoid 但无 NMS），本类内部完成 NMS。
 *
 * <h2>类别（11 类 DocLayNet）</h2>
 * <pre>
 *   0  caption       1  footnote    2  formula    3  list-item
 *   4  page-footer   5  page-header 6  picture    7  section-header
 *   8  table         9  text       10  title
 * </pre>
 *
 * <h2>输入</h2>
 * <ul>
 *   <li>输入尺寸：640×640（letterbox resize 到 640）</li>
 *   <li>归一化：/255.0</li>
 *   <li>CHW 布局</li>
 * </ul>
 *
 * <h2>输出</h2>
 * <ul>
 *   <li>shape = [1, 4+11, N]（NMS 前）</li>
 *   <li>本 Translator 内部 NMS 后输出 DetectedObjects（11 类标签 + 归一化坐标）</li>
 * </ul>
 *
 * <h2>注册</h2>
 * <pre>
 *   reg("doc-layout-yolo-imgsz640",
 *       "com.chua.deeplearning.support.onnx.layout.doclaynet.DocLayNetYolov8Translator",
 *       Image.class, DetectedObjects.class, LayoutDetector.class,
 *       "vision/layout/doclaynet/model.onnx");
 * </pre>
 *
 * <p>模型文件 (~6MB) 由 utils-support-models-onnx-doclaynet JAR 提供，使用前请先
 * 通过 {@code scripts/fetch-doclaynet.ps1} 拉取模型并部署到云效。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DocLayNetYolov8Translator implements Translator<Image, DetectedObjects> {

    /**
     * DocLayNet 11 类（按字母序排列，与 class.names.txt 保持一致）。
     */
    public static final List<String> DOCLAYNET_CLASSES = Collections.unmodifiableList(
            Arrays.asList(
                    "caption",        // 0
                    "footnote",       // 1
                    "formula",        // 2
                    "list-item",      // 3
                    "page-footer",    // 4
                    "page-header",    // 5
                    "picture",        // 6
                    "section-header", // 7
                    "table",          // 8
                    "text",           // 9
                    "title"           // 10
            )
    );

    /**
     * 模型相对路径（classpath 资源）：class.names.txt 与 model.onnx 同目录。
     */
    public static final String CLASS_NAMES_RESOURCE = "vision/layout/doclaynet/class.names.txt";

    /**
     * 默认输入尺寸：YOLOv8 @ 640（letterbox）。
     */
    private static final int DEFAULT_INPUT_SIZE = 640;

    /**
     * 默认置信度阈值。
     */
    private static final float DEFAULT_THRESHOLD = 0.25f;

    /**
     * 默认 NMS IoU 阈值。
     */
    private static final float DEFAULT_NMS_THRESHOLD = 0.45f;

    private final int inputSize;
    private final float threshold;
    private final float nmsThreshold;
    private final List<String> classes;

    private int imageWidth;
    private int imageHeight;

    /**
     * 默认构造：640×640、0.25 阈值、0.45 NMS、加载 class.names.txt 自动识别类别数。
     */
    public DocLayNetYolov8Translator() {
        this(DEFAULT_INPUT_SIZE, DEFAULT_THRESHOLD, DEFAULT_NMS_THRESHOLD, loadClassesOrDefault());
    }

    /**
     * 自定义输入尺寸。
     *
     * @param inputSize     输入尺寸（正方形）
     * @param threshold     置信度阈值
     * @param nmsThreshold  NMS IoU 阈值
     */
    public DocLayNetYolov8Translator(int inputSize, float threshold, float nmsThreshold) {
        this(inputSize, threshold, nmsThreshold, loadClassesOrDefault());
    }

    /**
     * 完全自定义。
     *
     * @param inputSize     输入尺寸（正方形）
     * @param threshold     置信度阈值
     * @param nmsThreshold  NMS IoU 阈值
     * @param classes       类别列表（顺序须与模型输出一致）
     */
    public DocLayNetYolov8Translator(int inputSize, float threshold, float nmsThreshold, List<String> classes) {
        if (inputSize <= 0) {
            throw new IllegalArgumentException("inputSize 必须 > 0: " + inputSize);
        }
        if (threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold 必须在 [0, 1]: " + threshold);
        }
        if (nmsThreshold < 0 || nmsThreshold > 1) {
            throw new IllegalArgumentException("nmsThreshold 必须在 [0, 1]: " + nmsThreshold);
        }
        this.inputSize = inputSize;
        this.threshold = threshold;
        this.nmsThreshold = nmsThreshold;
        this.classes = new ArrayList<>(classes);
        log.info("DocLayNetYolov8 初始化: input={}x{}, threshold={}, nms={}, classes={}",
                inputSize, inputSize, threshold, nmsThreshold, classes.size());
    }

    /**
     * 优先从 classpath 加载 class.names.txt（11 类），失败时回退到默认列表。
     *
     * @return 类别列表
     */
    static List<String> loadClassesOrDefault() {
        try (InputStream is = DocLayNetYolov8Translator.class.getClassLoader()
                .getResourceAsStream(CLASS_NAMES_RESOURCE)) {
            if (is == null) {
                log.warn("classpath 未找到 {}, 使用默认 DocLayNet 11 类", CLASS_NAMES_RESOURCE);
                return DOCLAYNET_CLASSES;
            }
            List<String> classes = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        classes.add(line);
                    }
                }
            }
            if (classes.isEmpty()) {
                log.warn("{} 为空, 使用默认 DocLayNet 11 类", CLASS_NAMES_RESOURCE);
                return DOCLAYNET_CLASSES;
            }
            log.info("DocLayNetYolov8 从 class.names.txt 加载 {} 类", classes.size());
            return classes;
        } catch (IOException e) {
            log.warn("加载 class.names.txt 失败: {}, 使用默认 11 类", e.getMessage());
            return DOCLAYNET_CLASSES;
        }
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        imageWidth = input.getWidth();
        imageHeight = input.getHeight();
        if (log.isDebugEnabled()) {
            log.debug("DocLayNet 输入: {}x{}", imageWidth, imageHeight);
        }

        // 用 BufferedImage + AWT 缩放（规避 DJL input.resize 走 NDArray 不支持的 op）
        Object wrapped = input.getWrappedImage();
        java.awt.image.BufferedImage src = wrapped instanceof java.awt.image.BufferedImage b
                ? b
                : (java.awt.image.BufferedImage) ai.djl.modality.cv.BufferedImageFactory.getInstance().fromImage(input).getWrappedImage();
        java.awt.image.BufferedImage resized = ImageUtils.resize(src, inputSize, inputSize, org.opencv.imgproc.Imgproc.INTER_CUBIC);

        return new NDList(toNormalizedChw(ctx, resized));
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) throws Exception {
        NDArray output = list.get(0);

        long f0 = output.getShape().get(1);
        long f1 = output.getShape().get(2);
        float[] data = output.toType(DataType.FLOAT32, false).toFloatArray();

        long numBoxes, numFeatures;
        boolean needTranspose = f0 < f1;
        if (needTranspose) {
            numBoxes = f1;
            numFeatures = f0;
        } else {
            numBoxes = f0;
            numFeatures = f1;
        }

        int expectedFeatures = 4 + classes.size();
        if (numFeatures != expectedFeatures) {
            log.warn("feature 维度不匹配: actual={}, expected={} (4+classes={})",
                    numFeatures, expectedFeatures, classes.size());
        }

        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();

        for (int i = 0; i < numBoxes; i++) {
            float cx, cy, w, h;
            if (needTranspose) {
                cx = data[i * (int) numFeatures + 0];
                cy = data[i * (int) numFeatures + 1];
                w = data[i * (int) numFeatures + 2];
                h = data[i * (int) numFeatures + 3];
            } else {
                cx = data[0 * (int) numBoxes + i];
                cy = data[1 * (int) numBoxes + i];
                w = data[2 * (int) numBoxes + i];
                h = data[3 * (int) numBoxes + i];
            }

            int classId = 0;
            float maxScore = 0f;
            for (int c = 0; c < classes.size() && (4 + c) < numFeatures; c++) {
                float score;
                if (needTranspose) {
                    score = data[i * (int) numFeatures + (4 + c)];
                } else {
                    score = data[(4 + c) * (int) numBoxes + i];
                }
                score = sigmoid(score);
                if (score > maxScore) {
                    maxScore = score;
                    classId = c;
                }
            }

            if (maxScore < threshold) {
                continue;
            }

            double x0 = cx - w / 2.0;
            double y0 = cy - h / 2.0;
            double x1 = cx + w / 2.0;
            double y1 = cy + h / 2.0;

            x0 = Math.max(0, Math.min(inputSize, x0));
            y0 = Math.max(0, Math.min(inputSize, y0));
            x1 = Math.max(0, Math.min(inputSize, x1));
            y1 = Math.max(0, Math.min(inputSize, y1));

            double scaleX = (double) imageWidth / inputSize;
            double scaleY = (double) imageHeight / inputSize;

            double origX = x0 * scaleX;
            double origY = y0 * scaleY;
            double origW = (x1 - x0) * scaleX;
            double origH = (y1 - y0) * scaleY;

            origX = Math.max(0, Math.min(imageWidth, origX));
            origY = Math.max(0, Math.min(imageHeight, origY));
            origW = Math.max(1, Math.min(imageWidth - origX, origW));
            origH = Math.max(1, Math.min(imageHeight - origY, origH));

            // 归一化坐标 (0..1) 给 DetectedObjects
            double nx = origX / imageWidth;
            double ny = origY / imageHeight;
            double nw = origW / imageWidth;
            double nh = origH / imageHeight;

            boxes.add(new Rectangle(nx, ny, nw, nh));
            names.add(classes.get(classId));
            probs.add((double) maxScore);
        }

        List<Integer> keep = NMSUtils.nms(boxes, probs, nmsThreshold);
        List<String> finalNames = new ArrayList<>();
        List<Double> finalProbs = new ArrayList<>();
        List<BoundingBox> finalBoxes = new ArrayList<>();
        for (int idx : keep) {
            finalNames.add(names.get(idx));
            finalProbs.add(probs.get(idx));
            finalBoxes.add(boxes.get(idx));
        }

        log.info("DocLayNetYolov8 检测: {} raw -> {} after NMS", boxes.size(), finalNames.size());
        return new DetectedObjects(finalNames, finalProbs, finalBoxes);
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     * 推荐输入尺寸（DocLayNet 论文中常用 640、800、1024）。
     *
     * @return 推荐尺寸数组
     */
    public static int[] getRecommendedSizes() {
        return new int[]{640, 800, 1024};
    }

    /**
     * 11 类 DocLayNet 类别列表。
     *
     * @return 类别列表
     */
    public static List<String> getSupportedClasses() {
        return DOCLAYNET_CLASSES;
    }

    /**
     * 模型描述。
     *
     * @return 中文描述
     */
    public static String getModelDescription() {
        return "DocLayNet YOLOv8 文档版面分析 (11 类) - 6MB - 640x640 输入";
    }

    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    private NDArray toNormalizedChw(TranslatorContext ctx, java.awt.image.BufferedImage bi) {
        int width = bi.getWidth();
        int height = bi.getHeight();
        int channels = 3;
        int[] rgb = bi.getRGB(0, 0, width, height, null, 0, width);
        float[] chw = new float[channels * height * width];
        int planeSize = height * width;
        for (int i = 0; i < planeSize; i++) {
            chw[i] = ((rgb[i] >> 16) & 0xFF) / 255.0f;
            chw[planeSize + i] = ((rgb[i] >> 8) & 0xFF) / 255.0f;
            chw[2 * planeSize + i] = (rgb[i] & 0xFF) / 255.0f;
        }
        return ctx.getNDManager().create(chw, new Shape(1, channels, height, width));
    }
}
