package com.chua.deeplearning.support.onnx.detection.single;

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
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单类 YOLOv8 通用目标检测 Translator 抽象基类。
 *
 * <p>用于表格检测、印章检测、单一物体检测等场景。
 * 子类提供 {@link #className()} 与 {@link #classNamesResourcePath()}。
 *
 * <h2>输入/输出</h2>
 * <ul>
 *   <li>输入：YOLOv8 标准 [1, 3, H, W]，H=W=inputSize，RGB /255.0 归一化，CHW</li>
 *   <li>输出：YOLOv8 标准 [1, 4+1, N]（NMS 前），内部 NMS 输出 DetectedObjects</li>
 * </ul>
 *
 * <h2>类别加载</h2>
 * <p>从 classpath {@link #classNamesResourcePath()} 加载（通常 1 行），缺失时回退
 * {@link #defaultClassName()}。
 *
 * @since 4.0.0.42
 */
@Slf4j
public abstract class AbstractSingleClassYolov8Translator implements Translator<Image, DetectedObjects> {

    /**
     * 默认输入尺寸：YOLOv8 @ 640。
     */
    protected static final int DEFAULT_INPUT_SIZE = 640;

    /**
     * 默认置信度阈值。
     */
    protected static final float DEFAULT_THRESHOLD = 0.25f;

    /**
     * 默认 NMS IoU 阈值。
     */
    protected static final float DEFAULT_NMS_THRESHOLD = 0.45f;

    /** 输入尺寸 */
    private final int inputSize;
    /** 阈值 */
    private final float threshold;
    /** NMS 阈值 */
    /** NMS阈值 */
    private final float nmsThreshold;
    /** 类别名称列表 */
    /** Classes */
    private final List<String> classes;

    /** 图像宽度 */
    /** 图片宽度 */
    private int imageWidth;
    /** 图像高度 */
    /** 图片高度 */
    private int imageHeight;

    /** 创建 AbstractSingleClassYolov8Translator 实例 */
    protected AbstractSingleClassYolov8Translator() {
        this(DEFAULT_INPUT_SIZE, DEFAULT_THRESHOLD, DEFAULT_NMS_THRESHOLD);
    }

    /**
     * 创建 AbstractSingleClassYolov8Translator 实例
     * @param inputSize inputSize
     * @param float float
     * @param float float
     */
    protected AbstractSingleClassYolov8Translator(int inputSize, float threshold, float nmsThreshold) {
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
        this.classes = Collections.singletonList(loadClassName());
        log.info("{} 初始化: input={}x{}, threshold={}, nms={}",
                getClass().getSimpleName(), inputSize, inputSize, threshold, nmsThreshold);
    }

    /**
     * classpath 资源路径，例如 {@code vision/table/yolov8n/class.names.txt}。
     */
    protected abstract String classNamesResourcePath();

    /**
     * 资源缺失或解析失败时回退的类别名。
     */
    protected abstract String defaultClassName();

    /**
     * 单类名（子类可重写以支持多类别，但本基类约定单类）。
     */
    public String className() {
        return classes.get(0);
    }

    /** 加载ClassName */
    private String loadClassName() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(classNamesResourcePath())) {
            if (is == null) {
                log.warn("classpath 未找到 {}, 使用默认类别: {}", classNamesResourcePath(), defaultClassName());
                return defaultClassName();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        log.info("{} 从 class.names.txt 加载类别: {}", getClass().getSimpleName(), line);
                        return line;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("加载 class.names.txt 失败: {}, 使用默认: {}", e.getMessage(), defaultClassName());
        }
        return defaultClassName();
    }

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        imageWidth = input.getWidth();
        imageHeight = input.getHeight();
        if (log.isDebugEnabled()) {
            log.debug("{} 输入: {}x{}", getClass().getSimpleName(), imageWidth, imageHeight);
        }
        Image resized = input.resize(inputSize, inputSize, true);
        NDArray array = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        return new NDList(toNormalizedChw(ctx, array));
    }

    @Override
    /** 处理Output */
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
            log.warn("{} feature 维度不匹配: actual={}, expected={}",
                    getClass().getSimpleName(), numFeatures, expectedFeatures);
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

        log.info("{} 检测: {} raw -> {} after NMS", getClass().getSimpleName(), boxes.size(), finalNames.size());
        return new DetectedObjects(finalNames, finalProbs, finalBoxes);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     * 当前 Translator 实际加载的类别名。
     */
    public String actualClassName() {
        return classes.get(0);
    }

    /**
     * 默认输入尺寸。
     */
    public int getInputSize() {
        return inputSize;
    }

    /** Sigmoid */
    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    /** ToNormalizedChw */
    private NDArray toNormalizedChw(TranslatorContext ctx, NDArray array) {
        Shape shape = array.getShape();
        if (shape.dimension() != 3) {
            throw new IllegalArgumentException(getClass().getSimpleName() + " 仅支持 HWC 格式, shape=" + shape);
        }

        int height = (int) shape.get(0);
        int width = (int) shape.get(1);
        int channels = (int) shape.get(2);
        float[] source = array.toType(DataType.FLOAT32, false).toFloatArray();
        float[] chw = new float[source.length];
        int planeSize = height * width;

        for (int h = 0; h < height; h++) {
            for (int w = 0; w < width; w++) {
                int hwOffset = h * width + w;
                int sourceOffset = hwOffset * channels;
                for (int c = 0; c < channels; c++) {
                    chw[c * planeSize + hwOffset] = source[sourceOffset + c] / 255.0f;
                }
            }
        }

        return ctx.getNDManager().create(chw, new Shape(1, channels, height, width));
    }
}
