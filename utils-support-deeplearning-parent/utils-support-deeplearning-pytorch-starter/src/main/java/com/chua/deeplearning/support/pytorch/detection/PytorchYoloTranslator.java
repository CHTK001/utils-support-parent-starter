package com.chua.deeplearning.support.pytorch.detection;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.LetterBoxUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * pytorch YOLO 目标检测 Translator。
 * <p>
 * 支持 yolov5/v8 风格输出：letterbox 预处理 + conf 过滤 + NMS。
 * 适用于 torchscript 导出的 YOLO 检测模型。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PytorchYoloTranslator implements Translator<Image, DetectedObjects> {

    /**
     * 默认输入尺寸。
     */
    private static final int DEFAULT_INPUT_SIZE = 640;

    /**
     * 默认置信度阈值。
     */
    private static final float DEFAULT_THRESHOLD = 0.25f;

    /**
     * 默认 NMS 阈值。
     */
    private static final float DEFAULT_NMS_THRESHOLD = 0.45f;

    /**
     * COCO 80 类。
     */
    private static final List<String> COCO_CLASSES = List.of(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
            "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
            "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
            "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard",
            "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase",
            "scissors", "teddy bear", "hair drier", "toothbrush"
    );

    /**
     * 输入尺寸。
     */
    private final int inputSize;

    /**
     * 置信度阈值。
     */
    private final float threshold;

    /**
     * NMS 阈值。
     */
    private final float nmsThreshold;

    /**
     * 类别列表。
     */
    private final List<String> classes;

    /**
     * 默认构造（COCO 80 类）。
     */
    public PytorchYoloTranslator() {
        this(DEFAULT_INPUT_SIZE, DEFAULT_THRESHOLD, DEFAULT_NMS_THRESHOLD, COCO_CLASSES);
    }

    /**
     * 构造检测器。
     *
     * @param inputSize    输入尺寸
     * @param threshold    置信度阈值
     * @param nmsThreshold NMS 阈值
     * @param classes      类别列表
     */
    public PytorchYoloTranslator(int inputSize, float threshold, float nmsThreshold, List<String> classes) {
        this.inputSize = inputSize;
        this.threshold = threshold;
        this.nmsThreshold = nmsThreshold;
        this.classes = (classes == null || classes.isEmpty()) ? List.of("object") : classes;
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        ctx.setAttachment("sourceWidth", input.getWidth());
        ctx.setAttachment("sourceHeight", input.getHeight());
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        LetterBoxUtils.ResizeResult letterBoxResult = LetterBoxUtils.letterbox(
                ctx.getNDManager(),
                array,
                inputSize,
                inputSize,
                114f,
                LetterBoxUtils.PaddingPosition.CENTER
        );
        array = letterBoxResult.image;
        ctx.setAttachment("scale", letterBoxResult.r);
        ctx.setAttachment("padW", (float) letterBoxResult.left);
        ctx.setAttachment("padH", (float) letterBoxResult.top);

        array = array.transpose(2, 0, 1);
        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }
        array = array.div(255.0f);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        int sourceWidth = (int) ctx.getAttachment("sourceWidth");
        int sourceHeight = (int) ctx.getAttachment("sourceHeight");
        float scale = (float) ctx.getAttachment("scale");
        float padW = (float) ctx.getAttachment("padW");
        float padH = (float) ctx.getAttachment("padH");

        NDArray output = list.singletonOrThrow();
        // 统一为 [N, C]：可能是 [1, C, N] 或 [1, N, C]
        if (output.getShape().dimension() == 3) {
            if (output.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
                output = output.squeeze(0);
            }
            // [C, N] -> [N, C]
            if (output.getShape().get(0) < output.getShape().get(1) // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
                    && output.getShape().get(0) <= 84) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
                output = output.transpose();
            }
        }

        long rows = output.getShape().get(0); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        long cols = output.getShape().dimension() > 1 ? output.getShape().get(1) : 0;
        if (rows == 0 || cols < 5) {
            return new DetectedObjects(List.of(), List.of(), List.of());
        }

        List<String> classNames = new ArrayList<>();
        List<Double> probabilities = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();

        float[] data = output.toFloatArray();
        int c = (int) cols;
        for (int i = 0; i < rows; i++) {
            int base = i * c;
            float cx = data[base];
            float cy = data[base + 1];
            float w = data[base + 2];
            float h = data[base + 3];

            int classId;
            float score;
            if (c == 6) {
                // [x,y,w,h,conf,cls]
                score = data[base + 4];
                classId = Math.round(data[base + 5]);
            } else {
                // [x,y,w,h,cls0,cls1,...]
                classId = 0;
                score = data[base + 4];
                for (int k = 5; k < c; k++) {
                    if (data[base + k] > score) {
                        score = data[base + k];
                        classId = k - 4;
                    }
                }
            }

            if (score < threshold) {
                continue;
            }
            if (classId < 0 || classId >= classes.size()) {
                continue;
            }

            // 去 letterbox 还原到原图，并归一化到 0~1
            float x1 = (cx - w / 2f - padW) / scale;
            float y1 = (cy - h / 2f - padH) / scale;
            float x2 = (cx + w / 2f - padW) / scale;
            float y2 = (cy + h / 2f - padH) / scale;
            x1 = Math.max(0, Math.min(x1, sourceWidth));
            y1 = Math.max(0, Math.min(y1, sourceHeight));
            x2 = Math.max(0, Math.min(x2, sourceWidth));
            y2 = Math.max(0, Math.min(y2, sourceHeight));
            float nw = Math.max(0, x2 - x1) / sourceWidth;
            float nh = Math.max(0, y2 - y1) / sourceHeight;
            float nx = x1 / sourceWidth;
            float ny = y1 / sourceHeight;
            if (nw <= 0 || nh <= 0) {
                continue;
            }

            classNames.add(classes.get(classId));
            probabilities.add((double) score);
            boxes.add(new Rectangle(nx, ny, nw, nh));
        }

        if (boxes.isEmpty()) {
            return new DetectedObjects(List.of(), List.of(), List.of());
        }

        // 简单 NMS
        List<Integer> keep = nms(boxes, probabilities, nmsThreshold);
        List<String> outNames = new ArrayList<>();
        List<Double> outProbs = new ArrayList<>();
        List<BoundingBox> outBoxes = new ArrayList<>();
        for (int idx : keep) {
            outNames.add(classNames.get(idx));
            outProbs.add(probabilities.get(idx));
            outBoxes.add(boxes.get(idx));
        }
        return new DetectedObjects(outNames, outProbs, outBoxes);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
    * 简易 NMS。
    *
    * @param boxes         框
    * @param probabilities 置信度
    * @param threshold     iou 阈值
    * @return 保留索引
    */
    private static List<Integer> nms(List<BoundingBox> boxes, List<Double> probabilities, float threshold) {
        int n = boxes.size();
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort((a, b) -> Double.compare(probabilities.get(b), probabilities.get(a)));

        boolean[] removed = new boolean[n];
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = order.get(i);
            if (removed[idx]) {
                continue;
            }
            keep.add(idx);
            Rectangle a = (Rectangle) boxes.get(idx);
            for (int j = i + 1; j < n; j++) {
                int jdx = order.get(j);
                if (removed[jdx]) {
                    continue;
                }
                Rectangle b = (Rectangle) boxes.get(jdx);
                if (iou(a, b) > threshold) {
                    removed[jdx] = true;
                }
            }
        }
        return keep;
    }

    /**
     * 计算 iou。
     *
     * @param a 框 A
     * @param b 框 B
     * @return IoU
     */
    private static double iou(Rectangle a, Rectangle b) {
        double x1 = Math.max(a.getX(), b.getX());
        double y1 = Math.max(a.getY(), b.getY());
        double x2 = Math.min(a.getX() + a.getWidth(), b.getX() + b.getWidth());
        double y2 = Math.min(a.getY() + a.getHeight(), b.getY() + b.getHeight());
        double inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        double union = a.getWidth() * a.getHeight() + b.getWidth() * b.getHeight() - inter;
        return union <= 0 ? 0 : inter / union;
    }
}
